package com.pythonnxd.a8ostpatcher.engine

import java.io.File
import java.io.FileInputStream
import kotlin.math.abs

object NativeMpcCodec {
    @Volatile
    private var loaded: Boolean = false

    private fun ensureLoaded() {
        if (!loaded) {
            synchronized(this) {
                if (!loaded) {
                    System.loadLibrary("a8mpc")
                    loaded = true
                }
            }
        }
    }

    external fun nativeEncodeWavToMpc(wavPath: String, mpcPath: String, quality: Float): Int
    external fun nativeDecodeMpcToWav(mpcPath: String, wavPath: String): Int
    external fun nativeProbeMpcInfo(mpcPath: String): DoubleArray?

    fun encodeWavToMpc(wavPath: String, mpcPath: String, quality: Float): Int {
        ensureLoaded()
        return nativeEncodeWavToMpc(wavPath, mpcPath, quality)
    }

    fun decodeMpcToWav(mpcPath: String, wavPath: String): Int {
        ensureLoaded()
        return nativeDecodeMpcToWav(mpcPath, wavPath)
    }

    fun probeMpcInfo(mpcPath: String): MpcInfo? {
        ensureLoaded()
        val arr = nativeProbeMpcInfo(mpcPath) ?: return null
        if (arr.size < 4) return null
        return MpcInfo(
            durationSec = arr[0],
            sampleRate = arr[1].toInt(),
            channels = arr[2].toInt(),
            totalSamples = arr[3].toLong(),
        )
    }
}

data class MpcInfo(
    val durationSec: Double,
    val sampleRate: Int,
    val channels: Int,
    val totalSamples: Long,
)

/**
 * Pure-Kotlin Musepack SV8 (MPCK) SH packet parser for fast header validation
 * and exact millisecond duration reading without requiring native calls.
 */
object MpcHeaderParser {
    private val VALID_SAMPLE_RATES = intArrayOf(44100, 48000, 37800, 32000)

    private fun readVarint(buf: ByteArray, startIdx: Int): Pair<Long?, Int> {
        var idx = startIdx
        var value = 0L
        while (idx < buf.size) {
            val b = buf[idx].toInt() and 0xFF
            idx++
            value = (value shl 7) or (b and 0x7F).toLong()
            if ((b and 0x80) == 0) {
                return value to idx
            }
        }
        return null to idx
    }

    fun parseHeader(file: File): MpcInfo? {
        if (!file.isFile || file.length() < 16) return null
        val data = ByteArray(minOf(4096L, file.length()).toInt())
        FileInputStream(file).use { fis ->
            var off = 0
            while (off < data.size) {
                val n = fis.read(data, off, data.size - off)
                if (n <= 0) break
                off += n
            }
        }
        return parseHeaderBytes(data)
    }

    fun parseHeaderBytes(data: ByteArray): MpcInfo? {
        if (data.size < 16) return null
        if (data[0] != 'M'.code.toByte() || data[1] != 'P'.code.toByte() ||
            data[2] != 'C'.code.toByte() || data[3] != 'K'.code.toByte()
        ) {
            return null
        }

        var pos = 4
        while (pos + 3 < data.size) {
            val k0 = data[pos].toInt().toChar()
            val k1 = data[pos + 1].toInt().toChar()
            val (plen, payloadStart) = readVarint(data, pos + 2)
            if (plen == null || plen < 3L) break
            val nextPos = pos + plen.toInt()
            if (k0 == 'S' && k1 == 'H') {
                var p = payloadStart + 5 // CRC32 (4 bytes) + StreamVersion (1 byte)
                val (samples, p1) = readVarint(data, p)
                val (begSil, p2) = readVarint(data, p1)
                p = p2
                if (samples != null && p < data.size) {
                    val flags = data[p].toInt() and 0xFF
                    val freqIdx = (flags ushr 5) and 0x07
                    val sr = if (freqIdx in VALID_SAMPLE_RATES.indices) VALID_SAMPLE_RATES[freqIdx] else return null
                    val ch = if (p + 1 < data.size) (((data[p + 1].toInt() and 0xFF) ushr 4) + 1) else 2
                    val netSamples = maxOf(0L, samples - (begSil ?: 0L))
                    return MpcInfo(
                        durationSec = netSamples.toDouble() / sr.toDouble(),
                        sampleRate = sr,
                        channels = ch,
                        totalSamples = netSamples,
                    )
                }
                break
            }
            if (nextPos <= pos) break
            pos = nextPos
        }
        return null
    }

    fun isValidMpc8(file: File): Boolean {
        val info = parseHeader(file) ?: return false
        return info.sampleRate in VALID_SAMPLE_RATES && info.channels in 1..2 && info.totalSamples > 0
    }

    fun verifyDurationMatch(expectedSec: Double, mpcFile: File, toleranceMs: Double = 25.0): Pair<Boolean, Double> {
        val actual = parseHeader(mpcFile)?.durationSec ?: return false to Double.POSITIVE_INFINITY
        val diffMs = abs(actual - expectedSec) * 1000.0
        return (diffMs <= toleranceMs) to diffMs
    }
}
