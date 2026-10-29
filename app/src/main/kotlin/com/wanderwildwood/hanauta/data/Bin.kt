package com.wanderwildwood.hanauta.data

import java.io.File

/**
 * Where a deleted take waits for Undo: its files are moved here, not erased, and are erased for
 * good as soon as anything else is bound (another delete) or the user moves on. Only one take
 * waits at a time. Plain files, so it can be tested without a phone.
 */
class Bin(private val dir: File, private val bin: File) {

    /** The id waiting, or null. */
    var waiting: Long? = null
        private set

    /** Moves every file named for [id] (`<id>.json`, `<id>.wav`, `<id>.pitch`) into the bin; an earlier take there goes for good. */
    fun put(id: Long) {
        empty()
        bin.mkdirs()
        for (ext in EXTS) {
            val f = File(dir, "$id.$ext")
            if (f.exists() && !f.renameTo(File(bin, f.name))) {
                f.copyTo(File(bin, f.name), overwrite = true)
                f.delete()
            }
        }
        waiting = id
    }

    /** Puts the waiting take's files back; returns its id, or null when nothing waited. */
    fun restore(): Long? {
        val id = waiting ?: return null
        for (ext in EXTS) {
            val f = File(bin, "$id.$ext")
            if (f.exists() && !f.renameTo(File(dir, f.name))) {
                f.copyTo(File(dir, f.name), overwrite = true)
                f.delete()
            }
        }
        waiting = null
        return id
    }

    /** Erases what waits, for good. */
    fun empty() {
        bin.deleteRecursively()
        waiting = null
    }

    private companion object {
        val EXTS = listOf("json", "wav", "pitch")
    }
}
