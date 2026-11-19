package com.pythonnxd.a8ostpatcher.engine

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class InstallResult(
    val added: Int,
    val skipped: Int,
    val textsUpdated: Int,
    val backupPath: String?,
)

data class HealthRepairResult(
    val backupPath: String?,
    val textsFixed: Int,
    val eventsFixed: Int,
    val soundsPruned: Int,
    val orphansDeleted: Int,
    val reclaimedBytes: Long,
    val auditAfter: HealthAudit,
)

object LibraryManager {

    private fun isoNow(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        return sdf.format(Date())
    }

    private fun stampNow(): String {
        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
        return sdf.format(Date())
    }

    fun parseSongDisplay(rawVal: String, baseId: String): Pair<String, String> {
        var cleaned = rawVal.trim()
        if (cleaned.startsWith("#>") && cleaned.endsWith("#|")) {
            cleaned = cleaned.substring(2, cleaned.length - 2).trim()
        }
        val m1 = Regex("^\"(.+?)\"\\s*(?:\\([^)]*\\)\\s*)?[-–—]\\s*(.+)$").find(cleaned)
        if (m1 != null) return m1.groupValues[1].trim() to m1.groupValues[2].trim()

        val m2 = Regex("^\"(.+?)\"\\s*(.+?)\\s*[-–—]\\s*(.+)$").find(cleaned)
        if (m2 != null) {
            return "${m2.groupValues[1].trim()} ${m2.groupValues[2].trim()}".trim() to m2.groupValues[3].trim()
        }
        val fallback = baseId.removePrefix("m_").replace('_', ' ').trim()
            .split(Regex("\\s+")).joinToString(" ") { w ->
                w.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }
        return fallback.ifBlank { baseId } to "Unknown Artist"
    }

    fun backupsRoot(appFilesDir: File, gameDirPath: String): File {
        val key = File(gameDirPath).name.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "asphalt8" }
        return File(appFilesDir, "backups/$key").apply { mkdirs() }
    }

    fun profilesRoot(appFilesDir: File): File =
        File(appFilesDir, "profiles").apply { mkdirs() }

    fun createBackup(appFilesDir: File, gameDirPath: String, reason: String = "patch"): File {
        val paths = GamePathResolver.resolve(gameDirPath)
            ?: throw IllegalStateException("Valid Asphalt 8 folder is required.")
        val bRoot = backupsRoot(appFilesDir, gameDirPath)
        val backupDir = File(bRoot, "music_mod_backup_${stampNow()}").apply { mkdirs() }

        if (paths.soundpackFile.isFile) {
            paths.soundpackFile.copyTo(File(backupDir, "soundpack"), overwrite = true)
        }
        if (paths.textDir.isDirectory) {
            val textBak = File(backupDir, "text").apply { mkdirs() }
            paths.textDir.listFiles { f -> f.isFile && f.name.lowercase().endsWith(".texts") }?.forEach { tf ->
                tf.copyTo(File(textBak, tf.name), overwrite = true)
            }
        }
        val meta = JSONObject().apply {
            put("game_dir", gameDirPath)
            put("created_at", isoNow())
            put("reason", reason)
        }
        File(backupDir, ".backup_meta.json").writeText(meta.toString(2))
        return backupDir
    }

    fun listBackups(appFilesDir: File, gameDirPath: String): List<BackupEntry> {
        if (gameDirPath.isBlank()) return emptyList()
        val bRoot = backupsRoot(appFilesDir, gameDirPath)
        val out = mutableListOf<BackupEntry>()
        bRoot.listFiles { d -> d.isDirectory && d.name.startsWith("music_mod_backup_") }?.forEach { dir ->
            val sp = File(dir, "soundpack")
            val textFiles = File(dir, "text").listFiles { f -> f.isFile && f.name.lowercase().endsWith(".texts") }.orEmpty()
            if (!sp.isFile && textFiles.isEmpty()) return@forEach
            val metaFile = File(dir, ".backup_meta.json")
            val meta = if (metaFile.isFile) {
                try { JSONObject(metaFile.readText()) } catch (_: Exception) { JSONObject() }
            } else JSONObject()
            val sz = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            out.add(
                BackupEntry(
                    id = dir.name,
                    path = dir.absolutePath,
                    createdAt = meta.optString("created_at").ifBlank { isoNow() },
                    reason = meta.optString("reason", "patch"),
                    hasSoundpack = sp.isFile,
                    textsCount = textFiles.size,
                    sizeBytes = sz,
                    mtimeMs = dir.lastModified(),
                )
            )
        }
        return out.sortedByDescending { it.mtimeMs }
    }

    fun restoreBackup(gameDirPath: String, backupDir: File): Int {
        val paths = GamePathResolver.resolve(gameDirPath)
            ?: throw IllegalStateException("Valid Asphalt 8 folder is required.")
        val spBak = File(backupDir, "soundpack")
        if (spBak.isFile) {
            paths.soundpackFile.parentFile?.mkdirs()
            spBak.copyTo(paths.soundpackFile, overwrite = true)
        }
        var restoredTexts = 0
        val txtBak = File(backupDir, "text")
        if (txtBak.isDirectory) {
            paths.textDir.mkdirs()
            txtBak.listFiles { f -> f.isFile && f.name.lowercase().endsWith(".texts") }?.forEach { tf ->
                tf.copyTo(File(paths.textDir, tf.name), overwrite = true)
                restoredTexts++
            }
        }
        return restoredTexts
    }

    fun deleteBackup(appFilesDir: File, gameDirPath: String, backupDir: File): Boolean {
        val rootCanon = backupsRoot(appFilesDir, gameDirPath).canonicalPath
        val targetCanon = backupDir.canonicalPath
        require(backupDir.name.startsWith("music_mod_backup_") && targetCanon.startsWith(rootCanon + File.separator)) {
            "Refusing to delete outside patcher backups folder."
        }
        return backupDir.deleteRecursively()
    }

    fun listInstalledTracks(gameDirPath: String): List<InstalledTrack> {
        val paths = GamePathResolver.resolve(gameDirPath) ?: return emptyList()
        if (!paths.soundpackFile.isFile) return emptyList()

        val doc = SoundpackManager.parseXml(paths.soundpackFile)
        val soundsElem = SoundpackManager.firstChildElement(doc.documentElement, "sounds")
        val eventsElem = SoundpackManager.firstChildElement(doc.documentElement, "events")

        val soundMap = LinkedHashMap<String, Map<String, String>>()
        if (soundsElem != null) {
            for (s in SoundpackManager.directChildElements(soundsElem, "sound")) {
                val lbl = s.getAttribute("label")
                if (!lbl.startsWith("m_")) continue
                soundMap[lbl] = mapOf(
                    "filename" to s.getAttribute("filename"),
                    "group" to s.getAttribute("group"),
                )
            }
        }

        val trackCategories = HashMap<String, MutableList<StationCategory>>()
        val fullSoundtrackIds = HashSet<String>()
        if (eventsElem != null) {
            for (ev in SoundpackManager.directChildElements(eventsElem, "event")) {
                val evLow = ev.getAttribute("label").trim().lowercase()
                val ids = ev.getAttribute("value").split(",").map { it.trim() }.filter { it.isNotEmpty() }
                val cat = SoundpackManager.STATION_EVENT_TO_CATEGORY[evLow]
                if (cat != null) {
                    for (tid in ids) {
                        val list = trackCategories.getOrPut(tid) { mutableListOf() }
                        if (cat !in list) list.add(cat)
                    }
                } else if (evLow == "ev_radio_full_soundtrack") {
                    fullSoundtrackIds.addAll(ids)
                }
            }
        }

        val songStrings = HashMap<String, String>()
        if (paths.textDir.isDirectory) {
            val enFile = paths.textDir.listFiles { f -> f.isFile && f.name.equals("en.texts", ignoreCase = true) }?.firstOrNull()
                ?: paths.textDir.listFiles { f -> f.isFile && f.name.lowercase().endsWith(".texts") }?.minByOrNull { it.name.lowercase() }
            if (enFile != null) {
                for (entry in TextsManager.readTexts(enFile)) {
                    val k = entry.keyString()
                    if (k.startsWith("STR_SONG_")) {
                        songStrings[k.substring(9)] = entry.valueString()
                    }
                }
            }
        }

        val tracks = mutableListOf<InstalledTrack>()
        for ((label, attrs) in soundMap) {
            if (label.startsWith("m_outro_")) continue
            val stripped = label.removePrefix("m_")
            val outroId = "m_outro_$stripped"
            val outroAttrs = soundMap[outroId]
            val cats = trackCategories[label].orEmpty()
            val primaryCat = when {
                cats.isNotEmpty() -> cats[0]
                attrs["group"] == "m_ui" || stripped.endsWith("_menu") -> StationCategory.MENU
                label in fullSoundtrackIds -> StationCategory.ROCK
                else -> StationCategory.ROCK
            }
            val rawCatName = if (cats.isNotEmpty()) cats[0].displayName else if (primaryCat == StationCategory.MENU) "Menu" else "Unassigned"
            val (title, artist) = parseSongDisplay(songStrings[label] ?: "", label)
            val mainFile = attrs["filename"].orEmpty().ifBlank { "$label.mpc" }
            val mainPath = File(paths.musicDir, mainFile)
            val outroFile = outroAttrs?.get("filename")?.ifBlank { "$outroId.mpc" }
            val outroPath = outroFile?.let { File(paths.musicDir, it) }

            tracks.add(
                InstalledTrack(
                    uid = "lib_$label",
                    baseId = label,
                    title = title,
                    artist = artist,
                    category = primaryCat,
                    rawCategoryName = rawCatName,
                    mainFile = mainFile,
                    mainPath = mainPath.absolutePath,
                    mainExists = mainPath.isFile,
                    hasOutro = outroAttrs != null,
                    outroId = if (outroAttrs != null) outroId else null,
                    outroFile = outroFile,
                    outroPath = outroPath?.absolutePath,
                    outroExists = outroPath?.isFile == true,
                )
            )
        }
        return tracks.sortedWith(compareBy<InstalledTrack>({ it.category.ordinal }, { it.title.lowercase() }, { it.baseId }))
    }

    fun installQueueTracks(
        appFilesDir: File,
        tempDir: File,
        gameDirPath: String,
        tracks: List<QueueTrack>,
        qualityPreset: QualityPreset,
        keepOriginal: Boolean,
        conflictPolicy: ConflictPolicy,
        autoBackup: Boolean,
        onProgress: (current: Int, total: Int, stage: String) -> Unit,
    ): InstallResult {
        val paths = GamePathResolver.resolve(gameDirPath)
            ?: throw IllegalStateException("Select a valid Asphalt 8 folder first.")
        require(paths.soundpackFile.isFile && paths.musicDir.isDirectory) {
            "Asphalt 8 folder is missing soundpack or music directory."
        }
        val selected = tracks.filter { it.selected }
        require(selected.isNotEmpty()) { "Select at least one song to install." }

        val backupPath = if (autoBackup) {
            onProgress(0, selected.size, "Backing up soundpack and titles...")
            createBackup(appFilesDir, gameDirPath, reason = "install").absolutePath
        } else null

        val existingIds = SoundpackManager.getExistingTrackIds(paths.soundpackFile)
        val specs = mutableListOf<SoundpackTrackSpec>()
        val titleUpdates = mutableListOf<SongTitleUpdate>()
        var skipped = 0

        selected.forEachIndexed { idx, track ->
            onProgress(idx + 1, selected.size, "Encoding \"${track.title}\"...")
            if (track.baseId in existingIds && conflictPolicy == ConflictPolicy.SKIP) {
                skipped++
                return@forEachIndexed
            }

            val mainFilename = "${track.baseId}.mpc"
            val mainDst = File(paths.musicDir, mainFilename)
            AudioPipeline.convertToMpc(
                srcFile = File(track.srcPath),
                dstFile = mainDst,
                tempDir = tempDir,
                qualityPreset = qualityPreset,
            )

            var outroFilename: String? = null
            if (track.hasOutro) {
                val outroSrc = File(track.outroSrcPath ?: track.srcPath)
                if (outroSrc.isFile) {
                    outroFilename = "${track.outroId}.mpc"
                    val outroDst = File(paths.musicDir, outroFilename)
                    AudioPipeline.convertToMpc(
                        srcFile = outroSrc,
                        dstFile = outroDst,
                        tempDir = tempDir,
                        qualityPreset = qualityPreset,
                        startSec = track.outroStartSec,
                        endSec = track.outroEndSec,
                        fadeInSec = if (track.outroFadeInEnabled) track.outroFadeIn else 0.0,
                        fadeOutSec = if (track.outroFadeOutEnabled) track.outroFadeOut else 0.0,
                    )
                }
            }

            specs.add(
                SoundpackTrackSpec(
                    baseId = track.baseId,
                    mainFile = mainFilename,
                    category = track.category,
                    hasOutro = outroFilename != null,
                    outroId = if (outroFilename != null) track.outroId else null,
                    outroFile = outroFilename,
                )
            )
            titleUpdates.add(SongTitleUpdate(track.baseId, track.title, track.artist))
        }

        onProgress(selected.size, selected.size, "Patching soundpack and language dictionaries...")
        val spRes = SoundpackManager.patchSoundpack(
            soundpackFile = paths.soundpackFile,
            newTracks = specs,
            keepOriginal = keepOriginal,
            onConflict = conflictPolicy,
        )
        val textsUpdated = if (paths.textDir.isDirectory && titleUpdates.isNotEmpty()) {
            TextsManager.addSongTitles(paths.textDir, titleUpdates, conflictPolicy)
        } else 0

        return InstallResult(
            added = spRes.added,
            skipped = skipped + spRes.skipped,
            textsUpdated = textsUpdated,
            backupPath = backupPath,
        )
    }

    fun updateInstalledTrack(
        appFilesDir: File,
        gameDirPath: String,
        track: InstalledTrack,
        newTitle: String,
        newArtist: String,
        newCategory: StationCategory,
        autoBackup: Boolean,
    ) {
        val paths = GamePathResolver.resolve(gameDirPath)
            ?: throw IllegalStateException("Select a valid Asphalt 8 folder.")
        if (autoBackup) createBackup(appFilesDir, gameDirPath, reason = "edit_metadata")

        if (paths.textDir.isDirectory) {
            TextsManager.addSongTitles(
                paths.textDir,
                listOf(SongTitleUpdate(track.baseId, newTitle.ifBlank { track.baseId }, newArtist.ifBlank { "Custom Artist" })),
                ConflictPolicy.OVERWRITE,
            )
        }

        val doc = SoundpackManager.parseXml(paths.soundpackFile)
        val soundsElem = SoundpackManager.firstChildElement(doc.documentElement, "sounds")
        val eventsElem = SoundpackManager.firstChildElement(doc.documentElement, "events")
        val targetGroup = if (newCategory == StationCategory.MENU) "m_ui" else "m_radio"

        soundsElem?.let { se ->
            SoundpackManager.directChildElements(se, "sound").firstOrNull { it.getAttribute("label") == track.baseId }
                ?.setAttribute("group", targetGroup)
        }
        eventsElem?.let { ee ->
            for (ev in SoundpackManager.directChildElements(ee, "event")) {
                val lbl = ev.getAttribute("label")
                if (!SoundpackManager.isPlaylistEvent(lbl)) continue
                val vals = ev.getAttribute("value").split(",").map { it.trim() }.filter { it.isNotEmpty() && it != track.baseId }
                ev.setAttribute("value", vals.joinToString(","))
            }
            for (targetEv in SoundpackManager.findTargetPlaylistEvents(ee, newCategory.eventLabel)) {
                val vals = targetEv.getAttribute("value").split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
                if (track.baseId !in vals) vals.add(track.baseId)
                targetEv.setAttribute("value", vals.joinToString(","))
            }
        }
        SoundpackManager.writeXml(doc, paths.soundpackFile)
    }

    fun saveInstalledTrackOutro(
        appFilesDir: File,
        tempDir: File,
        gameDirPath: String,
        baseId: String,
        srcFile: File,
        startSec: Double?,
        endSec: Double?,
        fadeInSec: Double,
        fadeOutSec: Double,
        qualityPreset: QualityPreset,
        autoBackup: Boolean,
    ) {
        val paths = GamePathResolver.resolve(gameDirPath)
            ?: throw IllegalStateException("Select a valid Asphalt 8 folder.")
        if (autoBackup) createBackup(appFilesDir, gameDirPath, reason = "edit_outro")

        val stripped = baseId.removePrefix("m_")
        val outroId = "m_outro_$stripped"
        val outroFileName = "$outroId.mpc"
        val outroDst = File(paths.musicDir, outroFileName)
        val tmpMpc = File(tempDir, "outro_${System.nanoTime()}.mpc")
        try {
            AudioPipeline.convertToMpc(
                srcFile = srcFile,
                dstFile = tmpMpc,
                tempDir = tempDir,
                qualityPreset = qualityPreset,
                startSec = startSec,
                endSec = endSec,
                fadeInSec = fadeInSec,
                fadeOutSec = fadeOutSec,
            )
            tmpMpc.copyTo(outroDst, overwrite = true)
        } finally {
            tmpMpc.delete()
        }

        val doc = SoundpackManager.parseXml(paths.soundpackFile)
        val soundsElem = SoundpackManager.firstChildElement(doc.documentElement, "sounds")!!
        val eventsElem = SoundpackManager.firstChildElement(doc.documentElement, "events")!!
        SoundpackManager.directChildElements(soundsElem, "sound")
            .filter { it.getAttribute("label") == outroId }
            .forEach { soundsElem.removeChild(it) }
        SoundpackManager.directChildElements(eventsElem, "event")
            .filter { it.getAttribute("label") == "ev_$outroId" }
            .forEach { eventsElem.removeChild(it) }

        soundsElem.appendChild(doc.createElement("sound").apply {
            setAttribute("bank", "MUSIC")
            setAttribute("filename", outroFileName)
            setAttribute("format", "mpc")
            setAttribute("group", "m_outro")
            setAttribute("label", outroId)
            setAttribute("loadingflags", "none")
            setAttribute("loop", "no")
            setAttribute("priority", "1.0")
        })
        eventsElem.appendChild(doc.createElement("event").apply {
            setAttribute("label", "ev_$outroId")
            setAttribute("type", "playlist")
            setAttribute("value", outroId)
        })
        soundsElem.setAttribute("size", SoundpackManager.directChildElements(soundsElem, "sound").size.toString())
        eventsElem.setAttribute("size", SoundpackManager.directChildElements(eventsElem, "event").size.toString())
        SoundpackManager.writeXml(doc, paths.soundpackFile)
    }

    fun removeInstalledTrackOutro(
        appFilesDir: File,
        gameDirPath: String,
        baseId: String,
        deleteFile: Boolean,
        autoBackup: Boolean,
    ) {
        val paths = GamePathResolver.resolve(gameDirPath)
            ?: throw IllegalStateException("Select a valid Asphalt 8 folder.")
        if (autoBackup) createBackup(appFilesDir, gameDirPath, reason = "remove_outro")

        val outroId = "m_outro_${baseId.removePrefix("m_")}"
        val doc = SoundpackManager.parseXml(paths.soundpackFile)
        val soundsElem = SoundpackManager.firstChildElement(doc.documentElement, "sounds")
        val eventsElem = SoundpackManager.firstChildElement(doc.documentElement, "events")
        val toDelete = mutableListOf<String>()

        soundsElem?.let { se ->
            SoundpackManager.directChildElements(se, "sound")
                .filter { it.getAttribute("label") == outroId }
                .forEach {
                    it.getAttribute("filename").takeIf { fn -> fn.isNotEmpty() }?.let { fn -> toDelete.add(fn) }
                    se.removeChild(it)
                }
            se.setAttribute("size", SoundpackManager.directChildElements(se, "sound").size.toString())
        }
        eventsElem?.let { ee ->
            SoundpackManager.directChildElements(ee, "event")
                .filter { it.getAttribute("label") == "ev_$outroId" }
                .forEach { ee.removeChild(it) }
            ee.setAttribute("size", SoundpackManager.directChildElements(ee, "event").size.toString())
        }
        SoundpackManager.writeXml(doc, paths.soundpackFile)

        if (deleteFile) {
            (toDelete.ifEmpty { listOf("$outroId.mpc") }).forEach { fn ->
                File(paths.musicDir, fn).takeIf { it.isFile }?.delete()
            }
        }
    }

    fun removeInstalledTracks(
        appFilesDir: File,
        gameDirPath: String,
        baseIds: List<String>,
        deleteFiles: Boolean,
        autoBackup: Boolean,
    ): Int {
        if (baseIds.isEmpty()) return 0
        val paths = GamePathResolver.resolve(gameDirPath)
            ?: throw IllegalStateException("Select a valid Asphalt 8 folder.")
        if (autoBackup) createBackup(appFilesDir, gameDirPath, reason = "remove_tracks")

        val rmLabels = HashSet<String>()
        for (bid in baseIds) {
            rmLabels.add(bid)
            rmLabels.add("m_outro_${bid.removePrefix("m_")}")
        }
        val rmEvents = rmLabels.map { "ev_$it" }.toSet()
        val filesToDelete = HashSet<String>()

        val doc = SoundpackManager.parseXml(paths.soundpackFile)
        val soundsElem = SoundpackManager.firstChildElement(doc.documentElement, "sounds")
        val eventsElem = SoundpackManager.firstChildElement(doc.documentElement, "events")

        soundsElem?.let { se ->
            SoundpackManager.directChildElements(se, "sound")
                .filter { it.getAttribute("label") in rmLabels }
                .forEach {
                    it.getAttribute("filename").takeIf { fn -> fn.isNotEmpty() }?.let { fn -> filesToDelete.add(fn) }
                    se.removeChild(it)
                }
            se.setAttribute("size", SoundpackManager.directChildElements(se, "sound").size.toString())
        }
        eventsElem?.let { ee ->
            for (ev in SoundpackManager.directChildElements(ee, "event")) {
                if (ev.getAttribute("label") in rmEvents) {
                    ee.removeChild(ev)
                    continue
                }
                val raw = ev.getAttribute("value")
                if (raw.isNotEmpty()) {
                    val parts = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    if (parts.any { it in rmLabels }) {
                        ev.setAttribute("value", parts.filter { it !in rmLabels }.joinToString(","))
                    }
                }
            }
            ee.setAttribute("size", SoundpackManager.directChildElements(ee, "event").size.toString())
        }
        SoundpackManager.writeXml(doc, paths.soundpackFile)

        if (paths.textDir.isDirectory) {
            TextsManager.removeSongTitles(paths.textDir, baseIds)
        }
        if (deleteFiles && paths.musicDir.isDirectory) {
            val musicCanon = paths.musicDir.canonicalPath
            for (fn in filesToDelete) {
                val f = File(paths.musicDir, fn)
                if (f.canonicalPath.startsWith(musicCanon + File.separator) && f.isFile) {
                    f.delete()
                }
            }
        }
        return baseIds.size
    }

    fun auditHealth(gameDirPath: String): HealthAudit {
        val paths = GamePathResolver.resolve(gameDirPath)
            ?: throw IllegalStateException("Select a valid Asphalt 8 folder.")
        val diskAllLower = HashMap<String, String>()
        val diskMpcLower = HashMap<String, String>()
        paths.musicDir.listFiles { f -> f.isFile }?.sortedBy { it.name.lowercase() }?.forEach { f ->
            val low = f.name.lowercase()
            diskAllLower[low] = f.name
            if (low.endsWith(".mpc")) diskMpcLower[low] = f.name
        }

        val doc = SoundpackManager.parseXml(paths.soundpackFile)
        val allSounds = SoundpackManager.firstChildElement(doc.documentElement, "sounds")
            ?.let { SoundpackManager.directChildElements(it, "sound") }.orEmpty()
        val allEvents = SoundpackManager.firstChildElement(doc.documentElement, "events")
            ?.let { SoundpackManager.directChildElements(it, "event") }.orEmpty()

        val soundByLabel = HashMap<String, org.w3c.dom.Element>()
        val referencedLower = HashSet<String>()
        val missingFiles = mutableListOf<MissingFileIssue>()
        val mainMusicLabels = HashSet<String>()

        for (s in allSounds) {
            val lbl = s.getAttribute("label").trim()
            val fn = s.getAttribute("filename").trim()
            val bank = s.getAttribute("bank").trim()
            val grp = s.getAttribute("group").trim()
            if (lbl.isNotEmpty()) soundByLabel[lbl] = s
            if (fn.isNotEmpty()) referencedLower.add(fn.lowercase())
            val isMusic = bank == "MUSIC" || fn.lowercase().endsWith(".mpc")
            if (!isMusic) continue
            val isOutro = grp == "m_outro" || lbl.startsWith("m_outro_") || fn.lowercase().startsWith("m_outro_")
            if (fn.isNotEmpty() && fn.lowercase() !in diskAllLower) {
                missingFiles.add(MissingFileIssue(lbl, fn, isOutro))
            }
            if (!isOutro && lbl.isNotEmpty() && grp in setOf("m_radio", "m_ui")) {
                mainMusicLabels.add(lbl)
            }
        }

        val orphanFiles = mutableListOf<OrphanFileIssue>()
        var orphanBytes = 0L
        for ((low, realFn) in diskMpcLower.entries.sortedBy { it.key }) {
            if (low !in referencedLower) {
                val f = File(paths.musicDir, realFn)
                val sz = f.length()
                orphanFiles.add(OrphanFileIssue(realFn, f.absolutePath, sz))
                orphanBytes += sz
            }
        }

        val eventLabels = allEvents.map { it.getAttribute("label").trim() }.filter { it.isNotEmpty() }.toSet()
        val playlistIds = HashSet<String>()
        val brokenRefs = mutableListOf<BrokenPlaylistRef>()
        for (ev in allEvents) {
            val lbl = ev.getAttribute("label").trim()
            if (!SoundpackManager.isPlaylistEvent(lbl)) continue
            val raw = ev.getAttribute("value").trim()
            if (raw.isEmpty()) continue
            for (item in raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }) {
                if (item !in soundByLabel) brokenRefs.add(BrokenPlaylistRef(lbl, item))
                else playlistIds.add(item)
            }
        }

        val missingEvents = (playlistIds + mainMusicLabels).sorted().filter { tid ->
            tid in soundByLabel && "ev_$tid" !in eventLabels
        }

        val existingKeys = HashSet<String>()
        var hasRefTexts = false
        if (paths.textDir.isDirectory) {
            val tf = paths.textDir.listFiles { f -> f.isFile && f.name.equals("en.texts", ignoreCase = true) }?.firstOrNull()
                ?: paths.textDir.listFiles { f -> f.isFile && f.name.lowercase().endsWith(".texts") }?.minByOrNull { it.name.lowercase() }
            if (tf != null) {
                hasRefTexts = true
                TextsManager.readTexts(tf).forEach { existingKeys.add(it.keyString()) }
            }
        }
        val missingTexts = mutableListOf<MissingTextIssue>()
        if (hasRefTexts) {
            for (tid in playlistIds.sorted()) {
                val s = soundByLabel[tid] ?: continue
                if (tid.startsWith("m_outro_") || s.getAttribute("group") == "m_outro") continue
                val key = "STR_SONG_$tid"
                if (key !in existingKeys) missingTexts.add(MissingTextIssue(tid, key))
            }
        }

        val issues = missingFiles.size + orphanFiles.size + brokenRefs.size + missingEvents.size + missingTexts.size
        return HealthAudit(
            healthy = issues == 0,
            issueCount = issues,
            totalSounds = allSounds.size,
            totalEvents = allEvents.size,
            playlistTracks = playlistIds.size,
            missingFiles = missingFiles,
            orphanFiles = orphanFiles,
            orphanTotalBytes = orphanBytes,
            brokenPlaylistRefs = brokenRefs,
            missingEvents = missingEvents,
            missingTexts = missingTexts,
        )
    }

    fun repairHealth(
        appFilesDir: File,
        gameDirPath: String,
        fixTexts: Boolean = true,
        fixEvents: Boolean = true,
        pruneMissing: Boolean = false,
        deleteOrphans: Boolean = false,
        autoBackup: Boolean = true,
    ): HealthRepairResult {
        val before = auditHealth(gameDirPath)
        val doTexts = fixTexts && before.missingTexts.isNotEmpty()
        val doEvents = fixEvents && (before.brokenPlaylistRefs.isNotEmpty() || before.missingEvents.isNotEmpty())
        val doPrune = pruneMissing && before.missingFiles.isNotEmpty()
        val doOrphans = deleteOrphans && before.orphanFiles.isNotEmpty()
        if (!doTexts && !doEvents && !doPrune && !doOrphans) {
            return HealthRepairResult(null, 0, 0, 0, 0, 0L, before)
        }

        val paths = GamePathResolver.resolve(gameDirPath)!!
        val bakPath = if (autoBackup) createBackup(appFilesDir, gameDirPath, reason = "health_repair").absolutePath else null

        var textsFixed = 0
        if (doTexts && paths.textDir.isDirectory) {
            val updates = before.missingTexts.map { mt ->
                val pretty = mt.baseId.removePrefix("m_").replace('_', ' ').trim()
                    .split(Regex("\\s+")).joinToString(" ") { w ->
                        w.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                    }.ifBlank { mt.baseId }
                SongTitleUpdate(mt.baseId, pretty, "Custom Artist")
            }
            textsFixed = TextsManager.addSongTitles(paths.textDir, updates, ConflictPolicy.SKIP)
        }

        var eventsFixed = 0
        var soundsPruned = 0
        if (doEvents || doPrune) {
            val doc = SoundpackManager.parseXml(paths.soundpackFile)
            val soundsElem = SoundpackManager.firstChildElement(doc.documentElement, "sounds")
            val eventsElem = SoundpackManager.firstChildElement(doc.documentElement, "events")
            var modified = false

            val missingLabels = before.missingFiles.map { it.label }.toSet()
            if (doPrune && soundsElem != null) {
                SoundpackManager.directChildElements(soundsElem, "sound")
                    .filter { it.getAttribute("label") in missingLabels }
                    .forEach { soundsElem.removeChild(it); soundsPruned++; modified = true }
                eventsElem?.let { ee ->
                    val rmEv = missingLabels.map { "ev_$it" }.toSet()
                    SoundpackManager.directChildElements(ee, "event")
                        .filter { it.getAttribute("label") in rmEv }
                        .forEach { ee.removeChild(it); modified = true }
                }
            }

            val validLabels = soundsElem?.let { se ->
                SoundpackManager.directChildElements(se, "sound").map { it.getAttribute("label").trim() }.toSet()
            }.orEmpty()

            if (eventsElem != null) {
                val existingEv = HashSet<String>()
                for (ev in SoundpackManager.directChildElements(eventsElem, "event")) {
                    val lbl = ev.getAttribute("label").trim()
                    if (lbl.isNotEmpty()) existingEv.add(lbl)
                    if (!SoundpackManager.isPlaylistEvent(lbl)) continue
                    val raw = ev.getAttribute("value").trim()
                    if (raw.isEmpty()) continue
                    val items = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    val kept = items.filter { it in validLabels }
                    if (kept.size != items.size) {
                        ev.setAttribute("value", kept.joinToString(","))
                        eventsFixed += items.size - kept.size
                        modified = true
                    }
                }
                if (doEvents) {
                    for (tid in before.missingEvents) {
                        val evName = "ev_$tid"
                        if (tid in validLabels && evName !in existingEv) {
                            eventsElem.appendChild(doc.createElement("event").apply {
                                setAttribute("label", evName)
                                setAttribute("type", "playlist")
                                setAttribute("value", tid)
                            })
                            existingEv.add(evName)
                            eventsFixed++
                            modified = true
                        }
                    }
                }
            }

            if (modified) {
                soundsElem?.setAttribute("size", SoundpackManager.directChildElements(soundsElem, "sound").size.toString())
                eventsElem?.setAttribute("size", SoundpackManager.directChildElements(eventsElem, "event").size.toString())
                SoundpackManager.writeXml(doc, paths.soundpackFile)
            }
        }

        var orphansDeleted = 0
        var reclaimed = 0L
        if (doOrphans) {
            val musicCanon = paths.musicDir.canonicalPath
            for (o in before.orphanFiles) {
                val f = File(o.path)
                if (f.canonicalPath.startsWith(musicCanon + File.separator) && f.isFile && f.delete()) {
                    orphansDeleted++
                    reclaimed += o.sizeBytes
                }
            }
        }

        return HealthRepairResult(
            backupPath = bakPath,
            textsFixed = textsFixed,
            eventsFixed = eventsFixed,
            soundsPruned = soundsPruned,
            orphansDeleted = orphansDeleted,
            reclaimedBytes = reclaimed,
            auditAfter = auditHealth(gameDirPath),
        )
    }

    fun saveProfile(appFilesDir: File, gameDirPath: String, name: String): ModProfile {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "Profile name is required." }
        val paths = GamePathResolver.resolve(gameDirPath)
            ?: throw IllegalStateException("Select a valid Asphalt 8 folder.")
        val tracks = listInstalledTracks(gameDirPath)
        val slug = clean.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(32).ifBlank { "profile" }
        val pid = "${slug}_${stampNow()}"
        val pdir = File(profilesRoot(appFilesDir), pid).apply { mkdirs() }
        paths.soundpackFile.copyTo(File(pdir, "soundpack"), overwrite = true)

        val pTexts = File(pdir, "text").apply { mkdirs() }
        var textCount = 0
        if (paths.textDir.isDirectory) {
            paths.textDir.listFiles { f -> f.isFile && f.name.lowercase().endsWith(".texts") }?.forEach { tf ->
                tf.copyTo(File(pTexts, tf.name), overwrite = true)
                textCount++
            }
        }
        val createdAt = isoNow()
        val meta = JSONObject().apply {
            put("id", pid)
            put("name", clean)
            put("created_at", createdAt)
            put("track_count", tracks.size)
            put("text_files", textCount)
        }
        File(pdir, "profile_meta.json").writeText(meta.toString(2))
        val sizeBytes = pdir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        return ModProfile(pid, clean, createdAt, tracks.size, textCount, sizeBytes)
    }

    fun listProfiles(appFilesDir: File): List<ModProfile> {
        val root = profilesRoot(appFilesDir)
        val out = mutableListOf<ModProfile>()
        root.listFiles { d -> d.isDirectory }?.forEach { dir ->
            val mf = File(dir, "profile_meta.json")
            val sp = File(dir, "soundpack")
            if (!mf.isFile || !sp.isFile) return@forEach
            try {
                val j = JSONObject(mf.readText())
                val sz = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                out.add(
                    ModProfile(
                        id = dir.name,
                        name = j.optString("name", dir.name),
                        createdAt = j.optString("created_at", ""),
                        trackCount = j.optInt("track_count", 0),
                        textFiles = j.optInt("text_files", 0),
                        sizeBytes = sz,
                    )
                )
            } catch (_: Exception) {}
        }
        return out.sortedByDescending { it.createdAt }
    }

    fun applyProfile(appFilesDir: File, gameDirPath: String, profileId: String, autoBackup: Boolean): Int {
        val pdir = File(profilesRoot(appFilesDir), profileId)
        val srcSp = File(pdir, "soundpack")
        require(srcSp.isFile) { "Profile soundpack not found." }
        val paths = GamePathResolver.resolve(gameDirPath)
            ?: throw IllegalStateException("Select a valid Asphalt 8 folder.")
        if (autoBackup) createBackup(appFilesDir, gameDirPath, reason = "apply_profile")
        srcSp.copyTo(paths.soundpackFile, overwrite = true)
        var texts = 0
        val pTexts = File(pdir, "text")
        if (pTexts.isDirectory) {
            paths.textDir.mkdirs()
            pTexts.listFiles { f -> f.isFile && f.name.lowercase().endsWith(".texts") }?.forEach { tf ->
                tf.copyTo(File(paths.textDir, tf.name), overwrite = true)
                texts++
            }
        }
        return texts
    }

    fun deleteProfile(appFilesDir: File, profileId: String): Boolean {
        val rootCanon = profilesRoot(appFilesDir).canonicalPath
        val pdir = File(profilesRoot(appFilesDir), profileId)
        require(pdir.canonicalPath.startsWith(rootCanon + File.separator)) { "Invalid profile id." }
        return pdir.deleteRecursively()
    }

    fun exportModPackZip(
        destZip: File,
        packName: String,
        queueTracks: List<QueueTrack>,
    ): Int {
        require(queueTracks.isNotEmpty()) { "Select at least one track to export." }
        val used = HashSet<String>()
        val manifestTracks = JSONArray()
        val jobs = mutableListOf<Pair<File, String>>()

        fun uniquePath(folder: String, stem: String, ext: String): String {
            val safe = stem.replace(Regex("[<>:\"/\\\\|?*\\x00-\\x1f]+"), "_").trim('.', ' ').ifBlank { "track" }
            var cand = "$folder/$safe$ext"
            var idx = 2
            while (cand.lowercase() in used) {
                cand = "$folder/${safe}_${idx}$ext"
                idx++
            }
            used.add(cand.lowercase())
            return cand
        }

        for (t in queueTracks) {
            val src = File(t.srcPath)
            if (!src.isFile) continue
            val ext = if (src.extension.isNotEmpty()) ".${src.extension}" else ".ogg"
            val relMain = uniquePath(t.category.displayName, src.nameWithoutExtension, ext)
            jobs.add(src to relMain)

            var relOutro: String? = null
            if (t.hasOutro) {
                val oSrc = File(t.outroSrcPath ?: t.srcPath)
                if (oSrc.isFile) {
                    relOutro = if (oSrc.canonicalPath == src.canonicalPath) {
                        relMain
                    } else {
                        val oExt = if (oSrc.extension.isNotEmpty()) ".${oSrc.extension}" else ".ogg"
                        val r = uniquePath("Outro", oSrc.nameWithoutExtension, oExt)
                        jobs.add(oSrc to r)
                        r
                    }
                }
            }

            manifestTracks.put(JSONObject().apply {
                put("file", relMain)
                put("title", t.title)
                put("artist", t.artist)
                put("category", t.category.displayName)
                if (relOutro != null) put("outro_file", relOutro)
                t.outroStartSec?.let { put("outro_start", it) }
                t.outroEndSec?.let { put("outro_end", it) }
                if (t.outroFadeIn > 0.0) put("outro_fade_in", t.outroFadeIn)
                if (t.outroFadeOut > 0.0) put("outro_fade_out", t.outroFadeOut)
                put("outro_fade_in_enabled", t.outroFadeInEnabled)
                put("outro_fade_out_enabled", t.outroFadeOutEnabled)
            })
        }

        val utc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        val manifest = JSONObject().apply {
            put("format", "a8_custom_ost_pack")
            put("version", 1)
            put("name", packName.ifBlank { "A8 Custom OST Pack" })
            put("created_at", utc)
            put("tracks", manifestTracks)
        }

        destZip.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(destZip).buffered()).use { zos ->
            for ((src, arcRel) in jobs) {
                zos.putNextEntry(ZipEntry(arcRel))
                src.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
            zos.putNextEntry(ZipEntry("pack_manifest.json"))
            zos.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        return manifestTracks.length()
    }
}
