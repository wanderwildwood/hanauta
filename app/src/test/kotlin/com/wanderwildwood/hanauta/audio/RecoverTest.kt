package com.wanderwildwood.hanauta.audio

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecoverTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun aRecordingWithNoTakeIsFound() {
        val dir = tmp.newFolder("takes")
        for (name in listOf("100.wav", "100.json", "100.pitch", "200.wav", "300.wav", "300.json.tmp", "300.begun", "500.begun", "notes.wav", "400.json")) {
            dir.resolve(name).writeText("x")
        }
        assertEquals(listOf(200L, 300L), Recover.orphans(dir))
    }

    @Test fun whatATakeWasBegunWithIsAStrayOnlyWithNoRecording() {
        val dir = tmp.newFolder("strays")
        for (name in listOf("100.wav", "100.begun", "200.begun", "300.json", "300.begun", "notes.txt")) {
            dir.resolve(name).writeText("x")
        }
        assertEquals(listOf("200.begun", "300.begun"), Recover.strays(dir).map { it.name }.sorted())
        assertEquals(emptyList<java.io.File>(), Recover.strays(tmp.root.resolve("nowhere")))
    }

    @Test fun anEmptyOrMissingFolderHasNone() {
        assertEquals(emptyList<Long>(), Recover.orphans(tmp.newFolder("empty")))
        assertEquals(emptyList<Long>(), Recover.orphans(tmp.root.resolve("nowhere")))
    }
}
