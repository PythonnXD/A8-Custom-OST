package com.pythonnxd.a8ostpatcher.engine

import com.pythonnxd.a8ostpatcher.privileged.GameAccessMode

enum class StationCategory(val displayName: String, val badgeText: String, val eventLabel: String) {
    ROCK("Rock", "ROCK", "ev_radio_rock"),
    ELECTRO("Electro", "ELECTRO", "ev_radio_electro"),
    DUBSTEP("Dubstep", "BASS", "ev_radio_dubstep"),
    MENU("Menu", "MENU", "ev_menu_music");

    companion object {
        fun fromString(raw: String?): StationCategory {
            return when (raw?.trim()?.lowercase()) {
                "electro", "electronic" -> ELECTRO
                "dubstep", "bass" -> DUBSTEP
                "menu", "ui" -> MENU
                else -> ROCK
            }
        }
    }
}

enum class QualityPreset(
    val id: String,
    val label: String,
    val sampleRate: Int,
    val mpcQuality: Float,
) {
    HIGH("high", "High (44.1 kHz · ~240 kbps)", 44100, 7.0f),
    ULTRA("ultra", "Ultra (44.1 kHz · ~270 kbps)", 44100, 8.0f),
    STANDARD("standard", "Standard (32 kHz · ~210 kbps)", 32000, 6.0f),
    COMPACT("compact", "Compact (32 kHz · ~130 kbps)", 32000, 4.0f);

    companion object {
        fun fromId(raw: String?): QualityPreset {
            val k = raw?.trim()?.lowercase()
            if (k == "stock") return COMPACT
            return entries.firstOrNull { it.id == k } ?: HIGH
        }
    }
}

enum class AppThemeMode(val id: String, val label: String) {
    DARK("dark", "Dark"),
    LIGHT("light", "Light"),
    MATERIAL_YOU_DARK("material_you_dark", "Material You Dark"),
    MATERIAL_YOU_LIGHT("material_you_light", "Material You Light");

    val isLight: Boolean
        get() = this == LIGHT || this == MATERIAL_YOU_LIGHT

    val isMaterialYou: Boolean
        get() = this == MATERIAL_YOU_DARK || this == MATERIAL_YOU_LIGHT

    companion object {
        fun fromId(raw: String?): AppThemeMode =
            entries.firstOrNull { it.id == raw } ?: DARK
    }
}

enum class ConflictPolicy(val id: String, val label: String) {
    OVERWRITE("overwrite", "Overwrite"),
    SKIP("skip", "Skip");

    companion object {
        fun fromId(raw: String?): ConflictPolicy =
            if (raw == "skip") SKIP else OVERWRITE
    }
}

data class AudioProfile(
    val durationSec: Double = 0.0,
    val peakDb: Double? = null,
    val rmsDb: Double? = null,
    val clipRatio: Double = 0.0,
    val badge: String = "Unknown",
    val peaks: List<Float> = emptyList(),
)

data class QueueTrack(
    val uid: String,
    val baseId: String,
    val outroId: String,
    val srcPath: String,
    val srcName: String,
    val title: String,
    val artist: String,
    val category: StationCategory = StationCategory.ROCK,
    val selected: Boolean = false,
    val conflict: Boolean = false,
    val hasOutro: Boolean = false,
    val outroSrcPath: String? = null,
    val outroSrcName: String? = null,
    val outroStartSec: Double? = null,
    val outroEndSec: Double? = null,
    val outroFadeIn: Double = 0.0,
    val outroFadeOut: Double = 0.0,
    val outroFadeInEnabled: Boolean = false,
    val outroFadeOutEnabled: Boolean = false,
    val profile: AudioProfile? = null,
)

data class InstalledTrack(
    val uid: String,
    val baseId: String,
    val title: String,
    val artist: String,
    val category: StationCategory,
    val rawCategoryName: String,
    val mainFile: String,
    val mainPath: String,
    val mainExists: Boolean,
    val hasOutro: Boolean,
    val outroId: String?,
    val outroFile: String?,
    val outroPath: String?,
    val outroExists: Boolean,
    val selected: Boolean = false,
    val dirty: Boolean = false,
)

data class BackupEntry(
    val id: String,
    val path: String,
    val createdAt: String,
    val reason: String,
    val hasSoundpack: Boolean,
    val textsCount: Int,
    val sizeBytes: Long,
    val mtimeMs: Long,
)

data class MissingFileIssue(val label: String, val filename: String, val isOutro: Boolean)
data class OrphanFileIssue(val filename: String, val path: String, val sizeBytes: Long)
data class BrokenPlaylistRef(val event: String, val label: String)
data class MissingTextIssue(val baseId: String, val key: String)

data class HealthAudit(
    val healthy: Boolean,
    val issueCount: Int,
    val totalSounds: Int,
    val totalEvents: Int,
    val playlistTracks: Int,
    val missingFiles: List<MissingFileIssue>,
    val orphanFiles: List<OrphanFileIssue>,
    val orphanTotalBytes: Long,
    val brokenPlaylistRefs: List<BrokenPlaylistRef>,
    val missingEvents: List<String>,
    val missingTexts: List<MissingTextIssue>,
)

data class ModProfile(
    val id: String,
    val name: String,
    val createdAt: String,
    val trackCount: Int,
    val textFiles: Int,
    val sizeBytes: Long,
)

data class UpdateInfo(
    val currentVersion: String,
    val latestVersion: String,
    val tagName: String,
    val updateAvailable: Boolean,
    val downloadUrl: String,
    val releaseUrl: String,
    val sizeBytes: Long,
    val publishedAt: String,
    val notes: String,
)

data class ReleaseNoteSection(
    val title: String,
    val items: List<String>,
)

data class OpenSourceAck(
    val name: String,
    val role: String,
    val license: String,
    val displayUrl: String,
    val url: String,
)

data class AppPrefs(
    val themeMode: AppThemeMode = AppThemeMode.DARK,
    val qualityPreset: QualityPreset = QualityPreset.HIGH,
    val keepOriginal: Boolean = true,
    val autoBackup: Boolean = true,
    val warnUnsavedPlaylist: Boolean = true,
    val outroEditorEnabled: Boolean = true,
    val showNowPlayingBar: Boolean = true,
    val reduceMotion: Boolean = false,
    val loopPlayback: Boolean = false,
    val conflictPolicy: ConflictPolicy = ConflictPolicy.OVERWRITE,
    val gameDir: String = "",
    val accessMode: GameAccessMode = GameAccessMode.STANDARD,
)

data class InstallProgress(
    val active: Boolean = false,
    val current: Int = 0,
    val total: Int = 0,
    val stage: String = "",
    val done: Boolean = false,
    val error: String? = null,
    val summary: String? = null,
)
