package com.pythonnxd.a8ostpatcher.privileged

import java.io.File

data class RemoteGameLayout(
    val rootPath: String,
    val dataDirPath: String,
    val soundpackDirPath: String,
    val soundpackFilePath: String,
    val soundpackFileName: String,
    val musicDirPath: String,
    val textDirPath: String,
)

data class PulledMirrorWorkspace(
    val mirrorDir: File,
    val layout: RemoteGameLayout,
    /** lowercase filename -> (exactFilename, sizeBytes) on remote /Android/data/.../music */
    val remoteMusicIndex: Map<String, Pair<String, Long>>,
)

/**
 * Bridges restricted /Android/data/<package>/files directories (via Shizuku or Root)
 * into a lightweight local mirror so SoundpackManager, TextsManager, AudioPipeline,
 * and LibraryManager operate identically without copying hundreds of MB of stock audio.
 */
object PrivilegedGameBridge {

    const val DEFAULT_ASPHALT8_DATA_PATH = "/storage/emulated/0/Android/data/com.gameloft.android.ANMP.GloftA8HM/files"

    private fun joinPath(parent: String, child: String): String =
        parent.trimEnd('/') + "/" + child.trimStart('/')

    private fun findChildCi(backend: PrivilegedFileBackend, parentDir: String, targetName: String): RemoteFileEntry? {
        val low = targetName.lowercase()
        return backend.listEntries(parentDir).firstOrNull { it.name.lowercase() == low }
    }

    private fun resolveDataCandidate(
        backend: PrivilegedFileBackend,
        rootPath: String,
        dataDirPath: String,
    ): RemoteGameLayout? {
        if (!backend.isDirectory(dataDirPath)) return null
        val entries = backend.listEntries(dataDirPath)
        val spDirEntry = entries.firstOrNull { it.isDirectory && it.name.equals("soundpack", ignoreCase = true) } ?: return null
        val musicDirEntry = entries.firstOrNull { it.isDirectory && it.name.equals("music", ignoreCase = true) } ?: return null
        val textDirEntry = entries.firstOrNull { it.isDirectory && it.name.equals("text", ignoreCase = true) }

        val spDirPath = joinPath(dataDirPath, spDirEntry.name)
        val spFiles = backend.listEntries(spDirPath).filter { !it.isDirectory }
        val spFile = spFiles.firstOrNull { it.name.equals("soundpack", ignoreCase = true) }
            ?: spFiles.firstOrNull { it.name.equals("soundpack.xml", ignoreCase = true) }
            ?: return null

        val musicDirPath = joinPath(dataDirPath, musicDirEntry.name)
        val textDirPath = joinPath(dataDirPath, textDirEntry?.name ?: "text")

        return RemoteGameLayout(
            rootPath = rootPath,
            dataDirPath = dataDirPath,
            soundpackDirPath = spDirPath,
            soundpackFilePath = joinPath(spDirPath, spFile.name),
            soundpackFileName = spFile.name,
            musicDirPath = musicDirPath,
            textDirPath = textDirPath,
        )
    }

    fun resolveRemoteLayout(backend: PrivilegedFileBackend, rawRootPath: String): RemoteGameLayout? {
        val clean = rawRootPath.trim().trimEnd('/')
        if (clean.isEmpty() || !backend.isDirectory(clean)) return null

        val candidateDataDirs = LinkedHashSet<String>()
        candidateDataDirs.add(clean)
        findChildCi(backend, clean, "Data")?.takeIf { it.isDirectory }?.let {
            candidateDataDirs.add(joinPath(clean, it.name))
        }
        for (sub in listOf("files", "assets", "Content")) {
            val subEntry = findChildCi(backend, clean, sub)?.takeIf { it.isDirectory } ?: continue
            val subPath = joinPath(clean, subEntry.name)
            findChildCi(backend, subPath, "Data")?.takeIf { it.isDirectory }?.let {
                candidateDataDirs.add(joinPath(subPath, it.name))
            }
            candidateDataDirs.add(subPath)
        }

        for (cand in candidateDataDirs) {
            val resolved = resolveDataCandidate(backend, clean, cand)
            if (resolved != null) return resolved
        }
        return null
    }

    fun detectRestrictedGameDirs(
        backend: PrivilegedFileBackend,
        storageRootPath: String = "/storage/emulated/0",
    ): List<String> {
        val base = storageRootPath.trimEnd('/')
        val probes = LinkedHashSet<String>()
        probes.add("$base/Android/data/com.gameloft.android.ANMP.GloftA8HM/files")
        probes.add("$base/Android/data/com.gameloft.android.ANMP.GloftA8HM")
        probes.add("$base/Android/data/com.gameloft.android.ANMP.GloftA8H2/files")
        probes.add("$base/Android/data/com.gameloft.android.ANMP.GloftA8H2")

        // Also scan /Android/data for regional/alternate Asphalt 8 packages (e.g. GloftA8*)
        val dataRoot = "$base/Android/data"
        backend.listEntries(dataRoot)
            .filter { it.isDirectory && it.name.lowercase().contains("glofta8") }
            .forEach { pkg ->
                probes.add("$dataRoot/${pkg.name}/files")
                probes.add("$dataRoot/${pkg.name}")
            }

        val found = mutableListOf<String>()
        val seenDataDirs = HashSet<String>()
        for (p in probes) {
            val layout = resolveRemoteLayout(backend, p) ?: continue
            if (seenDataDirs.add(layout.dataDirPath.lowercase())) {
                found.add(p)
            }
        }
        return found
    }

    fun checkWritable(backend: PrivilegedFileBackend, layout: RemoteGameLayout): Pair<Boolean, String> {
        val dirs = listOf(layout.soundpackDirPath, layout.musicDirPath)
        for (d in dirs) {
            if (!backend.testWrite(d)) {
                return false to "Cannot write inside $d using ${backend.mode.label}."
            }
        }
        if (backend.isDirectory(layout.textDirPath) && !backend.testWrite(layout.textDirPath)) {
            return false to "Cannot write inside ${layout.textDirPath} using ${backend.mode.label}."
        }
        return true to ""
    }

    /**
     * Pulls only soundpack XML + language .texts files into mirrorDir, plus indexes remote music/*.mpc
     * filenames and byte sizes without copying stock music files.
     */
    fun pullMirror(
        backend: PrivilegedFileBackend,
        layout: RemoteGameLayout,
        mirrorDir: File,
    ): PulledMirrorWorkspace {
        val spDir = File(mirrorDir, "soundpack").apply { mkdirs() }
        val textDir = File(mirrorDir, "text").apply { mkdirs() }
        val musicDir = File(mirrorDir, "music").apply { mkdirs() }

        // Clear stale mirrored soundpack / texts / staged music files before pulling fresh remote state
        spDir.listFiles()?.forEach { it.delete() }
        textDir.listFiles()?.forEach { it.delete() }
        musicDir.listFiles()?.forEach { it.delete() }

        val localSp = File(spDir, layout.soundpackFileName)
        if (!backend.copyFromRemote(layout.soundpackFilePath, localSp)) {
            throw IllegalStateException("Could not read ${layout.soundpackFilePath} via ${backend.mode.label}.")
        }

        if (backend.isDirectory(layout.textDirPath)) {
            backend.listEntries(layout.textDirPath)
                .filter { !it.isDirectory && it.name.lowercase().endsWith(".texts") }
                .forEach { entry ->
                    val remoteTxt = joinPath(layout.textDirPath, entry.name)
                    backend.copyFromRemote(remoteTxt, File(textDir, entry.name))
                }
        }

        val musicIndex = LinkedHashMap<String, Pair<String, Long>>()
        backend.listEntries(layout.musicDirPath)
            .filter { !it.isDirectory }
            .forEach { entry ->
                musicIndex[entry.name.lowercase()] = entry.name to entry.sizeBytes
            }

        return PulledMirrorWorkspace(
            mirrorDir = mirrorDir,
            layout = layout,
            remoteMusicIndex = musicIndex,
        )
    }

    /**
     * Pushes modified soundpack, .texts files, newly staged .mpc tracks, and deletes removed remote .mpc files.
     */
    fun pushMirrorChanges(
        backend: PrivilegedFileBackend,
        workspace: PulledMirrorWorkspace,
        pushSoundpack: Boolean = true,
        pushTexts: Boolean = true,
        pushStagedMusic: Boolean = true,
        deleteRemoteMusicFiles: Collection<String> = emptyList(),
    ) {
        val layout = workspace.layout
        val mirrorDir = workspace.mirrorDir

        if (pushStagedMusic) {
            val musicDir = File(mirrorDir, "music")
            val staged = musicDir.listFiles { f -> f.isFile }.orEmpty()
            for (f in staged) {
                val remoteDst = joinPath(layout.musicDirPath, f.name)
                if (!backend.copyToRemote(f, remoteDst)) {
                    throw IllegalStateException("Could not write ${f.name} to ${layout.musicDirPath} via ${backend.mode.label}.")
                }
                f.delete()
            }
        }

        if (pushSoundpack) {
            val localSp = File(File(mirrorDir, "soundpack"), layout.soundpackFileName)
            if (localSp.isFile) {
                if (!backend.copyToRemote(localSp, layout.soundpackFilePath)) {
                    throw IllegalStateException("Could not write soundpack to ${layout.soundpackFilePath} via ${backend.mode.label}.")
                }
            }
        }

        if (pushTexts) {
            val textDir = File(mirrorDir, "text")
            val texts = textDir.listFiles { f -> f.isFile && f.name.lowercase().endsWith(".texts") }.orEmpty()
            if (texts.isNotEmpty()) {
                backend.mkdirs(layout.textDirPath)
                for (tf in texts) {
                    val remoteDst = joinPath(layout.textDirPath, tf.name)
                    if (!backend.copyToRemote(tf, remoteDst)) {
                        throw IllegalStateException("Could not write ${tf.name} to ${layout.textDirPath} via ${backend.mode.label}.")
                    }
                }
            }
        }

        for (fn in deleteRemoteMusicFiles) {
            if (fn.isBlank() || fn.contains("/") || fn.contains("\\")) continue
            val remoteFile = joinPath(layout.musicDirPath, fn)
            backend.deletePath(remoteFile)
        }
    }

    /**
     * Pulls a single remote .mpc file into a bounded preview cache for on-demand playback or outro trimming.
     */
    fun fetchTrackToCache(
        backend: PrivilegedFileBackend,
        layout: RemoteGameLayout,
        filename: String,
        cacheRoot: File,
    ): File {
        val previewDir = File(cacheRoot, "remote_audio_cache").apply { mkdirs() }
        val safeName = filename.replace(Regex("[/\\\\:*?\"<>|]"), "_")
        val localFile = File(previewDir, safeName)
        val remotePath = joinPath(layout.musicDirPath, filename)

        val remoteEntry = backend.listEntries(layout.musicDirPath)
            .firstOrNull { !it.isDirectory && it.name.equals(filename, ignoreCase = true) }
        val actualRemotePath = if (remoteEntry != null) joinPath(layout.musicDirPath, remoteEntry.name) else remotePath

        if (localFile.isFile && remoteEntry != null && localFile.length() == remoteEntry.sizeBytes && localFile.length() > 0L) {
            localFile.setLastModified(System.currentTimeMillis())
            return localFile
        }

        if (!backend.copyFromRemote(actualRemotePath, localFile)) {
            throw IllegalStateException("Could not read $filename from ${layout.musicDirPath} via ${backend.mode.label}.")
        }

        // Keep bounded LRU cache (max 8 tracks) so device storage stays clean
        val cached = previewDir.listFiles { f -> f.isFile }.orEmpty().sortedByDescending { it.lastModified() }
        if (cached.size > 8) {
            cached.drop(8).forEach { it.delete() }
        }
        return localFile
    }
}
