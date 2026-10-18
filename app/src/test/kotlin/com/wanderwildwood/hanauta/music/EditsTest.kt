package com.wanderwildwood.hanauta.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditsTest {
    private val tune = listOf(Note(0, 480, 60), Note(480, 240, 62), Note(960, 480, 64))

    @Test fun moves() {
        assertEquals(61, Edits.move(tune, 0, 1).notes[0].pitch)
        assertEquals(59, Edits.move(tune, 0, -1).notes[0].pitch)
    }

    @Test fun `lengthens up to the next note only`() {
        assertEquals(480, Edits.lengthen(tune, 1, 1, 240).notes[1].length)
        assertEquals(480, Edits.lengthen(tune, 1, 5, 240).notes[1].length)
        assertEquals(240, Edits.lengthen(tune, 0, -1, 240).notes[0].length)
        assertEquals(240, Edits.lengthen(tune, 1, -3, 240).notes[1].length)
        assertEquals(1440, Edits.lengthen(tune, 2, 4, 240).notes[2].length)
    }

    @Test fun merges() {
        val r = Edits.mergeNext(tune, 0)
        assertEquals(listOf(Note(0, 720, 60), Note(960, 480, 64)), r.notes)
        assertEquals(tune, Edits.mergeNext(tune, 2).notes)
    }

    @Test fun splits() {
        val r = Edits.split(tune, 0, 240)
        assertEquals(listOf(Note(0, 240, 60), Note(240, 240, 60)), r.notes.take(2))
        assertEquals(tune, Edits.split(tune, 1, 240).notes)
    }

    @Test fun deletes() {
        val r = Edits.delete(tune, 2)
        assertEquals(2, r.notes.size)
        assertEquals(1, r.selected)
        assertEquals(-1, Edits.delete(listOf(Note(0, 1, 60)), 0).selected)
    }

    @Test fun undo() {
        val u = Undo<List<Note>>(limit = 2)
        assertFalse(u.canUndo)
        u.push(tune); u.push(emptyList()); u.push(tune.take(1))
        assertEquals(tune.take(1), u.pop())
        assertEquals(emptyList<Note>(), u.pop())
        assertTrue(u.pop() == null)
    }
}
