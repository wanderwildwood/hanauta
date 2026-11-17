package com.wanderwildwood.hanauta.music

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackTimeTest {
    @Test
    fun readingsAreTimedByTheSamplesTheyReallyStandApart() {
        val track = Track(FloatArray(20_000), FloatArray(20_000))
        // Three minutes in: the reading taken at sample 18000 * 220 is heard at that sample.
        assertEquals(18_000 * 220 / 22050f, track.time(18_000), 1e-3f)
        assertEquals(Tracker.RATE.toFloat(), Track.HOP_SAMPLES / Track.HOP_SECONDS, 0.01f)
    }
}
