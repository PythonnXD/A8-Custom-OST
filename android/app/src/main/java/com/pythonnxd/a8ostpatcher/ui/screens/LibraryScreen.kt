package com.pythonnxd.a8ostpatcher.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pythonnxd.a8ostpatcher.engine.InstalledTrack
import com.pythonnxd.a8ostpatcher.engine.StationCategory
import com.pythonnxd.a8ostpatcher.state.MainUiState
import com.pythonnxd.a8ostpatcher.state.MainViewModel
import com.pythonnxd.a8ostpatcher.state.NowPlayingState
import com.pythonnxd.a8ostpatcher.ui.components.SegmentItem
import com.pythonnxd.a8ostpatcher.ui.components.SlidingSegmentedControl
import com.pythonnxd.a8ostpatcher.ui.theme.LocalAppColors
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = if (isTablet) 20.dp else 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("In-Game Library", color = colors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (ui.gameDirValid) "${ui.libraryTracks.size} registered tracks" else "Connect an Asphalt 8 folder",
                    color = colors.muted,
                    fontSize = 12.sp,
                )
            }
            if (selectedTracks.isNotEmpty()) {
                Button(
                    onClick = { showRemoveConfirm = true },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.danger, contentColor = Color.White),
                    shape = RoundedCornerShape(999.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp),
                    modifier = Modifier.height(34.dp),
                ) {
                    Text("Remove ${selectedTracks.size}", fontSize = 12.sp, fontWeight = FontWeight.Bold)
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

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StationFilterChip("All ${ui.libraryTracks.size}", ui.libraryCategoryFilter == null) { vm.setLibraryFilter(null) }
            StationCategory.entries.forEach { cat ->
                val count = ui.libraryTracks.count { it.category == cat }
                StationFilterChip("${cat.displayName} $count", ui.libraryCategoryFilter == cat) { vm.setLibraryFilter(cat) }
            }
        }

        OutlinedTextField(
            value = ui.librarySearch,
            onValueChange = { vm.setLibrarySearch(it) },
            placeholder = { Text("Search installed songs...", fontSize = 13.sp, color = colors.faint) },
            leadingIcon = { Icon(Icons.Rounded.Search, null, tint = colors.faint, modifier = Modifier.size(18.dp)) },
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

        if (ui.libraryLoading) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.accent)
            }
        } else if (isTablet) {
            Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                LazyColumn(
                    modifier = Modifier.weight(1.05f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 152.dp),
                ) {
                    items(filtered, key = { it.uid }) { track ->
                        LibraryTrackRowCard(
                            track = track,
                            isFocused = focusedTrack?.uid == track.uid,
                            isPlayingMain = nowPlaying.isPlaying && nowPlaying.uid == track.uid && !nowPlaying.isOutro,
                            isPlayingOutro = nowPlaying.isPlaying && nowPlaying.uid == track.uid && nowPlaying.isOutro,
                            showEditButton = false,
                            onSelectToggle = { vm.toggleLibrarySelection(track.uid) },
                            onClick = { vm.focusLibraryTrack(track.uid) },
                            onPlayMain = {
                                vm.player.togglePlay(
                                    uid = track.uid,
                                    title = track.title,
                                    artist = track.artist,
                                    category = track.category,
                                    srcFile = File(track.mainPath),
                                    isOutro = false,
                                    onError = { vm.showBanner(it, isError = true) },
                                )
                            },
                            onPlayOutro = {
                                track.outroPath?.let { op ->
                                    vm.player.togglePlay(
                                        uid = track.uid,
                                        title = track.title,
                                        artist = track.artist,
                                        category = track.category,
                                        srcFile = File(op),
                                        isOutro = true,
                                        onError = { vm.showBanner(it, isError = true) },
                                    )
                                }
                            },
                            onEdit = { vm.focusLibraryTrack(track.uid); tabletTab = 0 },
                        )
                    }
                }

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
                            Text("Select an installed track", color = colors.muted, fontSize = 13.sp)
                        }
                    } else {
                        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                                        onTrimLast20 = {
                                            val dur = com.pythonnxd.a8ostpatcher.engine.AudioPipeline.probeDuration(File(focusedTrack.mainPath)) ?: 180.0
                                            vm.saveLibraryTrackOutro(focusedTrack, null, maxOf(0.0, dur - 20.0), dur, 0.0, 1.2)
                                        },
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
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 160.dp),
            ) {
                items(filtered, key = { it.uid }) { track ->
                    LibraryTrackRowCard(
                        track = track,
                        isFocused = focusedTrack?.uid == track.uid,
                        isPlayingMain = nowPlaying.isPlaying && nowPlaying.uid == track.uid && !nowPlaying.isOutro,
                        isPlayingOutro = nowPlaying.isPlaying && nowPlaying.uid == track.uid && nowPlaying.isOutro,
                        showEditButton = true,
                        onSelectToggle = { vm.toggleLibrarySelection(track.uid) },
                        onClick = { vm.focusLibraryTrack(track.uid) },
                        onPlayMain = {
                            vm.player.togglePlay(
                                uid = track.uid,
                                title = track.title,
                                artist = track.artist,
                                category = track.category,
                                srcFile = File(track.mainPath),
                                isOutro = false,
                                onError = { vm.showBanner(it, isError = true) },
                            )
                        },
                        onPlayOutro = {
                            track.outroPath?.let { op ->
                                vm.player.togglePlay(
                                    uid = track.uid,
                                    title = track.title,
                                    artist = track.artist,
                                    category = track.category,
                                    srcFile = File(op),
                                    isOutro = true,
                                    onError = { vm.showBanner(it, isError = true) },
                                )
                            }
                        },
                        onEdit = { editingTrack = track },
                    )
                }
            }
        }
    }

    if (editingTrack != null && !isTablet) {
        val tr = editingTrack!!
        var sheetTab by remember(tr.uid) { mutableIntStateOf(0) }
        ModalBottomSheet(
            onDismissRequest = { editingTrack = null },
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
                verticalArrangement = Arrangement.spacedBy(12.dp),
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
                            val dur = com.pythonnxd.a8ostpatcher.engine.AudioPipeline.probeDuration(File(tr.mainPath)) ?: 180.0
                            vm.saveLibraryTrackOutro(tr, null, maxOf(0.0, dur - 20.0), dur, 0.0, 1.2)
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
            containerColor = colors.surface,
            title = { Text("Remove ${selectedTracks.size} song(s)?", color = colors.text, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Selected songs will be removed from soundpack playlists and game title tables.", color = colors.muted, fontSize = 13.sp)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { deleteAudioFiles = !deleteAudioFiles },
                    ) {
                        Checkbox(checked = deleteAudioFiles, onCheckedChange = { deleteAudioFiles = it })
                        Spacer(Modifier.width(6.dp))
                        Text("Also delete .mpc files from music folder", color = colors.text, fontSize = 13.sp)
                    }
                }
            },
            dismissButton = { TextButton(onClick = { showRemoveConfirm = false }) { Text("Cancel") } },
            confirmButton = {
                Button(
                    onClick = {
                        vm.removeLibraryTracks(selectedTracks, deleteAudioFiles)
                        showRemoveConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.danger, contentColor = Color.White),
                    shape = RoundedCornerShape(999.dp),
                ) {
                    Text("Remove")
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
    Surface(
        shape = RoundedCornerShape(15.dp),
        color = if (isPlayingMain || isPlayingOutro) colors.accentSoft.copy(alpha = 0.24f) else colors.surface,
        border = BorderStroke(1.dp, if (isFocused || isPlayingMain || isPlayingOutro) colors.accent.copy(alpha = 0.55f) else colors.border),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Checkbox(checked = track.selected, onCheckedChange = { onSelectToggle() }, modifier = Modifier.size(24.dp))
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(if (isPlayingMain) colors.accent else colors.raised)
                    .border(1.dp, if (isPlayingMain) colors.accent else colors.border, CircleShape)
                    .clickable(enabled = track.mainExists) { onPlayMain() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (isPlayingMain) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = "Preview",
                    tint = if (isPlayingMain) Color(0xFF0B0C0F) else if (track.mainExists) colors.text else colors.faint,
                    modifier = Modifier.size(18.dp),
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(track.title, color = colors.text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(track.artist, color = colors.muted, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    MiniTag(track.rawCategoryName, colors.accentSoft, colors.accentInk)
                    if (!track.mainExists) MiniTag("Missing", colors.danger.copy(alpha = 0.16f), colors.danger)
                }
            }
            if (track.hasOutro && track.outroExists) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(if (isPlayingOutro) colors.accent else colors.accentSoft)
                        .clickable { onPlayOutro() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (isPlayingOutro) Icons.Rounded.Pause else Icons.Rounded.GraphicEq,
                        contentDescription = "Preview outro",
                        tint = if (isPlayingOutro) Color(0xFF0B0C0F) else colors.accentInk,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            if (showEditButton) {
                Surface(
                    shape = RoundedCornerShape(9.dp),
                    color = colors.raised,
                    border = BorderStroke(1.dp, colors.border),
                    modifier = Modifier.height(30.dp).clickable { onEdit() },
                ) {
                    Box(Modifier.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                        Text("Edit", color = colors.text, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
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

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Song title") }, singleLine = true, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = artist, onValueChange = { artist = it }, label = { Text("Artist") }, singleLine = true, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StationCategory.entries.forEach { cat ->
                val active = station == cat
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (active) colors.accentSoft else colors.ground,
                    border = BorderStroke(1.dp, if (active) colors.accent else colors.border),
                    modifier = Modifier.weight(1f).height(36.dp).clickable { station = cat },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(cat.displayName, color = if (active) colors.accentInk else colors.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onRemove) { Text("Remove from game", color = colors.danger) }
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
private fun LibraryOutroPane(
    track: InstalledTrack,
    onAttachCustom: () -> Unit,
    onTrimLast20: () -> Unit,
    onRemoveOutro: () -> Unit,
) {
    val colors = LocalAppColors.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            if (track.hasOutro) "Registered outro: ${track.outroFile ?: track.outroId}" else "No outro attached to this track yet.",
            color = colors.muted,
            fontSize = 12.5.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onAttachCustom, shape = RoundedCornerShape(999.dp)) {
                Text("Attach custom audio")
            }
            if (track.mainExists) {
                OutlinedButton(onClick = onTrimLast20, shape = RoundedCornerShape(999.dp)) {
                    Text("Cut last 20s")
                }
            }
        }
        if (track.hasOutro) {
            TextButton(onClick = onRemoveOutro) {
                Text("Remove outro", color = colors.danger)
            }
        }
    }
}
