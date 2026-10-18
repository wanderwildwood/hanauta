package com.wanderwildwood.hanauta.music

import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/** What a tune is written out with, besides its notes. */
data class Score(
    val name: String,
    val notes: List<Note>,
    val tempo: Float,
    val beatsPerBar: Int,
    val key: Key,
)

/**
 * A standard MIDI file, type 1: a first track with the tempo, metre, key and name, and a second
 * with the notes on channel 1, piano. Every DAW reads it; in LMMS it is Project → Import.
 */
object Midi {
    private const val VELOCITY = 96

    fun write(score: Score): ByteArray {
        val out = ByteArrayOutputStream()
        out.ascii("MThd")
        out.int32(6)
        out.int16(1)       // type 1
        out.int16(2)       // two tracks
        out.int16(PPQ)
        out.track(conductor(score))
        out.track(melody(score))
        return out.toByteArray()
    }

    private fun conductor(s: Score): ByteArray {
        val t = ByteArrayOutputStream()
        t.meta(0x03, s.name.toByteArray(Charsets.UTF_8))
        val microsPerQuarter = (60_000_000f / s.tempo).roundToInt()
        t.meta(0x51, byteArrayOf((microsPerQuarter shr 16).toByte(), (microsPerQuarter shr 8).toByte(), microsPerQuarter.toByte()))
        // Beats per bar over a quarter note; 24 clocks a click, 8 thirty-seconds a quarter.
        t.meta(0x58, byteArrayOf(s.beatsPerBar.toByte(), 2, 24, 8))
        if (!s.key.chromatic) t.meta(0x59, byteArrayOf(s.key.fifths.toByte(), if (s.key.minor) 1 else 0))
        t.endOfTrack()
        return t.toByteArray()
    }

    private fun melody(s: Score): ByteArray {
        val t = ByteArrayOutputStream()
        t.meta(0x03, s.name.toByteArray(Charsets.UTF_8))
        t.varLen(0); t.write(0xC0); t.write(0)  // program: acoustic grand piano
        // Note-offs before note-ons at the same tick, so a note ending where the next begins
        // never cuts the next one off.
        data class Ev(val tick: Int, val on: Boolean, val pitch: Int)
        val events = s.notes.flatMap { listOf(Ev(it.start, true, it.pitch), Ev(it.end, false, it.pitch)) }
            .sortedWith(compareBy<Ev> { it.tick }.thenBy { it.on })
        var now = 0
        for (e in events) {
            t.varLen(e.tick - now)
            now = e.tick
            t.write(if (e.on) 0x90 else 0x80)
            t.write(e.pitch.coerceIn(0, 127))
            t.write(if (e.on) VELOCITY else 0x40)
        }
        t.endOfTrack()
        return t.toByteArray()
    }

    private fun ByteArrayOutputStream.track(body: ByteArray) {
        ascii("MTrk")
        int32(body.size)
        write(body)
    }

    private fun ByteArrayOutputStream.meta(type: Int, data: ByteArray) {
        varLen(0)
        write(0xFF); write(type)
        varLen(data.size)
        write(data)
    }

    private fun ByteArrayOutputStream.endOfTrack() = meta(0x2F, ByteArray(0))

    private fun ByteArrayOutputStream.ascii(s: String) = write(s.toByteArray(Charsets.US_ASCII))
    private fun ByteArrayOutputStream.int32(v: Int) { write(v ushr 24); write(v ushr 16); write(v ushr 8); write(v) }
    private fun ByteArrayOutputStream.int16(v: Int) { write(v ushr 8); write(v) }

    /** MIDI's variable-length number: seven bits a byte, the high bit set on all but the last. */
    fun ByteArrayOutputStream.varLen(value: Int) {
        require(value >= 0)
        var buffer = value and 0x7F
        var v = value ushr 7
        while (v > 0) {
            buffer = (buffer shl 8) or 0x80 or (v and 0x7F)
            v = v ushr 7
        }
        while (true) {
            write(buffer and 0xFF)
            if (buffer and 0x80 != 0) buffer = buffer ushr 8 else break
        }
    }
}

/** Note names as a musician reads them, spelt with sharps or flats as the key has them. */
object NoteNames {
    private val SHARPS = arrayOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")
    private val FLATS = arrayOf("C", "D♭", "D", "E♭", "E", "F", "G♭", "G", "A♭", "A", "B♭", "B")

    fun flats(key: Key) = key.fifths < 0

    fun pitchClass(pc: Int, flats: Boolean) = (if (flats) FLATS else SHARPS)[((pc % 12) + 12) % 12]

    /** "E4": middle C is C4. */
    fun name(pitch: Int, key: Key) = pitchClass(pitch, flats(key)) + (pitch / 12 - 1)
}
