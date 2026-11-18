package com.wanderwildwood.hanauta.audio

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.wanderwildwood.hanauta.R
import com.wanderwildwood.hanauta.ui.Notice
import java.io.File
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Where the take under way has got to. The screens watch this; the service moves it on. */
sealed interface Stage {
    data object Idle : Stage
    /** The bar of clicks before the tune: [beat] is 1 to the bar's beats. */
    data class CountIn(val beat: Int, val beats: Int) : Stage
    data class Listening(val seconds: Int, val loud: Boolean) : Stage
    /** Reading the notes out of the recording; [share] from 0 to 1. */
    data class Finding(val share: Float) : Stage
    data class Done(val takeId: Long) : Stage
    data class Failed(val reason: Reason) : Stage
}

enum class Reason { NoMicrophone, Unreadable, NoNotes }

/** What the next take is recorded with. */
data class Setup(val tempo: Int, val beatsPerBar: Int, val click: Boolean)

object Recording {
    internal val stage = MutableStateFlow<Stage>(Stage.Idle)
    val now: StateFlow<Stage> = stage

    /** True while a take that asked for the click is being made without one: the speaker would not play it. */
    internal val clickSilentFlow = MutableStateFlow(false)
    val clickSilent: StateFlow<Boolean> = clickSilentFlow

    /** Taken to change [stage] from idle to under way, or to begin a restore: never both at once. */
    private val gate = Any()

    /** True while a restore is putting a backup's takes in place of these. */
    @Volatile var restoring = false
        private set

    /**
     * Held while the notes of one recording are being found (a take, a file read in, or one cut
     * off at an earlier run): one at a time, so two long takes never share the memory.
     */
    internal val finding = Any()

    /**
     * A restore may begin: nothing is being recorded, read or recovered. Until [endRestore],
     * no take is begun and no file is read in.
     */
    fun beginRestore(): Boolean = synchronized(gate) {
        if (busy || restoring || Recover.running) {
            false
        } else {
            restoring = true
            true
        }
    }

    fun endRestore() {
        restoring = false
    }

    /** Moves [stage] on to [first] unless a take is under way or a restore is; true when it did. */
    private fun begin(first: Stage): Boolean = synchronized(gate) {
        if (busy || restoring) {
            false
        } else {
            stage.value = first
            true
        }
    }

    fun start(context: Context, setup: Setup) {
        if (busy) return
        clickSilentFlow.value = false
        // Here, not when the service starts: a Stop pressed before then is kept.
        RecordService.stopListening = false
        if (!begin(if (setup.click) Stage.CountIn(1, setup.beatsPerBar) else Stage.Listening(0, false))) {
            if (restoring) Notice.say(context.getString(R.string.restore_working))
            return
        }
        context.startForegroundService(
            Intent(context, RecordService::class.java)
                .setAction(RecordService.RECORD)
                .putExtra("tempo", setup.tempo)
                .putExtra("beats", setup.beatsPerBar)
                .putExtra("click", setup.click),
        )
    }

    /** A sound file made somewhere else, read for its notes as a take sung in its own time. */
    fun import(context: Context, uri: Uri, name: String?, beatsPerBar: Int) {
        if (!begin(Stage.Finding(0f))) {
            if (restoring) Notice.say(context.getString(R.string.restore_working))
            return
        }
        startImport(context, uri, name, beatsPerBar)
    }

    /**
     * A sound file another app shared with Hum, read the same way. The loan to read it may end
     * with the share, so it is copied into the app's own storage first and read from there;
     * the copy is removed once it has been read. False, and nothing done, while a take is under
     * way or a restore is.
     */
    fun importShared(context: Context, uri: Uri, beatsPerBar: Int): Boolean {
        if (!begin(Stage.Finding(0f))) return false
        val app = context.applicationContext
        thread(name = "hum-shared") {
            val inbox = inbox(app)
            inbox.listFiles()?.forEach { it.delete() }
            val copy = File(inbox, "shared-${System.currentTimeMillis()}")
            val name = sharedName(app, uri)
            val copied = runCatching {
                inbox.mkdirs()
                app.contentResolver.openInputStream(uri)!!.use { input ->
                    copy.outputStream().use { out ->
                        // No sound file of the longest take comes near this; stop rather than fill the phone.
                        val buf = ByteArray(1 shl 16)
                        var total = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            check(total <= MAX_SHARED_BYTES) { "too big" }
                            out.write(buf, 0, n)
                        }
                    }
                }
            }.isSuccess
            if (!copied || copy.length() == 0L) {
                copy.delete()
                stage.value = Stage.Failed(Reason.Unreadable)
                return@thread
            }
            // No name to go by: blank, so the take is named for when it was read, not for the copy.
            runCatching { startImport(app, Uri.fromFile(copy), name ?: "", beatsPerBar) }.onFailure {
                copy.delete()
                stage.value = Stage.Failed(Reason.Unreadable)
            }
        }
        return true
    }

    /** A shared file's name as its sender gives it, without the ending; null when it gives none. */
    fun sharedName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.substringBeforeLast('.') else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /** Ten minutes of 96 kHz stereo 24-bit WAV is about 350 MB; anything past this is not a take. */
    private const val MAX_SHARED_BYTES = 400L * 1024 * 1024

    /** Where a shared file waits to be read. */
    internal fun inbox(context: Context) = File(context.cacheDir, "shared")

    private fun startImport(context: Context, uri: Uri, name: String?, beatsPerBar: Int) {
        context.startForegroundService(
            Intent(context, RecordService::class.java)
                .setAction(RecordService.IMPORT)
                .setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra("name", name)
                .putExtra("beats", beatsPerBar),
        )
    }

    fun stop() {
        RecordService.stopListening = true
    }

    /** The take has been opened, or the failure read: back to nothing under way. */
    fun clear() {
        val s = stage.value
        if (s is Stage.Done || s is Stage.Failed) stage.value = Stage.Idle
    }

    val busy get() = stage.value.let { it is Stage.CountIn || it is Stage.Listening || it is Stage.Finding }
}
