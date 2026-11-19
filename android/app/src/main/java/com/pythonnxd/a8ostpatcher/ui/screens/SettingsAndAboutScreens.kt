package com.pythonnxd.a8ostpatcher.ui.screens

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pythonnxd.a8ostpatcher.BuildConfig
import com.pythonnxd.a8ostpatcher.R
import com.pythonnxd.a8ostpatcher.engine.AppThemeMode
import com.pythonnxd.a8ostpatcher.engine.QualityPreset
import com.pythonnxd.a8ostpatcher.engine.UpdateChecker
import com.pythonnxd.a8ostpatcher.state.MainUiState
import com.pythonnxd.a8ostpatcher.state.MainViewModel
import com.pythonnxd.a8ostpatcher.ui.components.LiquidGlassCard
import com.pythonnxd.a8ostpatcher.ui.components.interactivePressScale
import com.pythonnxd.a8ostpatcher.ui.theme.LocalAppColors
import com.pythonnxd.a8ostpatcher.ui.theme.LocalReduceMotion

@Composable
fun SettingsScreen(
    ui: MainUiState,
    vm: MainViewModel,
    isTablet: Boolean,
    hasAllFilesAccess: Boolean,
    onRequestAllFilesAccess: () -> Unit,
    onChooseGameFolder: () -> Unit,
) {
    val colors = LocalAppColors.current
    val reduceMotion = LocalReduceMotion.current
    val view = LocalView.current
    val prefs = ui.prefs

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = if (isTablet) 28.dp else 16.dp)
            .padding(top = 8.dp, bottom = 186.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Settings",
            color = colors.text,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.4).sp,
        )

        // 1. Asphalt 8 Folder & Storage Access
        SettingsGroupCard(title = "Asphalt 8 folder") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (ui.gameDirValid) "Connected" else "Not configured",
                        color = if (ui.gameDirValid) colors.ok else colors.warn,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !hasAllFilesAccess) {
                        TextButton(onClick = onRequestAllFilesAccess) {
                            Text("Grant all files access", color = colors.accentInk, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                Text(
                    prefs.gameDir.ifBlank { "Pick your unpacked Asphalt 8 folder (contains soundpack, music, and text)." },
                    color = colors.muted,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
                if (ui.gameDirHint.isNotBlank() && !ui.gameDirWritable) {
                    Text(ui.gameDirHint, color = colors.danger, fontSize = 13.5.sp, lineHeight = 19.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val chooseInteraction = remember { MutableInteractionSource() }
                    Button(
                        onClick = onChooseGameFolder,
                        interactionSource = chooseInteraction,
                        shape = RoundedCornerShape(999.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.capsuleBg, contentColor = colors.capsuleInk),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 11.dp),
                        modifier = Modifier
                            .defaultMinSize(minHeight = 46.dp)
                            .interactivePressScale(chooseInteraction, 0.94f),
                    ) {
                        Text("Choose folder", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                    val autoInteraction = remember { MutableInteractionSource() }
                    OutlinedButton(
                        onClick = { vm.autoDetectGameFolder() },
                        interactionSource = autoInteraction,
                        shape = RoundedCornerShape(999.dp),
                        border = BorderStroke(1.dp, colors.glassSpecularTop),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 11.dp),
                        modifier = Modifier
                            .defaultMinSize(minHeight = 46.dp)
                            .interactivePressScale(autoInteraction, 0.94f),
                    ) {
                        Text("Auto-detect", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // 2. Appearance (Dark, Light, Material You Dark, Material You Light)
        SettingsGroupCard(title = "Appearance") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Theme", color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ThemeOptionChip(
                            label = "Dark",
                            selected = prefs.themeMode == AppThemeMode.DARK,
                            onClick = { vm.updatePrefs { it.copy(themeMode = AppThemeMode.DARK) } },
                            modifier = Modifier.weight(1f),
                        )
                        ThemeOptionChip(
                            label = "Light",
                            selected = prefs.themeMode == AppThemeMode.LIGHT,
                            onClick = { vm.updatePrefs { it.copy(themeMode = AppThemeMode.LIGHT) } },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ThemeOptionChip(
                            label = "Material You Dark",
                            selected = prefs.themeMode == AppThemeMode.MATERIAL_YOU_DARK,
                            onClick = { vm.updatePrefs { it.copy(themeMode = AppThemeMode.MATERIAL_YOU_DARK) } },
                            modifier = Modifier.weight(1f),
                        )
                        ThemeOptionChip(
                            label = "Material You Light",
                            selected = prefs.themeMode == AppThemeMode.MATERIAL_YOU_LIGHT,
                            onClick = { vm.updatePrefs { it.copy(themeMode = AppThemeMode.MATERIAL_YOU_LIGHT) } },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        // 3. Audio & Library
        SettingsGroupCard(title = "Audio and library") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Audio quality", color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Choose the Musepack SV8 encoding preset used when installing songs. Valid untrimmed .mpc files are copied straight over at original volume.",
                    color = colors.muted,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    QualityPreset.entries.forEach { preset ->
                        val active = prefs.qualityPreset == preset
                        val interaction = remember { MutableInteractionSource() }
                        val bg by animateColorAsState(
                            targetValue = if (active) colors.accentSoft else colors.ground.copy(alpha = 0.52f),
                            animationSpec = if (reduceMotion) snap() else tween(170),
                            label = "presetBg${preset.name}",
                        )
                        val borderColor by animateColorAsState(
                            targetValue = if (active) colors.accent.copy(alpha = 0.72f) else colors.glassSpecularMid,
                            animationSpec = if (reduceMotion) snap() else tween(170),
                            label = "presetBorder${preset.name}",
                        )
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = bg,
                            border = BorderStroke(1.dp, borderColor),
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 48.dp)
                                .interactivePressScale(interaction, 0.97f)
                                .clickable(interactionSource = interaction, indication = null) {
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    vm.updatePrefs { it.copy(qualityPreset = preset) }
                                },
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    preset.label,
                                    color = if (active) colors.accentInk else colors.text,
                                    fontSize = 14.5.sp,
                                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                                    modifier = Modifier.weight(1f),
                                )
                                AnimatedVisibility(
                                    visible = active,
                                    enter = if (reduceMotion) EnterTransition.None else scaleIn(spring(0.55f, 520f)) + fadeIn(tween(140)),
                                    exit = if (reduceMotion) ExitTransition.None else scaleOut(tween(100)) + fadeOut(tween(100)),
                                ) {
                                    Icon(Icons.Rounded.Check, null, tint = colors.accentInk, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                }
            }
            HorizontalDivider(color = colors.rule)
            SettingSwitchRow(
                title = "Keep stock songs",
                description = "Play Asphalt 8's built-in soundtrack alongside your custom songs. Turn this off to hear only your own music.",
                checked = prefs.keepOriginal,
                onCheckedChange = { v -> vm.updatePrefs { it.copy(keepOriginal = v) } },
            )
        }

        // 4. Backups and warnings
        SettingsGroupCard(title = "Backups and warnings") {
            SettingSwitchRow(
                title = "Auto backup",
                description = "Save a copy of your game soundpack and title files before every install, edit, removal, or repair.",
                checked = prefs.autoBackup,
                onCheckedChange = { v -> vm.updatePrefs { it.copy(autoBackup = v) } },
                extraAction = {
                    val bInteraction = remember { MutableInteractionSource() }
                    OutlinedButton(
                        onClick = { vm.createManualBackup() },
                        enabled = ui.gameDirValid && !ui.busyAction,
                        interactionSource = bInteraction,
                        shape = RoundedCornerShape(999.dp),
                        border = BorderStroke(1.dp, colors.glassSpecularTop),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        modifier = Modifier
                            .defaultMinSize(minHeight = 40.dp)
                            .interactivePressScale(bInteraction, 0.94f),
                    ) {
                        Text("Back up now", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                    }
                },
            )
            HorizontalDivider(color = colors.rule)
            SettingSwitchRow(
                title = "Unsaved playlist warning",
                description = "Ask before clearing queued songs that haven't been exported or installed yet.",
                checked = prefs.warnUnsavedPlaylist,
                onCheckedChange = { v -> vm.updatePrefs { it.copy(warnUnsavedPlaylist = v) } },
            )
        }

        // 5. Outros
        SettingsGroupCard(title = "Outros") {
            SettingSwitchRow(
                title = "Outro editor",
                description = "Show the waveform editor with trim handles and fades when setting up an outro. Turn this off to attach custom outro files directly.",
                checked = prefs.outroEditorEnabled,
                onCheckedChange = { v -> vm.updatePrefs { it.copy(outroEditorEnabled = v) } },
            )
        }

        // 6. App (Reduce motion + About the app)
        SettingsGroupCard(title = "App") {
            SettingSwitchRow(
                title = "Reduce motion",
                description = "Disable UI animations, transitions, and moving indicators across the app.",
                checked = prefs.reduceMotion,
                onCheckedChange = { v -> vm.updatePrefs { it.copy(reduceMotion = v) } },
            )
            HorizontalDivider(color = colors.rule)
            val aboutInteraction = remember { MutableInteractionSource() }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .interactivePressScale(aboutInteraction, 0.98f)
                    .clickable(interactionSource = aboutInteraction, indication = null) { vm.openAboutScreen() }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("About the app", color = colors.text, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "App version, GitHub page, open-source licenses, update check, and changelog.",
                        color = colors.muted,
                        fontSize = 14.sp,
                        lineHeight = 19.sp,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(shape = RoundedCornerShape(999.dp), color = colors.glassCardRaised) {
                        Text(
                            "v${BuildConfig.VERSION_NAME}",
                            color = colors.muted,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 4.dp),
                        )
                    }
                    Icon(Icons.Rounded.ChevronRight, null, tint = colors.muted, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}

@Composable
fun AboutScreen(
    ui: MainUiState,
    vm: MainViewModel,
    isTablet: Boolean,
    onOpenUrl: (String) -> Unit,
) {
    val colors = LocalAppColors.current
    val upd = ui.updateInfo

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = if (isTablet) 28.dp else 16.dp)
            .padding(top = 8.dp, bottom = 186.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val backInteraction = remember { MutableInteractionSource() }
            LiquidGlassCard(
                shape = RoundedCornerShape(999.dp),
                modifier = Modifier
                    .defaultMinSize(minHeight = 40.dp)
                    .interactivePressScale(backInteraction, 0.93f)
                    .clickable(interactionSource = backInteraction, indication = null) { vm.closeAboutScreen() },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = colors.text, modifier = Modifier.size(18.dp))
                    Text("Settings", color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Text("About the app", color = colors.text, fontSize = 23.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp)
        }

        // Frosted Hero Card (GitHub link belongs here ONLY, NOT inside Open-source licenses)
        LiquidGlassCard(
            shape = RoundedCornerShape(22.dp),
            elevated = true,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Image(
                    painter = painterResource(R.drawable.app_icon),
                    contentDescription = "Asphalt 8 Custom OST Patcher",
                    modifier = Modifier
                        .size(54.dp)
                        .clip(RoundedCornerShape(14.dp)),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Asphalt 8 Custom OST Patcher", color = colors.text, fontSize = 16.5.sp, fontWeight = FontWeight.Bold)
                    Text("Installed v${BuildConfig.VERSION_NAME}", color = colors.muted, fontSize = 13.5.sp)
                    Text(
                        "Add custom songs, outros, and soundpacks to Asphalt 8: Airborne.",
                        color = colors.muted,
                        fontSize = 13.5.sp,
                        lineHeight = 18.sp,
                    )
                }
                val ghInteraction = remember { MutableInteractionSource() }
                OutlinedButton(
                    onClick = { onOpenUrl(UpdateChecker.REPO_GITHUB_URL) },
                    interactionSource = ghInteraction,
                    shape = RoundedCornerShape(999.dp),
                    border = BorderStroke(1.dp, colors.glassSpecularTop),
                    contentPadding = PaddingValues(horizontal = 14.dp),
                    modifier = Modifier
                        .defaultMinSize(minHeight = 40.dp)
                        .interactivePressScale(ghInteraction, 0.94f),
                ) {
                    Text("GitHub", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // Updates & Changelog card
        SettingsGroupCard(title = "Updates and changelog") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = colors.ground.copy(alpha = 0.55f),
                    border = BorderStroke(1.dp, colors.glassSpecularMid),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when {
                                            ui.updateError != null -> colors.danger
                                            upd?.updateAvailable == true -> colors.accent
                                            upd != null -> colors.ok
                                            else -> colors.faint
                                        }
                                    )
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = when {
                                        ui.updateChecking -> "Checking for Android updates..."
                                        ui.updateError != null -> "Could not check updates"
                                        upd?.updateAvailable == true -> "Version ${upd.latestVersion} is available"
                                        upd != null -> "You're up to date"
                                        else -> "Ready to check for updates"
                                    },
                                    color = colors.text,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = ui.updateError ?: "Installed v${BuildConfig.VERSION_NAME}",
                                    color = if (ui.updateError != null) colors.danger else colors.muted,
                                    fontSize = 13.5.sp,
                                )
                            }
                        }
                        Surface(shape = RoundedCornerShape(999.dp), color = colors.glassCardRaised) {
                            Text(
                                text = "v${upd?.latestVersion ?: BuildConfig.VERSION_NAME}",
                                color = colors.accentInk,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 11.dp, vertical = 4.dp),
                            )
                        }
                    }
                }

                if (ui.downloadingUpdate) {
                    LinearProgressIndicator(
                        progress = { ui.downloadProgress.coerceIn(0.05f, 1f) },
                        color = colors.accent,
                        trackColor = colors.raised,
                        modifier = Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(999.dp)),
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val checkInteraction = remember { MutableInteractionSource() }
                    OutlinedButton(
                        onClick = { vm.checkForUpdates(silentPopup = false) },
                        enabled = !ui.updateChecking && !ui.downloadingUpdate,
                        interactionSource = checkInteraction,
                        shape = RoundedCornerShape(999.dp),
                        border = BorderStroke(1.dp, colors.glassSpecularTop),
                        modifier = Modifier
                            .defaultMinSize(minHeight = 44.dp)
                            .interactivePressScale(checkInteraction, 0.94f),
                    ) {
                        Text("Check for updates", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                    if (upd?.updateAvailable == true) {
                        val dlInteraction = remember { MutableInteractionSource() }
                        Button(
                            onClick = { vm.downloadUpdateApk() },
                            enabled = !ui.downloadingUpdate,
                            interactionSource = dlInteraction,
                            shape = RoundedCornerShape(999.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = colors.capsuleBg, contentColor = colors.capsuleInk),
                            modifier = Modifier
                                .defaultMinSize(minHeight = 44.dp)
                                .interactivePressScale(dlInteraction, 0.94f),
                        ) {
                            Text("Download and install", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                // Changelog section
                HorizontalDivider(color = colors.rule)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Changelog", color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text("v${BuildConfig.VERSION_NAME}", color = colors.faint, fontSize = 13.5.sp)
                }
                val sections = UpdateChecker.LOCAL_CHANGELOG[BuildConfig.VERSION_NAME].orEmpty()
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = colors.ground.copy(alpha = 0.55f),
                    border = BorderStroke(1.dp, colors.glassSpecularMid),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        sections.forEach { sec ->
                            Text(sec.title, color = colors.text, fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
                            sec.items.forEach { bullet ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("•", color = colors.accentInk, fontSize = 14.sp)
                                    Text(bullet, color = colors.muted, fontSize = 13.5.sp, lineHeight = 19.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Complete third-party open-source acknowledgements
        SettingsGroupCard(title = "Open-source licenses and acknowledgements") {
            UpdateChecker.OPEN_SOURCE_ACKNOWLEDGEMENTS.forEachIndexed { idx, ack ->
                if (idx > 0) HorizontalDivider(color = colors.rule)
                val ackInteraction = remember { MutableInteractionSource() }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .interactivePressScale(ackInteraction, 0.98f)
                        .clickable(interactionSource = ackInteraction, indication = null) { onOpenUrl(ack.url) }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(ack.name, color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(ack.role, color = colors.muted, fontSize = 13.5.sp, lineHeight = 19.sp)
                        Text("${ack.displayUrl} ↗", color = colors.accentInk, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.width(12.dp))
                    Surface(shape = RoundedCornerShape(9.dp), color = colors.glassCardRaised) {
                        Text(
                            ack.license,
                            color = colors.muted,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Automatic launch Update pop-up: clean footer with ONLY Later and Download and install.
 */
@Composable
fun UpdatePopupDialog(
    ui: MainUiState,
    vm: MainViewModel,
) {
    val info = ui.updateInfo ?: return
    if (!ui.showStartupUpdatePopup || !info.updateAvailable) return
    val colors = LocalAppColors.current

    AlertDialog(
        onDismissRequest = { vm.dismissUpdatePopup() },
        containerColor = colors.glassDockFallback,
        titleContentColor = colors.text,
        textContentColor = colors.muted,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Image(
                    painter = painterResource(R.drawable.app_icon),
                    contentDescription = null,
                    modifier = Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)),
                )
                Column {
                    Text("Update available", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("Asphalt 8 Custom OST Patcher v${info.latestVersion}", color = colors.muted, fontSize = 13.5.sp)
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = colors.ground.copy(alpha = 0.60f),
                    border = BorderStroke(1.dp, colors.glassSpecularMid),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Version ${info.latestVersion} is ready", color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            val sizeTxt = if (info.sizeBytes > 0) " · ${"%.1f".format(info.sizeBytes / (1024.0 * 1024.0))} MB" else ""
                            Text("Installed v${info.currentVersion}$sizeTxt", color = colors.muted, fontSize = 13.sp)
                        }
                        Surface(shape = RoundedCornerShape(999.dp), color = colors.accentSoft) {
                            Text(
                                "v${info.currentVersion} → v${info.latestVersion}",
                                color = colors.accentInk,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
                if (info.notes.isNotBlank()) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = colors.ground.copy(alpha = 0.60f),
                        border = BorderStroke(1.dp, colors.glassSpecularMid),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp),
                    ) {
                        Text(
                            text = info.notes,
                            color = colors.muted,
                            fontSize = 13.5.sp,
                            lineHeight = 19.sp,
                            modifier = Modifier.verticalScroll(rememberScrollState()).padding(14.dp),
                        )
                    }
                }
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = { vm.dismissUpdatePopup() },
                shape = RoundedCornerShape(999.dp),
                modifier = Modifier.defaultMinSize(minHeight = 42.dp),
            ) {
                Text("Later", fontSize = 14.sp)
            }
        },
        confirmButton = {
            Button(
                onClick = { vm.downloadUpdateApk() },
                shape = RoundedCornerShape(999.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.capsuleBg, contentColor = colors.capsuleInk),
                modifier = Modifier.defaultMinSize(minHeight = 42.dp),
            ) {
                Text("Download and install", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        },
    )
}

@Composable
private fun SettingsGroupCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalAppColors.current
    LiquidGlassCard(
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Text(
                text = title,
                color = colors.muted,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            HorizontalDivider(color = colors.rule)
            content()
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    extraAction: (@Composable () -> Unit)? = null,
) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = colors.text, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
            Text(description, color = colors.muted, fontSize = 14.sp, lineHeight = 20.sp)
            if (extraAction != null) {
                Spacer(Modifier.height(6.dp))
                extraAction()
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ThemeOptionChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    val reduceMotion = LocalReduceMotion.current
    val view = LocalView.current
    val interaction = remember { MutableInteractionSource() }
    val bg by animateColorAsState(
        targetValue = if (selected) colors.accentSoft else colors.ground.copy(alpha = 0.52f),
        animationSpec = if (reduceMotion) snap() else tween(170),
        label = "themeBg$label",
    )
    val border by animateColorAsState(
        targetValue = if (selected) colors.accent else colors.glassSpecularMid,
        animationSpec = if (reduceMotion) snap() else tween(170),
        label = "themeBorder$label",
    )
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = bg,
        border = BorderStroke(1.dp, border),
        modifier = modifier
            .defaultMinSize(minHeight = 46.dp)
            .interactivePressScale(interaction, 0.95f)
            .clickable(interactionSource = interaction, indication = null) {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onClick()
            },
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp)) {
            Text(
                label,
                color = if (selected) colors.accentInk else colors.muted,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
