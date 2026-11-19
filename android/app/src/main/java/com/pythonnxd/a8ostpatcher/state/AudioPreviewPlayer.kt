package com.pythonnxd.a8ostpatcher.state

import android.media.MediaPlayer
import com.pythonnxd.a8ostpatcher.engine.AudioPipeline
import com.pythonnxd.a8ostpatcher.engine.StationCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class NowPlayingState(
    val uid: String? = null,
    val title: String = "",
    val artist: String = "",
    val category: StationCategory = StationCategory.ROCK,
    val isOutro: Boolean = false,
    val isPlaying: Boolean = false,
    val isPreparing: Boolean = false,
    val isLoaded: Boolean = false,
    val positionMs: Int = 0,
    val durationMs: Int = 0,
    val loop: Boolean = false,
) {
    /**
     * Matches PC v1.4.3.1 lock contract: while a track is actively playing or paused mid-playback,
     * Now Playing remains strictly locked to that track regardless of which other row is selected.
     */
    val isLocked: Boolean get() = uid != null && (isPlaying || isLoaded)
}

class AudioPreviewPlayer(
    private val cacheDir: File,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(NowPlayingState())
    val state: StateFlow<NowPlayingState> = _state.asStateFlow()

    private var player: MediaPlayer? = null
    private var tickJob: Job? = null
    private var loadJob: Job? = null

    fun setLoop(enabled: Boolean) {
        _state.update { it.copy(loop = enabled) }
        try { player?.isLooping = enabled } catch (_: Exception) {}
    }

    /**
     * Updates displayed metadata ONLY if the edited track is the currently active track,
     * never switching Now Playing to a different row while a song is playing/loaded.
     */
    fun syncMetadataIfActive(uid: String, title: String, artist: String, category: StationCategory) {
        _state.update { cur ->
            if (cur.uid == uid) cur.copy(title = title, artist = artist, category = category)
            else cur
        }
    }

    fun togglePlay(
        uid: String,
        title: String,
        artist: String,
        category: StationCategory,
        srcFile: File,
        isOutro: Boolean = false,
        startSec: Double? = null,
        endSec: Double? = null,
        fadeInSec: Double = 0.0,
        fadeOutSec: Double = 0.0,
        onError: (String) -> Unit = {},
    ) {
        val cur = _state.value
        val hasCut = isOutro && (startSec != null || endSec != null || fadeInSec > 0.0 || fadeOutSec > 0.0)
        if (cur.uid == uid && cur.isOutro == isOutro && cur.isLoaded && !hasCut) {
            val mp = player
            if (mp != null) {
                if (mp.isPlaying) {
                    mp.pause()
                    _state.update { it.copy(isPlaying = false, positionMs = mp.currentPosition) }
                } else {
                    mp.start()
                    _state.update { it.copy(isPlaying = true) }
                    startTicking()
                }
                return
            }
        }
        if (cur.uid == uid && cur.isOutro == isOutro && hasCut && cur.isPlaying) {
            pause()
            return
        }

        loadJob?.cancel()
        releasePlayerOnly()
        _state.update {
            it.copy(
                uid = uid,
                title = title,
                artist = artist,
                category = category,
                isOutro = isOutro,
                isPlaying = false,
                isPreparing = true,
                isLoaded = false,
                positionMs = 0,
                durationMs = 0,
            )
        }

        loadJob = scope.launch {
            try {
                val playable = withContext(Dispatchers.IO) {
                    AudioPipeline.preparePlayablePreview(
                        srcFile = srcFile,
                        cacheDir = File(cacheDir, "audio_preview"),
                        startSec = startSec,
                        endSec = endSec,
                        fadeInSec = fadeInSec,
                        fadeOutSec = fadeOutSec,
                    )
                }
                val mp = MediaPlayer()
                mp.setDataSource(playable.absolutePath)
                mp.isLooping = _state.value.loop
                mp.prepare()
                val dur = mp.duration
                mp.setOnCompletionListener {
                    if (!_state.value.loop) {
                        tickJob?.cancel()
                        _state.update {
                            it.copy(isPlaying = false, isLoaded = false, uid = null, positionMs = 0)
                        }
                    }
                }
                player = mp
                mp.start()
                _state.update {
                    it.copy(
                        isPlaying = true,
                        isPreparing = false,
                        isLoaded = true,
                        durationMs = dur,
                        positionMs = 0,
                    )
                }
                startTicking()
            } catch (e: Exception) {
                _state.update {
                    it.copy(isPlaying = false, isPreparing = false, isLoaded = false, uid = null)
                }
                onError("Could not play preview: ${e.message ?: "unsupported audio"}")
            }
        }
    }

    fun toggleCurrentPlayPause() {
        val mp = player ?: return
        if (!_state.value.isLoaded) return
        if (mp.isPlaying) {
            mp.pause()
            _state.update { it.copy(isPlaying = false, positionMs = mp.currentPosition) }
        } else {
            mp.start()
            _state.update { it.copy(isPlaying = true) }
            startTicking()
        }
    }

    fun pause() {
        try {
            player?.let { mp ->
                if (mp.isPlaying) mp.pause()
                _state.update { it.copy(isPlaying = false, positionMs = mp.currentPosition) }
            }
        } catch (_: Exception) {}
    }

    fun seekToFraction(fraction: Float) {
        val mp = player ?: return
        val dur = _state.value.durationMs
        if (dur <= 0) return
        val target = (fraction.coerceIn(0f, 1f) * dur).toInt()
        try {
            mp.seekTo(target)
            _state.update { it.copy(positionMs = target) }
        } catch (_: Exception) {}
    }

    fun stopIfUidMatches(uids: Set<String>) {
        val activeUid = _state.value.uid ?: return
        if (activeUid in uids) stopAndClear()
    }

    fun stopAndClear() {
        loadJob?.cancel()
        releasePlayerOnly()
        _state.update { cur -> NowPlayingState(loop = cur.loop) }
    }

    private fun startTicking() {
        tickJob?.cancel()
        tickJob = scope.launch {
            while (isActive && _state.value.isPlaying) {
                val mp = player
                if (mp != null) {
                    try {
                        _state.update { it.copy(positionMs = mp.currentPosition, durationMs = mp.duration) }
                    } catch (_: Exception) {}
                }
                delay(120)
            }
        }
    }

    private fun releasePlayerOnly() {
        tickJob?.cancel()
        try { player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        player = null
    }

    fun release() {
        loadJob?.cancel()
        releasePlayerOnly()
    }
}
