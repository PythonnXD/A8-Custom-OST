package com.pythonnxd.a8ostpatcher.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pythonnxd.a8ostpatcher.engine.InstalledTrack
import com.pythonnxd.a8ostpatcher.engine.StationCategory
import com.pythonnxd.a8ostpatcher.state.MainUiState
import com.pythonnxd.a8ostpatcher.state.MainViewModel
import com.pythonnxd.a8ostpatcher.state.NowPlayingState
import com.pythonnxd.a8ostpatcher.ui.components.AnimatedCategoryFilterRow
import com.pythonnxd.a8ostpatcher.ui.components.CategoryFilterItem
import com.pythonnxd.a8ostpatcher.ui.components.LiquidGlassCard
import com.pythonnxd.a8ostpatcher.ui.components.SegmentItem
import com.pythonnxd.a8ostpatcher.ui.components.SlidingSegmentedControl
import com.pythonnxd.a8ostpatcher.ui.components.interactivePressScale
import com.pythonnxd.a8ostpatcher.ui.theme.LocalAppColors
import com.pythonnxd.a8ostpatcher.ui.theme.LocalReduceMotion
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    ui: MainUiState,
    nowPlaying: NowPlayingState,
    vm: MainViewModel,
    isTablet: Boolean,
    onChooseGameFolder: () -> Unit,
    onPickCustomOutroForLibrary: (InstalledTrack) -> Unit,
) {
    val colors = LocalAppColors.current
    val reduceMotion = LocalReduceMotion.current

    val filtered = remember(ui.libraryTracks, ui.libraryCategoryFilter, ui.librarySearch) {
        val q = ui.librarySearch.trim().lowercase()
        ui.libraryTracks.filter { t ->
            (ui.libraryCategoryFilter == null || t.category == ui.libraryCategoryFilter) &&
                (q.isEmpty() || t.title.lowercase().contains(q) || t.artist.lowercase().contains(q) || t.baseId.lowercase().contains(q))
        }
    }
    val selectedTracks = ui.libraryTracks.filter { it.selected }
    val focusedTrack = ui.libraryTracks.firstOrNull { it.uid == ui.focusedLibraryUid } ?: filtered.firstOrNull()

    var editingTrack by remember { mutableStateOf<InstalledTrack?>(null) }
    var showRemoveConfirm by remember { mutableStateOf(false) }
    var deleteAudioFiles by remember { mutableStateOf(false) }
    var tabletTab by remember { mutableIntStateOf(0) }

    val categoryItems = remember(ui.libraryTracks) {
        buildList {
            add(CategoryFilterItem("all", "All ${ui.libraryTracks.size}"))
            StationCategory.entries.forEach { cat ->
                val count = ui.libraryTracks.count { it.category == cat }
                add(CategoryFilterItem(cat.name, "${cat.displayName} $count"))
            }
        }
    }
    val selectedCategoryIdx = remember(ui.libraryCategoryFilter) {
        if (ui.libraryCategoryFilter == null) 0 else StationCategory.entries.indexOf(ui.libraryCategoryFilter) + 1
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = if (isTablet) 24.dp else 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("In-Game Library", color = colors.text, fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp)
                Text(
                    if (ui.gameDirValid) "${ui.libraryTracks.size} registered tracks" else "Connect an Asphalt 8 folder",
                    color = colors.muted,
                    fontSize = 14.sp,
                )
            }
            AnimatedVisibility(
                visible = selectedTracks.isNotEmpty(),
                enter = if (reduceMotion) EnterTransition.None else scaleIn(spring(0.70f, 480f)) + fadeIn(tween(150)),
                exit = if (reduceMotion) ExitTransition.None else scaleOut(tween(110)) + fadeOut(tween(110)),
            ) {
                val removeInteraction = remember { MutableInteractionSource() }
                Button(
                    onClick = { showRemoveConfirm = true },
                    interactionSource = removeInteraction,
                    colors = ButtonDefaults.buttonColors(containerColor = colors.danger, contentColor = Color.White),
                    shape = RoundedCornerShape(999.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    modifier = Modifier
                        .defaultMinSize(minHeight = 42.dp)
                        .interactivePressScale(removeInteraction, 0.94f),
                ) {
                    Text("Remove ${selectedTracks.size}", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        if (!ui.gameDirValid) {
            EmptyWorkspaceCard(
                icon = Icons.Rounded.FolderOff,
                title = "Asphalt 8 folder not selected",
                subtitle = "Choose your unpacked Asphalt 8 folder containing soundpack, music, and text directories to manage installed tracks.",
                primaryLabel = "Choose folder",
                onPrimary = onChooseGameFolder,
                secondaryLabel = "Auto-detect",
                onSecondary = { vm.autoDetectGameFolder() },
                modifier = Modifier.weight(1f),
            )
            return@Column
        }

        // Animated Frosted Category Selection Row
        AnimatedCategoryFilterRow(
            items = categoryItems,
            selectedIndex = selectedCategoryIdx,
            onSelect = { idx ->
                if (idx == 0) vm.setLibraryFilter(null)
                else vm.setLibraryFilter(StationCategory.entries[idx - 1])
            },
        )

        OutlinedTextField(
            value = ui.librarySearch,
            onValueChange = { vm.setLibrarySearch(it) },
            placeholder = { Text("Search installed songs...", fontSize = 14.5.sp, color = colors.faint) },
            leadingIcon = { Icon(Icons.Rounded.Search, null, tint = colors.faint, modifier = Modifier.size(20.dp)) },
            trailingIcon = if (ui.librarySearch.isNotEmpty()) {
                {
                    IconButton(onClick = { vm.setLibrarySearch("") }) {
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

        if (ui.libraryLoading) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.accent)
            }
        } else if (isTablet) {
            Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
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
                            LibraryTrackRowCard(
                                track = track,
                                isFocused = focusedTrack?.uid == track.uid,
                                isPlayingMain = nowPlaying.isPlaying && nowPlaying.uid == track.uid && !nowPlaying.isOutro,
                                isPlayingOutro = nowPlaying.isPlaying && nowPlaying.uid == track.uid && nowPlaying.isOutro,
                                showEditButton = false,
                                onSelectToggle = { vm.toggleLibrarySelection(track.uid) },
                                onClick = { vm.focusLibraryTrack(track.uid) },
                                onPlayMain = { vm.playInstalledTrack(track, isOutro = false) },
                                onPlayOutro = { vm.playInstalledTrack(track, isOutro = true) },
                                onEdit = { vm.focusLibraryTrack(track.uid); tabletTab = 0 },
                            )
                        }
                    }
                }

                LiquidGlassCard(
                    shape = RoundedCornerShape(22.dp),
                    modifier = Modifier
                        .weight(0.95f)
                        .fillMaxHeight()
                        .padding(bottom = 166.dp),
                ) {
                    if (focusedTrack == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Select an installed track", color = colors.muted, fontSize = 15.sp)
                        }
                    } else {
                        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            SlidingSegmentedControl(
                                items = listOf(
                                    SegmentItem("Song details", Icons.Rounded.Edit),
                                    SegmentItem("Outro", Icons.Rounded.GraphicEq),
                                ),
                                selectedIndex = tabletTab,
                                onSelect = { tabletTab = it },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Box(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                                if (tabletTab == 0) {
                                    LibraryDetailsForm(
                                        track = focusedTrack,
                                        onSave = { t, a, c -> vm.saveLibraryTrackEdit(focusedTrack, t, a, c) },
                                        onRemove = { vm.removeLibraryTracks(listOf(focusedTrack), deleteFiles = false) },
                                    )
                                } else {
                                    LibraryOutroPane(
                                        track = focusedTrack,
                                        onAttachCustom = { onPickCustomOutroForLibrary(focusedTrack) },
                                        onTrimLast20 = { vm.trimInstalledTrackLast20s(focusedTrack) },
                                        onRemoveOutro = { vm.removeLibraryTrackOutro(focusedTrack, deleteFile = true) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
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
                        LibraryTrackRowCard(
                            track = track,
                            isFocused = focusedTrack?.uid == track.uid,
                            isPlayingMain = nowPlaying.isPlaying && nowPlaying.uid == track.uid && !nowPlaying.isOutro,
                            isPlayingOutro = nowPlaying.isPlaying && nowPlaying.uid == track.uid && nowPlaying.isOutro,
                            showEditButton = true,
                            onSelectToggle = { vm.toggleLibrarySelection(track.uid) },
                            onClick = { vm.focusLibraryTrack(track.uid) },
                            onPlayMain = { vm.playInstalledTrack(track, isOutro = false) },
                            onPlayOutro = { vm.playInstalledTrack(track, isOutro = true) },
                            onEdit = { editingTrack = track },
                        )
                    }
                }
            }
        }
    }

    if (editingTrack != null && !isTablet) {
        val tr = editingTrack!!
        var sheetTab by remember(tr.uid) { mutableIntStateOf(0) }
        ModalBottomSheet(
            onDismissRequest = { editingTrack = null },
            containerColor = colors.glassDockFallback,
            contentColor = colors.text,
            dragHandle = { BottomSheetDefaults.DragHandle(color = colors.muted.copy(alpha = 0.48f)) },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                SlidingSegmentedControl(
                    items = listOf(
                        SegmentItem("Song details", Icons.Rounded.Edit),
                        SegmentItem("Outro", Icons.Rounded.GraphicEq),
                    ),
                    selectedIndex = sheetTab,
                    onSelect = { sheetTab = it },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (sheetTab == 0) {
                    LibraryDetailsForm(
                        track = tr,
                        onSave = { t, a, c ->
                            vm.saveLibraryTrackEdit(tr, t, a, c)
                            editingTrack = null
                        },
                        onRemove = {
                            vm.removeLibraryTracks(listOf(tr), deleteFiles = false)
                            editingTrack = null
                        },
                    )
                } else {
                    LibraryOutroPane(
                        track = tr,
                        onAttachCustom = {
                            editingTrack = null
                            onPickCustomOutroForLibrary(tr)
                        },
                        onTrimLast20 = {
                            vm.trimInstalledTrackLast20s(tr)
                            editingTrack = null
                        },
                        onRemoveOutro = {
                            vm.removeLibraryTrackOutro(tr, deleteFile = true)
                            editingTrack = null
                        },
                    )
                }
            }
        }
    }

    if (showRemoveConfirm) {
        AlertDialog(
            onDismissRequest = { showRemoveConfirm = false },
            containerColor = colors.glassDockFallback,
            title = { Text("Remove ${selectedTracks.size} song(s)?", color = colors.text, fontSize = 19.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Selected songs will be removed from soundpack playlists and game title tables.",
                        color = colors.muted,
                        fontSize = 14.5.sp,
                        lineHeight = 20.sp,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { deleteAudioFiles = !deleteAudioFiles },
                    ) {
                        Checkbox(checked = deleteAudioFiles, onCheckedChange = { deleteAudioFiles = it })
                        Spacer(Modifier.width(8.dp))
                        Text("Also delete .mpc files from music folder", color = colors.text, fontSize = 14.5.sp)
                    }
                }
            },
            dismissButton = { TextButton(onClick = { showRemoveConfirm = false }) { Text("Cancel", fontSize = 14.sp) } },
            confirmButton = {
                Button(
                    onClick = {
                        vm.removeLibraryTracks(selectedTracks, deleteAudioFiles)
                        showRemoveConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.danger, contentColor = Color.White),
                    shape = RoundedCornerShape(999.dp),
                ) {
                    Text("Remove", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            },
        )
    }
}

@Composable
private fun LibraryTrackRowCard(
    track: InstalledTrack,
    isFocused: Boolean,
    isPlayingMain: Boolean,
    isPlayingOutro: Boolean,
    showEditButton: Boolean,
    onSelectToggle: () -> Unit,
    onClick: () -> Unit,
    onPlayMain: () -> Unit,
    onPlayOutro: () -> Unit,
    onEdit: () -> Unit,
) {
    val colors = LocalAppColors.current
    val highlighted = isFocused || isPlayingMain || isPlayingOutro
    val playInteraction = remember { MutableInteractionSource() }
    val editInteraction = remember { MutableInteractionSource() }

    LiquidGlassCard(
        shape = RoundedCornerShape(18.dp),
        tintColor = if (isPlayingMain || isPlayingOutro) colors.accentSoft.copy(alpha = 0.28f) else null,
        accentBorder = if (highlighted) colors.accent else null,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Checkbox(checked = track.selected, onCheckedChange = { onSelectToggle() }, modifier = Modifier.size(26.dp))
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .interactivePressScale(playInteraction, 0.90f)
                    .clip(CircleShape)
                    .background(if (isPlayingMain) colors.accent else colors.glassCardRaised)
                    .border(1.dp, if (isPlayingMain) colors.accent else colors.glassSpecularTop, CircleShape)
                    .clickable(enabled = track.mainExists, interactionSource = playInteraction, indication = null) { onPlayMain() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (isPlayingMain) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = "Preview",
                    tint = if (isPlayingMain) Color(0xFF0B0C0F) else if (track.mainExists) colors.text else colors.faint,
                    modifier = Modifier.size(21.dp),
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    track.title,
                    color = colors.text,
                    fontSize = 15.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        track.artist,
                        color = colors.muted,
                        fontSize = 13.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    MiniTag(track.rawCategoryName, colors.accentSoft, colors.accentInk)
                    if (!track.mainExists) MiniTag("Missing", colors.danger.copy(alpha = 0.18f), colors.danger)
                }
            }
            if (track.hasOutro && track.outroExists) {
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
                        if (isPlayingOutro) Icons.Rounded.Pause else Icons.Rounded.GraphicEq,
                        contentDescription = "Preview outro",
                        tint = if (isPlayingOutro) Color(0xFF0B0C0F) else colors.accentInk,
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
            if (showEditButton) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = colors.glassCardRaised,
                    border = BorderStroke(1.dp, colors.glassSpecularMid),
                    modifier = Modifier
                        .defaultMinSize(minHeight = 36.dp)
                        .interactivePressScale(editInteraction, 0.93f)
                        .clickable(interactionSource = editInteraction, indication = null) { onEdit() },
                ) {
                    Box(Modifier.padding(horizontal = 13.dp, vertical = 7.dp), contentAlignment = Alignment.Center) {
                        Text("Edit", color = colors.text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryDetailsForm(
    track: InstalledTrack,
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onRemove) { Text("Remove from game", color = colors.danger, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
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
private fun LibraryOutroPane(
    track: InstalledTrack,
    onAttachCustom: () -> Unit,
    onTrimLast20: () -> Unit,
    onRemoveOutro: () -> Unit,
) {
    val colors = LocalAppColors.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            if (track.hasOutro) "Registered outro: ${track.outroFile ?: track.outroId}" else "No outro attached to this track yet.",
            color = colors.muted,
            fontSize = 14.5.sp,
            lineHeight = 20.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onAttachCustom,
                shape = RoundedCornerShape(999.dp),
                modifier = Modifier.defaultMinSize(minHeight = 44.dp),
            ) {
                Text("Attach custom audio", fontSize = 14.sp)
            }
            if (track.mainExists) {
                OutlinedButton(
                    onClick = onTrimLast20,
                    shape = RoundedCornerShape(999.dp),
                    modifier = Modifier.defaultMinSize(minHeight = 44.dp),
                ) {
                    Text("Cut last 20s", fontSize = 14.sp)
                }
            }
        }
        if (track.hasOutro) {
            TextButton(onClick = onRemoveOutro) {
                Text("Remove outro", color = colors.danger, fontSize = 14.sp)
            }
        }
    }
}
