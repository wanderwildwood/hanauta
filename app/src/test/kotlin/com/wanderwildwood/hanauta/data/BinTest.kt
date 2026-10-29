package com.wanderwildwood.hanauta.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BinTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun setup(): Triple<File, File, Bin> {
        val dir = tmp.newFolder("takes")
        val bin = File(tmp.root, "takes-bin")
        return Triple(dir, bin, Bin(dir, bin))
    }

    private fun take(dir: File, id: Long) {
        File(dir, "$id.json").writeText("{}")
        File(dir, "$id.wav").writeText("w")
        File(dir, "$id.pitch").writeText("p")
    }

    @Test fun `a deleted take leaves the list and waits`() {
        val (dir, _, bin) = setup()
        take(dir, 1)
        bin.put(1)
        assertFalse(File(dir, "1.json").exists())
        assertEquals(1L, bin.waiting)
    }

    @Test fun `undo puts all three files back`() {
        val (dir, _, bin) = setup()
        take(dir, 1)
        bin.put(1)
        assertEquals(1L, bin.restore())
        assertTrue(File(dir, "1.json").exists() && File(dir, "1.wav").exists() && File(dir, "1.pitch").exists())
        assertNull(bin.waiting)
        assertNull(bin.restore())
    }

    @Test fun `moving on erases it for good`() {
        val (dir, binDir, bin) = setup()
        take(dir, 1)
        bin.put(1)
        bin.empty()
        assertFalse(binDir.exists())
        assertNull(bin.restore())
        assertFalse(File(dir, "1.wav").exists())
    }

    @Test fun `a second delete makes the first final`() {
        val (dir, _, bin) = setup()
        take(dir, 1); take(dir, 2)
        bin.put(1)
        bin.put(2)
        assertEquals(2L, bin.restore())
        assertTrue(File(dir, "2.json").exists())
        assertFalse(File(dir, "1.json").exists())
    }

    @Test fun `a take with no pitch file still goes and comes back`() {
        val (dir, _, bin) = setup()
        File(dir, "3.json").writeText("{}")
        bin.put(3)
        assertEquals(3L, bin.restore())
        assertTrue(File(dir, "3.json").exists())
    }
}
