package com.wanderwildwood.hanauta.data

import com.wanderwildwood.hanauta.music.Grid
import com.wanderwildwood.hanauta.music.Key
import com.wanderwildwood.hanauta.music.Note
import com.wanderwildwood.hanauta.music.SungNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LibraryBackupTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun take(id: Long, name: String) = Take(
        id = id, name = name, seconds = 4.5f, tempo = 96f, beatsPerBar = 3, key = Key(2, false),
        grid = Grid.Eighth, beatZero = 0.25f, tuning = 0f, clicked = true,
        sung = listOf(SungNote(0f, 0.5f, 62.1f)), notes = listOf(Note(0, 240, 62), Note(240, 240, 64)),
    )

    private fun library(): File = tmp.newFolder("takes").apply {
        for (t in listOf(take(1_700_000_000_000, "Walking tune"), take(1_700_000_100_000, "Kettle song"))) {
            File(this, "${t.id}.json").writeText(t.toJson().toString())
            File(this, "${t.id}.wav").writeBytes(ByteArray(6000).also { java.util.Random(t.id).nextBytes(it) })
            File(this, "${t.id}.pitch").writeBytes(byteArrayOf(1, 2, 3))
        }
        File(this, "notes.txt").writeText("not a take file")
    }

    private fun backupOf(dir: File, settings: Map<String, Any> = emptyMap()): ByteArray =
        ByteArrayOutputStream().also { LibraryBackup.write(it, dir, settings, Instant.parse("2026-11-03T10:00:00Z")) }.toByteArray()

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, body) in entries) {
                z.putNextEntry(ZipEntry(name)); z.write(body.toByteArray()); z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun refusedAs(kind: LibraryBackup.Refused.Kind, bytes: ByteArray) {
        try {
            LibraryBackup.unpack(ByteArrayInputStream(bytes), tmp.newFolder())
            fail("expected $kind")
        } catch (e: LibraryBackup.Refused) {
            assertEquals(kind, e.kind)
        }
    }

    private val head = """{"format":"hanauta-backup","version":1,"settings":{}}"""

    @Test
    fun theWholeLibraryAndTheSettingsComeBack() {
        val lib = library()
        val settings = mapOf("tempo" to 96, "beats" to 3, "click" to false, "grid" to "Sixteenth", "play_voice" to true)
        val back = LibraryBackup.unpack(ByteArrayInputStream(backupOf(lib, settings)), tmp.newFolder("staging"))
        assertEquals(2, back.count)
        assertEquals(settings, back.settings)
        val names = back.takes.listFiles()!!.map { it.name }.sorted()
        assertEquals(
            listOf("1700000000000.json", "1700000000000.pitch", "1700000000000.wav", "1700000100000.json", "1700000100000.pitch", "1700000100000.wav"),
            names,
        )
        for (n in names) assertTrue(n, File(lib, n).readBytes().contentEquals(File(back.takes, n).readBytes()))
    }

    @Test
    fun restoringReplacesTheLibraryAndKeepsTheOldOneIfTheMoveFails() {
        val live = tmp.newFolder("app", "takes").apply { File(this, "5.json").writeText("old") }
        val back = LibraryBackup.unpack(ByteArrayInputStream(backupOf(library())), File(live.parentFile, "takes-restore"))
        assertTrue(LibraryBackup.swapIn(back.takes, live))
        assertFalse(File(live, "5.json").exists())
        assertEquals(6, live.listFiles()!!.size)
        assertFalse(File(live.parentFile, "takes-before-restore").exists())

        // Nothing staged to move in: the library that was there stays.
        assertFalse(LibraryBackup.swapIn(File(live.parentFile, "missing"), live))
        assertEquals(6, live.listFiles()!!.size)
    }

    @Test
    fun aFileThatIsNotOursChangesNothing() {
        refusedAs(LibraryBackup.Refused.Kind.NOT_A_BACKUP, "just some text".toByteArray())
        refusedAs(LibraryBackup.Refused.Kind.NOT_A_BACKUP, zipOf("takes/1.wav" to "sound"))
        refusedAs(LibraryBackup.Refused.Kind.NOT_A_BACKUP, zipOf(LibraryBackup.HEAD to """{"format":"something-else","version":1}"""))
    }

    @Test
    fun aNewerOrBrokenBackupIsRefused() {
        refusedAs(LibraryBackup.Refused.Kind.NEWER, zipOf(LibraryBackup.HEAD to """{"format":"hanauta-backup","version":2,"settings":{}}"""))
        refusedAs(LibraryBackup.Refused.Kind.DAMAGED, zipOf(LibraryBackup.HEAD to head, "takes/7.json" to "{not json"))
        // A take stored under another take's id.
        refusedAs(LibraryBackup.Refused.Kind.DAMAGED, zipOf(LibraryBackup.HEAD to head, "takes/7.json" to take(8, "Ada's whistle").toJson().toString()))
        refusedAs(LibraryBackup.Refused.Kind.DAMAGED, zipOf(LibraryBackup.HEAD to """{"format":"hanauta-backup","version":1,"settings":{"tempo":900}}"""))
        val whole = backupOf(library())
        refusedAs(LibraryBackup.Refused.Kind.DAMAGED, whole.copyOf(whole.size * 2 / 5))
    }

    @Test
    fun strayFilesAndNamesThatClimbOutAreLeftBehind() {
        val staging = tmp.newFolder("deep", "staging")
        val back = LibraryBackup.unpack(
            ByteArrayInputStream(
                zipOf(
                    LibraryBackup.HEAD to head,
                    "takes/3.json" to take(3, "Tomas's tune").toJson().toString(),
                    "takes/3.wav" to "sound",
                    "takes/9.wav" to "a recording with no take",
                    "takes/../escaped.json" to "no",
                    "../escaped" to "no",
                ),
            ),
            staging,
        )
        assertEquals(1, back.count)
        assertEquals(listOf("3.json", "3.wav"), back.takes.listFiles()!!.map { it.name }.sorted())
        assertFalse(File(staging, "escaped.json").exists())
        assertFalse(File(staging.parentFile, "escaped").exists())
    }
}
