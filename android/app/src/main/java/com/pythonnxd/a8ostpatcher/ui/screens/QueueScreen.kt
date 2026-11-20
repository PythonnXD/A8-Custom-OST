package com.pythonnxd.a8ostpatcher.ui.screens

import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pythonnxd.a8ostpatcher.R
import com.pythonnxd.a8ostpatcher.engine.*
import com.pythonnxd.a8ostpatcher.state.MainUiState
import com.pythonnxd.a8ostpatcher.state.MainViewModel
import com.pythonnxd.a8ostpatcher.state.NowPlayingState
import com.pythonnxd.a8ostpatcher.ui.components.*
import com.pythonnxd.a8ostpatcher.ui.theme.LocalAppColors
import com.pythonnxd.a8ostpatcher.ui.theme.LocalReduceMotion
import java.io.File
import kotlin.math.max
import kotlin.math.min

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(
    ui: MainUiState,
    nowPlaying: NowPlayingState,
    vm: MainViewModel,
    isTablet: Boolean,
    onAddSongs: () -> Unit,
    onAddFolder: () -> Unit,
    onExportZip: () -> Unit,
    onPickCustomOutroForQueue: (String) -> Unit,
) {
    val colors = LocalAppColors.current
    val reduceMotion = LocalReduceMotion.current
    val view = LocalView.current

    val filtered = remember(ui.queueTracks, ui.queueCategoryFilter, ui.queueSearch) {
        val q = ui.queueSearch.trim().lowercase()
        ui.queueTracks.filter { t ->
            (ui.queueCategoryFilter == null || t.category == ui.queueCategoryFilter) &&
                (q.isEmpty() || t.title.lowercase().contains(q) || t.artist.lowercase().contains(q) || t.srcName.lowercase().contains(q))
        }
    }
    val selectedCount = ui.queueTracks.count { it.selected }
    val selectionMode = selectedCount > 0
    val focusedTrack = ui.queueTracks.firstOrNull { it.uid == ui.focusedQueueUid } ?: filtered.firstOrNull()

    var editingTrackUid by remember { mutableStateOf<String?>(null) }
    var outroEditingTrackUid by remember { mutableStateOf<String?>(null) }
    var tabletInspectorTab by remember { mutableIntStateOf(0) } // 0 = Song details, 1 = Outro
    var showSelectionOptions by remember { mutableStateOf(false) }
    var showBatchCategoryRow by remember { mutableStateOf(false) }
    var showBatchArtistDialog by remember { mutableStateOf(false) }
    var batchArtistInput by remember { mutableStateOf("") }
    var batchStation by remember { mutableStateOf(StationCategory.ROCK) }

    LaunchedEffect(selectedCount) {
        if (selectedCount == 0) {
            showSelectionOptions = false
            showBatchCategoryRow = false
        }
    }

    BackHandler(enabled = selectionMode) {
        vm.clearQueueSelection()
    }

    val categoryItems = remember(ui.queueTracks) {
        buildList {
            add(CategoryFilterItem("all", "All ${ui.queueTracks.size}"))
            StationCategory.entries.forEach { cat ->
                val count = ui.queueTracks.count { it.category == cat }
                add(CategoryFilterItem(cat.name, "${cat.displayName} $count"))
            }
        }
    }
    val selectedCategoryIdx = remember(ui.queueCategoryFilter) {
        if (ui.queueCategoryFilter == null) 0 else StationCategory.entries.indexOf(ui.queueCategoryFilter) + 1
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = if (isTablet) 24.dp else 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Header row: Logo + Overwrite/Skip + Install CTA
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Image(
                painter = painterResource(if (colors.isLight) R.drawable.a8_logo_light else R.drawable.a8_logo_dark),
                contentDescription = "Asphalt 8: Airborne",
                modifier = Modifier.height(29.dp),
                contentScale = ContentScale.Fit,
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (isTablet) {
                    SlidingSegmentedControl(
                        items = listOf(SegmentItem("Overwrite"), SegmentItem("Skip")),
                        selectedIndex = if (ui.prefs.conflictPolicy == ConflictPolicy.SKIP) 1 else 0,
                        onSelect = { idx ->
                            val pol = if (idx == 1) ConflictPolicy.SKIP else ConflictPolicy.OVERWRITE
                            vm.updatePrefs { it.copy(conflictPolicy = pol) }
                        },
                        modifier = Modifier.width(196.dp),
                    )
                }

                val installInteraction = remember { MutableInteractionSource() }
                val installTargetCount = if (selectedCount > 0) selectedCount else ui.queueTracks.size
                Button(
                    onClick = { vm.startInstallSelected() },
                    enabled = installTargetCount > 0 && ui.gameDirValid && !ui.installProgress.active,
                    interactionSource = installInteraction,
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.capsuleBg,
                        contentColor = colors.capsuleInk,
                    ),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
                    modifier = Modifier
                        .defaultMinSize(minHeight = 42.dp)
                        .interactivePressScale(installInteraction, 0.94f),
                ) {
                    AnimatedContent(
                        targetState = if (installTargetCount > 0) "Install $installTargetCount" else "Install",
                        transitionSpec = {
                            if (reduceMotion) EnterTransition.None togetherWith ExitTransition.None
                            else (slideInVertically(spring(0.75f, 480f)) { it / 2 } + fadeIn(tween(150))) togetherWith
                                (slideOutVertically(tween(110)) { -it / 2 } + fadeOut(tween(100)))
                        },
                        label = "installCtaLabel",
                    ) { label ->
                        Text(
                            text = label,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }

        // Frosted Quick Actions Bar with dynamic off-screen scroll edge feather
        val actionsScroll = rememberScrollState()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScrollEdgeFade(actionsScroll, edgeWidth = 28.dp)
                .horizontalScroll(actionsScroll),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ActionPill(Icons.Rounded.Add, "Songs", onClick = onAddSongs)
            ActionPill(Icons.Rounded.FolderOpen, "Folder", onClick = onAddFolder)
            if (ui.queueTracks.isNotEmpty()) {
                ActionPill(Icons.Rounded.Archive, "Export .zip", onClick = onExportZip)
                ActionPill(Icons.Rounded.DeleteSweep, "Clear", isDanger = true, onClick = { vm.requestClearQueue() })
            }
            if (!isTablet) {
                val conflictInteraction = remember { MutableInteractionSource() }
                LiquidGlassCard(
                    shape = RoundedCornerShape(999.dp),
                    modifier = Modifier
                        .defaultMinSize(minHeight = 40.dp)
                        .interactivePressScale(conflictInteraction, 0.94f)
                        .clickable(interactionSource = conflictInteraction, indication = null) {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            val next = if (ui.prefs.conflictPolicy == ConflictPolicy.OVERWRITE) ConflictPolicy.SKIP else ConflictPolicy.OVERWRITE
                            vm.updatePrefs { it.copy(conflictPolicy = next) }
                        },
                ) {
                    Box(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "On conflict: ${ui.prefs.conflictPolicy.label}",
                            color = colors.muted,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }

        // Animated Frosted Category Selection Row
        AnimatedCategoryFilterRow(
            items = categoryItems,
            selectedIndex = selectedCategoryIdx,
            onSelect = { idx ->
                if (idx == 0) vm.setQueueFilter(null)
                else vm.setQueueFilter(StationCategory.entries[idx - 1])
            },
        )

        if (ui.queueTracks.isNotEmpty()) {
            OutlinedTextField(
                value = ui.queueSearch,
                onValueChange = { vm.setQueueSearch(it) },
                placeholder = { Text("Search queued songs...", fontSize = 14.5.sp, color = colors.faint) },
                leadingIcon = { Icon(Icons.Rounded.Search, null, tint = colors.faint, modifier = Modifier.size(20.dp)) },
                trailingIcon = if (ui.queueSearch.isNotEmpty()) {
                    {
                        IconButton(onClick = { vm.setQueueSearch("") }) {
                            Icon(Icons.Rounded.Close, "Clear search", tint = colors.muted, modifier = Modifier.size(18.dp))
                        }
                    }
                } else null,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = colors.glassCardBg,
                    unfocusedContainerColor = colors.glassCardBg,
                    focusedBorderColor = colors.accent,
                    unfocusedBorderColor = colors.glassSpecularMid,
                    focusedTextColor = colors.text,
                    unfocusedTextColor = colors.text,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 52.dp),
            )
        }

        // Compact Contextual Frosted Selection Bar (appears on hold-to-select, expands options via three-dots button)
        AnimatedVisibility(
            visible = selectionMode,
            enter = if (reduceMotion) EnterTransition.None else fadeIn(tween(170)) + expandVertically(spring(0.78f, 440f)),
            exit = if (reduceMotion) ExitTransition.None else fadeOut(tween(120)) + shrinkVertically(tween(140)),
        ) {
            LiquidGlassCard(
                shape = RoundedCornerShape(16.dp),
                elevated = true,
                accentBorder = colors.accent,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            IconButton(
                                onClick = { vm.clearQueueSelection() },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(Icons.Rounded.Close, "Exit selection", tint = colors.muted, modifier = Modifier.size(19.dp))
                            }
                            Surface(shape = RoundedCornerShape(999.dp), color = colors.accentSoft) {
                                Text(
                                    "$selectedCount selected",
                                    color = colors.accentInk,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                )
                            }
                            val allFilteredSelected = filtered.isNotEmpty() && filtered.all { it.selected }
                            Text(
                                if (allFilteredSelected) "Deselect all" else "Select all",
                                color = colors.muted,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                        vm.toggleSelectAllQueue(filtered.map { it.uid }.toSet())
                                    }
                                    .padding(horizontal = 6.dp, vertical = 4.dp),
                            )
                        }

                        // Three-dots selection options button
                        val optsInteraction = remember { MutableInteractionSource() }
                        Surface(
                            shape = CircleShape,
                            color = if (showSelectionOptions) colors.accentSoft else colors.glassCardRaised,
                            border = BorderStroke(
                                1.dp,
                                if (showSelectionOptions) colors.accent.copy(alpha = 0.65f) else colors.glassSpecularMid,
                            ),
                            modifier = Modifier
                                .size(36.dp)
                                .interactivePressScale(optsInteraction, 0.90f)
                                .clickable(interactionSource = optsInteraction, indication = null) {
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    showSelectionOptions = !showSelectionOptions
                                    if (!showSelectionOptions) showBatchCategoryRow = false
                                },
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Rounded.MoreVert,
                                    contentDescription = "Selection options",
                                    tint = if (showSelectionOptions) colors.accentInk else colors.text,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }

                    // Expandable row of selection actions: Set Artist, Set Category, Remove
                    AnimatedVisibility(
                        visible = showSelectionOptions,
                        enter = if (reduceMotion) EnterTransition.None else fadeIn(tween(150)) + expandVertically(spring(0.80f, 460f)),
                        exit = if (reduceMotion) ExitTransition.None else fadeOut(tween(110)) + shrinkVertically(tween(130)),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val optionsScroll = rememberScrollState()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScrollEdgeFade(optionsScroll, edgeWidth = 22.dp)
                                    .horizontalScroll(optionsScroll),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                ActionPill(Icons.Rounded.Person, "Set artist") {
                                    batchArtistInput = ""
                                    showBatchArtistDialog = true
                                }
                                ActionPill(
                                    Icons.Rounded.Radio,
                                    "Set category",
                                ) {
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    showBatchCategoryRow = !showBatchCategoryRow
                                }
                                ActionPill(Icons.Rounded.DeleteOutline, "Remove", isDanger = true) {
                                    vm.removeSelectedQueueTracks()
                                }
                            }

                            AnimatedVisibility(
                                visible = showBatchCategoryRow,
                                enter = if (reduceMotion) EnterTransition.None else fadeIn(tween(140)) + expandVertically(spring(0.82f, 480f)),
                                exit = if (reduceMotion) ExitTransition.None else fadeOut(tween(100)) + shrinkVertically(tween(120)),
                            ) {
                                val catPickScroll = rememberScrollState()
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScrollEdgeFade(catPickScroll, edgeWidth = 22.dp)
                                        .horizontalScroll(catPickScroll),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    StationCategory.entries.forEach { cat ->
                                        val active = batchStation == cat
                                        val stInteraction = remember { MutableInteractionSource() }
                                        val bg by animateColorAsState(
                                            if (active) colors.accentSoft else colors.glassCardRaised,
                                            if (reduceMotion) snap() else tween(160),
                                            label = "batchStBg${cat.name}",
                                        )
                                        Surface(
                                            shape = RoundedCornerShape(999.dp),
                                            color = bg,
                                            border = BorderStroke(1.dp, if (active) colors.accent.copy(alpha = 0.65f) else colors.border),
                                            modifier = Modifier
                                                .defaultMinSize(minHeight = 36.dp)
                                                .interactivePressScale(stInteraction, 0.93f)
                                                .clickable(interactionSource = stInteraction, indication = null) {
                                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                                    batchStation = cat
                                                    vm.batchUpdateQueueStation(cat)
                                                    showBatchCategoryRow = false
                                                },
                                        ) {
                                            Box(Modifier.padding(horizontal = 13.dp, vertical = 7.dp), contentAlignment = Alignment.Center) {
                                                Text(
                                                    cat.displayName,
                                                    color = if (active) colors.accentInk else colors.text,
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Main content: Empty state OR Phone list / Tablet split pane
        if (ui.queueTracks.isEmpty()) {
            EmptyWorkspaceCard(
                icon = Icons.Rounded.LibraryMusic,
                title = "Your import queue is empty",
                subtitle = "Add songs, a music folder (with or without Rock/Electro/Dubstep/Menu subfolders), or a .zip Mod Pack.",
                primaryLabel = "Add songs",
                onPrimary = onAddSongs,
                secondaryLabel = "Add folder",
                onSecondary = onAddFolder,
                modifier = Modifier.weight(1f),
            )
        } else if (isTablet) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                LazyColumn(
                    modifier = Modifier.weight(1.05f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 172.dp),
                ) {
                    items(filtered, key = { it.uid }) { track ->
                        Box(
                            modifier = if (reduceMotion) Modifier else Modifier.animateItem(
                                fadeInSpec = tween(180),
                                placementSpec = spring(dampingRatio = 0.80f, stiffness = 420f),
                                fadeOutSpec = tween(140),
                            )
                        ) {
                            QueueTrackRowCard(
                                track = track,
                                selectionMode = selectionMode,
                                isFocused = focusedTrack?.uid == track.uid,
                                isPlayingMain = nowPlaying.isPlaying && nowPlaying.uid == track.uid && !nowPlaying.isOutro,
                                isPlayingOutro = nowPlaying.isPlaying && nowPlaying.uid == track.uid && nowPlaying.isOutro,
                                showInlineEditButton = false,
                                onSelectToggle = { vm.toggleQueueSelection(track.uid) },
                                onRowClick = { vm.focusQueueTrack(track.uid) },
                                onPlayMain = {
                                    vm.player.togglePlay(
                                        uid = track.uid,
                                        title = track.title,
                                        artist = track.artist,
                                        category = track.category,
                                        srcFile = File(track.srcPath),
                                        isOutro = false,
                                        onError = { vm.showBanner(it, isError = true) },
                                    )
                                },
                                onPlayOutro = {
                                    val src = File(track.outroSrcPath ?: track.srcPath)
                                    vm.player.togglePlay(
                                        uid = track.uid,
                                        title = track.title,
                                        artist = track.artist,
                                        category = track.category,
                                        srcFile = src,
                                        isOutro = true,
                                        startSec = track.outroStartSec,
                                        endSec = track.outroEndSec,
                                        fadeInSec = if (track.outroFadeInEnabled) track.outroFadeIn else 0.0,
                                        fadeOutSec = if (track.outroFadeOutEnabled) track.outroFadeOut else 0.0,
                                        onError = { vm.showBanner(it, isError = true) },
                                    )
                                },
                                onEditClick = { vm.focusQueueTrack(track.uid); tabletInspectorTab = 0 },
                                onOutroClick = {
                                    if (ui.prefs.outroEditorEnabled) {
                                        vm.focusQueueTrack(track.uid)
                                        tabletInspectorTab = 1
                                    } else {
                                        onPickCustomOutroForQueue(track.uid)
                                    }
                                },
                            )
                        }
                    }
                }

                // Right Tablet Frosted Liquid Glass Inspector Pane
                LiquidGlassCard(
                    shape = RoundedCornerShape(22.dp),
                    modifier = Modifier
                        .weight(0.95f)
                        .fillMaxHeight()
                        .padding(bottom = 166.dp),
                ) {
                    if (focusedTrack == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Select a song to edit details or outro", color = colors.muted, fontSize = 15.sp)
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            SlidingSegmentedControl(
                                items = listOf(
                                    SegmentItem("Song details", Icons.Rounded.Edit),
                                    SegmentItem("Outro", Icons.Rounded.GraphicEq),
                                ),
                                selectedIndex = tabletInspectorTab,
                                onSelect = { tabletInspectorTab = it },
                                modifier = Modifier.fillMaxWidth(),
                            )

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                if (tabletInspectorTab == 0) {
                                    SingleTrackDetailsForm(
                                        track = focusedTrack,
                                        onSave = { title, artist, cat ->
                                            vm.updateQueueTrack(focusedTrack.uid, title, artist, cat)
                                            vm.showBanner("Saved song details.")
                                        },
                                        onRemove = { vm.removeQueueTrack(focusedTrack.uid) },
                                    )
                                } else {
                                    QueueOutroEditorBody(
                                        track = focusedTrack,
                                        nowPlaying = nowPlaying,
                                        outroEditorEnabled = ui.prefs.outroEditorEnabled,
                                        onPickCustomOutro = { onPickCustomOutroForQueue(focusedTrack.uid) },
                                        onPreview = { s, e, fi, fo ->
                                            vm.player.togglePlay(
                                                uid = focusedTrack.uid,
                                                title = focusedTrack.title,
                                                artist = focusedTrack.artist,
                                                category = focusedTrack.category,
                                                srcFile = File(focusedTrack.srcPath),
                                                isOutro = true,
                                                startSec = s,
                                                endSec = e,
                                                fadeInSec = fi,
                                                fadeOutSec = fo,
                                                onError = { vm.showBanner(it, isError = true) },
                                            )
                                        },
                                        onPreviewCustom = { customFile ->
                                            vm.player.togglePlay(
                                                uid = focusedTrack.uid,
                                                title = focusedTrack.title,
                                                artist = focusedTrack.artist,
                                                category = focusedTrack.category,
                                                srcFile = customFile,
                                                isOutro = true,
                                                onError = { vm.showBanner(it, isError = true) },
                                            )
                                        },
                                        onStopPreview = { vm.player.pause() },
                                        onSaveTrim = { s, e, fi, fo, fiOn, foOn ->
                                            vm.saveQueueTrackOutro(focusedTrack.uid, null, s, e, fi, fo, fiOn, foOn)
                                        },
                                        onRemoveOutro = { vm.removeQueueTrackOutro(focusedTrack.uid) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // Phone list with 1:1 swipe quick actions + animated item placement
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 186.dp),
            ) {
                items(filtered, key = { it.uid }) { track ->
                    Box(
                        modifier = if (reduceMotion) Modifier else Modifier.animateItem(
                            fadeInSpec = tween(180),
                            placementSpec = spring(dampingRatio = 0.80f, stiffness = 420f),
                            fadeOutSpec = tween(140),
                        )
                    ) {
                        SwipeableTrackCard(
                            enabled = !selectionMode,
                            onOutroClick = {
                                if (ui.prefs.outroEditorEnabled) outroEditingTrackUid = track.uid
                                else onPickCustomOutroForQueue(track.uid)
                            },
                            onRemoveClick = { vm.removeQueueTrack(track.uid) },
                        ) {
                            QueueTrackRowCard(
                                track = track,
                                selectionMode = selectionMode,
                                isFocused = focusedTrack?.uid == track.uid,
                                isPlayingMain = nowPlaying.isPlaying && nowPlaying.uid == track.uid && !nowPlaying.isOutro,
                                isPlayingOutro = nowPlaying.isPlaying && nowPlaying.uid == track.uid && nowPlaying.isOutro,
                                showInlineEditButton = true,
                                onSelectToggle = { vm.toggleQueueSelection(track.uid) },
                                onRowClick = { vm.focusQueueTrack(track.uid) },
                                onPlayMain = {
                                    vm.player.togglePlay(
                                        uid = track.uid,
                                        title = track.title,
                                        artist = track.artist,
                                        category = track.category,
                                        srcFile = File(track.srcPath),
                                        isOutro = false,
                                        onError = { vm.showBanner(it, isError = true) },
                                    )
                                },
                                onPlayOutro = {
                                    val src = File(track.outroSrcPath ?: track.srcPath)
                                    vm.player.togglePlay(
                                        uid = track.uid,
                                        title = track.title,
                                        artist = track.artist,
                                        category = track.category,
                                        srcFile = src,
                                        isOutro = true,
                                        startSec = track.outroStartSec,
                                        endSec = track.outroEndSec,
                                        fadeInSec = if (track.outroFadeInEnabled) track.outroFadeIn else 0.0,
                                        fadeOutSec = if (track.outroFadeOutEnabled) track.outroFadeOut else 0.0,
                                        onError = { vm.showBanner(it, isError = true) },
                                    )
                                },
                                onEditClick = { editingTrackUid = track.uid },
                                onOutroClick = {
                                    if (ui.prefs.outroEditorEnabled) outroEditingTrackUid = track.uid
                                    else onPickCustomOutroForQueue(track.uid)
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    // Phone draggable GPU Frosted Liquid Glass Edit Song Details Sheet
    val editTrack = ui.queueTracks.firstOrNull { it.uid == editingTrackUid }
    if (editTrack != null && !isTablet) {
        LiquidGlassBottomSheet(
            onDismissRequest = { editingTrackUid = null },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp),
            ) {
                Text("Edit song details", color = colors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(14.dp))
                SingleTrackDetailsForm(
                    track = editTrack,
                    onSave = { t, a, c ->
                        vm.updateQueueTrack(editTrack.uid, t, a, c)
                        editingTrackUid = null
                    },
                    onRemove = {
                        vm.removeQueueTrack(editTrack.uid)
                        editingTrackUid = null
                    },
                )
            }
        }
    }

    // Phone draggable GPU Frosted Liquid Glass Outro Editor Sheet (blurs live app screen behind sheet)
    val outroTrack = ui.queueTracks.firstOrNull { it.uid == outroEditingTrackUid }
    if (outroTrack != null && !isTablet) {
        LiquidGlassBottomSheet(
            onDismissRequest = { outroEditingTrackUid = null },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp),
            ) {
                Text("Outro editor", color = colors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("\"${outroTrack.title}\" - ${outroTrack.artist}", color = colors.muted, fontSize = 14.sp)
                Spacer(Modifier.height(14.dp))
                QueueOutroEditorBody(
                    track = outroTrack,
                    nowPlaying = nowPlaying,
                    outroEditorEnabled = ui.prefs.outroEditorEnabled,
                    onPickCustomOutro = {
                        outroEditingTrackUid = null
                        onPickCustomOutroForQueue(outroTrack.uid)
                    },
                    onPreview = { s, e, fi, fo ->
                        vm.player.togglePlay(
                            uid = outroTrack.uid,
                            title = outroTrack.title,
                            artist = outroTrack.artist,
                            category = outroTrack.category,
                            srcFile = File(outroTrack.srcPath),
                            isOutro = true,
                            startSec = s,
                            endSec = e,
                            fadeInSec = fi,
                            fadeOutSec = fo,
                            onError = { vm.showBanner(it, isError = true) },
                        )
                    },
                    onPreviewCustom = { customFile ->
                        vm.player.togglePlay(
                            uid = outroTrack.uid,
                            title = outroTrack.title,
                            artist = outroTrack.artist,
                            category = outroTrack.category,
                            srcFile = customFile,
                            isOutro = true,
                            onError = { vm.showBanner(it, isError = true) },
                        )
                    },
                    onStopPreview = { vm.player.pause() },
                    onSaveTrim = { s, e, fi, fo, fiOn, foOn ->
                        vm.saveQueueTrackOutro(outroTrack.uid, null, s, e, fi, fo, fiOn, foOn)
                        outroEditingTrackUid = null
                    },
                    onRemoveOutro = {
                        vm.removeQueueTrackOutro(outroTrack.uid)
                        outroEditingTrackUid = null
                    },
                )
            }
        }
    }

    // Batch Set Artist Dialog (opened from Selection Options -> Set artist)
    if (showBatchArtistDialog) {
        AlertDialog(
            onDismissRequest = { showBatchArtistDialog = false },
            containerColor = colors.glassDockFallback,
            titleContentColor = colors.text,
            textContentColor = colors.muted,
            title = { Text("Set artist on $selectedCount song(s)", fontSize = 19.sp, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = batchArtistInput,
                    onValueChange = { batchArtistInput = it },
                    placeholder = { Text("Artist name", fontSize = 14.5.sp, color = colors.faint) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            dismissButton = {
                TextButton(onClick = { showBatchArtistDialog = false }) {
                    Text("Cancel", fontSize = 14.sp)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (batchArtistInput.isNotBlank()) {
                            vm.batchUpdateQueueArtist(batchArtistInput)
                            batchArtistInput = ""
                            showBatchArtistDialog = false
                        }
                    },
                    enabled = batchArtistInput.isNotBlank(),
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.capsuleBg, contentColor = colors.capsuleInk),
                ) {
                    Text("Apply", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            },
        )
    }

    // Install Progress Dialog
    if (ui.installProgress.active) {
        val ip = ui.installProgress
        AlertDialog(
            onDismissRequest = { if (ip.done) vm.dismissInstallDialog() },
            containerColor = colors.glassDockFallback,
            titleContentColor = colors.text,
            textContentColor = colors.muted,
            title = {
                Text(
                    when {
                        ip.error != null -> "Installation failed"
                        ip.done -> "Installation complete"
                        else -> "Installing songs"
                    },
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!ip.done) {
                        Text(ip.stage, color = colors.text, fontSize = 15.sp)
                        LinearProgressIndicator(
                            progress = { if (ip.total > 0) (ip.current.toFloat() / ip.total.toFloat()).coerceIn(0.05f, 1f) else 0.1f },
                            color = colors.accent,
                            trackColor = colors.raised,
                            modifier = Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(999.dp)),
                        )
                        Text("${ip.current} / ${ip.total}", color = colors.muted, fontSize = 14.sp)
                    } else if (ip.error != null) {
                        Text(ip.error, color = colors.danger, fontSize = 15.sp)
                    } else {
                        Text(ip.summary ?: "Songs installed cleanly.", color = colors.text, fontSize = 15.sp)
                    }
                }
            },
            confirmButton = {
                if (ip.done) {
                    Button(
                        onClick = { vm.dismissInstallDialog() },
                        shape = RoundedCornerShape(999.dp),
                    ) {
                        Text("Done", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            },
        )
    }

    // Unsaved playlist warning with "Don't show this again" checkbox
    if (ui.showUnsavedClearDialog) {
        var dontShowAgain by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { vm.dismissUnsavedClearDialog() },
            containerColor = colors.glassDockFallback,
            titleContentColor = colors.text,
            textContentColor = colors.muted,
            title = { Text("Clear unsaved queue?", fontSize = 19.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "You have songs in the Import Queue that haven't been exported to a Mod Pack or installed yet.",
                        fontSize = 14.5.sp,
                        lineHeight = 20.sp,
                        color = colors.muted,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { dontShowAgain = !dontShowAgain }
                            .padding(vertical = 4.dp),
                    ) {
                        Checkbox(checked = dontShowAgain, onCheckedChange = { dontShowAgain = it })
                        Spacer(Modifier.width(8.dp))
                        Text("Don't show this again", color = colors.text, fontSize = 14.5.sp)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.dismissUnsavedClearDialog() }) {
                    Text("Cancel", fontSize = 14.sp)
                }
            },
            confirmButton = {
                Button(
                    onClick = { vm.confirmClearQueue(dontShowAgain) },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.danger, contentColor = Color.White),
                    shape = RoundedCornerShape(999.dp),
                ) {
                    Text("Clear queue", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QueueTrackRowCard(
    track: QueueTrack,
    selectionMode: Boolean,
    isFocused: Boolean,
    isPlayingMain: Boolean,
    isPlayingOutro: Boolean,
    showInlineEditButton: Boolean,
    onSelectToggle: () -> Unit,
    onRowClick: () -> Unit,
    onPlayMain: () -> Unit,
    onPlayOutro: () -> Unit,
    onEditClick: () -> Unit,
    onOutroClick: () -> Unit,
) {
    val colors = LocalAppColors.current
    val reduceMotion = LocalReduceMotion.current
    val view = LocalView.current
    val highlighted = isPlayingMain || isPlayingOutro || (selectionMode && track.selected) || (!selectionMode && isFocused)
    val playInteraction = remember { MutableInteractionSource() }
    val editInteraction = remember { MutableInteractionSource() }

    LiquidGlassCard(
        shape = RoundedCornerShape(18.dp),
        tintColor = when {
            isPlayingMain || isPlayingOutro -> colors.accentSoft.copy(alpha = 0.28f)
            selectionMode && track.selected -> colors.accentSoft.copy(alpha = 0.16f)
            else -> null
        },
        accentBorder = if (highlighted) colors.accent else null,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    if (selectionMode) {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        onSelectToggle()
                    } else {
                        onRowClick()
                    }
                },
                onLongClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    onSelectToggle()
                },
            ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AnimatedVisibility(
                visible = selectionMode,
                enter = if (reduceMotion) EnterTransition.None else expandHorizontally(spring(0.80f, 480f)) + fadeIn(tween(140)),
                exit = if (reduceMotion) ExitTransition.None else shrinkHorizontally(tween(140)) + fadeOut(tween(110)),
            ) {
                Checkbox(
                    checked = track.selected,
                    onCheckedChange = { onSelectToggle() },
                    modifier = Modifier.size(26.dp),
                )
            }

            Box(
                modifier = Modifier
                    .size(42.dp)
                    .interactivePressScale(playInteraction, 0.90f)
                    .clip(CircleShape)
                    .background(if (isPlayingMain) colors.accent else colors.glassCardRaised)
                    .border(1.dp, if (isPlayingMain) colors.accent else colors.glassSpecularTop, CircleShape)
                    .clickable(interactionSource = playInteraction, indication = null) { onPlayMain() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (isPlayingMain) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (isPlayingMain) "Pause" else "Preview",
                    tint = if (isPlayingMain) Color(0xFF0B0C0F) else colors.text,
                    modifier = Modifier.size(21.dp),
                )
            }

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = track.title,
                    color = colors.text,
                    fontSize = 15.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = track.artist,
                        color = colors.muted,
                        fontSize = 13.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    MiniTag(track.category.displayName, colors.accentSoft, colors.accentInk)
                    if (track.conflict) {
                        MiniTag("Exists", colors.warn.copy(alpha = 0.18f), colors.warn)
                    }
                    track.profile?.takeIf { it.badge != "Unknown" }?.let { prof ->
                        val bc = when (prof.badge) {
                            "Clip risk" -> colors.danger
                            "Hot mix", "Quiet" -> colors.warn
                            else -> colors.ok
                        }
                        MiniTag(prof.badge, bc.copy(alpha = 0.16f), bc)
                    }
                }
            }

            if (track.hasOutro) {
                val outroInteraction = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .interactivePressScale(outroInteraction, 0.90f)
                        .clip(CircleShape)
                        .background(if (isPlayingOutro) colors.accent else colors.accentSoft)
                        .clickable(interactionSource = outroInteraction, indication = null) { onPlayOutro() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (isPlayingOutro) Icons.Rounded.Pause else Icons.Rounded.GraphicEq,
                        contentDescription = "Preview outro",
                        tint = if (isPlayingOutro) Color(0xFF0B0C0F) else colors.accentInk,
                        modifier = Modifier.size(17.dp),
                    )
                }
            }

            if (showInlineEditButton) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = colors.glassCardRaised,
                    border = BorderStroke(1.dp, colors.glassSpecularMid),
                    modifier = Modifier
                        .defaultMinSize(minHeight = 36.dp)
                        .interactivePressScale(editInteraction, 0.93f)
                        .clickable(interactionSource = editInteraction, indication = null) { onEditClick() },
                ) {
                    Box(Modifier.padding(horizontal = 13.dp, vertical = 7.dp), contentAlignment = Alignment.Center) {
                        Text("Edit", color = colors.text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            } else {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = if (track.hasOutro) colors.accentSoft else colors.glassCardRaised,
                    modifier = Modifier
                        .defaultMinSize(minHeight = 34.dp)
                        .interactivePressScale(editInteraction, 0.93f)
                        .clickable(interactionSource = editInteraction, indication = null) { onOutroClick() },
                ) {
                    Box(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
                        Text(
                            text = if (track.hasOutro) "Outro" else "+ Outro",
                            color = if (track.hasOutro) colors.accentInk else colors.muted,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SingleTrackDetailsForm(
    track: QueueTrack,
    onSave: (String, String, StationCategory) -> Unit,
    onRemove: () -> Unit,
) {
    val colors = LocalAppColors.current
    var title by remember(track.uid, track.title) { mutableStateOf(track.title) }
    var artist by remember(track.uid, track.artist) { mutableStateOf(track.artist) }
    var station by remember(track.uid, track.category) { mutableStateOf(track.category) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("Song title", fontSize = 14.sp) },
            textStyle = MaterialTheme.typography.bodyMedium,
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = artist,
            onValueChange = { artist = it },
            label = { Text("Artist", fontSize = 14.sp) },
            textStyle = MaterialTheme.typography.bodyMedium,
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Station", color = colors.muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            SlidingSegmentedControl(
                items = StationCategory.entries.map { SegmentItem(it.displayName) },
                selectedIndex = StationCategory.entries.indexOf(station).coerceAtLeast(0),
                onSelect = { station = StationCategory.entries[it] },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onRemove) {
                Text("Remove", color = colors.danger, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
            }
            val saveInteraction = remember { MutableInteractionSource() }
            Button(
                onClick = { onSave(title, artist, station) },
                interactionSource = saveInteraction,
                shape = RoundedCornerShape(999.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.capsuleBg, contentColor = colors.capsuleInk),
                modifier = Modifier
                    .defaultMinSize(minHeight = 44.dp)
                    .interactivePressScale(saveInteraction, 0.94f),
            ) {
                Text("Save changes", fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun QueueOutroEditorBody(
    track: QueueTrack,
    nowPlaying: NowPlayingState,
    outroEditorEnabled: Boolean,
    onPickCustomOutro: () -> Unit,
    onPreview: (Double, Double, Double, Double) -> Unit,
    onPreviewCustom: (File) -> Unit,
    onStopPreview: () -> Unit,
    onSaveTrim: (Double, Double, Double, Double, Boolean, Boolean) -> Unit,
    onRemoveOutro: () -> Unit,
) {
    val colors = LocalAppColors.current
    val dur = max(1.0, track.profile?.durationSec ?: 180.0)
    var modeTab by remember(track.uid) { mutableIntStateOf(if (track.outroSrcPath != null && track.outroSrcPath != track.srcPath) 1 else 0) }
    var startSec by remember(track.uid) { mutableDoubleStateOf((track.outroStartSec ?: max(0.0, dur - 20.0)).coerceIn(0.0, max(0.0, dur - 0.5))) }
    var endSec by remember(track.uid) { mutableDoubleStateOf((track.outroEndSec ?: dur).coerceIn(min(dur, startSec + 0.5), dur)) }
    var fadeInOn by remember(track.uid) { mutableStateOf(track.outroFadeInEnabled) }
    var fadeOutOn by remember(track.uid) { mutableStateOf(track.outroFadeOutEnabled) }
    var fadeInSec by remember(track.uid) { mutableFloatStateOf(track.outroFadeIn.toFloat().takeIf { it > 0f } ?: 0.8f) }
    var fadeOutSec by remember(track.uid) { mutableFloatStateOf(track.outroFadeOut.toFloat().takeIf { it > 0f } ?: 1.2f) }

    LaunchedEffect(dur) {
        if (endSec > dur) endSec = dur
        if (startSec >= endSec - 0.5) startSec = max(0.0, endSec - 20.0)
    }

    val isPreparingOutro = nowPlaying.uid == track.uid && nowPlaying.isOutro && nowPlaying.isPreparing
    val isPlayingOutro = nowPlaying.uid == track.uid && nowPlaying.isOutro && nowPlaying.isPlaying
    val playbackProgress = if (isPlayingOutro && nowPlaying.durationMs > 0) {
        (nowPlaying.positionMs.toFloat() / nowPlaying.durationMs.toFloat()).coerceIn(0f, 1f)
    } else null

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (!outroEditorEnabled) {
            Text(
                "Outro editor is turned off in Settings. Attach or remove a custom outro file directly.",
                color = colors.muted,
                fontSize = 14.5.sp,
                lineHeight = 20.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onPickCustomOutro, shape = RoundedCornerShape(999.dp)) {
                    Text(if (track.hasOutro) "Replace outro file" else "Attach outro file", fontSize = 14.sp)
                }
                if (track.hasOutro) {
                    TextButton(onClick = onRemoveOutro) { Text("Remove outro", color = colors.danger, fontSize = 14.sp) }
                }
            }
            return@Column
        }

        SlidingSegmentedControl(
            items = listOf(SegmentItem("Trim main track"), SegmentItem("Custom audio file")),
            selectedIndex = modeTab,
            onSelect = { modeTab = it },
            modifier = Modifier.fillMaxWidth(),
        )

        if (modeTab == 0) {
            WaveformTrimEditor(
                peaks = track.profile?.peaks.orEmpty(),
                durationSec = dur,
                startSec = startSec,
                endSec = endSec,
                onRangeChange = { s, e -> startSec = s; endSec = e },
                playbackProgress = playbackProgress,
            )

            val presetsScroll = rememberScrollState()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScrollEdgeFade(presetsScroll, edgeWidth = 22.dp)
                    .horizontalScroll(presetsScroll),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ActionPill(null, "Last 20s") { startSec = max(0.0, dur - 20.0); endSec = dur }
                ActionPill(null, "Last 30s") { startSec = max(0.0, dur - 30.0); endSec = dur }
                ActionPill(null, "First 25s") { startSec = 0.0; endSec = min(dur, 25.0) }
                ActionPill(null, "Full track") { startSec = 0.0; endSec = dur }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                LiquidGlassCard(
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Fade in", color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Switch(checked = fadeInOn, onCheckedChange = { fadeInOn = it })
                        }
                        if (fadeInOn) {
                            Text("${"%.1f".format(fadeInSec)}s", color = colors.accentInk, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                            Slider(value = fadeInSec, onValueChange = { fadeInSec = it }, valueRange = 0.2f..5.0f)
                        }
                    }
                }

                LiquidGlassCard(
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Fade out", color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Switch(checked = fadeOutOn, onCheckedChange = { fadeOutOn = it })
                        }
                        if (fadeOutOn) {
                            Text("${"%.1f".format(fadeOutSec)}s", color = colors.accentInk, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                            Slider(value = fadeOutSec, onValueChange = { fadeOutSec = it }, valueRange = 0.2f..5.0f)
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (track.hasOutro) {
                    TextButton(onClick = onRemoveOutro) { Text("Remove", color = colors.danger, fontSize = 14.sp) }
                } else {
                    Spacer(Modifier.width(1.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = {
                            if (isPlayingOutro) {
                                onStopPreview()
                            } else if (!isPreparingOutro) {
                                onPreview(
                                    startSec,
                                    endSec,
                                    if (fadeInOn) fadeInSec.toDouble() else 0.0,
                                    if (fadeOutOn) fadeOutSec.toDouble() else 0.0,
                                )
                            }
                        },
                        enabled = !isPreparingOutro,
                        shape = RoundedCornerShape(999.dp),
                        border = BorderStroke(
                            1.dp,
                            if (isPlayingOutro) colors.accent else colors.glassSpecularTop,
                        ),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (isPlayingOutro) colors.accentSoft else Color.Transparent,
                            contentColor = if (isPlayingOutro) colors.accentInk else colors.text,
                        ),
                        modifier = Modifier.defaultMinSize(minHeight = 44.dp),
                    ) {
                        if (isPreparingOutro) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(15.dp),
                                strokeWidth = 2.dp,
                                color = colors.accentInk,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Loading...", fontSize = 14.sp)
                        } else {
                            Icon(
                                if (isPlayingOutro) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(if (isPlayingOutro) "Stop" else "Preview", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Button(
                        onClick = {
                            onSaveTrim(
                                startSec,
                                endSec,
                                if (fadeInOn) fadeInSec.toDouble() else 0.0,
                                if (fadeOutOn) fadeOutSec.toDouble() else 0.0,
                                fadeInOn,
                                fadeOutOn,
                            )
                        },
                        shape = RoundedCornerShape(999.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.capsuleBg, contentColor = colors.capsuleInk),
                        modifier = Modifier.defaultMinSize(minHeight = 44.dp),
                    ) {
                        Text("Save outro", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        } else {
            LiquidGlassCard(
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Custom outro audio file", color = colors.text, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        track.outroSrcName ?: "No separate outro file attached yet.",
                        color = colors.muted,
                        fontSize = 14.sp,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = onPickCustomOutro, shape = RoundedCornerShape(999.dp)) {
                            Text("Choose audio file", fontSize = 14.sp)
                        }
                        val customFile = track.outroSrcPath?.takeIf { it != track.srcPath }?.let { File(it) }?.takeIf { it.isFile }
                        if (customFile != null) {
                            OutlinedButton(
                                onClick = {
                                    if (isPlayingOutro) onStopPreview() else if (!isPreparingOutro) onPreviewCustom(customFile)
                                },
                                enabled = !isPreparingOutro,
                                shape = RoundedCornerShape(999.dp),
                            ) {
                                Text(if (isPlayingOutro) "Stop" else "Preview", fontSize = 14.sp)
                            }
                        }
                        if (track.hasOutro) {
                            TextButton(onClick = onRemoveOutro) { Text("Remove", color = colors.danger, fontSize = 14.sp) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ActionPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    label: String,
    isDanger: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = LocalAppColors.current
    val interaction = remember { MutableInteractionSource() }
    LiquidGlassCard(
        shape = RoundedCornerShape(999.dp),
        tintColor = if (isDanger) colors.danger.copy(alpha = 0.16f) else null,
        accentBorder = if (isDanger) colors.danger else null,
        modifier = Modifier
            .defaultMinSize(minHeight = 40.dp)
            .interactivePressScale(interaction, 0.94f)
            .clickable(interactionSource = interaction, indication = null) { onClick() },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (icon != null) {
                Icon(icon, null, tint = if (isDanger) colors.danger else colors.text, modifier = Modifier.size(17.dp))
            }
            Text(
                label,
                color = if (isDanger) colors.danger else colors.text,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
fun MiniTag(text: String, bg: Color, fg: Color) {
    Surface(shape = RoundedCornerShape(7.dp), color = bg) {
        Text(
            text = text,
            color = fg,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
        )
    }
}

@Composable
fun EmptyWorkspaceCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    val reduceMotion = LocalReduceMotion.current
    val primaryInteraction = remember { MutableInteractionSource() }
    val secondaryInteraction = remember { MutableInteractionSource() }

    val iconBreath = if (reduceMotion) {
        1f
    } else {
        rememberInfiniteTransition(label = "emptyPulse").animateFloat(
            initialValue = 1.0f,
            targetValue = 1.06f,
            animationSpec = infiniteRepeatable(
                animation = tween(1900, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "emptyIconScale",
        ).value
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 128.dp),
        contentAlignment = Alignment.Center,
    ) {
        LiquidGlassCard(
            shape = RoundedCornerShape(24.dp),
            elevated = true,
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 480.dp),
        ) {
            Column(
                modifier = Modifier.padding(26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(58.dp)
                        .graphicsLayer { scaleX = iconBreath; scaleY = iconBreath }
                        .clip(CircleShape)
                        .background(colors.accentSoft)
                        .border(1.dp, colors.accent.copy(alpha = 0.45f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, null, tint = colors.accentInk, modifier = Modifier.size(28.dp))
                }
                Text(
                    title,
                    color = colors.text,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Text(
                    subtitle,
                    color = colors.muted,
                    fontSize = 14.5.sp,
                    lineHeight = 21.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onPrimary,
                        interactionSource = primaryInteraction,
                        shape = RoundedCornerShape(999.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.capsuleBg, contentColor = colors.capsuleInk),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 11.dp),
                        modifier = Modifier
                            .defaultMinSize(minHeight = 46.dp)
                            .interactivePressScale(primaryInteraction, 0.94f),
                    ) {
                        Text(primaryLabel, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                    }
                    if (secondaryLabel != null && onSecondary != null) {
                        OutlinedButton(
                            onClick = onSecondary,
                            interactionSource = secondaryInteraction,
                            shape = RoundedCornerShape(999.dp),
                            border = BorderStroke(1.dp, colors.glassSpecularTop),
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 11.dp),
                            modifier = Modifier
                                .defaultMinSize(minHeight = 46.dp)
                                .interactivePressScale(secondaryInteraction, 0.94f),
                        ) {
                            Text(secondaryLabel, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}
