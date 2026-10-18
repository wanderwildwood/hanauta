package com.wanderwildwood.hanauta.music

import org.junit.Assert.assertEquals
import org.junit.Test

class QuantiseTest {
    private val c = Key(0, false)

    @Test
    fun `between two semitones goes to the one in the key`() {
        assertEquals(64, Quantise.snap(63.45f, c))   // E, not E flat
        assertEquals(62, Quantise.snap(62.45f, c))
        assertEquals(63, Quantise.snap(63.45f, Key.Chromatic))
    }

    @Test
    fun `squarely on a note outside the key keeps it`() {
        assertEquals(66, Quantise.snap(66.1f, c))
        assertEquals(67, Quantise.snap(66.4f, c))
    }

    @Test
    fun `minor counts the raised seventh`() {
        val a = Key(9, true)
        assertEquals(true, a.contains(68)) // G sharp
        assertEquals(true, a.contains(67))
        assertEquals(false, a.contains(66))
    }

    @Test
    fun `key signatures`() {
        assertEquals(0, c.fifths)
        assertEquals(1, Key(7, false).fifths)
        assertEquals(-1, Key(5, false).fifths)
        assertEquals(-3, Key(0, true).fifths)
        assertEquals(1, Key(4, true).fifths)
        assertEquals(-2, Key(10, false).fifths)
    }

    @Test
    fun `starts and ends go to the nearest step`() {
        val sung = listOf(SungNote(1.02f, 1.27f, 60f), SungNote(1.31f, 1.88f, 62f))
        val p = Placing(120f, 4, c, Grid.Eighth, 1.0f, 0f)
        // At 120 an eighth is a quarter second.
        assertEquals(listOf(Note(0, 240, 60), Note(240, 720, 62)), Quantise.place(sung, p))
    }

    @Test
    fun `overlaps are trimmed and two at once keep the longer`() {
        val sung = listOf(SungNote(0f, 0.6f, 60f), SungNote(0.5f, 1f, 62f), SungNote(0.52f, 0.62f, 64f))
        val p = Placing(120f, 4, Key.Chromatic, Grid.Eighth, 0f, 0f)
        assertEquals(listOf(Note(0, 480, 60), Note(480, 480, 62)), Quantise.place(sung, p))
    }

    @Test
    fun `a tune started early moves on by a bar`() {
        val sung = listOf(SungNote(0.5f, 1.0f, 60f), SungNote(1.0f, 1.5f, 62f))
        val p = Quantise.align(sung, Placing(120f, 4, c, Grid.Eighth, 1.0f, 0f))
        assertEquals(-1.0f, p.beatZero, 1e-4f)
        assertEquals(listOf(Note(1440, 480, 60), Note(1920, 480, 62)), Quantise.place(sung, p))
    }

    @Test
    fun `tuning is the voice's distance from concert pitch`() {
        val sung = listOf(SungNote(0f, 1f, 60.3f), SungNote(1f, 2f, 61.2f), SungNote(2f, 3f, 64.25f))
        assertEquals(0.25f, Quantise.tuning(sung), 0.02f)
        val flat = listOf(SungNote(0f, 1f, 59.6f), SungNote(1f, 2f, 61.55f))
        assertEquals(-0.42f, Quantise.tuning(flat), 0.05f)
    }

    @Test
    fun `the key of a scale`() {
        val g = listOf(67, 69, 71, 72, 74, 76, 78, 79, 74, 67).mapIndexed { i, p -> SungNote(i.toFloat(), i + 1f, p.toFloat()) }
        assertEquals(Key(7, false), Quantise.guessKey(g, 0f))
        val a = listOf(69, 71, 72, 74, 76, 72, 69, 64, 69).mapIndexed { i, p -> SungNote(i.toFloat(), i + 1f, p.toFloat()) }
        assertEquals(Key(9, true), Quantise.guessKey(a, 0f))
    }
}
