package com.pythonnxd.a8ostpatcher

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.pythonnxd.a8ostpatcher.engine.AudioPipeline
import com.pythonnxd.a8ostpatcher.engine.InstalledTrack
import com.pythonnxd.a8ostpatcher.state.AppTab
import com.pythonnxd.a8ostpatcher.state.MainViewModel
import com.pythonnxd.a8ostpatcher.ui.components.FloatingPillNavBar
import com.pythonnxd.a8ostpatcher.ui.components.NowPlayingBar
import com.pythonnxd.a8ostpatcher.ui.screens.*
import com.pythonnxd.a8ostpatcher.ui.theme.A8PatcherTheme
import com.pythonnxd.a8ostpatcher.ui.theme.LocalAppColors
import com.pythonnxd.a8ostpatcher.ui.theme.LocalReduceMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val ui by vm.ui.collectAsState()
            val nowPlaying by vm.player.state.collectAsState()
            val isLight = ui.prefs.themeMode.isLight

            LaunchedEffect(isLight) {
                enableEdgeToEdge(
                    statusBarStyle = if (isLight) {
                        SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    },
                    navigationBarStyle = if (isLight) {
                        SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    },
                )
            }

            A8PatcherTheme(
                themeMode = ui.prefs.themeMode,
                reduceMotion = ui.prefs.reduceMotion,
            ) {
                MainAppShell(
                    vm = vm,
                    openExternalUrl = { url -> openUrlSafely(url) },
                    installDownloadedApk = { apk -> launchApkInstaller(apk) },
                    requestAllFilesPermission = { openAllFilesAccessSettings() },
                )
            }
        }
    }

    private fun openUrlSafely(url: String) {
        try {
            val uri = Uri.parse(url)
            if (uri.scheme == "https" || uri.scheme == "http") {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            }
        } catch (_: Exception) {}
    }

    private fun openAllFilesAccessSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }
    }

    private fun launchApkInstaller(apkFile: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.fileprovider", apkFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            vm.showBanner("Could not launch APK installer: ${e.message}", isError = true)
        }
    }
}

@Composable
private fun MainAppShell(
    vm: MainViewModel,
    openExternalUrl: (String) -> Unit,
    installDownloadedApk: (File) -> Unit,
    requestAllFilesPermission: () -> Unit,
) {
    val ui by vm.ui.collectAsState()
    val nowPlaying by vm.player.state.collectAsState()
    val colors = LocalAppColors.current
    val reduceMotion = LocalReduceMotion.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val config = LocalConfiguration.current
    val isTablet = config.screenWidthDp >= 600

    var hasAllFilesAccess by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else true
        )
    }

    LaunchedEffect(ui.tab) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            hasAllFilesAccess = Environment.isExternalStorageManager()
        }
    }

    LaunchedEffect(ui.readyApkFile) {
        ui.readyApkFile?.let { apk ->
            vm.consumeReadyApkFile()
            installDownloadedApk(apk)
        }
    }

    var pendingQueueOutroUid by remember { mutableStateOf<String?>(null) }
    var pendingLibraryOutroTrack by remember { mutableStateOf<InstalledTrack?>(null) }

    val pickSongsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.importAudioUris(uris)
    }

    val pickMusicFolderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.importFolderTreeUri(uri)
    }

    val pickGameFolderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val resolved = vm.resolveDirectPathFromTreeUri(uri)
            if (resolved != null) {
                vm.setGameDirectory(resolved.absolutePath)
            } else {
                vm.showBanner("Choose a folder on internal storage (primary storage) for Asphalt 8 files.", isError = true)
            }
        }
    }

    val pickCustomOutroLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val qUid = pendingQueueOutroUid
        val lTrack = pendingLibraryOutroTrack
        pendingQueueOutroUid = null
        pendingLibraryOutroTrack = null

        if (qUid != null) {
            vm.attachCustomOutroUriToQueueTrack(qUid, uri)
        } else if (lTrack != null) {
            scope.launch {
                val copied = withContext(Dispatchers.IO) {
                    AudioPipeline.copyUriToImportCache(context, uri, context.cacheDir)
                }
                if (copied != null) {
                    vm.saveLibraryTrackOutro(lTrack, copied, null, null, 0.0, 0.0)
                }
            }
        }
    }

    val exportZipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) {
            vm.exportSelectedQueueToZipUri(uri, "A8 Custom OST Pack")
        }
    }

    BackHandler(enabled = ui.showAboutScreen) {
        vm.closeAboutScreen()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.ground)
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Optional dismissible status banner
            ui.banner?.let { banner ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (banner.isError) colors.danger.copy(alpha = 0.16f) else colors.ok.copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, if (banner.isError) colors.danger.copy(alpha = 0.5f) else colors.ok.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = if (isTablet) 20.dp else 14.dp, vertical = 6.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = banner.text,
                            color = if (banner.isError) colors.danger else colors.ok,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = "Dismiss",
                            tint = colors.muted,
                            modifier = Modifier
                                .size(18.dp)
                                .clickable { vm.dismissBanner() },
                        )
                    }
                }
            }

            // Peer tabs use clean crossfade; Settings <-> About uses directional push/pop (instant when Reduce motion is ON)
            val routeKey = if (ui.tab == AppTab.SETTINGS && ui.showAboutScreen) "about" else ui.tab.name
            AnimatedContent(
                targetState = routeKey,
                transitionSpec = {
                    if (reduceMotion) {
                        EnterTransition.None togetherWith ExitTransition.None
                    } else if (targetState == "about" && initialState == AppTab.SETTINGS.name) {
                        (slideInHorizontally(tween(240)) { it / 3 } + fadeIn(tween(220))) togetherWith
                            (slideOutHorizontally(tween(240)) { -it / 6 } + fadeOut(tween(180)))
                    } else if (initialState == "about" && targetState == AppTab.SETTINGS.name) {
                        (slideInHorizontally(tween(240)) { -it / 6 } + fadeIn(tween(220))) togetherWith
                            (slideOutHorizontally(tween(240)) { it / 3 } + fadeOut(tween(180)))
                    } else {
                        fadeIn(tween(160)) togetherWith fadeOut(tween(120))
                    }
                },
                label = "mainContent",
                modifier = Modifier.weight(1f),
            ) { route ->
                when (route) {
                    AppTab.QUEUE.name -> QueueScreen(
                        ui = ui,
                        nowPlaying = nowPlaying,
                        vm = vm,
                        isTablet = isTablet,
                        onAddSongs = { pickSongsLauncher.launch(arrayOf("audio/*", "application/zip", "application/octet-stream")) },
                        onAddFolder = { pickMusicFolderLauncher.launch(null) },
                        onExportZip = { exportZipLauncher.launch("A8_Custom_OST_Pack.zip") },
                        onPickCustomOutroForQueue = { uid ->
                            pendingQueueOutroUid = uid
                            pendingLibraryOutroTrack = null
                            pickCustomOutroLauncher.launch(arrayOf("audio/*", "application/octet-stream"))
                        },
                    )
                    AppTab.LIBRARY.name -> LibraryScreen(
                        ui = ui,
                        nowPlaying = nowPlaying,
                        vm = vm,
                        isTablet = isTablet,
                        onChooseGameFolder = { pickGameFolderLauncher.launch(null) },
                        onPickCustomOutroForLibrary = { track ->
                            pendingQueueOutroUid = null
                            pendingLibraryOutroTrack = track
                            pickCustomOutroLauncher.launch(arrayOf("audio/*", "application/octet-stream"))
                        },
                    )
                    AppTab.TOOLS.name -> ToolsScreen(
                        ui = ui,
                        vm = vm,
                        isTablet = isTablet,
                        onChooseGameFolder = { pickGameFolderLauncher.launch(null) },
                    )
                    AppTab.SETTINGS.name -> SettingsScreen(
                        ui = ui,
                        vm = vm,
                        isTablet = isTablet,
                        hasAllFilesAccess = hasAllFilesAccess,
                        onRequestAllFilesAccess = requestAllFilesPermission,
                        onChooseGameFolder = { pickGameFolderLauncher.launch(null) },
                    )
                    "about" -> AboutScreen(
                        ui = ui,
                        vm = vm,
                        isTablet = isTablet,
                        onOpenUrl = openExternalUrl,
                    )
                }
            }
        }

        // Bottom overlay: Locked Now Playing bar + Floating Glass Pill Navigation Bar
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (ui.tab == AppTab.QUEUE || ui.tab == AppTab.LIBRARY) {
                NowPlayingBar(
                    state = nowPlaying,
                    onTogglePlayPause = { vm.player.toggleCurrentPlayPause() },
                    onSeek = { vm.player.seekToFraction(it) },
                    onToggleLoop = { vm.updatePrefs { it.copy(loopPlayback = !it.loopPlayback) } },
                    modifier = Modifier.widthIn(max = 640.dp),
                )
            }

            FloatingPillNavBar(
                selectedTab = ui.tab,
                onSelect = { vm.selectTab(it) },
                isTablet = isTablet,
            )
        }
    }

    UpdatePopupDialog(ui = ui, vm = vm)
}
