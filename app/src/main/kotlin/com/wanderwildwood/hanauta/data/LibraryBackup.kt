package com.wanderwildwood.hanauta.data

import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The whole library as one zip, written and read through the phone's file picker: every take's
 * three files (`<id>.json`, `<id>.wav`, `<id>.pitch`) under `takes/`, and a small JSON head with
 * the settings. Plain files and streams, so it can be tested without a phone.
 *
 * [unpack] reads the whole file into a staging folder and checks every take in it before the
 * caller swaps anything in; a wrong or broken file changes nothing.
 */
object LibraryBackup {

    const val FORMAT = "hanauta-backup"
    const val VERSION = 1
    const val HEAD = "hanauta-backup.json"
    private const val TAKES = "takes/"

    private val TAKE_FILE = Regex("""(\d{1,19})\.(json|wav|pitch)""")
    private const val MAX_HEAD_BYTES = 1024 * 1024

    class Refused(val kind: Kind) : Exception(kind.name) {
        enum class Kind { NOT_A_BACKUP, NEWER, DAMAGED }
    }

    /** A backup unpacked and checked: its takes folder in staging, how many takes, and its settings. */
    data class Unpacked(val takes: File, val count: Int, val settings: Map<String, Any>)

    /** The choices on the Settings and Record screens, by their stored keys. */
    val SETTING_KEYS = listOf("tempo", "beats", "click", "grid", "turn_with_phone", "play_voice")

    fun write(out: OutputStream, takesDir: File, settings: Map<String, Any>, written: Instant = Instant.now()) {
        val zip = ZipOutputStream(out)
        val head = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("written", written.toString())
            .put("settings", JSONObject().apply { for (k in SETTING_KEYS) settings[k]?.let { put(k, it) } })
        zip.putNextEntry(ZipEntry(HEAD))
        zip.write(head.toString(2).toByteArray(Charsets.UTF_8))
        zip.closeEntry()
        val files = takesDir.listFiles { f -> f.isFile && TAKE_FILE.matches(f.name) }.orEmpty().sortedBy { it.name }
        for (f in files) {
            zip.putNextEntry(ZipEntry(TAKES + f.name))
            f.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
        zip.finish()
        out.flush()
    }

    /**
     * Unpacks [input] into [staging] (emptied first) and checks it whole: the head must be ours and
     * no newer than this app, and every take must read, under its own id. Recordings and pitch
     * files with no take beside them are dropped. Any other entry is skipped.
     */
    fun unpack(input: InputStream, staging: File): Unpacked {
        staging.deleteRecursively()
        val takes = File(staging, "takes").apply { mkdirs() }
        var head: JSONObject? = null
        try {
            val zip = ZipInputStream(input)
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name.replace('\\', '/')
                when {
                    name == HEAD -> head = try {
                        JSONObject(String(readCapped(zip), Charsets.UTF_8))
                    } catch (e: JSONException) {
                        throw Refused(Refused.Kind.NOT_A_BACKUP)
                    }
                    name.startsWith(TAKES) && TAKE_FILE.matches(name.removePrefix(TAKES)) ->
                        File(takes, name.removePrefix(TAKES)).outputStream().use { zip.copyTo(it) }
                }
            }
        } catch (e: Refused) {
            throw e
        } catch (e: ZipException) {
            throw Refused(Refused.Kind.DAMAGED)
        } catch (e: EOFException) {
            throw Refused(Refused.Kind.DAMAGED)
        }
        val h = head ?: throw Refused(Refused.Kind.NOT_A_BACKUP)
        if (h.optString("format") != FORMAT) throw Refused(Refused.Kind.NOT_A_BACKUP)
        val version = h.optInt("version", -1)
        if (version > VERSION) throw Refused(Refused.Kind.NEWER)
        if (version < 1) throw Refused(Refused.Kind.DAMAGED)
        val settings = try {
            readSettings(h.getJSONObject("settings"))
        } catch (e: Exception) {
            throw Refused(Refused.Kind.DAMAGED)
        }

        val files = takes.listFiles().orEmpty()
        val ids = files.filter { it.name.endsWith(".json") }.map { f ->
            val id = f.name.removeSuffix(".json").toLong()
            val take = try {
                Take.fromJson(JSONObject(f.readText()))
            } catch (e: Exception) {
                throw Refused(Refused.Kind.DAMAGED)
            }
            if (take.id != id) throw Refused(Refused.Kind.DAMAGED)
            id
        }.toSet()
        for (f in files) {
            val id = TAKE_FILE.matchEntire(f.name)!!.groupValues[1].toLong()
            if (id !in ids) f.delete()
        }
        return Unpacked(takes, ids.size, settings)
    }

    private fun readSettings(o: JSONObject): Map<String, Any> {
        val out = linkedMapOf<String, Any>()
        if (o.has("tempo")) out["tempo"] = o.getInt("tempo").also { require(it in Prefs.MIN_TEMPO..Prefs.MAX_TEMPO) }
        if (o.has("beats")) out["beats"] = o.getInt("beats").also { require(it in 1..16) }
        if (o.has("click")) out["click"] = o.getBoolean("click")
        if (o.has("grid")) out["grid"] = o.getString("grid").also { g ->
            require(com.wanderwildwood.hanauta.music.Grid.entries.any { it.name == g })
        }
        if (o.has("turn_with_phone")) out["turn_with_phone"] = o.getBoolean("turn_with_phone")
        if (o.has("play_voice")) out["play_voice"] = o.getBoolean("play_voice")
        return out
    }

    private fun readCapped(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_HEAD_BYTES) throw Refused(Refused.Kind.NOT_A_BACKUP)
        }
        return out.toByteArray()
    }

    /**
     * Puts [staged] where [live] is: the live folder is set aside, the staged one moved in, and
     * only then is the old one erased. If the move fails the old folder goes back. Same disk only.
     */
    fun swapIn(staged: File, live: File): Boolean {
        val previous = File(live.parentFile, live.name + "-before-restore").apply { deleteRecursively() }
        if (live.exists() && !live.renameTo(previous)) return false
        if (!staged.renameTo(live)) {
            if (!live.exists() && previous.exists()) previous.renameTo(live)
            return false
        }
        previous.deleteRecursively()
        return true
    }
}
