package com.pythonnxd.a8ostpatcher.privileged

import android.os.ParcelFileDescriptor
import androidx.annotation.Keep
import java.io.File
import kotlin.system.exitProcess

/**
 * Shizuku UserService running in the privileged adb shell (uid 2000) or root (uid 0) process.
 * Streams files via OS ParcelFileDescriptors (no 1 MB Binder limit) and restricts writes/deletes
 * strictly to shared storage paths.
 */
@Keep
class PrivilegedFileService : IPrivilegedFileService.Stub() {

    private fun isAllowedStoragePath(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        val norm = try {
            File(path).canonicalPath
        } catch (_: Exception) {
            File(path).absolutePath
        }
        return norm.startsWith("/storage/") ||
            norm.startsWith("/sdcard/") ||
            norm.startsWith("/data/media/")
    }

    override fun destroy() {
        exitProcess(0)
    }

    override fun exists(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        return try {
            File(path).exists()
        } catch (_: Exception) {
            false
        }
    }

    override fun isDirectory(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        return try {
            File(path).isDirectory
        } catch (_: Exception) {
            false
        }
    }

    override fun isFile(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        return try {
            File(path).isFile
        } catch (_: Exception) {
            false
        }
    }

    override fun length(path: String?): Long {
        if (path.isNullOrBlank()) return 0L
        return try {
            val f = File(path)
            if (f.isFile) f.length() else 0L
        } catch (_: Exception) {
            0L
        }
    }

    override fun listEntries(dirPath: String?): Array<String> {
        if (dirPath.isNullOrBlank()) return emptyArray()
        return try {
            val dir = File(dirPath)
            if (!dir.isDirectory) return emptyArray()
            val files = dir.listFiles() ?: return emptyArray()
            files.map { f ->
                val kind = if (f.isDirectory) "D" else "F"
                val sz = if (f.isFile) f.length() else 0L
                "$kind|${f.name}|$sz"
            }.toTypedArray()
        } catch (_: Exception) {
            emptyArray()
        }
    }

    override fun mkdirs(path: String?): Boolean {
        if (!isAllowedStoragePath(path)) return false
        return try {
            val f = File(path!!)
            f.isDirectory || f.mkdirs()
        } catch (_: Exception) {
            false
        }
    }

    override fun deletePath(path: String?): Boolean {
        if (!isAllowedStoragePath(path)) return false
        return try {
            val f = File(path!!)
            if (!f.exists()) true
            else if (f.isFile) f.delete()
            else false
        } catch (_: Exception) {
            false
        }
    }

    override fun testWrite(dirPath: String?): Boolean {
        if (!isAllowedStoragePath(dirPath)) return false
        return try {
            val dir = File(dirPath!!)
            if (!dir.isDirectory && !dir.mkdirs()) return false
            val probe = File(dir, ".__a8_priv_probe_${System.currentTimeMillis()}")
            probe.writeText("ok")
            val ok = probe.isFile
            probe.delete()
            ok
        } catch (_: Exception) {
            false
        }
    }

    override fun openRead(path: String?): ParcelFileDescriptor? {
        if (!isAllowedStoragePath(path)) return null
        return try {
            val f = File(path!!)
            if (!f.isFile) return null
            ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (_: Exception) {
            null
        }
    }

    override fun openWrite(path: String?): ParcelFileDescriptor? {
        if (!isAllowedStoragePath(path)) return null
        return try {
            val f = File(path!!)
            f.parentFile?.mkdirs()
            ParcelFileDescriptor.open(
                f,
                ParcelFileDescriptor.MODE_CREATE or
                    ParcelFileDescriptor.MODE_WRITE_ONLY or
                    ParcelFileDescriptor.MODE_TRUNCATE,
            )
        } catch (_: Exception) {
            null
        }
    }
}
