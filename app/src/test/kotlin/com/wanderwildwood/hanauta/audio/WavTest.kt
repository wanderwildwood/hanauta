package com.wanderwildwood.hanauta.audio

import com.wanderwildwood.hanauta.music.Tracker
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

class WavTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun tone(n: Int) = FloatArray(n) { (0.5 * sin(it * 0.031) + 0.2 * sin(it * 0.17)).toFloat() }

    @Test fun aRecordingReadsBackAsWritten() {
        val f = tmp.newFile("a.wav")
        val x = tone(20_001)
        Wav.write(f, x, 44100)
        val (y, rate) = Wav.read(f)!!
        assertEquals(44100, rate)
        assertEquals(x.size, y.size)
        for (i in x.indices) assertEquals(x[i], y[i], 1f / 16000)
    }

    @Test fun readDownIsWhatTheTrackerWouldMakeOfIt() {
        val f = tmp.newFile("b.wav")
        Wav.write(f, tone(30_003), 44100)
        val (full, rate) = Wav.read(f)!!
        val (down, downRate) = Wav.readDown(f, Tracker.RATE)!!
        assertEquals(Tracker.RATE, downRate)
        assertArrayEquals(Tracker.resample(full, rate, Tracker.RATE), down, 0f)
    }

    @Test fun aStretchReadsAsThatStretchOfTheWhole() {
        val f = tmp.newFile("s.wav")
        Wav.write(f, tone(50_000), 44100)
        val (whole, _) = Wav.read(f)!!
        Wav.Reader(f).use { r ->
            assertEquals(50_000L, r.layout!!.frames)
            val out = FloatArray(20_000)
            r.read(12_345, 20_000, out)
            assertArrayEquals(whole.copyOfRange(12_345, 32_345), out, 0f)
        }
    }

    @Test fun readDownLeavesAnOddRateAsItIs() {
        val f = tmp.newFile("c.wav")
        Wav.write(f, tone(4800), 48000)
        val (down, rate) = Wav.readDown(f, Tracker.RATE)!!
        assertEquals(48000, rate)
        assertEquals(4800, down.size)
    }

    /** RIFF, WAVE, a fmt chunk, then [extra] before a short data chunk. */
    private fun withChunk(f: File, extra: ByteArray): File {
        val b = ByteBuffer.allocate(12 + 24 + extra.size + 8 + 200).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt(b.capacity() - 8); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16)
        b.putShort(1); b.putShort(1); b.putInt(44100); b.putInt(88200); b.putShort(2); b.putShort(16)
        b.put(extra)
        b.put("data".toByteArray()); b.putInt(200)
        f.writeBytes(b.array())
        return f
    }

    private fun chunk(id: String, size: Int) =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).put(id.toByteArray()).putInt(size).array()

    @Test fun anUnknownChunkIsSteppedOver() {
        val f = withChunk(tmp.newFile("d.wav"), chunk("LIST", 0))
        assertEquals(100, Wav.read(f)!!.first.size)
    }

    @Test(timeout = 5000) fun aChunkThatPointsBackIsRefusedNotLoopedOn() {
        assertNull(Wav.read(withChunk(tmp.newFile("e.wav"), chunk("LIST", -8))))
        assertNull(Wav.read(withChunk(tmp.newFile("f.wav"), chunk("LIST", -9))))
        assertNull(Wav.readDown(withChunk(tmp.newFile("g.wav"), chunk("junk", -8)), Tracker.RATE))
    }

    @Test fun aChunkLongerThanTheFileIsRefused() {
        assertNull(Wav.read(withChunk(tmp.newFile("h.wav"), chunk("LIST", 1_000_000))))
    }
}
