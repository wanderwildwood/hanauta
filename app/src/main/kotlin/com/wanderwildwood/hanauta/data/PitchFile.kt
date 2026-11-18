package com.wanderwildwood.hanauta.data

import com.wanderwildwood.hanauta.music.Track
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * A take's `.pitch` file: a marker, the seconds between readings, the time of the first
 * reading, the count, then each reading as a fractional MIDI note.
 *
 * Files kept before the marker came in start straight with the first reading's time and were
 * read every 0.01 s; a file without the marker is read that way. The marker read as a float is
 * about 218,000 seconds, a first reading no recording ever had, so the two cannot be mixed up.
 * An app from before the marker would misread a new file, so a backup restores forward only.
 */
object PitchFile {
    /** "HUMP". */
    const val MARK = 0x48554D50
    /** The spacing every file without [MARK] was read at. */
    const val OLD_HOP = 0.01f

    fun write(out: OutputStream, track: Track) {
        val d = DataOutputStream(out)
        d.writeInt(MARK)
        d.writeFloat(track.hop)
        d.writeFloat(track.offset)
        d.writeInt(track.size)
        for (m in track.midi) d.writeFloat(m)
        d.flush()
    }

    fun read(ins: InputStream): Track {
        val d = DataInputStream(ins)
        val first = d.readInt()
        val hop: Float
        val offset: Float
        if (first == MARK) {
            hop = d.readFloat()
            offset = d.readFloat()
        } else {
            hop = OLD_HOP
            offset = Float.fromBits(first)
        }
        require(hop.isFinite() && hop > 0f)
        val n = d.readInt()
        require(n >= 0)
        val midi = FloatArray(n) { d.readFloat() }
        return Track(midi, FloatArray(0), offset, hop)
    }
}
