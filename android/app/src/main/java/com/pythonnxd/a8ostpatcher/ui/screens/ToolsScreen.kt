package com.pythonnxd.a8ostpatcher.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pythonnxd.a8ostpatcher.engine.BackupEntry
import com.pythonnxd.a8ostpatcher.engine.ModProfile
import com.pythonnxd.a8ostpatcher.state.MainUiState
import com.pythonnxd.a8ostpatcher.state.MainViewModel
import com.pythonnxd.a8ostpatcher.state.ToolsSection
import com.pythonnxd.a8ostpatcher.ui.components.HoldToDeleteButton
import com.pythonnxd.a8ostpatcher.ui.components.SegmentItem
import com.pythonnxd.a8ostpatcher.ui.components.SlidingSegmentedControl
import com.pythonnxd.a8ostpatcher.ui.theme.LocalAppColors

private fun formatBytesShort(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return "%.1f KB".format(kb)
    return "%.2f MB".format(kb / 1024.0)
}

@Composable
fun ToolsScreen(
    ui: MainUiState,
    vm: MainViewModel,
    isTablet: Boolean,
    onChooseGameFolder: () -> Unit,
) {
    val colors = LocalAppColors.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = if (isTablet) 20.dp else 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Tools",
            color = colors.text,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 8.dp),
        )

        if (!isTablet) {
            SlidingSegmentedControl(
                items = listOf(
                    SegmentItem("Backups", Icons.Rounded.History),
                    SegmentItem("Health", Icons.Rounded.VerifiedUser),
                    SegmentItem("Profiles", Icons.Rounded.Layers),
                ),
                selectedIndex = ui.toolsSection.ordinal,
                onSelect = { vm.selectToolsSection(ToolsSection.entries[it]) },
                modifier = Modifier.fillMaxWidth(),
            )
            Box(Modifier.weight(1f)) {
                ToolsSectionContent(ui, vm, onChooseGameFolder)
            }
        } else {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(
                    modifier = Modifier.width(260.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ToolsNavCard(
                        title = "Backups",
                        subtitle = "${ui.backups.size} saved snapshot(s)",
                        icon = Icons.Rounded.History,
                        active = ui.toolsSection == ToolsSection.BACKUPS,
                        onClick = { vm.selectToolsSection(ToolsSection.BACKUPS) },
                    )
                    ToolsNavCard(
                        title = "Health check",
                        subtitle = ui.healthAudit?.let { if (it.healthy) "No issues found" else "${it.issueCount} issue(s)" } ?: "Connect folder",
                        icon = Icons.Rounded.VerifiedUser,
                        active = ui.toolsSection == ToolsSection.HEALTH,
                        onClick = { vm.selectToolsSection(ToolsSection.HEALTH) },
                    )
                    ToolsNavCard(
                        title = "MOD profiles",
                        subtitle = "${ui.profiles.size} saved loadout(s)",
                        icon = Icons.Rounded.Layers,
                        active = ui.toolsSection == ToolsSection.PROFILES,
                        onClick = { vm.selectToolsSection(ToolsSection.PROFILES) },
                    )
                }

                Box(Modifier.weight(1f)) {
                    ToolsSectionContent(ui, vm, onChooseGameFolder)
                }
            }
        }
    }
}

@Composable
private fun ToolsNavCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalAppColors.current
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (active) colors.raised else colors.surface,
        border = BorderStroke(1.dp, if (active) colors.accent.copy(alpha = 0.55f) else colors.border),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(icon, null, tint = if (active) colors.accentInk else colors.muted, modifier = Modifier.size(20.dp))
            Column {
                Text(title, color = colors.text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = colors.muted, fontSize = 11.5.sp)
            }
        }
    }
}

@Composable
private fun ToolsSectionContent(
    ui: MainUiState,
    vm: MainViewModel,
    onChooseGameFolder: () -> Unit,
) {
    when (ui.toolsSection) {
        ToolsSection.BACKUPS -> BackupsPane(ui, vm, onChooseGameFolder)
        ToolsSection.HEALTH -> HealthPane(ui, vm, onChooseGameFolder)
        ToolsSection.PROFILES -> ProfilesPane(ui, vm, onChooseGameFolder)
    }
}

@Composable
private fun BackupsPane(ui: MainUiState, vm: MainViewModel, onChooseGameFolder: () -> Unit) {
    val colors = LocalAppColors.current
    if (!ui.gameDirValid) {
        EmptyWorkspaceCard(
            icon = Icons.Rounded.History,
            title = "Connect Asphalt 8 folder",
            subtitle = "Backups snapshot your soundpack and language dictionaries before installs and edits.",
            primaryLabel = "Choose folder",
            onPrimary = onChooseGameFolder,
        )
        return
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 152.dp),
    ) {
        item {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = colors.surface,
                border = BorderStroke(1.dp, colors.border),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Game Backups", color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "Hold Delete to remove old snapshots safely without accidental taps.",
                            color = colors.muted,
                            fontSize = 12.sp,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Button(
                        onClick = { vm.createManualBackup() },
                        enabled = !ui.busyAction,
                        shape = RoundedCornerShape(999.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.capsuleBg, contentColor = colors.capsuleInk),
                    ) {
                        Text("Back up now", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        if (ui.backups.isEmpty()) {
            item {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = colors.surface,
                    border = BorderStroke(1.dp, colors.border),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "No backups saved yet. Tap Back up now or enable Auto backup in Settings.",
                        color = colors.muted,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        } else {
            items(ui.backups, key = { it.id }) { entry ->
                BackupRowCard(
                    entry = entry,
                    onRestore = { vm.restoreBackup(entry) },
                    onDeleteConfirmed = { vm.deleteBackup(entry) },
                )
            }
        }
    }
}

@Composable
private fun BackupRowCard(
    entry: BackupEntry,
    onRestore: () -> Unit,
    onDeleteConfirmed: () -> Unit,
) {
    val colors = LocalAppColors.current
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column {
                Text(entry.createdAt.replace('T', ' '), color = colors.text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Reason: ${entry.reason} · ${entry.textsCount} language files · ${formatBytesShort(entry.sizeBytes)}",
                    color = colors.muted,
                    fontSize = 11.5.sp,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = onRestore,
                    shape = RoundedCornerShape(999.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp),
                    modifier = Modifier.height(36.dp),
                ) {
                    Text("Restore", fontSize = 12.sp)
                }
                Spacer(Modifier.width(8.dp))
                HoldToDeleteButton(
                    label = "Hold to delete",
                    onConfirmed = onDeleteConfirmed,
                )
            }
        }
    }
}

@Composable
private fun HealthPane(ui: MainUiState, vm: MainViewModel, onChooseGameFolder: () -> Unit) {
    val colors = LocalAppColors.current
    if (!ui.gameDirValid) {
        EmptyWorkspaceCard(
            icon = Icons.Rounded.VerifiedUser,
            title = "Connect Asphalt 8 folder",
            subtitle = "Audit soundpack entries, missing .mpc audio files, orphan files, and localization tables.",
            primaryLabel = "Choose folder",
            onPrimary = onChooseGameFolder,
        )
        return
    }
    val audit = ui.healthAudit
    var fixTexts by remember { mutableStateOf(true) }
    var fixEvents by remember { mutableStateOf(true) }
    var pruneMissing by remember { mutableStateOf(false) }
    var deleteOrphans by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 152.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = colors.surface,
            border = BorderStroke(1.dp, if (audit?.healthy == true) colors.ok.copy(alpha = 0.45f) else colors.warn.copy(alpha = 0.45f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = when {
                        audit == null -> "Audit unavailable"
                        audit.healthy -> "Soundpack is healthy"
                        else -> "${audit.issueCount} issue(s) found"
                    },
                    color = if (audit?.healthy == true) colors.ok else colors.warn,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
                if (audit != null) {
                    Text(
                        "${audit.totalSounds} sounds · ${audit.totalEvents} events · ${audit.playlistTracks} playlist tracks",
                        color = colors.muted,
                        fontSize = 12.sp,
                    )
                }
            }
        }

        if (audit != null) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = colors.surface,
                border = BorderStroke(1.dp, colors.border),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    HealthMetricRow("Missing title entries", "${audit.missingTexts.size}", audit.missingTexts.isEmpty())
                    HealthMetricRow("Broken refs / missing events", "${audit.brokenPlaylistRefs.size + audit.missingEvents.size}", audit.brokenPlaylistRefs.isEmpty() && audit.missingEvents.isEmpty())
                    HealthMetricRow("Missing audio files", "${audit.missingFiles.size}", audit.missingFiles.isEmpty())
                    HealthMetricRow("Orphan .mpc files", "${audit.orphanFiles.size} (${formatBytesShort(audit.orphanTotalBytes)})", audit.orphanFiles.isEmpty())

                    if (!audit.healthy) {
                        HorizontalDivider(color = colors.rule)
                        HealthRepairToggle("Fill missing song titles", fixTexts) { fixTexts = it }
                        HealthRepairToggle("Fix broken playlist refs & events", fixEvents) { fixEvents = it }
                        HealthRepairToggle("Remove entries with missing files", pruneMissing) { pruneMissing = it }
                        HealthRepairToggle("Delete unreferenced orphan .mpc files", deleteOrphans) { deleteOrphans = it }
                        Button(
                            onClick = { vm.repairHealth(fixTexts, fixEvents, pruneMissing, deleteOrphans) },
                            enabled = !ui.busyAction,
                            shape = RoundedCornerShape(999.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = colors.capsuleBg, contentColor = colors.capsuleInk),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Repair", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HealthMetricRow(label: String, value: String, isOk: Boolean) {
    val colors = LocalAppColors.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = colors.text, fontSize = 13.sp)
        Text(value, color = if (isOk) colors.ok else colors.warn, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun HealthRepairToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Spacer(Modifier.width(6.dp))
        Text(label, color = colors.text, fontSize = 13.sp)
    }
}

@Composable
private fun ProfilesPane(ui: MainUiState, vm: MainViewModel, onChooseGameFolder: () -> Unit) {
    val colors = LocalAppColors.current
    var newName by remember { mutableStateOf("") }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 152.dp),
    ) {
        item {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = colors.surface,
                border = BorderStroke(1.dp, colors.border),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Save current soundtrack as profile", color = colors.text, fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            placeholder = { Text("e.g. Anime Rock Loadout", fontSize = 12.5.sp, color = colors.faint) },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            onClick = {
                                if (newName.isNotBlank()) {
                                    vm.saveProfile(newName)
                                    newName = ""
                                }
                            },
                            enabled = ui.gameDirValid && newName.isNotBlank(),
                            shape = RoundedCornerShape(999.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = colors.capsuleBg, contentColor = colors.capsuleInk),
                        ) {
                            Text("Save", fontWeight = FontWeight.SemiBold)
                        }
                    }
                    if (!ui.gameDirValid) {
                        TextButton(onClick = onChooseGameFolder) { Text("Choose Asphalt 8 folder first") }
                    }
                }
            }
        }

        if (ui.profiles.isEmpty()) {
            item {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = colors.surface,
                    border = BorderStroke(1.dp, colors.border),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "No MOD profiles saved yet. Save your active soundtrack setup above to switch between loadouts anytime.",
                        color = colors.muted,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        } else {
            items(ui.profiles, key = { it.id }) { profile ->
                ProfileRowCard(
                    profile = profile,
                    canApply = ui.gameDirValid && !ui.busyAction,
                    onApply = { vm.applyProfile(profile) },
                    onDelete = { vm.deleteProfile(profile) },
                )
            }
        }
    }
}

@Composable
private fun ProfileRowCard(
    profile: ModProfile,
    canApply: Boolean,
    onApply: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = LocalAppColors.current
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column {
                Text(profile.name, color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "${profile.trackCount} tracks · ${profile.textFiles} language files · ${formatBytesShort(profile.sizeBytes)}",
                    color = colors.muted,
                    fontSize = 11.5.sp,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(
                    onClick = onApply,
                    enabled = canApply,
                    shape = RoundedCornerShape(999.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp),
                    modifier = Modifier.height(36.dp),
                ) {
                    Text("Apply", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.width(8.dp))
                HoldToDeleteButton(label = "Hold to delete", onConfirmed = onDelete)
            }
        }
    }
}
