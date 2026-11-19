package com.pythonnxd.a8ostpatcher.engine

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class PrefsStore(context: Context) {
    private val sp = context.getSharedPreferences("a8_custom_ost_prefs", Context.MODE_PRIVATE)
    private val queueFile = File(context.filesDir, "queue_state.json")

    fun loadPrefs(): AppPrefs {
        return AppPrefs(
            themeMode = AppThemeMode.fromId(sp.getString("theme_mode", AppThemeMode.DARK.id)),
            qualityPreset = QualityPreset.fromId(sp.getString("quality_preset", QualityPreset.HIGH.id)),
            keepOriginal = sp.getBoolean("keep_original", true),
            autoBackup = sp.getBoolean("auto_backup", true),
            warnUnsavedPlaylist = sp.getBoolean("warn_unsaved_playlist", true),
            outroEditorEnabled = sp.getBoolean("outro_editor_enabled", true),
            reduceMotion = sp.getBoolean("reduce_motion", false),
            loopPlayback = sp.getBoolean("loop_playback", false),
            conflictPolicy = ConflictPolicy.fromId(sp.getString("conflict_policy", ConflictPolicy.OVERWRITE.id)),
            gameDir = sp.getString("game_dir", "") ?: "",
        )
    }

    fun savePrefs(prefs: AppPrefs) {
        sp.edit()
            .putString("theme_mode", prefs.themeMode.id)
            .putString("quality_preset", prefs.qualityPreset.id)
            .putBoolean("keep_original", prefs.keepOriginal)
            .putBoolean("auto_backup", prefs.autoBackup)
            .putBoolean("warn_unsaved_playlist", prefs.warnUnsavedPlaylist)
            .putBoolean("outro_editor_enabled", prefs.outroEditorEnabled)
            .putBoolean("reduce_motion", prefs.reduceMotion)
            .putBoolean("loop_playback", prefs.loopPlayback)
            .putString("conflict_policy", prefs.conflictPolicy.id)
            .putString("game_dir", prefs.gameDir)
            .apply()
    }

    fun saveQueue(tracks: List<QueueTrack>, dirty: Boolean) {
        try {
            val arr = JSONArray()
            for (t in tracks) {
                arr.put(JSONObject().apply {
                    put("uid", t.uid)
                    put("baseId", t.baseId)
                    put("outroId", t.outroId)
                    put("srcPath", t.srcPath)
                    put("srcName", t.srcName)
                    put("title", t.title)
                    put("artist", t.artist)
                    put("category", t.category.displayName)
                    put("selected", t.selected)
                    put("hasOutro", t.hasOutro)
                    if (t.outroSrcPath != null) put("outroSrcPath", t.outroSrcPath)
                    if (t.outroSrcName != null) put("outroSrcName", t.outroSrcName)
                    if (t.outroStartSec != null) put("outroStartSec", t.outroStartSec)
                    if (t.outroEndSec != null) put("outroEndSec", t.outroEndSec)
                    put("outroFadeIn", t.outroFadeIn)
                    put("outroFadeOut", t.outroFadeOut)
                    put("outroFadeInEnabled", t.outroFadeInEnabled)
                    put("outroFadeOutEnabled", t.outroFadeOutEnabled)
                })
            }
            val root = JSONObject().apply {
                put("dirty", dirty && tracks.isNotEmpty())
                put("tracks", arr)
            }
            queueFile.writeText(root.toString())
        } catch (_: Exception) {}
    }

    fun loadQueue(existingGameIds: Set<String>): Pair<List<QueueTrack>, Boolean> {
        if (!queueFile.isFile) return emptyList<QueueTrack>() to false
        return try {
            val root = JSONObject(queueFile.readText())
            val arr = root.optJSONArray("tracks") ?: return emptyList<QueueTrack>() to false
            val out = mutableListOf<QueueTrack>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val srcPath = o.optString("srcPath")
                if (srcPath.isBlank() || !File(srcPath).isFile) continue
                val outroPath = o.optString("outroSrcPath").takeIf { it.isNotBlank() && File(it).isFile }
                val hasOutro = o.optBoolean("hasOutro", false) && (outroPath != null || o.has("outroStartSec"))
                val baseId = o.optString("baseId")
                out.add(
                    QueueTrack(
                        uid = o.optString("uid").ifBlank { "imp_${i + 1}_$baseId" },
                        baseId = baseId,
                        outroId = o.optString("outroId", "m_outro_${baseId.removePrefix("m_")}"),
                        srcPath = srcPath,
                        srcName = o.optString("srcName", File(srcPath).name),
                        title = o.optString("title", "Untitled"),
                        artist = o.optString("artist", "Custom Artist"),
                        category = StationCategory.fromString(o.optString("category")),
                        selected = o.optBoolean("selected", true),
                        conflict = baseId in existingGameIds,
                        hasOutro = hasOutro,
                        outroSrcPath = outroPath,
                        outroSrcName = outroPath?.let { File(it).name },
                        outroStartSec = if (o.has("outroStartSec") && !o.isNull("outroStartSec")) o.optDouble("outroStartSec") else null,
                        outroEndSec = if (o.has("outroEndSec") && !o.isNull("outroEndSec")) o.optDouble("outroEndSec") else null,
                        outroFadeIn = o.optDouble("outroFadeIn", 0.0),
                        outroFadeOut = o.optDouble("outroFadeOut", 0.0),
                        outroFadeInEnabled = o.optBoolean("outroFadeInEnabled", false),
                        outroFadeOutEnabled = o.optBoolean("outroFadeOutEnabled", false),
                    )
                )
            }
            out to (root.optBoolean("dirty", false) && out.isNotEmpty())
        } catch (_: Exception) {
            emptyList<QueueTrack>() to false
        }
    }
}
