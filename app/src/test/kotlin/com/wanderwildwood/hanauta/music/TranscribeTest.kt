package com.wanderwildwood.hanauta.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class TranscribeTest {

    private fun describe(notes: List<Note>) = notes.joinToString(" ") { "${it.pitch}@${it.start}+${it.length}" }

    @Test
    fun `a hummed tune with the click comes out note for note`() {
        val samples = Hum.render(lead = 0.4)
        val t = Transcribe.run(samples, 44100, 4, Grid.Eighth, tempo = 100f, beatZero = 0.4f)
        val want = Hum.expected()
        assertEquals(describe(want), describe(t.notes))
        assertEquals(Key(0, false), t.placing.key)
        assertTrue("tuning ${t.placing.tuning}", abs(t.placing.tuning - 0.2f) < 0.08f)
    }

    @Test
    fun `without the click the tempo is found from the notes`() {
        val samples = Hum.render(lead = 0.7, tempo = 92.0, detune = -0.1, seed = 7)
        val t = Transcribe.run(samples, 44100, 4, Grid.Eighth)
        assertTrue("tempo ${t.placing.tempo}", abs(t.placing.tempo - 92f) < 2f)
        assertEquals(Hum.expected().map { it.pitch }, t.notes.map { it.pitch })
        // Placed on the grid as sung, counted from the first note.
        assertEquals(describe(Hum.expected()), describe(t.notes))
    }

    @Test
    fun `the same note sung twice is two notes`() {
        val sung = Segmenter.segment(Tracker.analyse(Hum.render(), 44100))
        val gs = sung.filter { abs(it.pitch - 67.2f) < 0.5f }
        // Held, then twice short in the first phrase; once more in the second.
        assertEquals(sung.joinToString { "%.2f-%.2f %.2f".format(it.start, it.end, it.pitch) }, 4, gs.size)
        // And nothing else was read as a note but the tune's own.
        assertEquals(Hum.expected().size, sung.size)
    }

    @Test
    fun `silence has no notes`() {
        val t = Transcribe.run(FloatArray(44100 * 2), 44100, 4, Grid.Eighth)
        assertTrue(t.notes.isEmpty())
    }

    @Test
    fun `a low hum an octave down is still read`() {
        val samples = Hum.render(detune = -12.0)
        val t = Transcribe.run(samples, 44100, 4, Grid.Eighth, tempo = 100f, beatZero = 0.4f)
        assertEquals(Hum.expected().map { it.pitch - 12 }, t.notes.map { it.pitch })
    }

    private fun scaled(by: Float) = Hum.render().let { x -> FloatArray(x.size) { x[it] * by } }

    @Test
    fun `a tune that never rises above the noise floor has no notes`() {
        // The same hum, so quiet that its loudest stretch is hiss-level: still clearly pitched,
        // which is what used to be taken for the loudest part of the take.
        val rms = kotlin.math.sqrt(scaled(0.04f).map { it * it.toDouble() }.average()).toFloat()
        assertTrue("rms $rms", rms < Segmenter.NOISE_FLOOR)
        val t = Transcribe.run(scaled(0.04f), 44100, 4, Grid.Eighth)
        assertTrue(describe(t.notes), t.notes.isEmpty())
    }

    @Test
    fun `random hiss as loud as a quiet room has no notes`() {
        val rnd = kotlin.random.Random(3)
        val hiss = FloatArray(44100 * 3) { (rnd.nextFloat() - 0.5f) * 0.02f }
        assertTrue(Transcribe.run(hiss, 44100, 4, Grid.Eighth).notes.isEmpty())
    }

    @Test
    fun `a soft but real hum above the floor is still read`() {
        val x = scaled(0.3f)
        val rms = kotlin.math.sqrt(x.map { it * it.toDouble() }.average()).toFloat()
        assertTrue("rms $rms", rms > Segmenter.NOISE_FLOOR * 2)
        val t = Transcribe.run(x, 44100, 4, Grid.Eighth, tempo = 100f, beatZero = 0.4f)
        assertEquals(Hum.expected().map { it.pitch }, t.notes.map { it.pitch })
    }
}
