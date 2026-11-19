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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.pythonnxd.a8ostpatcher.engine.AudioPipeline
import com.pythonnxd.a8ostpatcher.engine.InstalledTrack
import com.pythonnxd.a8ostpatcher.privileged.PrivilegedAccessManager
import com.pythonnxd.a8ostpatcher.state.AppTab
import com.pythonnxd.a8ostpatcher.state.MainViewModel
import com.pythonnxd.a8ostpatcher.ui.components.AmbientLiquidBackdrop
import com.pythonnxd.a8ostpatcher.ui.components.FloatingPillNavBar
import com.pythonnxd.a8ostpatcher.ui.components.LocalChromeHazeState
import com.pythonnxd.a8ostpatcher.ui.components.NowPlayingBar
import com.pythonnxd.a8ostpatcher.ui.components.liquidGlassChrome
import com.pythonnxd.a8ostpatcher.ui.screens.*
import com.pythonnxd.a8ostpatcher.ui.theme.A8PatcherTheme
import com.pythonnxd.a8ostpatcher.ui.theme.LocalAppColors
import com.pythonnxd.a8ostpatcher.ui.theme.LocalReduceMotion
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
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
                    openShizukuApp = { launchShizukuOrGuide() },
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

    private fun launchShizukuOrGuide() {
        val intent = vm.privileged.getShizukuLaunchIntent()
        if (intent != null) {
            try {
                startActivity(intent)
                return
            } catch (_: Exception) {}
        }
        openUrlSafely(PrivilegedAccessManager.SHIZUKU_SETUP_URL)
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

private fun routeOrder(route: String): Int = when (route) {
    AppTab.QUEUE.name -> 0
    AppTab.LIBRARY.name -> 1
    AppTab.TOOLS.name -> 2
    AppTab.SETTINGS.name -> 3
    "about" -> 4
    else -> 0
}

@Composable
private fun MainAppShell(
    vm: MainViewModel,
    openExternalUrl: (String) -> Unit,
    openShizukuApp: () -> Unit,
    installDownloadedApk: (File) -> Unit,
    requestAllFilesPermission: () -> Unit,
) {
    val ui by vm.ui.collectAsState()
    val nowPlaying by vm.player.state.collectAsState()
    val colors = LocalAppColors.current
    val reduceMotion = LocalReduceMotion.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val config = LocalConfiguration.current
    val isTablet = config.screenWidthDp >= 600
    val chromeHazeState = remember { HazeState() }

    var hasAllFilesAccess by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else true
        )
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    hasAllFilesAccess = Environment.isExternalStorageManager()
                }
                vm.refreshPrivilegedAccess()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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

    CompositionLocalProvider(LocalChromeHazeState provides chromeHazeState) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Haze source layer: ambient Liquid Glass backdrop + full edge-to-edge screen content
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .haze(state = chromeHazeState)
            ) {
                AmbientLiquidBackdrop(modifier = Modifier.fillMaxSize())

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.statusBars)
                ) {
                    val routeKey = if (ui.tab == AppTab.SETTINGS && ui.showAboutScreen) "about" else ui.tab.name
                    AnimatedContent(
                        targetState = routeKey,
                        transitionSpec = {
                            if (reduceMotion) {
                                EnterTransition.None togetherWith ExitTransition.None
                            } else if (targetState == "about" && initialState == AppTab.SETTINGS.name) {
                                (slideInHorizontally(spring(0.86f, 420f)) { it / 3 } + fadeIn(tween(200))) togetherWith
                                    (slideOutHorizontally(spring(0.86f, 420f)) { -it / 6 } + fadeOut(tween(150)))
                            } else if (initialState == "about" && targetState == AppTab.SETTINGS.name) {
                                (slideInHorizontally(spring(0.86f, 420f)) { -it / 6 } + fadeIn(tween(200))) togetherWith
                                    (slideOutHorizontally(spring(0.86f, 420f)) { it / 3 } + fadeOut(tween(150)))
                            } else {
                                val dir = if (routeOrder(targetState) >= routeOrder(initialState)) 1 else -1
                                (slideInHorizontally(spring(0.84f, 460f)) { dir * (it / 16) } +
                                    fadeIn(tween(190)) +
                                    scaleIn(tween(210), initialScale = 0.985f)) togetherWith
                                    (slideOutHorizontally(tween(150)) { -dir * (it / 24) } +
                                        fadeOut(tween(120)) +
                                        scaleOut(tween(130), targetScale = 0.99f))
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
                                onOpenShizukuApp = openShizukuApp,
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
            }

            // Floating Frosted Liquid Glass Status Banner (drops smoothly from top with real GPU blur)
            var lastBanner by remember { mutableStateOf(ui.banner) }
            LaunchedEffect(ui.banner) {
                if (ui.banner != null) lastBanner = ui.banner
            }
            AnimatedVisibility(
                visible = ui.banner != null,
                enter = if (reduceMotion) EnterTransition.None
                else slideInVertically(spring(0.74f, 440f)) { -it } + fadeIn(tween(180)) + scaleIn(tween(180), initialScale = 0.96f),
                exit = if (reduceMotion) ExitTransition.None
                else slideOutVertically(tween(160)) { -it } + fadeOut(tween(140)),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = if (isTablet) 24.dp else 16.dp, vertical = 10.dp),
            ) {
                val banner = lastBanner
                if (banner != null) {
                    val accent = if (banner.isError) colors.danger else colors.ok
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 560.dp)
                            .liquidGlassChrome(
                                shape = RoundedCornerShape(18.dp),
                                blurRadius = 24.dp,
                                shadowElevation = 14.dp,
                                borderAccent = accent,
                                tintOverride = accent.copy(alpha = if (colors.isLight) 0.16f else 0.22f),
                            )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                text = banner.text,
                                color = if (banner.isError) colors.danger else colors.ok,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = "Dismiss",
                                tint = colors.muted,
                                modifier = Modifier
                                    .size(22.dp)
                                    .clickable { vm.dismissBanner() },
                            )
                        }
                    }
                }
            }

            // Bottom floating overlay: Frosted Now Playing Bar + Resized Draggable Liquid Glass Pill Nav Bar
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(
                        start = if (isTablet) 24.dp else 20.dp,
                        end = if (isTablet) 24.dp else 20.dp,
                        top = 6.dp,
                        bottom = 12.dp,
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val showNowPlaying = (ui.tab == AppTab.QUEUE || ui.tab == AppTab.LIBRARY) && (nowPlaying.isLocked || nowPlaying.isPreparing)
                AnimatedVisibility(
                    visible = showNowPlaying,
                    enter = if (reduceMotion) EnterTransition.None
                    else slideInVertically(spring(0.76f, 420f)) { it / 2 } + fadeIn(tween(180)) + scaleIn(tween(200), initialScale = 0.95f),
                    exit = if (reduceMotion) ExitTransition.None
                    else slideOutVertically(tween(150)) { it / 2 } + fadeOut(tween(130)),
                ) {
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
    }

    UpdatePopupDialog(ui = ui, vm = vm)
}
