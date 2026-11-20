package com.pythonnxd.a8ostpatcher.state

import android.app.Application
import android.net.Uri
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pythonnxd.a8ostpatcher.BuildConfig
import com.pythonnxd.a8ostpatcher.engine.*
import com.pythonnxd.a8ostpatcher.privileged.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger

enum class AppTab(val label: String) {
    QUEUE("Queue"),
    LIBRARY("Library"),
    TOOLS("Tools"),
    SETTINGS("Settings"),
}

enum class ToolsSection(val label: String) {
    BACKUPS("Backups"),
    HEALTH("Health check"),
    PROFILES("MOD profiles"),
}

data class BannerMessage(
    val text: String,
    val isError: Boolean = false,
    val id: Long = System.nanoTime(),
)

data class MainUiState(
    val tab: AppTab = AppTab.QUEUE,
    val showAboutScreen: Boolean = false,
    val toolsSection: ToolsSection = ToolsSection.BACKUPS,
    val prefs: AppPrefs = AppPrefs(),
    val gameDirValid: Boolean = false,
    val gameDirWritable: Boolean = false,
    val gameDirHint: String = "",
    val shizukuStatus: ShizukuStatus = ShizukuStatus.NOT_RUNNING,
    val shizukuUid: Int = -1,
    val rootStatus: RootStatus = RootStatus.UNKNOWN,
    val queueTracks: List<QueueTrack> = emptyList(),
    val playlistDirty: Boolean = false,
    val queueCategoryFilter: StationCategory? = null,
    val queueSearch: String = "",
    val focusedQueueUid: String? = null,
    val libraryTracks: List<InstalledTrack> = emptyList(),
    val libraryLoading: Boolean = false,
    val libraryCategoryFilter: StationCategory? = null,
    val librarySearch: String = "",
    val focusedLibraryUid: String? = null,
    val backups: List<BackupEntry> = emptyList(),
    val healthAudit: HealthAudit? = null,
    val profiles: List<ModProfile> = emptyList(),
    val installProgress: InstallProgress = InstallProgress(),
    val banner: BannerMessage? = null,
    val showUnsavedClearDialog: Boolean = false,
    val updateChecking: Boolean = false,
    val updateInfo: UpdateInfo? = null,
    val updateError: String? = null,
    val showStartupUpdatePopup: Boolean = false,
    val downloadingUpdate: Boolean = false,
    val downloadProgress: Float = 0f,
    val readyApkFile: File? = null,
    val busyAction: Boolean = false,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val appCtx = application.applicationContext
    private val prefsStore = PrefsStore(appCtx)
    private val uidCounter = AtomicInteger(1)

    val privileged = PrivilegedAccessManager(appCtx)
    val player = AudioPreviewPlayer(appCtx.cacheDir, viewModelScope)

    private val _ui = MutableStateFlow(MainUiState())
    val ui: StateFlow<MainUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            privileged.shizukuStatus.collect { st ->
                _ui.update { it.copy(shizukuStatus = st, shizukuUid = privileged.shizukuUid.value) }
            }
        }
        viewModelScope.launch {
            privileged.rootStatus.collect { st ->
                _ui.update { it.copy(rootStatus = st) }
            }
        }
        privileged.onPermissionGrantedCallback = {
            if (_ui.value.prefs.accessMode == GameAccessMode.SHIZUKU) {
                connectPrivilegedGameDir(customPath = _ui.value.prefs.gameDir.takeIf { it.isNotBlank() })
            }
        }
        boot()
    }

    private fun mirrorDir(): File = File(appCtx.filesDir, "privileged_mirror").apply { mkdirs() }

    private fun nextUid(prefix: String = "imp"): String =
        "${prefix}_${uidCounter.getAndIncrement()}_${System.currentTimeMillis() % 100000}"

    private fun activeBackendOrNull(mode: GameAccessMode = _ui.value.prefs.accessMode): PrivilegedFileBackend? {
        if (!mode.isPrivileged) return null
        return privileged.getBackend(mode)
    }

    private fun validateGameLocationSync(
        path: String,
        mode: GameAccessMode,
    ): Triple<Boolean, Boolean, String> {
        val clean = path.trim()
        if (clean.isEmpty()) return Triple(false, false, "")
        if (mode.isPrivileged) {
            val backend = privileged.getBackend(mode)
            if (backend == null) {
                val hint = if (mode == GameAccessMode.SHIZUKU) {
                    "Start Shizuku and grant permission to access /Android/data/."
                } else {
                    "Root (su) permission is required to access /Android/data/."
                }
                return Triple(false, false, hint)
            }
            val layout = PrivilegedGameBridge.resolveRemoteLayout(backend, clean)
                ?: return Triple(false, false, "No Asphalt 8 soundpack and music folders were found at $clean.")
            val (writable, wErr) = PrivilegedGameBridge.checkWritable(backend, layout)
            return Triple(true, writable, wErr)
        } else {
            if (PrivilegedAccessManager.isRestrictedAndroidDataPath(clean) && !GamePathResolver.isValidGameDir(clean)) {
                return Triple(
                    false,
                    false,
                    "All files access to /Android/data/ only works on Android 12 and below. On Android 13+, switch Permission method to Shizuku (recommended, no root) or Root (su).",
                )
            }
            val valid = GamePathResolver.isValidGameDir(clean)
            if (!valid) {
                return Triple(false, false, "No Asphalt 8 soundpack and music folders were found in that directory.")
            }
            val (writable, wErr) = GamePathResolver.isWritable(clean)
            return Triple(true, writable, wErr)
        }
    }

    private fun pullExistingTrackIdsSync(path: String, mode: GameAccessMode): Set<String> {
        if (path.isBlank()) return emptySet()
        return if (mode.isPrivileged) {
            val backend = privileged.getBackend(mode) ?: return emptySet()
            val layout = PrivilegedGameBridge.resolveRemoteLayout(backend, path) ?: return emptySet()
            val ws = PrivilegedGameBridge.pullMirror(backend, layout, mirrorDir())
            GamePathResolver.resolve(ws.mirrorDir.absolutePath)?.soundpackFile?.let {
                SoundpackManager.getExistingTrackIds(it)
            }.orEmpty()
        } else {
            GamePathResolver.resolve(path)?.soundpackFile?.let {
                SoundpackManager.getExistingTrackIds(it)
            }.orEmpty()
        }
    }

    private fun boot() {
        viewModelScope.launch {
            var prefs = withContext(Dispatchers.IO) { prefsStore.loadPrefs() }
            val shizukuSt = withContext(Dispatchers.IO) { privileged.refreshShizukuStatus() }

            if (prefs.accessMode == GameAccessMode.STANDARD) {
                if (prefs.gameDir.isBlank() || !GamePathResolver.isValidGameDir(prefs.gameDir)) {
                    val detected = withContext(Dispatchers.IO) {
                        GamePathResolver.autoDetectAndroidPaths(Environment.getExternalStorageDirectory()).firstOrNull()
                    }
                    if (detected != null) {
                        prefs = prefs.copy(gameDir = detected)
                        withContext(Dispatchers.IO) { prefsStore.savePrefs(prefs) }
                    }
                }
            } else if (prefs.accessMode == GameAccessMode.SHIZUKU && shizukuSt == ShizukuStatus.READY) {
                if (prefs.gameDir.isBlank()) {
                    val detected = withContext(Dispatchers.IO) {
                        privileged.getBackend(GameAccessMode.SHIZUKU)?.let {
                            PrivilegedGameBridge.detectRestrictedGameDirs(it).firstOrNull()
                        }
                    }
                    if (detected != null) {
                        prefs = prefs.copy(gameDir = detected)
                        withContext(Dispatchers.IO) { prefsStore.savePrefs(prefs) }
                    }
                }
            }

            player.setLoop(prefs.loopPlayback)
            val (gameValid, writable, writeErr) = withContext(Dispatchers.IO) {
                if (prefs.accessMode == GameAccessMode.ROOT) {
                    // Never invoke `su` silently during cold boot unless root was already granted
                    Triple(false, false, "Tap Connect via Root in Settings to access /Android/data/.")
                } else {
                    validateGameLocationSync(prefs.gameDir, prefs.accessMode)
                }
            }
            val existingIds = if (gameValid) {
                withContext(Dispatchers.IO) { pullExistingTrackIdsSync(prefs.gameDir, prefs.accessMode) }
            } else emptySet()

            val (savedQueue, dirty) = withContext(Dispatchers.IO) { prefsStore.loadQueue(existingIds) }
            _ui.update {
                it.copy(
                    prefs = prefs,
                    shizukuStatus = shizukuSt,
                    shizukuUid = privileged.shizukuUid.value,
                    gameDirValid = gameValid,
                    gameDirWritable = writable,
                    gameDirHint = writeErr,
                    queueTracks = savedQueue,
                    playlistDirty = dirty,
                    focusedQueueUid = savedQueue.firstOrNull()?.uid,
                )
            }
            if (gameValid) {
                refreshGameData()
            }
            analyzeTracksInBackground(savedQueue)
            checkForUpdates(silentPopup = true)
        }
    }

    fun refreshPrivilegedAccess() {
        viewModelScope.launch {
            val st = withContext(Dispatchers.IO) { privileged.refreshShizukuStatus() }
            _ui.update { it.copy(shizukuStatus = st, shizukuUid = privileged.shizukuUid.value) }
            val cur = _ui.value
            if (cur.prefs.accessMode == GameAccessMode.SHIZUKU && st == ShizukuStatus.READY && !cur.gameDirValid) {
                connectPrivilegedGameDir(customPath = cur.prefs.gameDir.takeIf { it.isNotBlank() })
            }
        }
    }

    fun selectAccessMode(mode: GameAccessMode) {
        viewModelScope.launch {
            val updated = _ui.value.prefs.copy(accessMode = mode)
            withContext(Dispatchers.IO) { prefsStore.savePrefs(updated) }
            _ui.update { it.copy(prefs = updated) }

            when (mode) {
                GameAccessMode.STANDARD -> {
                    val (valid, writable, hint) = withContext(Dispatchers.IO) {
                        validateGameLocationSync(updated.gameDir, GameAccessMode.STANDARD)
                    }
                    _ui.update { it.copy(gameDirValid = valid, gameDirWritable = writable, gameDirHint = hint) }
                    if (valid) {
                        refreshGameData()
                        refreshQueueConflicts()
                    }
                }
                GameAccessMode.SHIZUKU -> {
                    val st = withContext(Dispatchers.IO) { privileged.refreshShizukuStatus() }
                    _ui.update { it.copy(shizukuStatus = st, shizukuUid = privileged.shizukuUid.value) }
                    if (st == ShizukuStatus.READY) {
                        connectPrivilegedGameDir(customPath = updated.gameDir.takeIf { PrivilegedAccessManager.isRestrictedAndroidDataPath(it) })
                    } else {
                        val hint = when (st) {
                            ShizukuStatus.NOT_INSTALLED -> "Install and start the Shizuku app to patch /Android/data/ without root."
                            ShizukuStatus.NOT_RUNNING -> "Open Shizuku and start its Wireless Debugging service first."
                            ShizukuStatus.PERMISSION_REQUIRED -> "Shizuku is running! Tap Grant Shizuku permission below."
                            ShizukuStatus.READY -> ""
                        }
                        _ui.update { it.copy(gameDirValid = false, gameDirWritable = false, gameDirHint = hint) }
                    }
                }
                GameAccessMode.ROOT -> {
                    _ui.update {
                        it.copy(
                            gameDirValid = false,
                            gameDirWritable = false,
                            gameDirHint = "Tap Connect via Root below to request su access for /Android/data/.",
                        )
                    }
                }
            }
        }
    }

    fun requestShizukuPermission() {
        val status = privileged.refreshShizukuStatus()
        when (status) {
            ShizukuStatus.READY -> connectPrivilegedGameDir(customPath = _ui.value.prefs.gameDir.takeIf { it.isNotBlank() })
            ShizukuStatus.PERMISSION_REQUIRED -> {
                if (!privileged.requestShizukuPermission()) {
                    showBanner("Could not request Shizuku permission. Update Shizuku to v11+.", isError = true)
                }
            }
            ShizukuStatus.NOT_RUNNING -> showBanner("Shizuku service is not running. Start Shizuku via Wireless Debugging first.", isError = true)
            ShizukuStatus.NOT_INSTALLED -> showBanner("Shizuku is not installed on this device.", isError = true)
        }
    }

    fun connectPrivilegedGameDir(customPath: String? = null) {
        val mode = _ui.value.prefs.accessMode
        if (!mode.isPrivileged) {
            autoDetectGameFolder()
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(busyAction = true) }
            try {
                if (mode == GameAccessMode.SHIZUKU) {
                    val st = withContext(Dispatchers.IO) { privileged.refreshShizukuStatus() }
                    if (st == ShizukuStatus.PERMISSION_REQUIRED) {
                        privileged.requestShizukuPermission()
                        return@launch
                    } else if (st != ShizukuStatus.READY) {
                        val msg = if (st == ShizukuStatus.NOT_INSTALLED) {
                            "Install Shizuku first to access /Android/data/ without root."
                        } else {
                            "Start Shizuku via Wireless Debugging, then try connecting again."
                        }
                        _ui.update { it.copy(gameDirValid = false, gameDirWritable = false, gameDirHint = msg) }
                        showBanner(msg, isError = true)
                        return@launch
                    }
                } else if (mode == GameAccessMode.ROOT) {
                    val hasRoot = withContext(Dispatchers.IO) { privileged.checkRootAvailable() }
                    if (!hasRoot) {
                        val msg = "Root (su) access was denied or is unavailable on this device. Try Shizuku mode instead."
                        _ui.update { it.copy(gameDirValid = false, gameDirWritable = false, gameDirHint = msg) }
                        showBanner(msg, isError = true)
                        return@launch
                    }
                }

                val backend = withContext(Dispatchers.IO) { privileged.getBackend(mode) }
                if (backend == null) {
                    showBanner("Could not initialize ${mode.label} file service.", isError = true)
                    return@launch
                }

                val targetPath = withContext(Dispatchers.IO) {
                    val explicit = customPath?.trim().orEmpty()
                    if (explicit.isNotEmpty() && PrivilegedGameBridge.resolveRemoteLayout(backend, explicit) != null) {
                        explicit
                    } else {
                        PrivilegedGameBridge.detectRestrictedGameDirs(backend).firstOrNull() ?: explicit
                    }
                }

                if (targetPath.isBlank()) {
                    val msg = "Asphalt 8 data was not found inside /Android/data/. Launch Asphalt 8 once or enter a custom path."
                    _ui.update { it.copy(gameDirValid = false, gameDirWritable = false, gameDirHint = msg) }
                    showBanner(msg, isError = true)
                    return@launch
                }

                val (valid, writable, hint) = withContext(Dispatchers.IO) {
                    validateGameLocationSync(targetPath, mode)
                }
                val updated = _ui.value.prefs.copy(gameDir = targetPath, accessMode = mode)
                withContext(Dispatchers.IO) { prefsStore.savePrefs(updated) }
                _ui.update {
                    it.copy(
                        prefs = updated,
                        gameDirValid = valid,
                        gameDirWritable = writable,
                        gameDirHint = hint,
                    )
                }
                if (valid) {
                    showBanner("Connected to Asphalt 8 via ${mode.label}.")
                    refreshGameData()
                    refreshQueueConflicts()
                } else {
                    showBanner(hint.ifBlank { "No Asphalt 8 soundpack/music found at $targetPath." }, isError = true)
                }
            } catch (e: Exception) {
                showBanner("Connection failed: ${e.message}", isError = true)
            } finally {
                _ui.update { it.copy(busyAction = false) }
            }
        }
    }

    fun selectTab(tab: AppTab) {
        _ui.update { it.copy(tab = tab, showAboutScreen = if (tab != AppTab.SETTINGS) false else it.showAboutScreen) }
        if (tab == AppTab.LIBRARY && _ui.value.gameDirValid && _ui.value.libraryTracks.isEmpty()) {
            refreshLibrary()
        } else if (tab == AppTab.TOOLS && _ui.value.gameDirValid) {
            refreshTools()
        }
    }

    fun openAboutScreen() {
        _ui.update { it.copy(showAboutScreen = true) }
        if (_ui.value.updateInfo == null && !_ui.value.updateChecking) {
            checkForUpdates(silentPopup = false)
        }
    }

    fun closeAboutScreen() {
        _ui.update { it.copy(showAboutScreen = false) }
    }

    fun selectToolsSection(section: ToolsSection) {
        _ui.update { it.copy(toolsSection = section) }
    }

    fun dismissBanner() {
        _ui.update { it.copy(banner = null) }
    }

    fun showBanner(text: String, isError: Boolean = false) {
        _ui.update { it.copy(banner = BannerMessage(text, isError)) }
    }

    fun setGameDirectory(path: String) {
        val clean = path.trim()
        val mode = _ui.value.prefs.accessMode
        if (mode.isPrivileged) {
            connectPrivilegedGameDir(customPath = clean)
            return
        }
        viewModelScope.launch {
            val (valid, writable, writeErr) = withContext(Dispatchers.IO) {
                validateGameLocationSync(clean, GameAccessMode.STANDARD)
            }
            val updated = _ui.value.prefs.copy(gameDir = clean)
            withContext(Dispatchers.IO) { prefsStore.savePrefs(updated) }
            _ui.update {
                it.copy(
                    prefs = updated,
                    gameDirValid = valid,
                    gameDirWritable = writable,
                    gameDirHint = writeErr,
                )
            }
            if (valid) {
                showBanner("Asphalt 8 folder connected.")
                refreshGameData()
                refreshQueueConflicts()
            } else {
                showBanner(writeErr, isError = true)
            }
        }
    }

    fun autoDetectGameFolder() {
        val mode = _ui.value.prefs.accessMode
        if (mode.isPrivileged) {
            connectPrivilegedGameDir(customPath = null)
            return
        }
        viewModelScope.launch {
            val detected = withContext(Dispatchers.IO) {
                GamePathResolver.autoDetectAndroidPaths(Environment.getExternalStorageDirectory()).firstOrNull()
            }
            if (detected != null) {
                setGameDirectory(detected)
            } else if (privileged.refreshShizukuStatus() == ShizukuStatus.READY) {
                // Seamless assist: if Shizuku is already authorized and no loose folder exists, detect /Android/data/
                selectAccessMode(GameAccessMode.SHIZUKU)
            } else {
                showBanner(
                    "No loose Asphalt 8 folder found. Choose a loose folder or switch to Shizuku mode in Settings for /Android/data/.",
                    isError = true,
                )
            }
        }
    }

    fun refreshGameData() {
        refreshLibrary()
        refreshTools()
    }

    fun refreshLibrary() {
        val gdir = _ui.value.prefs.gameDir
        val mode = _ui.value.prefs.accessMode
        if (!_ui.value.gameDirValid || gdir.isBlank()) return
        viewModelScope.launch {
            _ui.update { it.copy(libraryLoading = true) }
            try {
                val tracks = withContext(Dispatchers.IO) {
                    if (mode.isPrivileged) {
                        val backend = activeBackendOrNull(mode)
                            ?: throw IllegalStateException("${mode.label} service is not connected.")
                        val layout = PrivilegedGameBridge.resolveRemoteLayout(backend, gdir)
                            ?: throw IllegalStateException("Asphalt 8 folder is unreachable via ${mode.label}.")
                        val ws = PrivilegedGameBridge.pullMirror(backend, layout, mirrorDir())
                        LibraryManager.listInstalledTracks(ws.mirrorDir.absolutePath, ws.remoteMusicIndex)
                    } else {
                        LibraryManager.listInstalledTracks(gdir)
                    }
                }
                _ui.update { cur ->
                    val nextFocus = cur.focusedLibraryUid?.takeIf { uid -> tracks.any { it.uid == uid } }
                        ?: tracks.firstOrNull()?.uid
                    cur.copy(libraryTracks = tracks, libraryLoading = false, focusedLibraryUid = nextFocus)
                }
            } catch (e: Exception) {
                _ui.update { it.copy(libraryLoading = false) }
                showBanner("Could not scan library: ${e.message}", isError = true)
            }
        }
    }

    fun refreshTools() {
        val gdir = _ui.value.prefs.gameDir
        val mode = _ui.value.prefs.accessMode
        viewModelScope.launch {
            val profiles = withContext(Dispatchers.IO) { LibraryManager.listProfiles(appCtx.filesDir) }
            if (!_ui.value.gameDirValid || gdir.isBlank()) {
                _ui.update { it.copy(profiles = profiles, backups = emptyList(), healthAudit = null) }
                return@launch
            }
            val backups = withContext(Dispatchers.IO) { LibraryManager.listBackups(appCtx.filesDir, gdir) }
            val audit = withContext(Dispatchers.IO) {
                try {
                    if (mode.isPrivileged) {
                        val backend = activeBackendOrNull(mode) ?: return@withContext null
                        val layout = PrivilegedGameBridge.resolveRemoteLayout(backend, gdir) ?: return@withContext null
                        val ws = PrivilegedGameBridge.pullMirror(backend, layout, mirrorDir())
                        LibraryManager.auditHealth(ws.mirrorDir.absolutePath, ws.remoteMusicIndex)
                    } else {
                        LibraryManager.auditHealth(gdir)
                    }
                } catch (_: Exception) {
                    null
                }
            }
            _ui.update { it.copy(backups = backups, healthAudit = audit, profiles = profiles) }
        }
    }

    private fun refreshQueueConflicts() {
        val gdir = _ui.value.prefs.gameDir
        val mode = _ui.value.prefs.accessMode
        if (!_ui.value.gameDirValid || gdir.isBlank()) return
        viewModelScope.launch {
            val ids = withContext(Dispatchers.IO) { pullExistingTrackIdsSync(gdir, mode) }
            _ui.update { cur ->
                cur.copy(queueTracks = cur.queueTracks.map { it.copy(conflict = it.baseId in ids) })
            }
        }
    }

    /* ---------- Library Preview & On-Demand Remote Fetch ---------- */

    fun playInstalledTrack(track: InstalledTrack, isOutro: Boolean) {
        val mode = _ui.value.prefs.accessMode
        val gdir = _ui.value.prefs.gameDir
        val fileName = if (isOutro) track.outroFile else track.mainFile
        val directPath = if (isOutro) track.outroPath else track.mainPath
        if (fileName.isNullOrBlank() || directPath.isNullOrBlank()) return

        if (!mode.isPrivileged) {
            player.togglePlay(
                uid = track.uid,
                title = track.title,
                artist = track.artist,
                category = track.category,
                srcFile = File(directPath),
                isOutro = isOutro,
                onError = { showBanner(it, isError = true) },
            )
            return
        }

        // Toggle pause immediately if already playing this exact track
        val np = player.state.value
        if (np.uid == track.uid && np.isOutro == isOutro && np.isLocked) {
            player.toggleCurrentPlayPause()
            return
        }

        viewModelScope.launch {
            try {
                val cachedFile = withContext(Dispatchers.IO) {
                    val backend = activeBackendOrNull(mode)
                        ?: throw IllegalStateException("${mode.label} service is not connected.")
                    val layout = PrivilegedGameBridge.resolveRemoteLayout(backend, gdir)
                        ?: throw IllegalStateException("Asphalt 8 folder is unreachable.")
                    PrivilegedGameBridge.fetchTrackToCache(backend, layout, fileName, appCtx.cacheDir)
                }
                player.togglePlay(
                    uid = track.uid,
                    title = track.title,
                    artist = track.artist,
                    category = track.category,
                    srcFile = cachedFile,
                    isOutro = isOutro,
                    onError = { showBanner(it, isError = true) },
                )
            } catch (e: Exception) {
                showBanner("Could not load track audio: ${e.message}", isError = true)
            }
        }
    }

    fun trimInstalledTrackLast20s(track: InstalledTrack) {
        val mode = _ui.value.prefs.accessMode
        val gdir = _ui.value.prefs.gameDir
        viewModelScope.launch {
            _ui.update { it.copy(busyAction = true) }
            try {
                val mainLocal = withContext(Dispatchers.IO) {
                    if (mode.isPrivileged) {
                        val backend = activeBackendOrNull(mode)
                            ?: throw IllegalStateException("${mode.label} service is not connected.")
                        val layout = PrivilegedGameBridge.resolveRemoteLayout(backend, gdir)
                            ?: throw IllegalStateException("Asphalt 8 folder is unreachable.")
                        PrivilegedGameBridge.fetchTrackToCache(backend, layout, track.mainFile, appCtx.cacheDir)
                    } else {
                        File(track.mainPath)
                    }
                }
                val dur = withContext(Dispatchers.IO) { AudioPipeline.probeDuration(mainLocal) } ?: 180.0
                saveLibraryTrackOutroInternal(
                    track = track,
                    srcFile = mainLocal,
                    startSec = maxOf(0.0, dur - 20.0),
                    endSec = dur,
                    fadeInSec = 0.0,
                    fadeOutSec = 1.2,
                )
            } catch (e: Exception) {
                showBanner("Could not trim outro: ${e.message}", isError = true)
            } finally {
                _ui.update { it.copy(busyAction = false) }
            }
        }
    }

    /* ---------- Queue Management ---------- */

    fun importAudioUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _ui.update { it.copy(busyAction = true) }
            try {
                val files = withContext(Dispatchers.IO) {
                    uris.mapNotNull { AudioPipeline.copyUriToImportCache(appCtx, it, appCtx.cacheDir) }
                }
                addScannedFiles(files)
            } catch (e: Exception) {
                showBanner("Could not import songs: ${e.message}", isError = true)
            } finally {
                _ui.update { it.copy(busyAction = false) }
            }
        }
    }

    fun importFolderTreeUri(treeUri: Uri) {
        viewModelScope.launch {
            _ui.update { it.copy(busyAction = true) }
            try {
                val directFile = resolveDirectPathFromTreeUri(treeUri)
                if (directFile != null && directFile.isDirectory) {
                    addScannedFiles(listOf(directFile))
                } else {
                    val copiedDir = withContext(Dispatchers.IO) { copyDocumentTreeAudioToCache(treeUri) }
                    if (copiedDir != null) {
                        addScannedFiles(listOf(copiedDir))
                    } else {
                        showBanner("No supported audio files found in that folder.", isError = true)
                    }
                }
            } catch (e: Exception) {
                showBanner("Could not scan music folder: ${e.message}", isError = true)
            } finally {
                _ui.update { it.copy(busyAction = false) }
            }
        }
    }

    fun resolveDirectPathFromTreeUri(treeUri: Uri): File? {
        val docId = android.provider.DocumentsContract.getTreeDocumentId(treeUri) ?: return null
        val split = docId.split(":")
        if (split.size >= 2 && split[0].equals("primary", ignoreCase = true)) {
            return File(Environment.getExternalStorageDirectory(), split[1])
        }
        return null
    }

    private fun copyDocumentTreeAudioToCache(treeUri: Uri): File? {
        val rootDoc = DocumentFile.fromTreeUri(appCtx, treeUri) ?: return null
        val dstRoot = File(appCtx.cacheDir, "imported_folders/${System.currentTimeMillis()}").apply { mkdirs() }
        var count = 0
        fun walk(doc: DocumentFile, targetDir: File, depth: Int) {
            if (depth > 2) return
            for (child in doc.listFiles()) {
                val name = child.name ?: continue
                if (child.isDirectory) {
                    walk(child, File(targetDir, name).apply { mkdirs() }, depth + 1)
                } else if (child.isFile) {
                    val low = name.lowercase()
                    val relevant = low == "pack_manifest.json" ||
                        low.endsWith(".zip") ||
                        AudioPipeline.AUDIO_EXTENSIONS.any { low.endsWith(it) }
                    if (!relevant) continue
                    val out = File(targetDir, name)
                    appCtx.contentResolver.openInputStream(child.uri)?.use { inp ->
                        FileOutputStream(out).use { o -> inp.copyTo(o) }
                        count++
                    }
                }
            }
        }
        walk(rootDoc, dstRoot, 0)
        return if (count > 0) dstRoot else null
    }

    private suspend fun addScannedFiles(files: List<File>) {
        val gdir = _ui.value.prefs.gameDir
        val mode = _ui.value.prefs.accessMode
        val existingIds = if (_ui.value.gameDirValid) {
            withContext(Dispatchers.IO) { pullExistingTrackIdsSync(gdir, mode) }
        } else emptySet()

        val scanned = withContext(Dispatchers.IO) {
            AudioPipeline.scanSources(
                filesOrDirs = files,
                existingGameIds = existingIds,
                packExtractRoot = File(appCtx.cacheDir, "mod_packs"),
                uidGenerator = { nextUid("imp") },
            )
        }
        if (scanned.isEmpty()) {
            showBanner("No supported audio files (.mp3, .ogg, .wav, .flac, .m4a, .aac, .mpc) or .zip packs found.", isError = true)
            return
        }
        val existingPaths = _ui.value.queueTracks.map { it.srcPath.lowercase() }.toSet()
        val uniqueNew = scanned.filter { it.srcPath.lowercase() !in existingPaths }
        val merged = _ui.value.queueTracks + uniqueNew
        _ui.update { cur ->
            cur.copy(
                queueTracks = merged,
                playlistDirty = true,
                focusedQueueUid = cur.focusedQueueUid ?: merged.firstOrNull()?.uid,
            )
        }
        persistQueue()
        showBanner("Added ${uniqueNew.size} song(s) to queue.")
        analyzeTracksInBackground(uniqueNew)
    }

    private fun analyzeTracksInBackground(tracks: List<QueueTrack>) {
        if (tracks.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val tmpDir = File(appCtx.cacheDir, "analysis").apply { mkdirs() }
            for (t in tracks) {
                val f = File(t.srcPath)
                if (!f.isFile) continue
                val profile = AudioPipeline.analyzeAudioProfile(f, tmpDir, numBins = 96, includePeaks = true)
                _ui.update { cur ->
                    cur.copy(queueTracks = cur.queueTracks.map {
                        if (it.uid == t.uid) it.copy(profile = profile) else it
                    })
                }
            }
        }
    }

    fun setQueueFilter(cat: StationCategory?) {
        _ui.update { it.copy(queueCategoryFilter = cat) }
    }

    fun setQueueSearch(query: String) {
        _ui.update { it.copy(queueSearch = query) }
    }

    fun focusQueueTrack(uid: String) {
        _ui.update { it.copy(focusedQueueUid = uid) }
    }

    fun toggleQueueSelection(uid: String) {
        _ui.update { cur ->
            cur.copy(queueTracks = cur.queueTracks.map {
                if (it.uid == uid) it.copy(selected = !it.selected) else it
            })
        }
        persistQueue()
    }

    fun toggleSelectAllQueue(filteredUids: Set<String>) {
        _ui.update { cur ->
            val target = cur.queueTracks.filter { it.uid in filteredUids }
            val allChecked = target.isNotEmpty() && target.all { it.selected }
            cur.copy(queueTracks = cur.queueTracks.map {
                if (it.uid in filteredUids) it.copy(selected = !allChecked) else it
            })
        }
        persistQueue()
    }

    fun clearQueueSelection() {
        _ui.update { cur ->
            cur.copy(queueTracks = cur.queueTracks.map { it.copy(selected = false) })
        }
        persistQueue()
    }

    fun updateQueueTrack(
        uid: String,
        title: String,
        artist: String,
        category: StationCategory,
    ) {
        val cleanTitle = title.trim().ifBlank { "Untitled" }
        val cleanArtist = artist.trim().ifBlank { "Custom Artist" }
        _ui.update { cur ->
            cur.copy(
                playlistDirty = true,
                queueTracks = cur.queueTracks.map {
                    if (it.uid == uid) it.copy(title = cleanTitle, artist = cleanArtist, category = category) else it
                },
            )
        }
        player.syncMetadataIfActive(uid, cleanTitle, cleanArtist, category)
        persistQueue()
    }

    fun batchUpdateQueueArtist(artist: String) {
        val clean = artist.trim()
        if (clean.isEmpty()) return
        _ui.update { cur ->
            cur.copy(
                playlistDirty = true,
                queueTracks = cur.queueTracks.map { t ->
                    if (t.selected) {
                        player.syncMetadataIfActive(t.uid, t.title, clean, t.category)
                        t.copy(artist = clean)
                    } else t
                },
            )
        }
        persistQueue()
        showBanner("Updated artist on selected songs.")
    }

    fun batchUpdateQueueStation(category: StationCategory) {
        _ui.update { cur ->
            cur.copy(
                playlistDirty = true,
                queueTracks = cur.queueTracks.map { t ->
                    if (t.selected) {
                        player.syncMetadataIfActive(t.uid, t.title, t.artist, category)
                        t.copy(category = category)
                    } else t
                },
            )
        }
        persistQueue()
        showBanner("Moved selected songs to ${category.displayName}.")
    }

    fun removeQueueTrack(uid: String) {
        player.stopIfUidMatches(setOf(uid))
        _ui.update { cur ->
            val remaining = cur.queueTracks.filterNot { it.uid == uid }
            val nextFocus = if (cur.focusedQueueUid == uid) remaining.firstOrNull()?.uid else cur.focusedQueueUid
            cur.copy(
                queueTracks = remaining,
                playlistDirty = remaining.isNotEmpty() && cur.playlistDirty,
                focusedQueueUid = nextFocus,
            )
        }
        persistQueue()
    }

    fun removeSelectedQueueTracks() {
        val toRemove = _ui.value.queueTracks.filter { it.selected }.map { it.uid }.toSet()
        if (toRemove.isEmpty()) return
        player.stopIfUidMatches(toRemove)
        _ui.update { cur ->
            val remaining = cur.queueTracks.filterNot { it.uid in toRemove }
            cur.copy(
                queueTracks = remaining,
                playlistDirty = remaining.isNotEmpty() && cur.playlistDirty,
                focusedQueueUid = remaining.firstOrNull()?.uid,
            )
        }
        persistQueue()
    }

    fun requestClearQueue() {
        val st = _ui.value
        if (st.queueTracks.isEmpty()) return
        if (st.prefs.warnUnsavedPlaylist && st.playlistDirty) {
            _ui.update { it.copy(showUnsavedClearDialog = true) }
        } else {
            confirmClearQueue(dontShowAgain = false)
        }
    }

    fun dismissUnsavedClearDialog() {
        _ui.update { it.copy(showUnsavedClearDialog = false) }
    }

    fun confirmClearQueue(dontShowAgain: Boolean) {
        val uids = _ui.value.queueTracks.map { it.uid }.toSet()
        player.stopIfUidMatches(uids)
        if (dontShowAgain) {
            updatePrefs { it.copy(warnUnsavedPlaylist = false) }
        }
        _ui.update {
            it.copy(
                queueTracks = emptyList(),
                playlistDirty = false,
                focusedQueueUid = null,
                showUnsavedClearDialog = false,
            )
        }
        persistQueue()
    }

    fun saveQueueTrackOutro(
        uid: String,
        customOutroFile: File?,
        startSec: Double?,
        endSec: Double?,
        fadeInSec: Double,
        fadeOutSec: Double,
        fadeInEnabled: Boolean,
        fadeOutEnabled: Boolean,
    ) {
        _ui.update { cur ->
            cur.copy(
                playlistDirty = true,
                queueTracks = cur.queueTracks.map { t ->
                    if (t.uid != uid) t
                    else t.copy(
                        hasOutro = true,
                        outroSrcPath = customOutroFile?.absolutePath ?: t.srcPath,
                        outroSrcName = customOutroFile?.name ?: t.srcName,
                        outroStartSec = if (customOutroFile == null) startSec else null,
                        outroEndSec = if (customOutroFile == null) endSec else null,
                        outroFadeIn = fadeInSec,
                        outroFadeOut = fadeOutSec,
                        outroFadeInEnabled = fadeInEnabled,
                        outroFadeOutEnabled = fadeOutEnabled,
                    )
                },
            )
        }
        persistQueue()
        showBanner("Outro saved for track.")
    }

    fun attachCustomOutroUriToQueueTrack(uid: String, uri: Uri) {
        viewModelScope.launch {
            val copied = withContext(Dispatchers.IO) {
                AudioPipeline.copyUriToImportCache(appCtx, uri, appCtx.cacheDir)
            }
            if (copied != null) {
                saveQueueTrackOutro(
                    uid = uid,
                    customOutroFile = copied,
                    startSec = null,
                    endSec = null,
                    fadeInSec = 0.0,
                    fadeOutSec = 0.0,
                    fadeInEnabled = false,
                    fadeOutEnabled = false,
                )
            } else {
                showBanner("Could not read selected outro audio file.", isError = true)
            }
        }
    }

    fun removeQueueTrackOutro(uid: String) {
        _ui.update { cur ->
            cur.copy(
                playlistDirty = true,
                queueTracks = cur.queueTracks.map { t ->
                    if (t.uid != uid) t
                    else t.copy(
                        hasOutro = false,
                        outroSrcPath = null,
                        outroSrcName = null,
                        outroStartSec = null,
                        outroEndSec = null,
                        outroFadeIn = 0.0,
                        outroFadeOut = 0.0,
                        outroFadeInEnabled = false,
                        outroFadeOutEnabled = false,
                    )
                },
            )
        }
        persistQueue()
    }

    fun startInstallSelected() {
        val st = _ui.value
        if (!st.gameDirValid) {
            showBanner("Select a valid Asphalt 8 folder in Settings first.", isError = true)
            return
        }
        val selected = st.queueTracks.filter { it.selected }.ifEmpty { st.queueTracks }
        if (selected.isEmpty()) {
            showBanner("Add at least one song to install.", isError = true)
            return
        }
        val installTracks = selected.map { it.copy(selected = true) }
        viewModelScope.launch {
            _ui.update {
                it.copy(
                    installProgress = InstallProgress(
                        active = true,
                        current = 0,
                        total = selected.size,
                        stage = "Preparing installation...",
                    )
                )
            }
            try {
                val res = withContext(Dispatchers.IO) {
                    val mode = st.prefs.accessMode
                    if (mode.isPrivileged) {
                        val backend = activeBackendOrNull(mode)
                            ?: throw IllegalStateException("${mode.label} service is not connected.")
                        val layout = PrivilegedGameBridge.resolveRemoteLayout(backend, st.prefs.gameDir)
                            ?: throw IllegalStateException("Asphalt 8 folder is unreachable via ${mode.label}.")
                        _ui.update { s -> s.copy(installProgress = s.installProgress.copy(stage = "Reading Asphalt 8 soundpack via ${mode.label}...")) }
                        val ws = PrivilegedGameBridge.pullMirror(backend, layout, mirrorDir())
                        val r = LibraryManager.installQueueTracks(
                            appFilesDir = appCtx.filesDir,
                            tempDir = File(appCtx.cacheDir, "install_tmp"),
                            gameDirPath = ws.mirrorDir.absolutePath,
                            tracks = installTracks,
                            qualityPreset = st.prefs.qualityPreset,
                            keepOriginal = st.prefs.keepOriginal,
                            conflictPolicy = st.prefs.conflictPolicy,
                            autoBackup = st.prefs.autoBackup,
                            onProgress = { cur, tot, stage ->
                                _ui.update { s ->
                                    s.copy(installProgress = s.installProgress.copy(current = cur, total = tot, stage = stage))
                                }
                            },
                            backupKeyDir = st.prefs.gameDir,
                        )
                        _ui.update { s -> s.copy(installProgress = s.installProgress.copy(stage = "Writing files to /Android/data/ via ${mode.label}...")) }
                        PrivilegedGameBridge.pushMirrorChanges(
                            backend = backend,
                            workspace = ws,
                            pushSoundpack = true,
                            pushTexts = true,
                            pushStagedMusic = true,
                        )
                        r
                    } else {
                        LibraryManager.installQueueTracks(
                            appFilesDir = appCtx.filesDir,
                            tempDir = File(appCtx.cacheDir, "install_tmp"),
                            gameDirPath = st.prefs.gameDir,
                            tracks = installTracks,
                            qualityPreset = st.prefs.qualityPreset,
                            keepOriginal = st.prefs.keepOriginal,
                            conflictPolicy = st.prefs.conflictPolicy,
                            autoBackup = st.prefs.autoBackup,
                            onProgress = { cur, tot, stage ->
                                _ui.update { s ->
                                    s.copy(installProgress = s.installProgress.copy(current = cur, total = tot, stage = stage))
                                }
                            },
                        )
                    }
                }
                val summary = "Installed ${res.added} song(s)" +
                    (if (res.skipped > 0) ", skipped ${res.skipped}" else "") +
                    " and updated ${res.textsUpdated} language file(s)."
                _ui.update {
                    it.copy(
                        playlistDirty = false,
                        installProgress = InstallProgress(
                            active = true,
                            current = selected.size,
                            total = selected.size,
                            stage = "Complete",
                            done = true,
                            summary = summary,
                        ),
                    )
                }
                persistQueue()
                refreshGameData()
                refreshQueueConflicts()
            } catch (e: Exception) {
                _ui.update {
                    it.copy(
                        installProgress = it.installProgress.copy(
                            done = true,
                            error = e.message ?: "Installation failed.",
                        )
                    )
                }
            }
        }
    }

    fun dismissInstallDialog() {
        _ui.update { it.copy(installProgress = InstallProgress()) }
    }

    /* ---------- In-Game Library ---------- */

    fun setLibraryFilter(cat: StationCategory?) {
        _ui.update { it.copy(libraryCategoryFilter = cat) }
    }

    fun setLibrarySearch(q: String) {
        _ui.update { it.copy(librarySearch = q) }
    }

    fun focusLibraryTrack(uid: String) {
        _ui.update { it.copy(focusedLibraryUid = uid) }
    }

    fun toggleLibrarySelection(uid: String) {
        _ui.update { cur ->
            cur.copy(libraryTracks = cur.libraryTracks.map {
                if (it.uid == uid) it.copy(selected = !it.selected) else it
            })
        }
    }

    fun toggleSelectAllLibrary(filteredUids: Set<String>) {
        _ui.update { cur ->
            val target = cur.libraryTracks.filter { it.uid in filteredUids }
            val allChecked = target.isNotEmpty() && target.all { it.selected }
            cur.copy(libraryTracks = cur.libraryTracks.map {
                if (it.uid in filteredUids) it.copy(selected = !allChecked) else it
            })
        }
    }

    fun clearLibrarySelection() {
        _ui.update { cur ->
            cur.copy(libraryTracks = cur.libraryTracks.map { it.copy(selected = false) })
        }
    }

    fun saveLibraryTrackEdit(track: InstalledTrack, title: String, artist: String, category: StationCategory) {
        val gdir = _ui.value.prefs.gameDir
        val mode = _ui.value.prefs.accessMode
        viewModelScope.launch {
            _ui.update { it.copy(busyAction = true) }
            try {
                withContext(Dispatchers.IO) {
                    if (mode.isPrivileged) {
                        val backend = activeBackendOrNull(mode)
                            ?: throw IllegalStateException("${mode.label} service is not connected.")
                        val layout = PrivilegedGameBridge.resolveRemoteLayout(backend, gdir)
                            ?: throw IllegalStateException("Asphalt 8 folder is unreachable.")
                        val ws = PrivilegedGameBridge.pullMirror(backend, layout, mirrorDir())
                        LibraryManager.updateInstalledTrack(
                            appFilesDir = appCtx.filesDir,
                            gameDirPath = ws.mirrorDir.absolutePath,
                            track = track,
                            newTitle = title,
                            newArtist = artist,
                            newCategory = category,
                            autoBackup = _ui.value.prefs.autoBackup,
                            backupKeyDir = gdir,
                        )
                        PrivilegedGameBridge.pushMirrorChanges(backend, ws, pushSoundpack = true, pushTexts = true, pushStagedMusic = false)
                    } else {
                        LibraryManager.updateInstalledTrack(
                            appFilesDir = appCtx.filesDir,
                            gameDirPath = gdir,
                            track = track,
                            newTitle = title,
                            newArtist = artist,
                            newCategory = category,
                            autoBackup = _ui.value.prefs.autoBackup,
                        )
                    }
                }
                player.syncMetadataIfActive(track.uid, title, artist, category)
                showBanner("Saved changes for \"$title\".")
                refreshGameData()
            } catch (e: Exception) {
                showBanner("Could not save track: ${e.message}", isError = true)
            } finally {
                _ui.update { it.copy(busyAction = false) }
            }
        }
    }

    fun saveLibraryTrackOutro(
        track: InstalledTrack,
        customOutroFile: File?,
        startSec: Double?,
        endSec: Double?,
        fadeInSec: Double,
        fadeOutSec: Double,
    ) {
        val src = customOutroFile ?: File(track.mainPath)
        viewModelScope.launch {
            _ui.update { it.copy(busyAction = true) }
            try {
                saveLibraryTrackOutroInternal(
                    track = track,
                    srcFile = src,
                    startSec = if (customOutroFile == null) startSec else null,
                    endSec = if (customOutroFile == null) endSec else null,
                    fadeInSec = fadeInSec,
                    fadeOutSec = fadeOutSec,
                )
            } catch (e: Exception) {
                showBanner("Could not save outro: ${e.message}", isError = true)
            } finally {
                _ui.update { it.copy(busyAction = false) }
            }
        }
    }

    private suspend fun saveLibraryTrackOutroInternal(
        track: InstalledTrack,
        srcFile: File,
        startSec: Double?,
        endSec: Double?,
        fadeInSec: Double,
        fadeOutSec: Double,
    ) {
        val gdir = _ui.value.prefs.gameDir
        val mode = _ui.value.prefs.accessMode
        withContext(Dispatchers.IO) {
            if (mode.isPrivileged) {
                val backend = activeBackendOrNull(mode)
                    ?: throw IllegalStateException("${mode.label} service is not connected.")
                val layout = PrivilegedGameBridge.resolveRemoteLayout(backend, gdir)
                    ?: throw IllegalStateException("Asphalt 8 folder is unreachable.")
                val ws = PrivilegedGameBridge.pullMirror(backend, layout, mirrorDir())
                LibraryManager.saveInstalledTrackOutro(
                    appFilesDir = appCtx.filesDir,
                    tempDir = File(appCtx.cacheDir, "outro_tmp"),
                    gameDirPath = ws.mirrorDir.absolutePath,
                    baseId = track.baseId,
                    srcFile = srcFile,
                    startSec = startSec,
                    endSec = endSec,
                    fadeInSec = fadeInSec,
                    fadeOutSec = fadeOutSec,
                    qualityPreset = _ui.value.prefs.qualityPreset,
                    autoBackup = _ui.value.prefs.autoBackup,
                    backupKeyDir = gdir,
                )
                PrivilegedGameBridge.pushMirrorChanges(backend, ws, pushSoundpack = true, pushTexts = false, pushStagedMusic = true)
            } else {
                LibraryManager.saveInstalledTrackOutro(
                    appFilesDir = appCtx.filesDir,
                    tempDir = File(appCtx.cacheDir, "outro_tmp"),
                    gameDirPath = gdir,
                    baseId = track.baseId,
                    srcFile = srcFile,
                    startSec = startSec,
                    endSec = endSec,
                    fadeInSec = fadeInSec,
                    fadeOutSec = fadeOutSec,
                    qualityPreset = _ui.value.prefs.qualityPreset,
                    autoBackup = _ui.value.prefs.autoBackup,
                )
            }
        }
        showBanner("Saved outro for \"${track.title}\".")
        refreshGameData()
    }

    fun removeLibraryTrackOutro(track: InstalledTrack, deleteFile: Boolean = true) {
        val gdir = _ui.value.prefs.gameDir
        val mode = _ui.value.prefs.accessMode
        viewModelScope.launch {
            _ui.update { it.copy(busyAction = true) }
            try {
                withContext(Dispatchers.IO) {
                    if (mode.isPrivileged) {
                        val backend = activeBackendOrNull(mode)
                            ?: throw IllegalStateException("${mode.label} service is not connected.")
                        val layout = PrivilegedGameBridge.resolveRemoteLayout(backend, gdir)
                            ?: throw IllegalStateException("Asphalt 8 folder is unreachable.")
                        val ws = PrivilegedGameBridge.pullMirror(backend, layout, mirrorDir())
                        val toDelete = mutableListOf<String>()
                        LibraryManager.removeInstalledTrackOutro(
                            appFilesDir = appCtx.filesDir,
                            gameDirPath = ws.mirrorDir.absolutePath,
                            baseId = track.baseId,
                            deleteFile = deleteFile,
                            autoBackup = _ui.value.prefs.autoBackup,
                            backupKeyDir = gdir,
                            deletedFileNamesOut = toDelete,
                        )
                        PrivilegedGameBridge.pushMirrorChanges(
                            backend = backend,
                            workspace = ws,
                            pushSoundpack = true,
                            pushTexts = false,
                            pushStagedMusic = false,
                            deleteRemoteMusicFiles = toDelete,
                        )
                    } else {
                        LibraryManager.removeInstalledTrackOutro(
                            appFilesDir = appCtx.filesDir,
                            gameDirPath = gdir,
                            baseId = track.baseId,
                            deleteFile = deleteFile,
                            autoBackup = _ui.value.prefs.autoBackup,
                        )
                    }
                }
                showBanner("Removed outro for \"${track.title}\".")
                refreshGameData()
            } catch (e: Exception) {
                showBanner("Could not remove outro: ${e.message}", isError = true)
            } finally {
                _ui.update { it.copy(busyAction = false) }
            }
        }
    }

    fun removeLibraryTracks(tracks: List<InstalledTrack>, deleteFiles: Boolean) {
        if (tracks.isEmpty()) return
        val gdir = _ui.value.prefs.gameDir
        val mode = _ui.value.prefs.accessMode
        player.stopIfUidMatches(tracks.map { it.uid }.toSet())
        viewModelScope.launch {
            _ui.update { it.copy(busyAction = true) }
            try {
                val removed = withContext(Dispatchers.IO) {
                    if (mode.isPrivileged) {
                        val backend = activeBackendOrNull(mode)
                            ?: throw IllegalStateException("${mode.label} service is not connected.")
                        val layout = PrivilegedGameBridge.resolveRemoteLayout(backend, gdir)
                            ?: throw IllegalStateException("Asphalt 8 folder is unreachable.")
                        val ws = PrivilegedGameBridge.pullMirror(backend, layout, mirrorDir())
                        val toDelete = mutableListOf<String>()
                        val n = LibraryManager.removeInstalledTracks(
                            appFilesDir = appCtx.filesDir,
                            gameDirPath = ws.mirrorDir.absolutePath,
                            baseIds = tracks.map { it.baseId },
                            deleteFiles = deleteFiles,
                            autoBackup = _ui.value.prefs.autoBackup,
                            backupKeyDir = gdir,
                            deletedFileNamesOut = toDelete,
                        )
                        PrivilegedGameBridge.pushMirrorChanges(
                            backend = backend,
                            workspace = ws,
                            pushSoundpack = true,
                            pushTexts = true,
                            pushStagedMusic = false,
                            deleteRemoteMusicFiles = toDelete,
                        )
                        n
                    } else {
                        LibraryManager.removeInstalledTracks(
                            appFilesDir = appCtx.filesDir,
                            gameDirPath = gdir,
                            baseIds = tracks.map { it.baseId },
                            deleteFiles = deleteFiles,
                            autoBackup = _ui.value.prefs.autoBackup,
                        )
                    }
                }
                showBanner("Removed $removed song(s) from Asphalt 8.")
                refreshGameData()
                refreshQueueConflicts()
            } catch (e: Exception) {
                showBanner("Could not remove tracks: ${e.message}", isError = true)
            } finally {
                _ui.update { it.copy(busyAction = false) }
            }
        }
    }

    /* ---------- Tools: Backups, Health, Profiles, Mod Pack Export ---------- */

    fun createManualBackup() {
        val gdir = _ui.value.prefs.gameDir
        val mode = _ui.value.prefs.accessMode
        if (!_ui.value.gameDirValid) {
            showBanner("Select a valid Asphalt 8 folder first.", isError = true)
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(busyAction = true) }
            try {
                withContext(Dispatchers.IO) {
                    if (mode.isPrivileged) {
                        val backend = activeBackendOrNull(mode)
                            ?: throw IllegalStateException("${mode.label} service is not connected.")
                        val layout = PrivilegedGameBridge.resolveRemoteLayout(backend, gdir)
                            ?: throw IllegalStateException("Asphalt 8 folder is unreachable.")
                        val ws = PrivilegedGameBridge.pullMirror(backend, layout, mirrorDir())
                        LibraryManager.createBackup(appCtx.filesDir, ws.mirrorDir.absolutePath, "manual", backupKeyDir = gdir)
                    } else {
                        LibraryManager.createBackup(appCtx.filesDir, gdir, "manual")
                    }
                }
                showBanner("Backup snapshot created.")
                refreshTools()
            } catch (e: Exception) {
                showBanner("Backup failed: ${e.message}", isError = true)
            } finally {
                _ui.update { it.copy(busyAction = false) }
            }
        }
    }

    fun restoreBackup(entry: BackupEntry) {
        val gdir = _ui.value.prefs.gameDir
        val mode = _ui.value.prefs.accessMode
        viewModelScope.launch {
            _ui.update { it.copy(busyAction = true) }
            try {
                val count = withContext(Dispatchers.IO) {
                    if (mode.isPrivileged) {
                        val backend = activeBackendOrNull(mode)
                            ?: throw IllegalStateException("${mode.label} service is not connected.")
                        val layout = PrivilegedGameBridge.resolveRemoteLayout(backend, gdir)
                            ?: throw IllegalStateException("Asphalt 8 folder is unreachable.")
                        val ws = PrivilegedGameBridge.pullMirror(backend, layout, mirrorDir())
                        val c = LibraryManager.restoreBackup(ws.mirrorDir.absolutePath, File(entry.path))
                        PrivilegedGameBridge.pushMirrorChanges(backend, ws, pushSoundpack = true, pushTexts = true, pushStagedMusic = false)
                        c
                    } else {
                        LibraryManager.restoreBackup(gdir, File(entry.path))
                    }
                }
                showBanner("Restored soundpack and $count language file(s).")
                refreshGameData()
                refreshQueueConflicts()
            } catch (e: Exception) {
                showBanner("Restore failed: ${e.message}", isError = true)
            } finally {
                _ui.update { it.copy(busyAction = false) }
            }
        }
    }

    fun deleteBackup(entry: BackupEntry) {
        val gdir = _ui.value.prefs.gameDir
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { LibraryManager.deleteBackup(appCtx.filesDir, gdir, File(entry.path)) }
                showBanner("Backup deleted.")
                refreshTools()
            } catch (e: Exception) {
                showBanner("Could not delete backup: ${e.message}", isError = true)
            }
        }
    }

    fun repairHealth(fixTexts: Boolean, fixEvents: Boolean, pruneMissing: Boolean, deleteOrphans: Boolean) {
        val gdir = _ui.value.prefs.gameDir
        val mode = _ui.value.prefs.accessMode
        if (!_ui.value.gameDirValid) return
        viewModelScope.launch {
            _ui.update { it.copy(busyAction = true) }
            try {
                val res = withContext(Dispatchers.IO) {
                    if (mode.isPrivileged) {
                        val backend = activeBackendOrNull(mode)
                            ?: throw IllegalStateException("${mode.label} service is not connected.")
                        val layout = PrivilegedGameBridge.resolveRemoteLayout(backend, gdir)
                            ?: throw IllegalStateException("Asphalt 8 folder is unreachable.")
                        val ws = PrivilegedGameBridge.pullMirror(backend, layout, mirrorDir())
                        val orphansToDelete = mutableListOf<String>()
                        val r = LibraryManager.repairHealth(
                            appFilesDir = appCtx.filesDir,
                            gameDirPath = ws.mirrorDir.absolutePath,
                            fixTexts = fixTexts,
                            fixEvents = fixEvents,
                            pruneMissing = pruneMissing,
                            deleteOrphans = deleteOrphans,
                            autoBackup = _ui.value.prefs.autoBackup,
                            remoteMusicIndex = ws.remoteMusicIndex,
                            backupKeyDir = gdir,
                            deletedOrphanNamesOut = orphansToDelete,
                        )
                        PrivilegedGameBridge.pushMirrorChanges(
                            backend = backend,
                            workspace = ws,
                            pushSoundpack = r.eventsFixed > 0 || r.soundsPruned > 0,
                            pushTexts = r.textsFixed > 0,
                            pushStagedMusic = false,
                            deleteRemoteMusicFiles = orphansToDelete,
                        )
                        r
                    } else {
                        LibraryManager.repairHealth(
                            appFilesDir = appCtx.filesDir,
                            gameDirPath = gdir,
                            fixTexts = fixTexts,
                            fixEvents = fixEvents,
                            pruneMissing = pruneMissing,
                            deleteOrphans = deleteOrphans,
                            autoBackup = _ui.value.prefs.autoBackup,
                        )
                    }
                }
                _ui.update { it.copy(healthAudit = res.auditAfter) }
                showBanner("Repair complete: ${res.textsFixed} titles, ${res.eventsFixed} events, ${res.soundsPruned} pruned, ${res.orphansDeleted} orphans cleaned.")
                refreshGameData()
            } catch (e: Exception) {
                showBanner("Repair failed: ${e.message}", isError = true)
            } finally {
                _ui.update { it.copy(busyAction = false) }
            }
        }
    }

    fun saveProfile(name: String) {
        val gdir = _ui.value.prefs.gameDir
        val mode = _ui.value.prefs.accessMode
        if (!_ui.value.gameDirValid) {
            showBanner("Select a valid Asphalt 8 folder first.", isError = true)
            return
        }
        viewModelScope.launch {
            try {
                val p = withContext(Dispatchers.IO) {
                    if (mode.isPrivileged) {
                        val backend = activeBackendOrNull(mode)
                            ?: throw IllegalStateException("${mode.label} service is not connected.")
                        val layout = PrivilegedGameBridge.resolveRemoteLayout(backend, gdir)
                            ?: throw IllegalStateException("Asphalt 8 folder is unreachable.")
                        val ws = PrivilegedGameBridge.pullMirror(backend, layout, mirrorDir())
                        LibraryManager.saveProfile(appCtx.filesDir, ws.mirrorDir.absolutePath, name, ws.remoteMusicIndex)
                    } else {
                        LibraryManager.saveProfile(appCtx.filesDir, gdir, name)
                    }
                }
                showBanner("Saved profile \"${p.name}\" (${p.trackCount} tracks).")
                refreshTools()
            } catch (e: Exception) {
                showBanner("Could not save profile: ${e.message}", isError = true)
            }
        }
    }

    fun applyProfile(profile: ModProfile) {
        val gdir = _ui.value.prefs.gameDir
        val mode = _ui.value.prefs.accessMode
        if (!_ui.value.gameDirValid) return
        viewModelScope.launch {
            _ui.update { it.copy(busyAction = true) }
            try {
                withContext(Dispatchers.IO) {
                    if (mode.isPrivileged) {
                        val backend = activeBackendOrNull(mode)
                            ?: throw IllegalStateException("${mode.label} service is not connected.")
                        val layout = PrivilegedGameBridge.resolveRemoteLayout(backend, gdir)
                            ?: throw IllegalStateException("Asphalt 8 folder is unreachable.")
                        val ws = PrivilegedGameBridge.pullMirror(backend, layout, mirrorDir())
                        LibraryManager.applyProfile(appCtx.filesDir, ws.mirrorDir.absolutePath, profile.id, _ui.value.prefs.autoBackup, backupKeyDir = gdir)
                        PrivilegedGameBridge.pushMirrorChanges(backend, ws, pushSoundpack = true, pushTexts = true, pushStagedMusic = false)
                    } else {
                        LibraryManager.applyProfile(appCtx.filesDir, gdir, profile.id, _ui.value.prefs.autoBackup)
                    }
                }
                showBanner("Applied profile \"${profile.name}\".")
                refreshGameData()
                refreshQueueConflicts()
            } catch (e: Exception) {
                showBanner("Could not apply profile: ${e.message}", isError = true)
            } finally {
                _ui.update { it.copy(busyAction = false) }
            }
        }
    }

    fun deleteProfile(profile: ModProfile) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { LibraryManager.deleteProfile(appCtx.filesDir, profile.id) }
                showBanner("Deleted profile \"${profile.name}\".")
                refreshTools()
            } catch (e: Exception) {
                showBanner("Could not delete profile: ${e.message}", isError = true)
            }
        }
    }

    fun exportSelectedQueueToZipUri(destUri: Uri, packName: String) {
        val tracks = _ui.value.queueTracks.filter { it.selected }.ifEmpty { _ui.value.queueTracks }
        if (tracks.isEmpty()) {
            showBanner("Add songs to the queue before exporting.", isError = true)
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(busyAction = true) }
            try {
                val count = withContext(Dispatchers.IO) {
                    val tmpZip = File(appCtx.cacheDir, "export_pack_${System.currentTimeMillis()}.zip")
                    try {
                        val n = LibraryManager.exportModPackZip(tmpZip, packName, tracks)
                        appCtx.contentResolver.openOutputStream(destUri)?.use { out ->
                            tmpZip.inputStream().use { inp -> inp.copyTo(out) }
                        } ?: throw IllegalStateException("Could not write destination zip.")
                        n
                    } finally {
                        tmpZip.delete()
                    }
                }
                _ui.update { it.copy(playlistDirty = false) }
                persistQueue()
                showBanner("Exported $count song(s) to Mod Pack .zip.")
            } catch (e: Exception) {
                showBanner("Export failed: ${e.message}", isError = true)
            } finally {
                _ui.update { it.copy(busyAction = false) }
            }
        }
    }

    /* ---------- Preferences & Updates ---------- */

    fun updatePrefs(transform: (AppPrefs) -> AppPrefs) {
        val next = transform(_ui.value.prefs)
        _ui.update { it.copy(prefs = next) }
        player.setLoop(next.loopPlayback)
        viewModelScope.launch(Dispatchers.IO) { prefsStore.savePrefs(next) }
    }

    fun checkForUpdates(silentPopup: Boolean) {
        viewModelScope.launch {
            _ui.update { it.copy(updateChecking = true, updateError = null) }
            val result = withContext(Dispatchers.IO) {
                UpdateChecker.checkForAndroidUpdate(BuildConfig.VERSION_NAME)
            }
            result.onSuccess { info ->
                _ui.update {
                    it.copy(
                        updateChecking = false,
                        updateInfo = info,
                        updateError = null,
                        showStartupUpdatePopup = if (silentPopup && info.updateAvailable) true else it.showStartupUpdatePopup,
                    )
                }
            }.onFailure { err ->
                _ui.update {
                    it.copy(
                        updateChecking = false,
                        updateError = if (silentPopup) null else (err.message ?: "Could not check updates."),
                    )
                }
            }
        }
    }

    fun dismissUpdatePopup() {
        _ui.update { it.copy(showStartupUpdatePopup = false) }
    }

    fun downloadUpdateApk() {
        val info = _ui.value.updateInfo ?: return
        if (info.downloadUrl.isBlank()) {
            showBanner("No direct APK asset attached to ${info.tagName}. Open GitHub releases to download.", isError = true)
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(downloadingUpdate = true, downloadProgress = 0f, showStartupUpdatePopup = false) }
            try {
                val apkFile = withContext(Dispatchers.IO) {
                    val out = File(appCtx.cacheDir, "A8_Custom_OST_Patcher-v${info.latestVersion}.apk")
                    UpdateChecker.downloadApk(info.downloadUrl, out, BuildConfig.VERSION_NAME) { prog ->
                        _ui.update { it.copy(downloadProgress = prog) }
                    }
                }
                _ui.update { it.copy(downloadingUpdate = false, readyApkFile = apkFile) }
            } catch (e: Exception) {
                _ui.update { it.copy(downloadingUpdate = false) }
                showBanner("Update download failed: ${e.message}", isError = true)
            }
        }
    }

    fun consumeReadyApkFile() {
        _ui.update { it.copy(readyApkFile = null) }
    }

    private fun persistQueue() {
        val st = _ui.value
        viewModelScope.launch(Dispatchers.IO) {
            prefsStore.saveQueue(st.queueTracks, st.playlistDirty)
        }
    }

    override fun onCleared() {
        player.release()
        privileged.release()
        super.onCleared()
    }
}
