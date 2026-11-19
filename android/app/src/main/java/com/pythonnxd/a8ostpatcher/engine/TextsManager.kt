package com.pythonnxd.a8ostpatcher.engine

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class TextEntry(val key: ByteArray, val value: ByteArray) {
    fun keyString(): String = String(key, Charsets.UTF_8)
    fun valueString(): String = String(value, Charsets.UTF_8)
}

data class SongTitleUpdate(val baseId: String, val title: String, val artist: String)

/**
 * Reads and writes Gameloft binary *.texts localization dictionaries.
 * Exact binary parity with engine/texts_manager.py:
 *   Header: <II (num_entries, text_table_offset)
 *   Index:  repeated (<I klen, key_bytes, <I text_offset)
 *   Table:  repeated (<I vlen, val_bytes)
 */
object TextsManager {

    private fun readLeInt(data: ByteArray, offset: Int): Int {
        return ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private fun writeLeInt(out: ByteArrayOutputStream, value: Int) {
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
        out.write(buf)
    }

    fun readTexts(file: File): MutableList<TextEntry> {
        if (!file.isFile) return mutableListOf()
        val data = file.readBytes()
        if (data.size < 8) return mutableListOf()

        val numEntries = readLeInt(data, 0)
        val textTableOffset = readLeInt(data, 4)
        var offset = 8
        val entries = ArrayList<TextEntry>(maxOf(0, numEntries))

        for (i in 0 until numEntries) {
            if (offset + 4 > data.size) break
            val klen = readLeInt(data, offset)
            offset += 4
            if (klen < 0 || offset + klen + 4 > data.size) break
            val key = data.copyOfRange(offset, offset + klen)
            offset += klen
            val toff = readLeInt(data, offset)
            offset += 4

            val vptr = textTableOffset + toff
            if (vptr < 0 || vptr + 4 > data.size) break
            val vlen = readLeInt(data, vptr)
            if (vlen < 0 || vptr + 4 + vlen > data.size) break
            val value = data.copyOfRange(vptr + 4, vptr + 4 + vlen)
            entries.add(TextEntry(key, value))
        }
        return entries
    }

    fun writeTexts(file: File, entries: List<TextEntry>) {
        val textTableBuf = ByteArrayOutputStream()
        val indexEntries = ArrayList<Pair<ByteArray, Int>>(entries.size)

        for (entry in entries) {
            val currentOffset = textTableBuf.size()
            writeLeInt(textTableBuf, entry.value.size)
            textTableBuf.write(entry.value)
            indexEntries.add(entry.key to currentOffset)
        }

        val indexBuf = ByteArrayOutputStream()
        for ((k, toff) in indexEntries) {
            writeLeInt(indexBuf, k.size)
            indexBuf.write(k)
            writeLeInt(indexBuf, toff)
        }

        val textTableOffset = 8 + indexBuf.size()
        val header = ByteArrayOutputStream(8)
        writeLeInt(header, entries.size)
        writeLeInt(header, textTableOffset)

        file.parentFile?.mkdirs()
        file.outputStream().buffered().use { out ->
            out.write(header.toByteArray())
            out.write(indexBuf.toByteArray())
            out.write(textTableBuf.toByteArray())
        }
    }

    fun addSongTitles(
        textsDir: File,
        songs: List<SongTitleUpdate>,
        onConflict: ConflictPolicy = ConflictPolicy.OVERWRITE,
    ): Int {
        if (!textsDir.isDirectory || songs.isEmpty()) return 0
        val textFiles = textsDir.listFiles { f -> f.isFile && f.name.lowercase().endsWith(".texts") }
            ?.sortedBy { it.name.lowercase() }
            ?: return 0
        if (textFiles.isEmpty()) return 0

        var updatedFiles = 0
        for (tf in textFiles) {
            val entries = readTexts(tf)
            if (entries.isEmpty()) continue

            val keyToIdx = HashMap<String, Int>(entries.size * 2)
            for (idx in entries.indices) {
                keyToIdx[entries[idx].keyString()] = idx
            }

            var modified = false
            for (s in songs) {
                val keyStr = "STR_SONG_${s.baseId}"
                val keyBytes = keyStr.toByteArray(Charsets.US_ASCII)
                val valBytes = "\"${s.title}\" - ${s.artist}".toByteArray(Charsets.UTF_8)
                val existingIdx = keyToIdx[keyStr]
                if (existingIdx != null) {
                    if (onConflict == ConflictPolicy.OVERWRITE) {
                        entries[existingIdx] = TextEntry(keyBytes, valBytes)
                        modified = true
                    }
                } else {
                    entries.add(TextEntry(keyBytes, valBytes))
                    keyToIdx[keyStr] = entries.lastIndex
                    modified = true
                }
            }

            if (modified) {
                writeTexts(tf, entries)
                updatedFiles++
            }
        }
        return updatedFiles
    }

    fun removeSongTitles(textsDir: File, baseIds: Collection<String>): Int {
        if (!textsDir.isDirectory || baseIds.isEmpty()) return 0
        val removeKeys = baseIds.map { "STR_SONG_$it" }.toSet()
        val textFiles = textsDir.listFiles { f -> f.isFile && f.name.lowercase().endsWith(".texts") }
            ?: return 0
        var updatedFiles = 0
        for (tf in textFiles) {
            val entries = readTexts(tf)
            val filtered = entries.filter { it.keyString() !in removeKeys }
            if (filtered.size != entries.size) {
                writeTexts(tf, filtered)
                updatedFiles++
            }
        }
        return updatedFiles
    }
}
