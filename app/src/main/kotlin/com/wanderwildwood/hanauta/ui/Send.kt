package com.wanderwildwood.hanauta.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.wanderwildwood.hanauta.R
import com.wanderwildwood.hanauta.data.Take
import com.wanderwildwood.hanauta.data.Takes
import com.wanderwildwood.hanauta.music.Midi
import com.wanderwildwood.hanauta.music.MusicXml
import androidx.core.content.ContextCompat
import java.io.File
import java.io.OutputStream
import kotlin.concurrent.thread

/**
 * A take sent through the share sheet, or saved to a file the person picks with Android's own
 * file picker (so anywhere, Nextcloud included), as a file named after it.
 */
object Send {
    /** [mime] is what Send tells other apps; [saveMime] is what the file picker is asked for. */
    enum class What(val extension: String, val mime: String, val saveMime: String = mime) {
        Midi("mid", "audio/midi"),
        // Plain XML for Send, so Files and mail take it; the picker is given the MusicXML type
        // so the file keeps its own ending.
        Sheet("musicxml", "application/xml", "application/vnd.recordare.musicxml+xml"),
        Recording("wav", "audio/wav"),
    }

    /** The take as [what], written to [out]; the recording is streamed, not read whole. */
    private fun write(context: Context, take: Take, what: What, out: OutputStream) = when (what) {
        What.Midi -> out.write(Midi.write(take.score()))
        What.Sheet -> out.write(MusicXml.write(take.score()).toByteArray())
        What.Recording -> Takes.get(context).wav(take.id).inputStream().use { it.copyTo(out, 1 shl 16) }
    }

    /**
     * Writes the take to the file the picker made at [uri], off the main thread: the recording
     * can be tens of megabytes and the place picked may be on the network.
     */
    fun save(context: Context, take: Take, what: What, uri: Uri) {
        val app = context.applicationContext
        thread(name = "hum-save") {
            val ok = runCatching {
                app.contentResolver.openOutputStream(uri, "wt")!!.use { write(app, take, what, it) }
            }.isSuccess
            // Said along the foot until the next press, not in a toast that goes by itself.
            ContextCompat.getMainExecutor(app).execute {
                Notice.say(app.getString(if (ok) R.string.save_done else R.string.save_failed))
            }
        }
    }

    /** Files handed out more than this long ago are cleared; a newer one may still be being read. */
    private const val KEEP_SENT_MS = 60 * 60 * 1000L

    /** Clears what was handed to other apps long enough ago that they have read it. */
    fun clearOld(context: Context) {
        val cutoff = System.currentTimeMillis() - KEEP_SENT_MS
        File(context.cacheDir, "send").listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }

    /**
     * Hands the take to another app through the share sheet. The file is written off the main
     * thread, since the recording can be tens of megabytes; the share sheet opens once it is.
     */
    fun send(context: Context, take: Take, what: What) {
        val app = context.applicationContext
        thread(name = "hum-send") {
            val file = runCatching {
                val dir = File(app.cacheDir, "send").apply { mkdirs() }
                clearOld(app)
                val file = File(dir, fileName(take.name) + "." + what.extension)
                try {
                    file.outputStream().use { write(app, take, what, it) }
                } catch (e: Throwable) {
                    file.delete()
                    throw e
                }
                file
            }.getOrNull()
            ContextCompat.getMainExecutor(app).execute {
                runCatching {
                    val f = file ?: error("not written")
                    val uri = FileProvider.getUriForFile(app, app.packageName + ".files", f)
                    val send = Intent(Intent.ACTION_SEND)
                        .setType(what.mime)
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .putExtra(Intent.EXTRA_TITLE, f.name)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    send.clipData = ClipData.newRawUri(f.name, uri)
                    context.startActivity(Intent.createChooser(send, f.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onFailure {
                    Notice.say(app.getString(R.string.send_failed))
                }
            }
        }
    }

    /** The take's name with nothing a file system minds. */
    fun fileName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), " ").replace(Regex("\\s+"), " ").trim().take(60).ifBlank { "hum" }
}
