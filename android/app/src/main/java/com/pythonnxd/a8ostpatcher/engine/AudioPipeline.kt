package com.pythonnxd.a8ostpatcher.engine

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sqrt

data class DecodedPcm(
    val samples: ShortArray, // interleaved stereo or mono 16-bit PCM
    val sampleRate: Int,
    val channels: Int,
) {
    val frameCount: Int get() = if (channels > 0) samples.size / channels else 0
    val durationSec: Double get() = if (sampleRate > 0) frameCount.toDouble() / sampleRate.toDouble() else 0.0
}

object AudioPipeline {
    val AUDIO_EXTENSIONS = setOf(".mpc", ".ogg", ".mp3", ".wav", ".flac", ".m4a", ".aac")
    val NATIVE_PLAYABLE_EXTS = setOf(".mp3", ".ogg", ".wav", ".flac", ".m4a", ".aac")

    private val pcmCacheLock = Any()
    private var lastDecodedKey: String? = null
    private var lastDecodedPcm: DecodedPcm? = null

    private fun decodeToPcmCached(srcFile: File, tempDir: File): DecodedPcm {
        val key = "${srcFile.absolutePath}|${srcFile.lastModified()}|${srcFile.length()}"
        synchronized(pcmCacheLock) {
            if (lastDecodedKey == key) {
                lastDecodedPcm?.let { return it }
            }
        }
        val decoded = decodeToPcm(srcFile, tempDir)
        // Cache up to ~32M shorts (~64 MB, ~6 min 44.1kHz stereo) so repeated outro trim previews are instant
        if (decoded.samples.size <= 32_000_000) {
            synchronized(pcmCacheLock) {
                lastDecodedKey = key
                lastDecodedPcm = decoded
            }
        }
        return decoded
    }

    fun sanitizeId(rawName: String, prefix: String = "m_"): String {
        val base = File(rawName).nameWithoutExtension
        val stripped = when {
            base.startsWith("m_outro_", ignoreCase = true) -> base.substring(8)
            base.startsWith("m_", ignoreCase = true) -> base.substring(2)
            else -> base
        }
        var safe = stripped.replace(Regex("[^a-zA-Z0-9_]"), "_").lowercase()
        if (safe.replace(Regex("[^a-z0-9]"), "").isEmpty()) {
            val md5 = MessageDigest.getInstance("MD5")
                .digest(stripped.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .take(8)
            safe = "track_$md5"
        }
        return "$prefix$safe"
    }

    fun formatDurationMs(seconds: Double): String {
        val msTotal = max(0L, round(seconds * 1000.0).toLong())
        val mins = msTotal / 60000L
        val rem = msTotal % 60000L
        val secs = rem / 1000L
        val ms = rem % 1000L
        return "%d:%02d.%03d".format(mins, secs, ms)
    }

    fun formatClock(seconds: Double): String {
        val s = max(0, seconds.toInt())
        return "%d:%02d".format(s / 60, s % 60)
    }

    fun extractMetadata(file: File, defaultArtist: String = "Custom Artist"): Pair<String, String> {
        val stem = file.nameWithoutExtension
        val clean = when {
            stem.startsWith("m_outro_", ignoreCase = true) -> stem.substring(8)
            stem.startsWith("m_", ignoreCase = true) -> stem.substring(2)
            else -> stem
        }
        var title = clean.replace('_', ' ').trim().split(Regex("\\s+"))
            .joinToString(" ") { w -> w.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } }
            .ifBlank { "Untitled" }
        var artist = defaultArtist

        if (!file.name.lowercase().endsWith(".mpc")) {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(file.absolutePath)
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim()
                    ?.takeIf { it.isNotEmpty() }?.let { title = it }
                val tagArtist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)?.trim()
                        ?.takeIf { it.isNotEmpty() }
                if (tagArtist != null) artist = tagArtist
            } catch (_: Exception) {
            } finally {
                try { mmr.release() } catch (_: Exception) {}
            }
        }
        return title to artist
    }

    fun probeDuration(file: File): Double? {
        if (!file.isFile) return null
        if (file.name.lowercase().endsWith(".mpc")) {
            MpcHeaderParser.parseHeader(file)?.let { return it.durationSec }
        }
        if (file.name.lowercase().endsWith(".wav")) {
            readWavDuration(file)?.let { return it }
        }
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(file.absolutePath)
            val ms = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            ms?.let { it / 1000.0 }
        } catch (_: Exception) {
            null
        } finally {
            try { mmr.release() } catch (_: Exception) {}
        }
    }

    fun readWavDuration(file: File): Double? {
        return try {
            FileInputStream(file).use { fis ->
                val hdr = ByteArray(12)
                if (fis.read(hdr) != 12) return null
                if (String(hdr, 0, 4, Charsets.US_ASCII) != "RIFF" || String(hdr, 8, 4, Charsets.US_ASCII) != "WAVE") return null
                var sr = 0
                var ch = 0
                var bps = 0
                val chdr = ByteArray(8)
                while (fis.read(chdr) == 8) {
                    val cid = String(chdr, 0, 4, Charsets.US_ASCII)
                    val csz = ByteBuffer.wrap(chdr, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                    if (cid == "fmt " && csz >= 16) {
                        val fmt = ByteArray(16)
                        if (fis.read(fmt) != 16) return null
                        val bb = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                        bb.short // audioFmt
                        ch = bb.short.toInt() and 0xFFFF
                        sr = bb.int
                        bb.int // byteRate
                        bb.short // blockAlign
                        bps = bb.short.toInt() and 0xFFFF
                        val skip = csz - 16 + (csz and 1L)
                        if (skip > 0) fis.skip(skip)
                    } else if (cid == "data" && sr > 0 && ch > 0 && bps > 0) {
                        val bytesPerFrame = ch * (bps / 8)
                        if (bytesPerFrame <= 0) return null
                        return (csz / bytesPerFrame).toDouble() / sr.toDouble()
                    } else {
                        val skip = csz + (csz and 1L)
                        if (skip > 0) fis.skip(skip)
                    }
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun readPcm16Wav(file: File): DecodedPcm {
        val bytes = file.readBytes()
        require(bytes.size >= 44) { "Invalid WAV file: ${file.name}" }
        require(String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE") {
            "Not a WAVE file: ${file.name}"
        }
        var offset = 12
        var sr = 44100
        var ch = 2
        var bps = 16
        while (offset + 8 <= bytes.size) {
            val cid = String(bytes, offset, 4, Charsets.US_ASCII)
            val csz = ByteBuffer.wrap(bytes, offset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val dataStart = offset + 8
            if (cid == "fmt " && csz >= 16 && dataStart + 16 <= bytes.size) {
                val bb = ByteBuffer.wrap(bytes, dataStart, 16).order(ByteOrder.LITTLE_ENDIAN)
                bb.short
                ch = bb.short.toInt() and 0xFFFF
                sr = bb.int
                bb.int
                bb.short
                bps = bb.short.toInt() and 0xFFFF
            } else if (cid == "data") {
                val avail = min(max(0, csz), bytes.size - dataStart)
                val shortCount = avail / 2
                val out = ShortArray(shortCount)
                val bb = ByteBuffer.wrap(bytes, dataStart, shortCount * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until shortCount) out[i] = bb.short
                return DecodedPcm(out, sr, max(1, ch))
            }
            val step = max(0, csz) + (csz and 1)
            val next = dataStart + step
            if (next <= offset) break
            offset = next
        }
        throw IllegalArgumentException("WAV missing data chunk: ${file.name}")
    }

    fun writePcm16Wav(pcm: DecodedPcm, dstFile: File) {
        dstFile.parentFile?.mkdirs()
        val dataBytes = pcm.samples.size * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataBytes)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)
            putShort(pcm.channels.toShort())
            putInt(pcm.sampleRate)
            putInt(pcm.sampleRate * pcm.channels * 2)
            putShort((pcm.channels * 2).toShort())
            putShort(16)
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataBytes)
        }.array()

        BufferedOutputStream(FileOutputStream(dstFile), 65536).use { out ->
            out.write(header)
            val chunk = ByteArray(16384)
            var idx = 0
            while (idx < pcm.samples.size) {
                val n = min(chunk.size / 2, pcm.samples.size - idx)
                val bb = ByteBuffer.wrap(chunk, 0, n * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until n) bb.putShort(pcm.samples[idx + i])
                out.write(chunk, 0, n * 2)
                idx += n
            }
        }
    }

    /**
     * Decodes any audio file (.mpc via native libmpcdec, .wav directly, or .mp3/.ogg/.flac/.m4a/.aac
     * via Android MediaExtractor + MediaCodec) into full-stream 16-bit PCM without truncation.
     */
    fun decodeToPcm(srcFile: File, tempDir: File): DecodedPcm {
        val low = srcFile.name.lowercase()
        if (low.endsWith(".mpc")) {
            val tmpWav = File(tempDir, "mpc_dec_${System.nanoTime()}.wav")
            try {
                val rc = NativeMpcCodec.decodeMpcToWav(srcFile.absolutePath, tmpWav.absolutePath)
                if (rc != 0 || !tmpWav.isFile) {
                    throw IllegalStateException("Failed to decode Musepack file (code $rc): ${srcFile.name}")
                }
                return readPcm16Wav(tmpWav)
            } finally {
                tmpWav.delete()
            }
        }
        if (low.endsWith(".wav")) {
            try {
                return readPcm16Wav(srcFile)
            } catch (_: Exception) {
                // Fall through to MediaCodec for 24-bit/float WAV
            }
        }
        return decodeWithMediaCodec(srcFile)
    }

    private fun decodeWithMediaCodec(srcFile: File): DecodedPcm {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(srcFile.absolutePath)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) {
                throw IllegalArgumentException("No audio stream found in ${srcFile.name}")
            }
            extractor.selectTrack(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            var sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            var channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val chunks = ArrayList<ShortArray>()
            var totalShorts = 0
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)!!
                        val read = extractor.readSampleData(inBuf, 0)
                        if (read < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, read, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFmt = codec.outputFormat
                        if (outFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (outFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (outFmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) pcmEncoding = outFmt.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    }
                    outIdx >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && info.size > 0) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            val arr = if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                val fCount = info.size / 4
                                val fb = outBuf.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                                ShortArray(fCount) {
                                    (fb.get() * 32767f).coerceIn(-32768f, 32767f).toInt().toShort()
                                }
                            } else {
                                val count = info.size / 2
                                val a = ShortArray(count)
                                outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(a)
                                a
                            }
                            chunks.add(arr)
                            totalShorts += arr.size
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                }
            }

            val merged = ShortArray(totalShorts)
            var pos = 0
            for (c in chunks) {
                System.arraycopy(c, 0, merged, pos, c.size)
                pos += c.size
            }
            return DecodedPcm(merged, sampleRate, max(1, channels))
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    /**
     * Trims, converts to stereo, resamples to targetRate, and applies optional unity-volume linear fades
     * with ZERO loudnorm dynamic compression.
     */
    fun processPcm(
        src: DecodedPcm,
        targetRate: Int,
        startSec: Double? = null,
        endSec: Double? = null,
        fadeInSec: Double = 0.0,
        fadeOutSec: Double = 0.0,
    ): DecodedPcm {
        val totalFrames = src.frameCount
        if (totalFrames <= 0) throw IllegalStateException("Decoded audio is empty.")

        val sFrame = if (startSec != null && startSec > 0.005) {
            min(totalFrames - 1, max(0, round(startSec * src.sampleRate).toInt()))
        } else 0
        val eFrame = if (endSec != null && endSec > 0.0) {
            min(totalFrames, max(sFrame + 1, round(endSec * src.sampleRate).toInt()))
        } else totalFrames

        val sliceFrames = max(1, eFrame - sFrame)
        val outFrames = if (src.sampleRate == targetRate) {
            sliceFrames
        } else {
            max(1, round(sliceFrames.toDouble() * targetRate.toDouble() / src.sampleRate.toDouble()).toInt())
        }

        val stereo = ShortArray(outFrames * 2)
        val srcCh = src.channels
        val ratio = src.sampleRate.toDouble() / targetRate.toDouble()

        for (i in 0 until outFrames) {
            val pos = sFrame + i * ratio
            val i0 = min(eFrame - 1, floor(pos).toInt())
            val i1 = min(eFrame - 1, i0 + 1)
            val frac = (pos - i0).toFloat()

            val l0 = src.samples[i0 * srcCh].toFloat()
            val r0 = if (srcCh >= 2) src.samples[i0 * srcCh + 1].toFloat() else l0
            val l1 = src.samples[i1 * srcCh].toFloat()
            val r1 = if (srcCh >= 2) src.samples[i1 * srcCh + 1].toFloat() else l1

            var l = l0 + (l1 - l0) * frac
            var r = r0 + (r1 - r0) * frac

            if (l > 32767f) l = 32767f else if (l < -32768f) l = -32768f
            if (r > 32767f) r = 32767f else if (r < -32768f) r = -32768f

            stereo[i * 2] = l.toInt().toShort()
            stereo[i * 2 + 1] = r.toInt().toShort()
        }

        val clipDur = outFrames.toDouble() / targetRate.toDouble()
        if (fadeInSec > 0.0) {
            val effFi = min(fadeInSec, max(0.05, clipDur * 0.45))
            val fiFrames = min(outFrames, max(1, round(effFi * targetRate).toInt()))
            for (i in 0 until fiFrames) {
                val gain = i.toFloat() / fiFrames.toFloat()
                stereo[i * 2] = (stereo[i * 2] * gain).toInt().toShort()
                stereo[i * 2 + 1] = (stereo[i * 2 + 1] * gain).toInt().toShort()
            }
        }
        if (fadeOutSec > 0.0) {
            val effFo = min(fadeOutSec, max(0.05, clipDur * 0.5))
            val foFrames = min(outFrames, max(1, round(effFo * targetRate).toInt()))
            val startIdx = max(0, outFrames - foFrames)
            for (i in startIdx until outFrames) {
                val gain = (outFrames - 1 - i).toFloat() / foFrames.toFloat()
                stereo[i * 2] = (stereo[i * 2] * gain).toInt().toShort()
                stereo[i * 2 + 1] = (stereo[i * 2 + 1] * gain).toInt().toShort()
            }
        }

        return DecodedPcm(stereo, targetRate, 2)
    }

    /**
     * Converts srcFile to Musepack SV8 (.mpc) at original mastered volume with millisecond duration verification.
     * Valid untrimmed .mpc files without fades are direct-copied cleanly with zero re-encoding.
     */
    fun convertToMpc(
        srcFile: File,
        dstFile: File,
        tempDir: File,
        qualityPreset: QualityPreset = QualityPreset.HIGH,
        startSec: Double? = null,
        endSec: Double? = null,
        fadeInSec: Double = 0.0,
        fadeOutSec: Double = 0.0,
    ) {
        dstFile.parentFile?.mkdirs()
        tempDir.mkdirs()

        val totalDur = probeDuration(srcFile)
        val hasStartTrim = startSec != null && startSec > 0.005
        val hasEndTrim = endSec != null && endSec > 0.0 && (totalDur == null || endSec < totalDur - 0.02)
        val hasCustom = hasStartTrim || hasEndTrim || fadeInSec > 0.0 || fadeOutSec > 0.0

        val stagedMpc = File(tempDir, "staged_${System.nanoTime()}.mpc")
        val stagedWav = File(tempDir, "staged_${System.nanoTime()}.wav")
        try {
            if (!hasCustom && MpcHeaderParser.isValidMpc8(srcFile)) {
                val srcDur = MpcHeaderParser.parseHeader(srcFile)?.durationSec
                srcFile.copyTo(stagedMpc, overwrite = true)
                if (srcDur != null) {
                    val (ok, diffMs) = MpcHeaderParser.verifyDurationMatch(srcDur, stagedMpc, toleranceMs = 1.5)
                    if (!ok) {
                        throw IllegalStateException("Staged .mpc copy duration mismatch (${"%.1f".format(diffMs)} ms) for ${srcFile.name}")
                    }
                }
                stagedMpc.copyTo(dstFile, overwrite = true)
                return
            }

            val decoded = decodeToPcm(srcFile, tempDir)
            val processed = processPcm(
                src = decoded,
                targetRate = qualityPreset.sampleRate,
                startSec = if (hasStartTrim) startSec else null,
                endSec = if (hasEndTrim) endSec else null,
                fadeInSec = fadeInSec,
                fadeOutSec = fadeOutSec,
            )
            writePcm16Wav(processed, stagedWav)

            val rc = NativeMpcCodec.encodeWavToMpc(
                stagedWav.absolutePath,
                stagedMpc.absolutePath,
                qualityPreset.mpcQuality,
            )
            if (rc != 0 || !stagedMpc.isFile) {
                throw IllegalStateException("Musepack SV8 encode failed (code $rc) for ${srcFile.name}")
            }

            val (ok, diffMs) = MpcHeaderParser.verifyDurationMatch(processed.durationSec, stagedMpc, toleranceMs = 25.0)
            if (!ok) {
                throw IllegalStateException(
                    "Converted duration mismatch for ${srcFile.name}: delta ${"%.1f".format(diffMs)} ms"
                )
            }
            stagedMpc.copyTo(dstFile, overwrite = true)
        } finally {
            stagedWav.delete()
            stagedMpc.delete()
        }
    }

    fun analyzeAudioProfile(srcFile: File, tempDir: File, numBins: Int = 96, includePeaks: Boolean = true): AudioProfile {
        return try {
            val decoded = decodeToPcmCached(srcFile, tempDir)
            val n = decoded.samples.size
            if (n == 0) {
                return AudioProfile(durationSec = 0.0, peakDb = -96.0, rmsDb = -96.0, badge = "Silent")
            }
            var maxAbs = 0
            var sumSq = 0.0
            var clipCount = 0
            val stride = max(1, n / 240_000)
            var sampled = 0
            var i = 0
            while (i < n) {
                val s = decoded.samples[i].toInt()
                val a = abs(s)
                if (a > maxAbs) maxAbs = a
                sumSq += s.toDouble() * s.toDouble()
                if (a >= 32400) clipCount++
                sampled++
                i += stride
            }

            val peakLin = maxAbs / 32768.0
            val peakDb = round(20.0 * log10(max(peakLin, 1e-5)) * 10.0) / 10.0
            val rmsLin = sqrt(sumSq / max(1, sampled)) / 32768.0
            val rmsDb = round(20.0 * log10(max(rmsLin, 1e-5)) * 10.0) / 10.0
            val clipRatio = clipCount.toDouble() / max(1, sampled)

            val badge = when {
                peakDb <= -60.0 || rmsDb <= -55.0 -> "Silent"
                peakDb >= -0.3 && clipRatio >= 0.002 -> "Clip risk"
                rmsDb >= -9.0 || (peakDb >= -0.5 && rmsDb >= -11.0) -> "Hot mix"
                rmsDb <= -24.0 -> "Quiet"
                else -> "Balanced"
            }

            val peaks = if (includePeaks && numBins > 0) {
                val ch = decoded.channels
                val frames = decoded.frameCount
                List(numBins) { bin ->
                    val f0 = (bin.toLong() * frames / numBins).toInt()
                    val f1 = max(f0 + 1, ((bin + 1).toLong() * frames / numBins).toInt())
                    val step = max(1, (f1 - f0) / 128)
                    var segMax = 0
                    var f = f0
                    while (f < min(f1, frames)) {
                        val a = abs(decoded.samples[f * ch].toInt())
                        if (a > segMax) segMax = a
                        f += step
                    }
                    min(1.0f, segMax / 32768f)
                }
            } else emptyList()

            AudioProfile(
                durationSec = decoded.durationSec,
                peakDb = peakDb,
                rmsDb = rmsDb,
                clipRatio = clipRatio,
                badge = badge,
                peaks = peaks,
            )
        } catch (_: Exception) {
            AudioProfile(durationSec = probeDuration(srcFile) ?: 0.0, badge = "Unknown")
        }
    }

    fun preparePlayablePreview(
        srcFile: File,
        cacheDir: File,
        startSec: Double? = null,
        endSec: Double? = null,
        fadeInSec: Double = 0.0,
        fadeOutSec: Double = 0.0,
    ): File {
        cacheDir.mkdirs()
        val ext = "." + srcFile.extension.lowercase()
        val hasTrimOrFade = (startSec != null && startSec > 0.005) || endSec != null || fadeInSec > 0.0 || fadeOutSec > 0.0
        if (!hasTrimOrFade && ext in NATIVE_PLAYABLE_EXTS) {
            return srcFile
        }
        val sig = "${srcFile.absolutePath}|${srcFile.lastModified()}|${srcFile.length()}|$startSec|$endSec|$fadeInSec|$fadeOutSec"
        val hash = MessageDigest.getInstance("MD5").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }.take(14)
        val outWav = File(cacheDir, "prev_$hash.wav")
        if (outWav.isFile && outWav.length() > 44) return outWav

        val decoded = decodeToPcmCached(srcFile, cacheDir)
        val processed = if (hasTrimOrFade) {
            processPcm(decoded, 44100, startSec, endSec, fadeInSec, fadeOutSec)
        } else {
            decoded
        }
        val tmp = File(cacheDir, "prev_${hash}.tmp.wav")
        writePcm16Wav(processed, tmp)
        if (outWav.exists()) outWav.delete()
        tmp.renameTo(outWav)
        return outWav
    }

    fun copyUriToImportCache(context: Context, uri: Uri, cacheRoot: File): File? {
        val importDir = File(cacheRoot, "imported_audio").apply { mkdirs() }
        var displayName = "track_${System.currentTimeMillis()}.mp3"
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) c.getString(idx)?.takeIf { it.isNotBlank() }?.let { displayName = it }
            }
        }
        val safeName = displayName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val hash = MessageDigest.getInstance("MD5").digest(uri.toString().toByteArray()).joinToString("") { "%02x".format(it) }.take(8)
        val sub = File(importDir, hash).apply { mkdirs() }
        val dst = File(sub, safeName)
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(dst).use { output -> input.copyTo(output) }
        } ?: return null
        return dst
    }

    fun scanSources(
        filesOrDirs: List<File>,
        existingGameIds: Set<String>,
        packExtractRoot: File,
        uidGenerator: () -> String,
    ): List<QueueTrack> {
        val expanded = mutableListOf<File>()
        for (f in filesOrDirs) {
            if (f.isFile && f.extension.equals("zip", ignoreCase = true)) {
                extractModPackZip(f, packExtractRoot)?.let { expanded.add(it) }
            } else if (f.exists()) {
                expanded.add(f)
            }
        }

        val tracksByPath = LinkedHashMap<String, QueueTrack>()
        val looseOutros = HashMap<String, File>()
        val manifestDirs = mutableListOf<File>()

        fun addTrack(file: File, category: StationCategory, outroFile: File?) {
            val keyPath = file.absolutePath.lowercase()
            if (keyPath in tracksByPath) return
            val baseId = sanitizeId(file.name, "m_")
            val stripped = baseId.removePrefix("m_")
            val (title, artist) = extractMetadata(file)
            tracksByPath[keyPath] = QueueTrack(
                uid = uidGenerator(),
                baseId = baseId,
                outroId = "m_outro_$stripped",
                srcPath = file.absolutePath,
                srcName = file.name,
                title = title,
                artist = artist,
                category = category,
                selected = false,
                conflict = baseId in existingGameIds,
                hasOutro = outroFile != null,
                outroSrcPath = outroFile?.absolutePath,
                outroSrcName = outroFile?.name,
            )
        }

        for (item in expanded) {
            if (item.isDirectory) {
                if (File(item, "pack_manifest.json").isFile) manifestDirs.add(item)
                val subdirs = item.listFiles { d -> d.isDirectory }.orEmpty()
                val subMap = subdirs.associateBy { it.name.lowercase() }
                val catNames = listOf("rock", "electro", "dubstep", "menu")
                val hasStructured = catNames.any { it in subMap } || "outro" in subMap
                if (hasStructured) {
                    val outroMap = HashMap<String, File>()
                    subMap["outro"]?.listFiles()?.forEach { f ->
                        if (f.isFile && isAudioFile(f)) {
                            outroMap[sanitizeId(f.name, "")] = f
                        }
                    }
                    for (cn in catNames) {
                        val cdir = subMap[cn] ?: continue
                        val cat = StationCategory.fromString(cn)
                        cdir.listFiles()?.sortedBy { it.name.lowercase() }?.forEach { f ->
                            if (f.isFile && isAudioFile(f)) {
                                val normKey = sanitizeId(f.name, "")
                                addTrack(f, cat, outroMap[normKey])
                            }
                        }
                    }
                } else {
                    val dirCat = StationCategory.fromString(item.name)
                    val audioFiles = item.listFiles()?.filter { it.isFile && isAudioFile(it) }
                        ?.sortedBy { it.name.lowercase() }.orEmpty()
                    val mains = mutableListOf<File>()
                    for (af in audioFiles) {
                        if (af.name.lowercase().startsWith("m_outro_")) {
                            looseOutros[sanitizeId(af.name, "")] = af
                        } else {
                            mains.add(af)
                        }
                    }
                    for (mf in mains) {
                        val key = sanitizeId(mf.name, "")
                        addTrack(mf, dirCat, looseOutros[key])
                    }
                }
            } else if (item.isFile && isAudioFile(item)) {
                if (item.name.lowercase().startsWith("m_outro_")) {
                    looseOutros[sanitizeId(item.name, "")] = item
                } else {
                    val parentCat = StationCategory.fromString(item.parentFile?.name)
                    val key = sanitizeId(item.name, "")
                    addTrack(item, parentCat, looseOutros[key])
                }
            }
        }

        // Reconcile loose outros and pack_manifest.json overrides
        var result = tracksByPath.values.map { tr ->
            if (!tr.hasOutro) {
                val key = tr.baseId.removePrefix("m_")
                val matched = looseOutros[key]
                if (matched != null) {
                    tr.copy(hasOutro = true, outroSrcPath = matched.absolutePath, outroSrcName = matched.name)
                } else tr
            } else tr
        }

        if (manifestDirs.isNotEmpty()) {
            result = applyPackManifests(result, manifestDirs)
        }
        return result
    }

    private fun isAudioFile(f: File): Boolean = ("." + f.extension.lowercase()) in AUDIO_EXTENSIONS

    private fun extractModPackZip(zipFile: File, rootDir: File): File? {
        return try {
            val sig = "${zipFile.absolutePath}|${zipFile.length()}|${zipFile.lastModified()}"
            val hash = MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }.take(14)
            val targetDir = File(rootDir, hash)
            val marker = File(targetDir, ".extracted_ok")
            if (marker.isFile) return resolvePackTarget(targetDir)

            targetDir.mkdirs()
            val canonRoot = targetDir.canonicalPath
            var hasRelevant = false
            ZipFile(zipFile).use { zf ->
                val entries = java.util.Collections.list(zf.entries()).filter { !it.isDirectory }
                if (entries.isEmpty() || entries.size > 1000) return null
                for (entry in entries) {
                    val rel = entry.name.replace('\\', '/').trimStart('/')
                    val parts = rel.split('/').filter { it.isNotEmpty() && it != "." }
                    if (parts.isEmpty() || ".." in parts || ':' in parts[0]) continue
                    val out = File(targetDir, parts.joinToString(File.separator))
                    if (!out.canonicalPath.startsWith(canonRoot + File.separator)) continue
                    if (out.name.equals("pack_manifest.json", ignoreCase = true) || isAudioFile(out)) {
                        hasRelevant = true
                    }
                    out.parentFile?.mkdirs()
                    zf.getInputStream(entry).use { inp -> FileOutputStream(out).use { o -> inp.copyTo(o) } }
                }
            }
            if (!hasRelevant) return null
            marker.writeText("ok")
            resolvePackTarget(targetDir)
        } catch (_: Exception) {
            null
        }
    }

    private fun resolvePackTarget(extractDir: File): File {
        if (File(extractDir, "pack_manifest.json").isFile) return extractDir
        val children = extractDir.listFiles().orEmpty()
        val dirs = children.filter { it.isDirectory }
        val directAudio = children.any { it.isFile && isAudioFile(it) }
        return if (dirs.size == 1 && !directAudio) dirs[0] else extractDir
    }

    private fun applyPackManifests(tracks: List<QueueTrack>, manifestDirs: List<File>): List<QueueTrack> {
        val byPath = HashMap<String, Pair<File, JSONObject>>()
        for (mdir in manifestDirs) {
            val mf = File(mdir, "pack_manifest.json")
            if (!mf.isFile) continue
            try {
                val root = JSONObject(mf.readText())
                val arr = root.optJSONArray("tracks") ?: continue
                val canonDir = mdir.canonicalPath
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val rel = obj.optString("file").replace('\\', '/').trimStart('.', '/')
                    if (rel.isEmpty() || ".." in rel) continue
                    val f = File(mdir, rel)
                    if (f.canonicalPath.startsWith(canonDir + File.separator)) {
                        byPath[f.canonicalPath.lowercase()] = mdir to obj
                    }
                }
            } catch (_: Exception) {}
        }
        if (byPath.isEmpty()) return tracks
        return tracks.map { tr ->
            val hit = byPath[File(tr.srcPath).canonicalPath.lowercase()] ?: return@map tr
            val (mdir, obj) = hit
            val outroRel = obj.optString("outro_file", "").replace('\\', '/').trimStart('.', '/')
            val outroFile = if (outroRel.isNotEmpty() && ".." !in outroRel) File(mdir, outroRel).takeIf { it.isFile } else null
            tr.copy(
                title = obj.optString("title", tr.title).ifBlank { tr.title },
                artist = obj.optString("artist", tr.artist).ifBlank { tr.artist },
                category = obj.optString("category").takeIf { it.isNotBlank() }?.let { StationCategory.fromString(it) } ?: tr.category,
                hasOutro = outroFile != null || tr.hasOutro,
                outroSrcPath = outroFile?.absolutePath ?: tr.outroSrcPath,
                outroSrcName = outroFile?.name ?: tr.outroSrcName,
                outroStartSec = if (obj.has("outro_start") && !obj.isNull("outro_start")) obj.optDouble("outro_start") else tr.outroStartSec,
                outroEndSec = if (obj.has("outro_end") && !obj.isNull("outro_end")) obj.optDouble("outro_end") else tr.outroEndSec,
                outroFadeIn = if (obj.has("outro_fade_in")) obj.optDouble("outro_fade_in", 0.0) else tr.outroFadeIn,
                outroFadeOut = if (obj.has("outro_fade_out")) obj.optDouble("outro_fade_out", 0.0) else tr.outroFadeOut,
                outroFadeInEnabled = obj.optBoolean("outro_fade_in_enabled", tr.outroFadeInEnabled),
                outroFadeOutEnabled = obj.optBoolean("outro_fade_out_enabled", tr.outroFadeOutEnabled),
            )
        }
    }
}
