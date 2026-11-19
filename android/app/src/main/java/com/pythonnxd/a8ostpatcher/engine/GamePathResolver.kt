package com.pythonnxd.a8ostpatcher.engine

import java.io.File

data class ResolvedGamePaths(
    val root: File,
    val baseDir: File,
    val dataDir: File,
    val soundpackDir: File,
    val soundpackFile: File,
    val musicDir: File,
    val textDir: File,
)

object GamePathResolver {

    private fun resolveChildCi(parent: File, targetName: String): File {
        if (parent.isDirectory) {
            val low = targetName.lowercase()
            val match = parent.listFiles()?.firstOrNull { it.name.lowercase() == low }
            if (match != null) return match
        }
        return File(parent, targetName)
    }

    private fun resolveSoundpackFile(spDir: File): File {
        val direct = resolveChildCi(spDir, "soundpack")
        if (direct.isFile) return direct
        val xmlAlt = resolveChildCi(spDir, "soundpack.xml")
        if (xmlAlt.isFile) return xmlAlt
        return direct
    }

    private fun buildPaths(root: File, base: File, data: File): ResolvedGamePaths {
        val spDir = resolveChildCi(data, "soundpack")
        return ResolvedGamePaths(
            root = root,
            baseDir = base,
            dataDir = data,
            soundpackDir = spDir,
            soundpackFile = resolveSoundpackFile(spDir),
            musicDir = resolveChildCi(data, "music"),
            textDir = resolveChildCi(data, "text"),
        )
    }

    /**
     * Resolves soundpack/music/text across Android (direct, files/, assets/, files/Data/, assets/Data/)
     * and PC (Data/, Content/Data/) directory layouts case-insensitively.
     */
    fun resolve(gameDirPath: String): ResolvedGamePaths? {
        val trimmed = gameDirPath.trim()
        if (trimmed.isEmpty()) return null
        val absRoot = File(trimmed).absoluteFile

        val candidates = mutableListOf<Pair<File, File>>()
        // 1. Direct data root (Android unpacked OBB / files/ / selected Data/ folder)
        val directBase = if (absRoot.name.equals("data", ignoreCase = true) && absRoot.parentFile?.isDirectory == true) {
            absRoot.parentFile!!
        } else {
            absRoot
        }
        candidates.add(directBase to absRoot)

        // 2. Standard <game>/Data
        candidates.add(absRoot to resolveChildCi(absRoot, "Data"))

        // 3. Android package wrappers (<pkg>/files/..., <apk>/assets/...)
        for (sub in listOf("files", "assets", "Content")) {
            val subDir = resolveChildCi(absRoot, sub)
            if (subDir.isDirectory) {
                candidates.add(subDir to resolveChildCi(subDir, "Data"))
                candidates.add(subDir to subDir)
            }
        }

        for ((baseCand, dataCand) in candidates) {
            if (!dataCand.isDirectory) continue
            val paths = buildPaths(absRoot, baseCand, dataCand)
            if (paths.soundpackFile.isFile && paths.musicDir.isDirectory) {
                return paths
            }
        }
        return buildPaths(absRoot, absRoot, resolveChildCi(absRoot, "Data"))
    }

    fun isValidGameDir(gameDirPath: String): Boolean {
        if (gameDirPath.isBlank()) return false
        val root = File(gameDirPath)
        if (!root.isDirectory) return false
        val paths = resolve(gameDirPath) ?: return false
        return paths.soundpackFile.isFile && paths.musicDir.isDirectory
    }

    fun isWritable(gameDirPath: String): Pair<Boolean, String> {
        val paths = resolve(gameDirPath) ?: return false to "Select a valid Asphalt 8 folder."
        if (!paths.soundpackFile.isFile || !paths.musicDir.isDirectory) {
            return false to "Folder does not contain Asphalt 8 soundpack and music directories."
        }
        val probeDirs = listOfNotNull(
            paths.soundpackDir.takeIf { it.isDirectory },
            paths.musicDir.takeIf { it.isDirectory },
            paths.textDir.takeIf { it.isDirectory },
        )
        for (dir in probeDirs) {
            val probe = File(dir, ".__a8_write_test_${System.currentTimeMillis()}")
            try {
                probe.writeText("ok")
                probe.delete()
            } catch (e: Exception) {
                return false to "Cannot write in ${dir.absolutePath}: ${e.message}"
            }
        }
        return true to ""
    }

    fun autoDetectAndroidPaths(storageRoot: File): List<String> {
        val probes = listOf(
            File(storageRoot, "Asphalt8_Loose"),
            File(storageRoot, "Asphalt8"),
            File(storageRoot, "Download/Asphalt8_Loose"),
            File(storageRoot, "Download/Asphalt8"),
            File(storageRoot, "Games/Asphalt8"),
            File(storageRoot, "Android/data/com.gameloft.android.ANMP.GloftA8HM/files"),
            File(storageRoot, "Android/data/com.gameloft.android.ANMP.GloftA8HM"),
        )
        val found = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (p in probes) {
            if (p.isDirectory && isValidGameDir(p.absolutePath)) {
                val canon = p.canonicalPath
                if (seen.add(canon)) found.add(p.absolutePath)
            }
        }
        return found
    }
}
