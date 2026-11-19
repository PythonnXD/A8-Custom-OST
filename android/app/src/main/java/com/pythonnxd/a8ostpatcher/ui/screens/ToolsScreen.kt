package com.pythonnxd.a8ostpatcher.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pythonnxd.a8ostpatcher.engine.BackupEntry
import com.pythonnxd.a8ostpatcher.engine.ModProfile
import com.pythonnxd.a8ostpatcher.state.MainUiState
import com.pythonnxd.a8ostpatcher.state.MainViewModel
import com.pythonnxd.a8ostpatcher.state.ToolsSection
import com.pythonnxd.a8ostpatcher.ui.components.HoldToDeleteButton
import com.pythonnxd.a8ostpatcher.ui.components.LiquidGlassCard
import com.pythonnxd.a8ostpatcher.ui.components.SegmentItem
import com.pythonnxd.a8ostpatcher.ui.components.SlidingSegmentedControl
import com.pythonnxd.a8ostpatcher.ui.components.interactivePressScale
import com.pythonnxd.a8ostpatcher.ui.theme.LocalAppColors
import com.pythonnxd.a8ostpatcher.ui.theme.LocalReduceMotion

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
    val reduceMotion = LocalReduceMotion.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = if (isTablet) 24.dp else 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            "Tools",
            color = colors.text,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.4).sp,
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
            AnimatedContent(
                targetState = ui.toolsSection,
                transitionSpec = {
                    if (reduceMotion) EnterTransition.None togetherWith ExitTransition.None
                    else {
                        val dir = if (targetState.ordinal >= initialState.ordinal) 1 else -1
                        (slideInHorizontally(spring(0.82f, 440f)) { dir * (it / 12) } + fadeIn(tween(180)) + scaleIn(tween(180), initialScale = 0.985f)) togetherWith
                            (slideOutHorizontally(tween(130)) { -dir * (it / 20) } + fadeOut(tween(110)))
                    }
                },
                label = "toolsSectionAnim",
                modifier = Modifier.weight(1f),
            ) { sec ->
                ToolsSectionContent(sec, ui, vm, onChooseGameFolder)
            }
        } else {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(
                    modifier = Modifier.width(276.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
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

                AnimatedContent(
                    targetState = ui.toolsSection,
                    transitionSpec = {
                        if (reduceMotion) EnterTransition.None togetherWith ExitTransition.None
                        else (fadeIn(tween(180)) + scaleIn(tween(180), initialScale = 0.985f)) togetherWith fadeOut(tween(110))
                    },
                    label = "toolsTabletSection",
                    modifier = Modifier.weight(1f),
                ) { sec ->
                    ToolsSectionContent(sec, ui, vm, onChooseGameFolder)
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
    val interaction = remember { MutableInteractionSource() }
    LiquidGlassCard(
        shape = RoundedCornerShape(18.dp),
        elevated = active,
        accentBorder = if (active) colors.accent else null,
        modifier = Modifier
            .fillMaxWidth()
            .interactivePressScale(interaction, 0.96f)
            .clickable(interactionSource = interaction, indication = null) { onClick() },
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, null, tint = if (active) colors.accentInk else colors.muted, modifier = Modifier.size(22.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, color = colors.text, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = colors.muted, fontSize = 13.5.sp)
            }
        }
    }
}

@Composable
private fun ToolsSectionContent(
    section: ToolsSection,
    ui: MainUiState,
    vm: MainViewModel,
    onChooseGameFolder: () -> Unit,
) {
    when (section) {
        ToolsSection.BACKUPS -> BackupsPane(ui, vm, onChooseGameFolder)
        ToolsSection.HEALTH -> HealthPane(ui, vm, onChooseGameFolder)
        ToolsSection.PROFILES -> ProfilesPane(ui, vm, onChooseGameFolder)
    }
}

@Composable
private fun BackupsPane(ui: MainUiState, vm: MainViewModel, onChooseGameFolder: () -> Unit) {
    val colors = LocalAppColors.current
    val reduceMotion = LocalReduceMotion.current
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
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 186.dp),
    ) {
        item {
            LiquidGlassCard(
                shape = RoundedCornerShape(20.dp),
                elevated = true,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Game Backups", color = colors.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "Hold Delete to remove old snapshots safely without accidental taps.",
                            color = colors.muted,
                            fontSize = 14.sp,
                            lineHeight = 19.sp,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    val backupInteraction = remember { MutableInteractionSource() }
                    Button(
                        onClick = { vm.createManualBackup() },
                        enabled = !ui.busyAction,
                        interactionSource = backupInteraction,
                        shape = RoundedCornerShape(999.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.capsuleBg, contentColor = colors.capsuleInk),
                        modifier = Modifier
                            .defaultMinSize(minHeight = 44.dp)
                            .interactivePressScale(backupInteraction, 0.94f),
                    ) {
                        Text("Back up now", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        if (ui.backups.isEmpty()) {
            item {
                LiquidGlassCard(
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "No backups saved yet. Tap Back up now or enable Auto backup in Settings.",
                        color = colors.muted,
                        fontSize = 14.5.sp,
                        lineHeight = 20.sp,
                        modifier = Modifier.padding(18.dp),
                    )
                }
            }
        } else {
            items(ui.backups, key = { it.id }) { entry ->
                Box(
                    modifier = if (reduceMotion) Modifier else Modifier.animateItem(
                        fadeInSpec = tween(180),
                        placementSpec = spring(0.80f, 420f),
                        fadeOutSpec = tween(140),
                    )
                ) {
                    BackupRowCard(
                        entry = entry,
                        onRestore = { vm.restoreBackup(entry) },
                        onDeleteConfirmed = { vm.deleteBackup(entry) },
                    )
                }
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
    LiquidGlassCard(
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(entry.createdAt.replace('T', ' '), color = colors.text, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Reason: ${entry.reason} · ${entry.textsCount} language files · ${formatBytesShort(entry.sizeBytes)}",
                    color = colors.muted,
                    fontSize = 13.5.sp,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val restoreInteraction = remember { MutableInteractionSource() }
                OutlinedButton(
                    onClick = onRestore,
                    interactionSource = restoreInteraction,
                    shape = RoundedCornerShape(999.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    modifier = Modifier
                        .defaultMinSize(minHeight = 42.dp)
                        .interactivePressScale(restoreInteraction, 0.94f),
                ) {
                    Text("Restore", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.width(10.dp))
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
            .padding(bottom = 186.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LiquidGlassCard(
            shape = RoundedCornerShape(20.dp),
            elevated = true,
            accentBorder = if (audit?.healthy == true) colors.ok else colors.warn,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = when {
                        audit == null -> "Audit unavailable"
                        audit.healthy -> "Soundpack is healthy"
                        else -> "${audit.issueCount} issue(s) found"
                    },
                    color = if (audit?.healthy == true) colors.ok else colors.warn,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
                if (audit != null) {
                    Text(
                        "${audit.totalSounds} sounds · ${audit.totalEvents} events · ${audit.playlistTracks} playlist tracks",
                        color = colors.muted,
                        fontSize = 14.sp,
                    )
                }
            }
        }

        if (audit != null) {
            LiquidGlassCard(
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                        val repairInteraction = remember { MutableInteractionSource() }
                        Button(
                            onClick = { vm.repairHealth(fixTexts, fixEvents, pruneMissing, deleteOrphans) },
                            enabled = !ui.busyAction,
                            interactionSource = repairInteraction,
                            shape = RoundedCornerShape(999.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = colors.capsuleBg, contentColor = colors.capsuleInk),
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 46.dp)
                                .interactivePressScale(repairInteraction, 0.96f),
                        ) {
                            Text("Repair", fontSize = 15.sp, fontWeight = FontWeight.Bold)
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
        Text(label, color = colors.text, fontSize = 14.5.sp)
        Text(value, color = if (isOk) colors.ok else colors.warn, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun HealthRepairToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Spacer(Modifier.width(8.dp))
        Text(label, color = colors.text, fontSize = 14.5.sp)
    }
}

@Composable
private fun ProfilesPane(ui: MainUiState, vm: MainViewModel, onChooseGameFolder: () -> Unit) {
    val colors = LocalAppColors.current
    val reduceMotion = LocalReduceMotion.current
    var newName by remember { mutableStateOf("") }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 186.dp),
    ) {
        item {
            LiquidGlassCard(
                shape = RoundedCornerShape(20.dp),
                elevated = true,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Save current soundtrack as profile", color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            placeholder = { Text("e.g. Anime Rock Loadout", fontSize = 14.sp, color = colors.faint) },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1f),
                        )
                        val saveInteraction = remember { MutableInteractionSource() }
                        Button(
                            onClick = {
                                if (newName.isNotBlank()) {
                                    vm.saveProfile(newName)
                                    newName = ""
                                }
                            },
                            enabled = ui.gameDirValid && newName.isNotBlank(),
                            interactionSource = saveInteraction,
                            shape = RoundedCornerShape(999.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = colors.capsuleBg, contentColor = colors.capsuleInk),
                            modifier = Modifier
                                .defaultMinSize(minHeight = 46.dp)
                                .interactivePressScale(saveInteraction, 0.94f),
                        ) {
                            Text("Save", fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    if (!ui.gameDirValid) {
                        TextButton(onClick = onChooseGameFolder) {
                            Text("Choose Asphalt 8 folder first", fontSize = 14.sp)
                        }
                    }
                }
            }
        }

        if (ui.profiles.isEmpty()) {
            item {
                LiquidGlassCard(
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "No MOD profiles saved yet. Save your active soundtrack setup above to switch between loadouts anytime.",
                        color = colors.muted,
                        fontSize = 14.5.sp,
                        lineHeight = 20.sp,
                        modifier = Modifier.padding(18.dp),
                    )
                }
            }
        } else {
            items(ui.profiles, key = { it.id }) { profile ->
                Box(
                    modifier = if (reduceMotion) Modifier else Modifier.animateItem(
                        fadeInSpec = tween(180),
                        placementSpec = spring(0.80f, 420f),
                        fadeOutSpec = tween(140),
                    )
                ) {
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
}

@Composable
private fun ProfileRowCard(
    profile: ModProfile,
    canApply: Boolean,
    onApply: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = LocalAppColors.current
    LiquidGlassCard(
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(profile.name, color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "${profile.trackCount} tracks · ${profile.textFiles} language files · ${formatBytesShort(profile.sizeBytes)}",
                    color = colors.muted,
                    fontSize = 13.5.sp,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                val applyInteraction = remember { MutableInteractionSource() }
                Button(
                    onClick = onApply,
                    enabled = canApply,
                    interactionSource = applyInteraction,
                    shape = RoundedCornerShape(999.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    modifier = Modifier
                        .defaultMinSize(minHeight = 42.dp)
                        .interactivePressScale(applyInteraction, 0.94f),
                ) {
                    Text("Apply", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.width(10.dp))
                HoldToDeleteButton(label = "Hold to delete", onConfirmed = onDelete)
            }
        }
    }
}
