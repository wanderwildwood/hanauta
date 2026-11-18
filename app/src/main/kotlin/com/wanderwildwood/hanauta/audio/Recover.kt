package com.wanderwildwood.hanauta.audio

import android.content.Context
import com.wanderwildwood.hanauta.data.Prefs
import com.wanderwildwood.hanauta.data.Take
import com.wanderwildwood.hanauta.data.Takes
import com.wanderwildwood.hanauta.music.Tracker
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/**
 * Takes cut off before their notes were found: the app stopped, or the phone switched off,
 * while a recording was being made or read. The recording is on disk with no take beside it, so
 * it was never listed. At the next start each one is read for its notes and becomes a take; one
 * that is not a recording, is under a second or holds no notes, is deleted. One whose reading
 * failed (out of memory, or the disk would not give it up) is kept, to be read at a later start:
 * it may be the only copy of what was sung.
 */
object Recover {

    @Volatile private var started = false

    /** Ids of the recordings in [dir] that have no take beside them, oldest first. */
    fun orphans(dir: File): List<Long> =
        (dir.list() ?: emptyArray()).asSequence()
            .filter { it.endsWith(".wav") }
            .mapNotNull { it.removeSuffix(".wav").toLongOrNull() }
            .filter { !File(dir, "$it.json").exists() }
            .sorted()
            .toList()

    /**
     * Once per process, and only while nothing is being recorded or read: the list is made at
     * once, so a take begun after this call is never mistaken for a cut-off one.
     */
    fun start(context: Context) {
        if (started || Recording.busy) return
        started = true
        val app = context.applicationContext
        val takes = Takes.get(app)
        val ids = orphans(takes.dir)
        if (ids.isEmpty()) return
        thread(name = "hum-recover", priority = Thread.MIN_PRIORITY) {
            for (id in ids) {
                val ok = try { readIn(app, takes, id) } catch (_: Throwable) { continue }
                if (!ok) takes.delete(id)
            }
        }
    }

    private fun readIn(context: Context, takes: Takes, id: Long): Boolean {
        // Cut off between writing the take and putting it in place: the take is already made.
        val tmp = File(takes.dir, "$id.json.tmp")
        if (tmp.exists()) {
            val kept = runCatching { Take.fromJson(JSONObject(tmp.readText())) }.getOrNull()
            tmp.delete()
            if (kept != null && kept.id == id) {
                takes.save(kept)
                return true
            }
        }
        // Read already brought down to the rate the notes are found at: a long take fits.
        val (samples, rate) = Wav.readDown(takes.wav(id), Tracker.RATE) ?: return false
        // Under a second is a press of Record and nothing more.
        if (rate <= 0 || samples.size < rate) return false
        val made = RecordService.takeOf(
            context, id, samples, rate,
            name = null, tempo = null, beatZero = null,
            beats = Prefs(context).beatsPerBar,
            onProgress = {},
        ) ?: return false
        takes.savePitch(id, made.second)
        takes.save(made.first)
        return true
    }
}
