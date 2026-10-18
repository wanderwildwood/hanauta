package com.wanderwildwood.hanauta.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import com.wanderwildwood.hanauta.R
import com.wanderwildwood.hanauta.data.Take
import com.wanderwildwood.hanauta.data.Takes
import com.wanderwildwood.hanauta.music.Midi
import com.wanderwildwood.hanauta.music.MusicXml
import java.io.File

/**
 * A take sent through the share sheet, as a file named after it: Files sends it on to the
 * computer, or anything else that takes a file does.
 */
object Send {
    enum class What(val extension: String, val mime: String) {
        Midi("mid", "audio/midi"),
        Sheet("musicxml", "application/vnd.recordare.musicxml+xml"),
        Recording("wav", "audio/wav"),
    }

    fun send(context: Context, take: Take, what: What) {
        runCatching {
            val dir = File(context.cacheDir, "send").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, fileName(take.name) + "." + what.extension)
            when (what) {
                What.Midi -> file.writeBytes(Midi.write(take.score()))
                What.Sheet -> file.writeText(MusicXml.write(take.score()))
                What.Recording -> Takes.get(context).wav(take.id).copyTo(file, overwrite = true)
            }
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
