package com.wanderwildwood.hanauta.audio

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
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

    fun start(context: Context, setup: Setup) {
        if (busy) return
        stage.value = if (setup.click) Stage.CountIn(1, setup.beatsPerBar) else Stage.Listening(0, false)
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
        if (busy) return
        stage.value = Stage.Finding(0f)
        startImport(context, uri, name, beatsPerBar)
    }

    /**
     * A sound file another app shared with Hum, read the same way. The loan to read it may end
     * with the share, so it is copied into the app's own storage first and read from there;
     * the copy is removed once it has been read.
     */
    fun importShared(context: Context, uri: Uri, beatsPerBar: Int) {
        if (busy) return
        stage.value = Stage.Finding(0f)
        val app = context.applicationContext
        thread(name = "hum-shared") {
            val inbox = inbox(app)
            inbox.listFiles()?.forEach { it.delete() }
            val copy = File(inbox, "shared-${System.currentTimeMillis()}")
            val name = runCatching {
                app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0)?.substringBeforeLast('.') else null
                }
            }.getOrNull()
            val copied = runCatching {
                inbox.mkdirs()
                app.contentResolver.openInputStream(uri)!!.use { input ->
                    copy.outputStream().use { input.copyTo(it) }
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
    }

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
