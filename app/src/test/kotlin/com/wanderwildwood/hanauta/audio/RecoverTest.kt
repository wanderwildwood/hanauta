package com.wanderwildwood.hanauta.audio

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecoverTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun aRecordingWithNoTakeIsFound() {
        val dir = tmp.newFolder("takes")
        for (name in listOf("100.wav", "100.json", "100.pitch", "200.wav", "300.wav", "300.json.tmp", "notes.wav", "400.json")) {
            dir.resolve(name).writeText("x")
        }
        assertEquals(listOf(200L, 300L), Recover.orphans(dir))
    }

    @Test fun anEmptyOrMissingFolderHasNone() {
        assertEquals(emptyList<Long>(), Recover.orphans(tmp.newFolder("empty")))
        assertEquals(emptyList<Long>(), Recover.orphans(tmp.root.resolve("nowhere")))
    }
}
