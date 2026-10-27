package com.wanderwildwood.hanauta.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import com.wanderwildwood.hanauta.R
import com.wanderwildwood.hanauta.data.Take
import com.wanderwildwood.hanauta.data.Takes
import com.wanderwildwood.hanauta.music.Midi
import com.wanderwildwood.hanauta.music.MusicXml
import java.io.File

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

    private fun bytes(context: Context, take: Take, what: What): ByteArray = when (what) {
        What.Midi -> Midi.write(take.score())
        What.Sheet -> MusicXml.write(take.score()).toByteArray()
        What.Recording -> Takes.get(context).wav(take.id).readBytes()
    }

    /** Writes the take to the file the picker made at [uri]. */
    fun save(context: Context, take: Take, what: What, uri: Uri) {
        val ok = runCatching {
            context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes(context, take, what)) }
        }.isSuccess
        Toast.makeText(context, context.getString(if (ok) R.string.save_done else R.string.save_failed), Toast.LENGTH_LONG).show()
    }

    fun send(context: Context, take: Take, what: What) {
        runCatching {
            val dir = File(context.cacheDir, "send").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, fileName(take.name) + "." + what.extension)
            file.writeBytes(bytes(context, take, what))
            val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
            val send = Intent(Intent.ACTION_SEND)
                .setType(what.mime)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_TITLE, file.name)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            send.clipData = ClipData.newRawUri(file.name, uri)
            context.startActivity(Intent.createChooser(send, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            Toast.makeText(context, context.getString(R.string.send_failed), Toast.LENGTH_LONG).show()
        }
    }

    /** The take's name with nothing a file system minds. */
    fun fileName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), " ").replace(Regex("\\s+"), " ").trim().take(60).ifBlank { "hum" }
}
