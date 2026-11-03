package com.wanderwildwood.hanauta.data

import android.content.Context
import android.net.Uri
import java.io.File

/** Backup and restore against a file the person picked: the Android half of [LibraryBackup]. */
object BackupFiles {

    sealed interface Result {
        data class Done(val takes: Int) : Result
        data class Refused(val kind: LibraryBackup.Refused.Kind) : Result
        data object Unreadable : Result
    }

    /** Writes the backup to [uri]; false, with the half-written file removed, when that fails. */
    fun save(context: Context, uri: Uri): Boolean {
        val ok = runCatching {
            context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                LibraryBackup.write(out.buffered(), Takes.get(context).dir, Prefs(context).backupValues())
            } ?: error("no stream")
        }.isSuccess
        if (!ok) runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) }
        return ok
    }

    /** Reads and checks the whole file first; only a file that passes replaces the library and settings. */
    fun restore(context: Context, uri: Uri): Result {
        val takes = Takes.get(context)
        val staging = File(takes.dir.parentFile, "takes-restore")
        try {
            val unpacked = try {
                context.contentResolver.openInputStream(uri)?.use { LibraryBackup.unpack(it, staging) }
                    ?: return Result.Unreadable
            } catch (e: LibraryBackup.Refused) {
                return Result.Refused(e.kind)
            } catch (e: java.io.IOException) {
                return Result.Unreadable
            }
            if (!takes.replaceAll(unpacked.takes)) return Result.Unreadable
            Prefs(context).restore(unpacked.settings)
            return Result.Done(unpacked.count)
        } finally {
            staging.deleteRecursively()
        }
    }
}
