package com.pythonnxd.a8ostpatcher.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
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

    val filtered = remember(ui.queueTracks, ui.queueCategoryFilter, ui.queueSearch) {
        val q = ui.queueSearch.trim().lowercase()
        ui.queueTracks.filter { t ->
            (ui.queueCategoryFilter == null || t.category == ui.queueCategoryFilter) &&
                (q.isEmpty() || t.title.lowercase().contains(q) || t.artist.lowercase().contains(q) || t.srcName.lowercase().contains(q))
        }
    }
    val selectedCount = ui.queueTracks.count { it.selected }
    val focusedTrack = ui.queueTracks.firstOrNull { it.uid == ui.focusedQueueUid } ?: filtered.firstOrNull()

    var editingTrackUid by remember { mutableStateOf<String?>(null) }
    var outroEditingTrackUid by remember { mutableStateOf<String?>(null) }
    var tabletInspectorTab by remember { mutableIntStateOf(0) } // 0 = Song details, 1 = Outro
    var batchArtistInput by remember { mutableStateOf("") }
    var batchStation by remember { mutableStateOf(StationCategory.ROCK) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = if (isTablet) 20.dp else 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Header row: Logo + Overwrite/Skip + Install CTA
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Image(
                painter = painterResource(if (colors.isLight) R.drawable.a8_logo_light else R.drawable.a8_logo_dark),
                contentDescription = "Asphalt 8: Airborne",
                modifier = Modifier.height(24.dp),
                contentScale = ContentScale.Fit,
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (isTablet) {
                    SlidingSegmentedControl(
                        items = listOf(SegmentItem("Overwrite"), SegmentItem("Skip")),
                        selectedIndex = if (ui.prefs.conflictPolicy == ConflictPolicy.SKIP) 1 else 0,
                        onSelect = { idx ->
                            val pol = if (idx == 1) ConflictPolicy.SKIP else ConflictPolicy.OVERWRITE
                            vm.updatePrefs { it.copy(conflictPolicy = pol) }
                        },
                        modifier = Modifier.width(172.dp),
                    )
                }

                Button(
                    onClick = { vm.startInstallSelected() },
                    enabled = selectedCount > 0 && ui.gameDirValid && !ui.installProgress.active,
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.capsuleBg,
                        contentColor = colors.capsuleInk,
                    ),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    modifier = Modifier.height(36.dp),
                ) {
                    Text(
                        text = if (selectedCount > 0) "Install $selectedCount" else "Install",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        // Source quick actions bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ActionPill(Icons.Rounded.Add, "Songs", onClick = onAddSongs)
            ActionPill(Icons.Rounded.FolderOpen, "Folder", onClick = onAddFolder)
            if (ui.queueTracks.isNotEmpty()) {
                ActionPill(Icons.Rounded.Archive, "Export .zip", onClick = onExportZip)
                ActionPill(Icons.Rounded.DeleteSweep, "Clear", isDanger = true, onClick = { vm.requestClearQueue() })
            }
            if (!isTablet) {
                Spacer(Modifier.width(2.dp))
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = colors.surface,
                    border = BorderStroke(1.dp, colors.border),
                    modifier = Modifier
                        .height(32.dp)
                        .clickable {
                            val next = if (ui.prefs.conflictPolicy == ConflictPolicy.OVERWRITE) ConflictPolicy.SKIP else ConflictPolicy.OVERWRITE
                            vm.updatePrefs { it.copy(conflictPolicy = next) }
                        },
                ) {
                    Box(Modifier.padding(horizontal = 11.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "On conflict: ${ui.prefs.conflictPolicy.label}",
                            color = colors.muted,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }

        // Filter chips + Search
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StationFilterChip(
                    label = "All ${ui.queueTracks.size}",
                    selected = ui.queueCategoryFilter == null,
                    onClick = { vm.setQueueFilter(null) },
                )
                StationCategory.entries.forEach { cat ->
                    val count = ui.queueTracks.count { it.category == cat }
                    StationFilterChip(
                        label = "${cat.displayName} $count",
                        selected = ui.queueCategoryFilter == cat,
                        onClick = { vm.setQueueFilter(cat) },
                    )
                }
            }
        }

        if (ui.queueTracks.isNotEmpty()) {
            OutlinedTextField(
                value = ui.queueSearch,
                onValueChange = { vm.setQueueSearch(it) },
                placeholder = { Text("Search queued songs...", fontSize = 13.sp, color = colors.faint) },
                leadingIcon = { Icon(Icons.Rounded.Search, null, tint = colors.faint, modifier = Modifier.size(18.dp)) },
                trailingIcon = if (ui.queueSearch.isNotEmpty()) {
                    {
                        IconButton(onClick = { vm.setQueueSearch("") }) {
                            Icon(Icons.Rounded.Close, "Clear search", tint = colors.muted, modifier = Modifier.size(16.dp))
                        }
                    }
                } else null,
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = colors.surface,
                    unfocusedContainerColor = colors.surface,
                    focusedBorderColor = colors.accent,
                    unfocusedBorderColor = colors.border,
                    focusedTextColor = colors.text,
                    unfocusedTextColor = colors.text,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            )
        }

        // Contextual Batch Edit Bar (hides cleanly when 0 selected, matching PC v1.4.3!)
        AnimatedVisibility(
            visible = selectedCount > 0,
            enter = if (reduceMotion) EnterTransition.None else fadeIn() + expandVertically(),
            exit = if (reduceMotion) ExitTransition.None else fadeOut() + shrinkVertically(),
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = colors.surface,
                border = BorderStroke(1.dp, colors.accent.copy(alpha = 0.42f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Surface(shape = RoundedCornerShape(999.dp), color = colors.accentSoft) {
                                Text(
                                    "$selectedCount selected",
                                    color = colors.accentInk,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                                )
                            }
                            Text(
                                "Toggle all",
                                color = colors.muted,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clickable {
                                    vm.toggleSelectAllQueue(filtered.map { it.uid }.toSet())
                                },
                            )
                        }
                        Text(
                            "Remove selected",
                            color = colors.danger,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable { vm.removeSelectedQueueTracks() },
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = batchArtistInput,
                            onValueChange = { batchArtistInput = it },
                            placeholder = { Text("Batch artist", fontSize = 12.sp, color = colors.faint) },
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = colors.ground,
                                unfocusedContainerColor = colors.ground,
                                focusedBorderColor = colors.accent,
                                unfocusedBorderColor = colors.border,
                                focusedTextColor = colors.text,
                                unfocusedTextColor = colors.text,
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp),
                        )
                        FilledTonalButton(
                            onClick = {
                                if (batchArtistInput.isNotBlank()) {
                                    vm.batchUpdateQueueArtist(batchArtistInput)
                                    batchArtistInput = ""
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            modifier = Modifier.height(40.dp),
                        ) {
                            Text("Apply", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Set station:", color = colors.muted, fontSize = 11.5.sp)
                        StationCategory.entries.forEach { cat ->
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = if (batchStation == cat) colors.accentSoft else colors.raised,
                                border = BorderStroke(1.dp, if (batchStation == cat) colors.accent.copy(alpha = 0.5f) else colors.border),
                                modifier = Modifier
                                    .height(28.dp)
                                    .clickable {
                                        batchStation = cat
                                        vm.batchUpdateQueueStation(cat)
                                    },
                            ) {
                                Box(Modifier.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                                    Text(cat.displayName, color = if (batchStation == cat) colors.accentInk else colors.text, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
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
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // Left track list
                LazyColumn(
                    modifier = Modifier.weight(1.05f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 152.dp),
                ) {
                    items(filtered, key = { it.uid }) { track ->
                        QueueTrackRowCard(
                            track = track,
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

                // Right Tablet inspector pane: Song details vs Outro separated with matching icons
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = colors.surface,
                    border = BorderStroke(1.dp, colors.border),
                    modifier = Modifier
                        .weight(0.95f)
                        .fillMaxHeight()
                        .padding(bottom = 148.dp),
                ) {
                    if (focusedTrack == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Select a song to edit details or outro", color = colors.muted, fontSize = 13.sp)
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
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
            // Phone list with 1:1 swipe quick actions
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 160.dp),
            ) {
                items(filtered, key = { it.uid }) { track ->
                    SwipeableTrackCard(
                        onOutroClick = {
                            if (ui.prefs.outroEditorEnabled) outroEditingTrackUid = track.uid
                            else onPickCustomOutroForQueue(track.uid)
                        },
                        onRemoveClick = { vm.removeQueueTrack(track.uid) },
                    ) {
                        QueueTrackRowCard(
                            track = track,
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

    // Phone draggable Edit Song Details ModalBottomSheet
    val editTrack = ui.queueTracks.firstOrNull { it.uid == editingTrackUid }
    if (editTrack != null && !isTablet) {
        ModalBottomSheet(
            onDismissRequest = { editingTrackUid = null },
            containerColor = colors.surface,
            contentColor = colors.text,
            dragHandle = { BottomSheetDefaults.DragHandle(color = colors.muted.copy(alpha = 0.4f)) },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp)
                    .padding(bottom = 28.dp),
            ) {
                Text("Edit song details", color = colors.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
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

    // Phone draggable Outro Editor ModalBottomSheet
    val outroTrack = ui.queueTracks.firstOrNull { it.uid == outroEditingTrackUid }
    if (outroTrack != null && !isTablet) {
        ModalBottomSheet(
            onDismissRequest = { outroEditingTrackUid = null },
            containerColor = colors.surface,
            contentColor = colors.text,
            dragHandle = { BottomSheetDefaults.DragHandle(color = colors.muted.copy(alpha = 0.4f)) },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp)
                    .padding(bottom = 28.dp),
            ) {
                Text("Outro editor", color = colors.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text("\"${outroTrack.title}\" - ${outroTrack.artist}", color = colors.muted, fontSize = 12.sp)
                Spacer(Modifier.height(12.dp))
                QueueOutroEditorBody(
                    track = outroTrack,
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

    // Install Progress Dialog (no redundant Close button when Cancel/Done is present!)
    if (ui.installProgress.active) {
        val ip = ui.installProgress
        AlertDialog(
            onDismissRequest = { if (ip.done) vm.dismissInstallDialog() },
            containerColor = colors.surface,
            titleContentColor = colors.text,
            textContentColor = colors.muted,
            title = {
                Text(
                    when {
                        ip.error != null -> "Installation failed"
                        ip.done -> "Installation complete"
                        else -> "Installing songs"
                    },
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!ip.done) {
                        Text(ip.stage, color = colors.text, fontSize = 13.sp)
                        LinearProgressIndicator(
                            progress = { if (ip.total > 0) (ip.current.toFloat() / ip.total.toFloat()).coerceIn(0.05f, 1f) else 0.1f },
                            color = colors.accent,
                            trackColor = colors.raised,
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(999.dp)),
                        )
                        Text("${ip.current} / ${ip.total}", color = colors.muted, fontSize = 12.sp)
                    } else if (ip.error != null) {
                        Text(ip.error, color = colors.danger, fontSize = 13.sp)
                    } else {
                        Text(ip.summary ?: "Songs installed cleanly.", color = colors.text, fontSize = 13.sp)
                    }
                }
            },
            confirmButton = {
                if (ip.done) {
                    Button(
                        onClick = { vm.dismissInstallDialog() },
                        shape = RoundedCornerShape(999.dp),
                    ) {
                        Text("Done")
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
            containerColor = colors.surface,
            titleContentColor = colors.text,
            textContentColor = colors.muted,
            title = { Text("Clear unsaved queue?", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "You have songs in the Import Queue that haven't been exported to a Mod Pack or installed yet.",
                        fontSize = 13.sp,
                        color = colors.muted,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { dontShowAgain = !dontShowAgain }
                            .padding(vertical = 4.dp),
                    ) {
                        Checkbox(checked = dontShowAgain, onCheckedChange = { dontShowAgain = it })
                        Spacer(Modifier.width(6.dp))
                        Text("Don't show this again", color = colors.text, fontSize = 13.sp)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.dismissUnsavedClearDialog() }) { Text("Cancel") }
            },
            confirmButton = {
                Button(
                    onClick = { vm.confirmClearQueue(dontShowAgain) },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.danger, contentColor = Color.White),
                    shape = RoundedCornerShape(999.dp),
                ) {
                    Text("Clear queue")
                }
            },
        )
    }
}

@Composable
private fun QueueTrackRowCard(
    track: QueueTrack,
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
    val highlighted = isPlayingMain || isPlayingOutro || isFocused

    Surface(
        shape = RoundedCornerShape(15.dp),
        color = if (isPlayingMain || isPlayingOutro) colors.accentSoft.copy(alpha = 0.24f) else colors.surface,
        border = BorderStroke(1.dp, if (highlighted) colors.accent.copy(alpha = 0.55f) else colors.border),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onRowClick() },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Checkbox(
                checked = track.selected,
                onCheckedChange = { onSelectToggle() },
                modifier = Modifier.size(24.dp),
            )

            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(if (isPlayingMain) colors.accent else colors.raised)
                    .border(1.dp, if (isPlayingMain) colors.accent else colors.border, CircleShape)
                    .clickable { onPlayMain() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (isPlayingMain) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (isPlayingMain) "Pause" else "Preview",
                    tint = if (isPlayingMain) Color(0xFF0B0C0F) else colors.text,
                    modifier = Modifier.size(18.dp),
                )
            }

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = track.title,
                    color = colors.text,
                    fontSize = 13.5.sp,
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
                        fontSize = 11.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    MiniTag(track.category.displayName, colors.accentSoft, colors.accentInk)
                    if (track.conflict) {
                        MiniTag("Exists", colors.warn.copy(alpha = 0.16f), colors.warn)
                    }
                    track.profile?.takeIf { it.badge != "Unknown" }?.let { prof ->
                        val bc = when (prof.badge) {
                            "Clip risk" -> colors.danger
                            "Hot mix", "Quiet" -> colors.warn
                            else -> colors.ok
                        }
                        MiniTag(prof.badge, bc.copy(alpha = 0.14f), bc)
                    }
                }
            }

            if (track.hasOutro) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(if (isPlayingOutro) colors.accent else colors.accentSoft)
                        .clickable { onPlayOutro() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (isPlayingOutro) Icons.Rounded.Pause else Icons.Rounded.GraphicEq,
                        contentDescription = "Preview outro",
                        tint = if (isPlayingOutro) Color(0xFF0B0C0F) else colors.accentInk,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }

            if (showInlineEditButton) {
                Surface(
                    shape = RoundedCornerShape(9.dp),
                    color = colors.raised,
                    border = BorderStroke(1.dp, colors.border),
                    modifier = Modifier
                        .height(30.dp)
                        .clickable { onEditClick() },
                ) {
                    Box(Modifier.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                        Text("Edit", color = colors.text, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            } else {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = if (track.hasOutro) colors.accentSoft else colors.raised,
                    modifier = Modifier
                        .height(26.dp)
                        .clickable { onOutroClick() },
                ) {
                    Box(Modifier.padding(horizontal = 9.dp), contentAlignment = Alignment.Center) {
                        Text(
                            text = if (track.hasOutro) "Outro" else "+ Outro",
                            color = if (track.hasOutro) colors.accentInk else colors.muted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Clean single-song details form (NO redundant "Set artist on selected" button!).
 */
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

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("Song title") },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = artist,
            onValueChange = { artist = it },
            label = { Text("Artist") },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Station", color = colors.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StationCategory.entries.forEach { cat ->
                    val active = station == cat
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (active) colors.accentSoft else colors.ground,
                        border = BorderStroke(1.dp, if (active) colors.accent else colors.border),
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp)
                            .clickable { station = cat },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                cat.displayName,
                                color = if (active) colors.accentInk else colors.muted,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onRemove) {
                Text("Remove", color = colors.danger)
            }
            Button(
                onClick = { onSave(title, artist, station) },
                shape = RoundedCornerShape(999.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.capsuleBg, contentColor = colors.capsuleInk),
            ) {
                Text("Save changes", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun QueueOutroEditorBody(
    track: QueueTrack,
    outroEditorEnabled: Boolean,
    onPickCustomOutro: () -> Unit,
    onPreview: (Double, Double, Double, Double) -> Unit,
    onSaveTrim: (Double, Double, Double, Double, Boolean, Boolean) -> Unit,
    onRemoveOutro: () -> Unit,
) {
    val colors = LocalAppColors.current
    val dur = max(1.0, track.profile?.durationSec ?: 180.0)
    var modeTab by remember(track.uid) { mutableIntStateOf(if (track.outroSrcPath != null && track.outroSrcPath != track.srcPath) 1 else 0) }
    var startSec by remember(track.uid) { mutableDoubleStateOf(track.outroStartSec ?: max(0.0, dur - 20.0)) }
    var endSec by remember(track.uid) { mutableDoubleStateOf(track.outroEndSec ?: dur) }
    var fadeInOn by remember(track.uid) { mutableStateOf(track.outroFadeInEnabled) }
    var fadeOutOn by remember(track.uid) { mutableStateOf(track.outroFadeOutEnabled) }
    var fadeInSec by remember(track.uid) { mutableFloatStateOf(track.outroFadeIn.toFloat().takeIf { it > 0f } ?: 0.8f) }
    var fadeOutSec by remember(track.uid) { mutableFloatStateOf(track.outroFadeOut.toFloat().takeIf { it > 0f } ?: 1.2f) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!outroEditorEnabled) {
            Text(
                "Outro editor is turned off in Settings. Attach or remove a custom outro file directly.",
                color = colors.muted,
                fontSize = 12.5.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPickCustomOutro, shape = RoundedCornerShape(999.dp)) {
                    Text(if (track.hasOutro) "Replace outro file" else "Attach outro file")
                }
                if (track.hasOutro) {
                    TextButton(onClick = onRemoveOutro) { Text("Remove outro", color = colors.danger) }
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
            )

            // Quick presets
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ActionPill(null, "Last 20s") { startSec = max(0.0, dur - 20.0); endSec = dur }
                ActionPill(null, "Last 30s") { startSec = max(0.0, dur - 30.0); endSec = dur }
                ActionPill(null, "First 25s") { startSec = 0.0; endSec = min(dur, 25.0) }
                ActionPill(null, "Full track") { startSec = 0.0; endSec = dur }
            }

            // Fade toggles
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = colors.ground,
                    border = BorderStroke(1.dp, colors.border),
                    modifier = Modifier.weight(1f),
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Fade in", color = colors.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Switch(checked = fadeInOn, onCheckedChange = { fadeInOn = it })
                        }
                        if (fadeInOn) {
                            Text("${"%.1f".format(fadeInSec)}s", color = colors.accentInk, fontSize = 11.5.sp)
                            Slider(value = fadeInSec, onValueChange = { fadeInSec = it }, valueRange = 0.2f..5.0f)
                        }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = colors.ground,
                    border = BorderStroke(1.dp, colors.border),
                    modifier = Modifier.weight(1f),
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Fade out", color = colors.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Switch(checked = fadeOutOn, onCheckedChange = { fadeOutOn = it })
                        }
                        if (fadeOutOn) {
                            Text("${"%.1f".format(fadeOutSec)}s", color = colors.accentInk, fontSize = 11.5.sp)
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
                    TextButton(onClick = onRemoveOutro) { Text("Remove", color = colors.danger) }
                } else {
                    Spacer(Modifier.width(1.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            onPreview(
                                startSec,
                                endSec,
                                if (fadeInOn) fadeInSec.toDouble() else 0.0,
                                if (fadeOutOn) fadeOutSec.toDouble() else 0.0,
                            )
                        },
                        shape = RoundedCornerShape(999.dp),
                    ) {
                        Text("Preview")
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
                    ) {
                        Text("Save outro", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        } else {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = colors.ground,
                border = BorderStroke(1.dp, colors.border),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Custom outro audio file", color = colors.text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        track.outroSrcName ?: "No separate outro file attached yet.",
                        color = colors.muted,
                        fontSize = 12.sp,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onPickCustomOutro, shape = RoundedCornerShape(999.dp)) {
                            Text("Choose audio file")
                        }
                        if (track.hasOutro) {
                            TextButton(onClick = onRemoveOutro) { Text("Remove outro", color = colors.danger) }
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
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (isDanger) colors.danger.copy(alpha = 0.12f) else colors.surface,
        border = BorderStroke(1.dp, if (isDanger) colors.danger.copy(alpha = 0.4f) else colors.border),
        modifier = Modifier
            .height(32.dp)
            .clickable { onClick() },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (icon != null) {
                Icon(icon, null, tint = if (isDanger) colors.danger else colors.text, modifier = Modifier.size(15.dp))
            }
            Text(label, color = if (isDanger) colors.danger else colors.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun StationFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (selected) colors.accentSoft else colors.surface,
        border = BorderStroke(1.dp, if (selected) colors.accent.copy(alpha = 0.6f) else colors.border),
        modifier = Modifier
            .height(30.dp)
            .clickable { onClick() },
    ) {
        Box(Modifier.padding(horizontal = 11.dp), contentAlignment = Alignment.Center) {
            Text(
                label,
                color = if (selected) colors.accentInk else colors.muted,
                fontSize = 11.5.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            )
        }
    }
}

@Composable
fun MiniTag(text: String, bg: Color, fg: Color) {
    Surface(shape = RoundedCornerShape(6.dp), color = bg) {
        Text(
            text = text,
            color = fg,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.5.dp),
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
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 110.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = colors.surface,
            border = BorderStroke(1.dp, colors.border),
            modifier = Modifier.fillMaxWidth().widthIn(max = 460.dp),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(colors.accentSoft),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, null, tint = colors.accentInk, modifier = Modifier.size(24.dp))
                }
                Text(title, color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, color = colors.muted, fontSize = 12.5.sp, lineHeight = 18.sp)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onPrimary,
                        shape = RoundedCornerShape(999.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.capsuleBg, contentColor = colors.capsuleInk),
                    ) {
                        Text(primaryLabel, fontWeight = FontWeight.SemiBold)
                    }
                    if (secondaryLabel != null && onSecondary != null) {
                        OutlinedButton(onClick = onSecondary, shape = RoundedCornerShape(999.dp)) {
                            Text(secondaryLabel)
                        }
                    }
                }
            }
        }
    }
}
