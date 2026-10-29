package com.wanderwildwood.hanauta.data

import android.content.Context
import com.wanderwildwood.hanauta.music.Grid
import com.wanderwildwood.hanauta.music.Key
import com.wanderwildwood.hanauta.music.Note
import com.wanderwildwood.hanauta.music.Placing
import com.wanderwildwood.hanauta.music.Score
import com.wanderwildwood.hanauta.music.SungNote
import com.wanderwildwood.hanauta.music.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * One take: the recording, the notes as they were sung, and the notes on the grid as they
 * stand after any fixing. [sung] is kept so a change of key, grid or tempo can place them again.
 */
data class Take(
    val id: Long,
    val name: String,
    val seconds: Float,
    val tempo: Float,
    val beatsPerBar: Int,
    val key: Key,
    val grid: Grid,
    val beatZero: Float,
    val tuning: Float,
    /** Recorded to the click, so the tempo and the bar lines are known, not guessed. */
    val clicked: Boolean,
    val sung: List<SungNote>,
    val notes: List<Note>,
) {
    val placing get() = Placing(tempo, beatsPerBar, key, grid, beatZero, tuning)
    fun score() = Score(name, notes, tempo, beatsPerBar, key)

    fun toJson(): JSONObject = JSONObject()
        .put("v", 1)
        .put("id", id).put("name", name).put("seconds", seconds.toDouble())
        .put("tempo", tempo.toDouble()).put("beats", beatsPerBar)
        .put("tonic", key.tonic).put("minor", key.minor)
        .put("grid", grid.name)
        .put("beatZero", beatZero.toDouble()).put("tuning", tuning.toDouble())
        .put("clicked", clicked)
        .put("sung", JSONArray().apply { sung.forEach { put(JSONArray().put(it.start.toDouble()).put(it.end.toDouble()).put(it.pitch.toDouble())) } })
        .put("notes", JSONArray().apply { notes.forEach { put(JSONArray().put(it.start).put(it.length).put(it.pitch)) } })

    companion object {
        fun fromJson(o: JSONObject) = Take(
            id = o.getLong("id"),
            name = o.getString("name"),
            seconds = o.getDouble("seconds").toFloat(),
            tempo = o.getDouble("tempo").toFloat(),
            beatsPerBar = o.getInt("beats"),
            key = Key(o.getInt("tonic"), o.getBoolean("minor")),
            grid = runCatching { Grid.valueOf(o.getString("grid")) }.getOrDefault(Grid.Eighth),
            beatZero = o.getDouble("beatZero").toFloat(),
            tuning = o.getDouble("tuning").toFloat(),
            clicked = o.optBoolean("clicked", false),
            sung = o.getJSONArray("sung").let { a ->
                (0 until a.length()).map { a.getJSONArray(it).let { n -> SungNote(n.getDouble(0).toFloat(), n.getDouble(1).toFloat(), n.getDouble(2).toFloat()) } }
            },
            notes = o.getJSONArray("notes").let { a ->
                (0 until a.length()).map { a.getJSONArray(it).let { n -> Note(n.getInt(0), n.getInt(1), n.getInt(2)) } }
            },
        )
    }
}

/**
 * The takes, each as three files in the app's own storage, named by its id: the take as JSON,
 * the recording as WAV, and the pitch the voice took through it, for the editor to draw.
 */
class Takes private constructor(context: Context) {
    val dir = File(context.filesDir, "takes").apply { mkdirs() }

    private val _version = MutableStateFlow(0)
    /** Goes up on every change, for the screens to read again. */
    val version: StateFlow<Int> = _version

    fun json(id: Long) = File(dir, "$id.json")
    fun wav(id: Long) = File(dir, "$id.wav")
    private fun pitch(id: Long) = File(dir, "$id.pitch")

    /** Newest first. */
    fun all(): List<Take> = (dir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray())
        .mapNotNull { runCatching { Take.fromJson(JSONObject(it.readText())) }.getOrNull() }
        .sortedByDescending { it.id }

    fun get(id: Long): Take? = runCatching { Take.fromJson(JSONObject(json(id).readText())) }.getOrNull()

    fun save(take: Take) {
        val tmp = File(dir, "${take.id}.json.tmp")
        tmp.writeText(take.toJson().toString())
        tmp.renameTo(json(take.id))
        _version.value++
    }

    private val bin = Bin(dir, File(dir.parentFile, "takes-bin")).also { it.empty() }

    private val _removed = MutableStateFlow<Take?>(null)
    /** The take just deleted, while Undo is on offer. */
    val removed: StateFlow<Take?> = _removed

    /** A delete the user asked for: the take waits in the bin for Undo until [settle]. */
    fun remove(id: Long) {
        val t = get(id)
        bin.put(id)
        _removed.value = t
        _version.value++
    }

    fun undo() {
        if (bin.restore() != null) {
            _removed.value = null
            _version.value++
        }
    }

    /** Makes a delete final: the user moved on. */
    fun settle() {
        if (_removed.value == null) return
        bin.empty()
        _removed.value = null
    }

    fun delete(id: Long) {
        json(id).delete(); wav(id).delete(); pitch(id).delete()
        _version.value++
    }

    fun savePitch(id: Long, track: Track) {
        DataOutputStream(pitch(id).outputStream().buffered()).use { out ->
            out.writeFloat(track.offset)
            out.writeInt(track.size)
            for (m in track.midi) out.writeFloat(m)
        }
    }

    /** The voice's pitch, a reading every hundredth of a second; empty if it was never kept. */
    fun pitchOf(id: Long): Track? = runCatching {
        DataInputStream(pitch(id).inputStream().buffered()).use { ins ->
            val offset = ins.readFloat()
            val n = ins.readInt()
            val midi = FloatArray(n) { ins.readFloat() }
            Track(midi, FloatArray(0), offset)
        }
    }.getOrNull()

    companion object {
        @Volatile private var instance: Takes? = null
        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: Takes(context.applicationContext).also { instance = it }
        }
    }
}
