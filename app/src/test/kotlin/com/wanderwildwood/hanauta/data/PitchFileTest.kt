package com.wanderwildwood.hanauta.data

import com.wanderwildwood.hanauta.music.Track
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class PitchFileTest {
    @Test fun `a new file keeps its spacing`() {
        val track = Track(floatArrayOf(60f, Float.NaN, 62.5f), FloatArray(3), 0.023f)
        val bytes = ByteArrayOutputStream().also { PitchFile.write(it, track) }.toByteArray()
        val back = PitchFile.read(ByteArrayInputStream(bytes))
        assertEquals(Track.HOP_SECONDS, back.hop, 0f)
        assertEquals(0.023f, back.offset, 0f)
        assertArrayEquals(track.midi, back.midi, 0f)
        assertEquals(track.time(2), back.time(2), 0f)
    }

    @Test fun `a file from before the marker is read every hundredth of a second`() {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { d ->
            d.writeFloat(0.023f)
            d.writeInt(18_001)
            repeat(18_001) { d.writeFloat(60f) }
        }
        val back = PitchFile.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals(PitchFile.OLD_HOP, back.hop, 0f)
        assertEquals(0.023f, back.offset, 0f)
        assertEquals(18_001, back.size)
        // Three minutes in, where the old notes were placed.
        assertEquals(0.023f + 180f, back.time(18_000), 1e-3f)
    }
}
