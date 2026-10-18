package com.wanderwildwood.hanauta.music

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream

/** Reads the file back the way a sequencer does, so a broken file fails here, not in a DAW. */
class MidiTest {
    private data class Ev(val tick: Int, val status: Int, val a: Int, val b: Int)

    private class Parsed(val format: Int, val division: Int, val tracks: List<List<Ev>>, val metas: List<Pair<Int, ByteArray>>)

    private fun parse(bytes: ByteArray): Parsed {
        val ins = DataInputStream(ByteArrayInputStream(bytes))
        fun tag() = String(ByteArray(4).also { ins.readFully(it) }, Charsets.US_ASCII)
        assertEquals("MThd", tag())
        assertEquals(6, ins.readInt())
        val format = ins.readUnsignedShort()
        val ntracks = ins.readUnsignedShort()
        val division = ins.readUnsignedShort()
        val tracks = ArrayList<List<Ev>>()
        val metas = ArrayList<Pair<Int, ByteArray>>()
        repeat(ntracks) {
            assertEquals("MTrk", tag())
            val len = ins.readInt()
            val body = ByteArray(len).also { ins.readFully(it) }
            var p = 0
            fun vlq(): Int { var v = 0; while (true) { val b = body[p++].toInt() and 0xFF; v = (v shl 7) or (b and 0x7F); if (b and 0x80 == 0) return v } }
            var tick = 0
            val evs = ArrayList<Ev>()
            var ended = false
            while (p < body.size) {
                tick += vlq()
                val status = body[p++].toInt() and 0xFF
                when {
                    status == 0xFF -> {
                        val type = body[p++].toInt() and 0xFF
                        val n = vlq()
                        metas += type to body.copyOfRange(p, p + n)
                        p += n
                        if (type == 0x2F) ended = true
                    }
                    status and 0xF0 == 0xC0 -> { evs += Ev(tick, status, body[p++].toInt(), 0) }
                    else -> { evs += Ev(tick, status, body[p].toInt(), body[p + 1].toInt()); p += 2 }
                }
            }
            assertEquals(true, ended)
            assertEquals(body.size, p)
            tracks += evs
        }
        assertEquals(-1, ins.read())
        return Parsed(format, division, tracks, metas)
    }

    @Test
    fun `a tune reads back as written`() {
        val notes = listOf(Note(0, 480, 60), Note(480, 240, 62), Note(720, 20000, 64))
        val p = parse(Midi.write(Score("Hum 1", notes, 100f, 3, Key(7, false))))
        assertEquals(1, p.format)
        assertEquals(480, p.division)
        assertEquals(2, p.tracks.size)
        val ons = p.tracks[1].filter { it.status == 0x90 }.map { Triple(it.tick, it.a, it.b) }
        val offs = p.tracks[1].filter { it.status == 0x80 }.map { it.tick to it.a }
        assertEquals(listOf(Triple(0, 60, 96), Triple(480, 62, 96), Triple(720, 64, 96)), ons)
        assertEquals(listOf(480 to 60, 720 to 62, 20720 to 64), offs)
        // Tempo 100 = 600000 microseconds a quarter; 3/4; G major.
        val tempo = p.metas.first { it.first == 0x51 }.second.fold(0) { a, b -> (a shl 8) or (b.toInt() and 0xFF) }
        assertEquals(600000, tempo)
        assertEquals(3, p.metas.first { it.first == 0x58 }.second[0].toInt())
        assertEquals(listOf(1, 0), p.metas.first { it.first == 0x59 }.second.map { it.toInt() })
        assertEquals("Hum 1", String(p.metas.first { it.first == 0x03 }.second))
    }

    @Test
    fun `a note ending where the next starts is let go first`() {
        val p = parse(Midi.write(Score("x", listOf(Note(0, 240, 60), Note(240, 240, 60)), 120f, 4, Key.Chromatic)))
        val at240 = p.tracks[1].filter { it.tick == 240 }.map { it.status }
        assertEquals(listOf(0x80, 0x90), at240)
        assertEquals(0, p.metas.count { it.first == 0x59 })
    }
}
