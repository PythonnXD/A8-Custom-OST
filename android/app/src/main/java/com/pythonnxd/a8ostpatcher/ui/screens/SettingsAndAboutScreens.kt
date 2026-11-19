package com.pythonnxd.a8ostpatcher.ui.screens

import android.os.Build
import android.os.Environment
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
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
import com.pythonnxd.a8ostpatcher.ui.theme.LocalAppColors

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
    val prefs = ui.prefs

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = if (isTablet) 24.dp else 14.dp)
            .padding(top = 8.dp, bottom = 160.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Settings", color = colors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)

        // 1. Asphalt 8 Folder & Storage Access
        SettingsGroupCard(title = "Asphalt 8 folder") {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (ui.gameDirValid) "Connected" else "Not configured",
                        color = if (ui.gameDirValid) colors.ok else colors.warn,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !hasAllFilesAccess) {
                        TextButton(onClick = onRequestAllFilesAccess) {
                            Text("Grant all files access", color = colors.accentInk, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                Text(
                    prefs.gameDir.ifBlank { "Pick your unpacked Asphalt 8 folder (contains soundpack, music, and text)." },
                    color = colors.muted,
                    fontSize = 12.sp,
                )
                if (ui.gameDirHint.isNotBlank() && !ui.gameDirWritable) {
                    Text(ui.gameDirHint, color = colors.danger, fontSize = 11.5.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onChooseGameFolder,
                        shape = RoundedCornerShape(999.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.capsuleBg, contentColor = colors.capsuleInk),
                    ) {
                        Text("Choose folder", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    OutlinedButton(
                        onClick = { vm.autoDetectGameFolder() },
                        shape = RoundedCornerShape(999.dp),
                    ) {
                        Text("Auto-detect", fontSize = 12.sp)
                    }
                }
            }
        }

        // 2. Appearance (Dark, Light, Material You Dark, Material You Light)
        SettingsGroupCard(title = "Appearance") {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Theme", color = colors.text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Audio quality", color = colors.text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Choose the Musepack SV8 encoding preset used when installing songs. Valid untrimmed .mpc files are copied straight over at original volume.",
                    color = colors.muted,
                    fontSize = 12.sp,
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    QualityPreset.entries.forEach { preset ->
                        val active = prefs.qualityPreset == preset
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (active) colors.accentSoft else colors.ground,
                            border = BorderStroke(1.dp, if (active) colors.accent.copy(alpha = 0.6f) else colors.border),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { vm.updatePrefs { it.copy(qualityPreset = preset) } },
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(preset.label, color = if (active) colors.accentInk else colors.text, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
                                if (active) Icon(Icons.Rounded.Check, null, tint = colors.accentInk, modifier = Modifier.size(16.dp))
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
                    OutlinedButton(
                        onClick = { vm.createManualBackup() },
                        enabled = ui.gameDirValid && !ui.busyAction,
                        shape = RoundedCornerShape(999.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        modifier = Modifier.height(32.dp),
                    ) {
                        Text("Back up now", fontSize = 11.5.sp)
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { vm.openAboutScreen() }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("About the app", color = colors.text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "App version, GitHub page, open-source licenses, update check, and changelog.",
                        color = colors.muted,
                        fontSize = 12.sp,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(shape = RoundedCornerShape(999.dp), color = colors.raised) {
                        Text(
                            "v${BuildConfig.VERSION_NAME}",
                            color = colors.muted,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                        )
                    }
                    Icon(Icons.Rounded.ChevronRight, null, tint = colors.muted)
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
            .padding(horizontal = if (isTablet) 24.dp else 14.dp)
            .padding(top = 8.dp, bottom = 160.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = colors.surface,
                border = BorderStroke(1.dp, colors.border),
                modifier = Modifier
                    .height(34.dp)
                    .clickable { vm.closeAboutScreen() },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = colors.text, modifier = Modifier.size(16.dp))
                    Text("Settings", color = colors.text, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Text("About the app", color = colors.text, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        }

        // Hero card (GitHub link belongs here ONLY, NOT inside Open-source licenses!)
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = colors.surface,
            border = BorderStroke(1.dp, colors.border),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Image(
                    painter = painterResource(R.drawable.app_icon),
                    contentDescription = "Asphalt 8 Custom OST Patcher",
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Asphalt 8 Custom OST Patcher", color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text("Installed v${BuildConfig.VERSION_NAME}", color = colors.muted, fontSize = 12.sp)
                    Text("Add custom songs, outros, and soundpacks to Asphalt 8: Airborne.", color = colors.muted, fontSize = 11.5.sp)
                }
                OutlinedButton(
                    onClick = { onOpenUrl(UpdateChecker.REPO_GITHUB_URL) },
                    shape = RoundedCornerShape(999.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    modifier = Modifier.height(34.dp),
                ) {
                    Text("GitHub", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // Updates & Changelog card (tracks separate Android android-v* releases!)
        SettingsGroupCard(title = "Updates and changelog") {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = colors.ground,
                    border = BorderStroke(1.dp, colors.border),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(9.dp)
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
                            Column {
                                Text(
                                    text = when {
                                        ui.updateChecking -> "Checking for Android updates..."
                                        ui.updateError != null -> "Could not check updates"
                                        upd?.updateAvailable == true -> "Version ${upd.latestVersion} is available"
                                        upd != null -> "You're up to date"
                                        else -> "Ready to check for updates"
                                    },
                                    color = colors.text,
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = ui.updateError ?: "Installed v${BuildConfig.VERSION_NAME}",
                                    color = if (ui.updateError != null) colors.danger else colors.muted,
                                    fontSize = 11.5.sp,
                                )
                            }
                        }
                        Surface(shape = RoundedCornerShape(999.dp), color = colors.raised) {
                            Text(
                                text = "v${upd?.latestVersion ?: BuildConfig.VERSION_NAME}",
                                color = colors.accentInk,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                            )
                        }
                    }
                }

                if (ui.downloadingUpdate) {
                    LinearProgressIndicator(
                        progress = { ui.downloadProgress.coerceIn(0.05f, 1f) },
                        color = colors.accent,
                        trackColor = colors.raised,
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(999.dp)),
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { vm.checkForUpdates(silentPopup = false) },
                        enabled = !ui.updateChecking && !ui.downloadingUpdate,
                        shape = RoundedCornerShape(999.dp),
                    ) {
                        Text("Check for updates", fontSize = 12.sp)
                    }
                    if (upd?.updateAvailable == true) {
                        Button(
                            onClick = { vm.downloadUpdateApk() },
                            enabled = !ui.downloadingUpdate,
                            shape = RoundedCornerShape(999.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = colors.capsuleBg, contentColor = colors.capsuleInk),
                        ) {
                            Text("Download and install", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                // Changelog section
                HorizontalDivider(color = colors.rule)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Changelog", color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text("v${BuildConfig.VERSION_NAME}", color = colors.faint, fontSize = 12.sp)
                }
                val sections = UpdateChecker.LOCAL_CHANGELOG[BuildConfig.VERSION_NAME].orEmpty()
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = colors.ground,
                    border = BorderStroke(1.dp, colors.border),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        sections.forEach { sec ->
                            Text(sec.title, color = colors.text, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                            sec.items.forEach { bullet ->
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("•", color = colors.accentInk, fontSize = 12.5.sp)
                                    Text(bullet, color = colors.muted, fontSize = 12.sp, lineHeight = 17.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Complete third-party open-source acknowledgements (clickable links, NO duplicate Open buttons, NO user GitHub link!)
        SettingsGroupCard(title = "Open-source licenses and acknowledgements") {
            UpdateChecker.OPEN_SOURCE_ACKNOWLEDGEMENTS.forEachIndexed { idx, ack ->
                if (idx > 0) HorizontalDivider(color = colors.rule)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenUrl(ack.url) }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(ack.name, color = colors.text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                        Text(ack.role, color = colors.muted, fontSize = 12.sp)
                        Text("${ack.displayUrl} ↗", color = colors.accentInk, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.width(10.dp))
                    Surface(shape = RoundedCornerShape(8.dp), color = colors.raised) {
                        Text(
                            ack.license,
                            color = colors.muted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
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
        containerColor = colors.surface,
        titleContentColor = colors.text,
        textContentColor = colors.muted,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Image(
                    painter = painterResource(R.drawable.app_icon),
                    contentDescription = null,
                    modifier = Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)),
                )
                Column {
                    Text("Update available", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("Asphalt 8 Custom OST Patcher v${info.latestVersion}", color = colors.muted, fontSize = 12.sp)
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = colors.ground,
                    border = BorderStroke(1.dp, colors.border),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Version ${info.latestVersion} is ready", color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            val sizeTxt = if (info.sizeBytes > 0) " · ${"%.1f".format(info.sizeBytes / (1024.0 * 1024.0))} MB" else ""
                            Text("Installed v${info.currentVersion}$sizeTxt", color = colors.muted, fontSize = 11.5.sp)
                        }
                        Surface(shape = RoundedCornerShape(999.dp), color = colors.accentSoft) {
                            Text(
                                "v${info.currentVersion} → v${info.latestVersion}",
                                color = colors.accentInk,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                            )
                        }
                    }
                }
                if (info.notes.isNotBlank()) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = colors.ground,
                        border = BorderStroke(1.dp, colors.border),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp),
                    ) {
                        Text(
                            text = info.notes,
                            color = colors.muted,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            modifier = Modifier.verticalScroll(rememberScrollState()).padding(12.dp),
                        )
                    }
                }
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = { vm.dismissUpdatePopup() },
                shape = RoundedCornerShape(999.dp),
            ) {
                Text("Later")
            }
        },
        confirmButton = {
            Button(
                onClick = { vm.downloadUpdateApk() },
                shape = RoundedCornerShape(999.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.capsuleBg, contentColor = colors.capsuleInk),
            ) {
                Text("Download and install", fontWeight = FontWeight.SemiBold)
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
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Text(
                text = title,
                color = colors.muted,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
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
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = colors.text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
            Text(description, color = colors.muted, fontSize = 12.sp, lineHeight = 17.sp)
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
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) colors.accentSoft else colors.ground,
        border = BorderStroke(1.dp, if (selected) colors.accent else colors.border),
        modifier = modifier
            .height(38.dp)
            .clickable { onClick() },
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text(
                label,
                color = if (selected) colors.accentInk else colors.muted,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
