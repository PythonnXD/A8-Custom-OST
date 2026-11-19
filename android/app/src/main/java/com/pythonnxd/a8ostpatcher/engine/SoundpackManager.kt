package com.pythonnxd.a8ostpatcher.engine

import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

data class SoundpackTrackSpec(
    val baseId: String,
    val mainFile: String,
    val category: StationCategory,
    val hasOutro: Boolean = false,
    val outroId: String? = null,
    val outroFile: String? = null,
)

data class SoundpackPatchResult(
    val added: Int,
    val skipped: Int,
    val totalSounds: Int,
    val totalEvents: Int,
)

object SoundpackManager {

    val STATION_EVENT_TO_CATEGORY = mapOf(
        "ev_radio_rock" to StationCategory.ROCK,
        "ev_radio_electro" to StationCategory.ELECTRO,
        "ev_radio_dubstep" to StationCategory.DUBSTEP,
        "ev_menu_music" to StationCategory.MENU,
        "ev_upd25_menu_music" to StationCategory.MENU,
    )

    val ALL_PLAYLIST_EVENTS_LOWER = setOf(
        "ev_radio_rock",
        "ev_radio_electro",
        "ev_radio_electronic",
        "ev_radio_dubstep",
        "ev_radio_bass",
        "ev_menu_music",
        "ev_upd25_menu_music",
        "ev_radio_full_soundtrack",
    )

    fun parseXml(file: File): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        return factory.newDocumentBuilder().parse(file)
    }

    fun writeXml(doc: Document, file: File) {
        file.parentFile?.mkdirs()
        val transformer = TransformerFactory.newInstance().newTransformer()
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        transformer.setOutputProperty(OutputKeys.INDENT, "yes")
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")
        transformer.transform(DOMSource(doc), StreamResult(file))
    }

    fun directChildElements(parent: Element, tagName: String): List<Element> {
        val out = mutableListOf<Element>()
        val nodes = parent.childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n is Element && n.tagName == tagName) {
                out.add(n)
            }
        }
        return out
    }

    fun firstChildElement(parent: Element, tagName: String): Element? {
        val nodes = parent.childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n is Element && n.tagName == tagName) return n
        }
        return null
    }

    fun isPlaylistEvent(label: String): Boolean {
        val low = label.trim().lowercase()
        return low in ALL_PLAYLIST_EVENTS_LOWER ||
            (low.startsWith("ev_") && low.contains("menu_music") && !low.startsWith("ev_sfx_"))
    }

    fun findTargetPlaylistEvents(eventsElem: Element, primaryEvName: String): List<Element> {
        val allEvents = directChildElements(eventsElem, "event")
        val primaryLow = primaryEvName.lowercase()
        val matches = mutableListOf<Element>()

        if (primaryLow == "ev_menu_music") {
            for (ev in allEvents) {
                val low = ev.getAttribute("label").trim().lowercase()
                if (low == "ev_menu_music" || (low.startsWith("ev_") && low.contains("menu_music") && !low.startsWith("ev_sfx_"))) {
                    matches.add(ev)
                }
            }
            return matches
        }

        for (ev in allEvents) {
            if (ev.getAttribute("label").trim().lowercase() == primaryLow) {
                matches.add(ev)
            }
        }
        if (matches.isNotEmpty()) return matches

        val fallbacks = when (primaryLow) {
            "ev_radio_electro" -> listOf("ev_radio_electronic", "ev_radio_full_soundtrack")
            "ev_radio_dubstep" -> listOf("ev_radio_bass", "ev_radio_full_soundtrack")
            else -> listOf("ev_radio_full_soundtrack")
        }
        for (fb in fallbacks) {
            for (ev in allEvents) {
                if (ev.getAttribute("label").trim().lowercase() == fb) {
                    matches.add(ev)
                }
            }
            if (matches.isNotEmpty()) return matches
        }
        return matches
    }

    fun getExistingTrackIds(soundpackFile: File): Set<String> {
        if (!soundpackFile.isFile) return emptySet()
        return try {
            val doc = parseXml(soundpackFile)
            val sounds = firstChildElement(doc.documentElement, "sounds") ?: return emptySet()
            directChildElements(sounds, "sound")
                .mapNotNull { it.getAttribute("label").takeIf { lbl -> lbl.isNotEmpty() } }
                .toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    fun patchSoundpack(
        soundpackFile: File,
        newTracks: List<SoundpackTrackSpec>,
        keepOriginal: Boolean = true,
        onConflict: ConflictPolicy = ConflictPolicy.OVERWRITE,
    ): SoundpackPatchResult {
        val doc = parseXml(soundpackFile)
        val root = doc.documentElement

        val soundsElem = firstChildElement(root, "sounds")
            ?: doc.createElement("sounds").also { root.appendChild(it) }
        val eventsElem = firstChildElement(root, "events")
            ?: doc.createElement("events").also { root.appendChild(it) }

        val existingLabels = directChildElements(soundsElem, "sound")
            .mapNotNull { it.getAttribute("label").takeIf { lbl -> lbl.isNotEmpty() } }
            .toMutableSet()

        var addedCount = 0
        var skippedCount = 0
        val catNewIds = LinkedHashMap<String, MutableList<String>>()

        for (t in newTracks) {
            val baseId = t.baseId
            val outroId = t.outroId
            val evName = t.category.eventLabel

            if (baseId in existingLabels) {
                if (onConflict == ConflictPolicy.SKIP) {
                    skippedCount++
                    continue
                } else {
                    val rmLabels = setOfNotNull(baseId, outroId)
                    for (s in directChildElements(soundsElem, "sound")) {
                        if (s.getAttribute("label") in rmLabels) soundsElem.removeChild(s)
                    }
                    val rmEv = rmLabels.map { "ev_$it" }.toSet()
                    for (ev in directChildElements(eventsElem, "event")) {
                        if (ev.getAttribute("label") in rmEv) eventsElem.removeChild(ev)
                    }
                }
            }

            val group = if (t.category == StationCategory.MENU) "m_ui" else "m_radio"
            soundsElem.appendChild(doc.createElement("sound").apply {
                setAttribute("bank", "MUSIC")
                setAttribute("filename", t.mainFile)
                setAttribute("format", "mpc")
                setAttribute("group", group)
                setAttribute("label", baseId)
                setAttribute("loadingflags", "none")
                setAttribute("loop", "no")
                setAttribute("priority", "1.0")
            })

            if (t.hasOutro && !outroId.isNullOrBlank() && !t.outroFile.isNullOrBlank()) {
                soundsElem.appendChild(doc.createElement("sound").apply {
                    setAttribute("bank", "MUSIC")
                    setAttribute("filename", t.outroFile)
                    setAttribute("format", "mpc")
                    setAttribute("group", "m_outro")
                    setAttribute("label", outroId)
                    setAttribute("loadingflags", "none")
                    setAttribute("loop", "no")
                    setAttribute("priority", "1.0")
                })
            }

            eventsElem.appendChild(doc.createElement("event").apply {
                setAttribute("label", "ev_$baseId")
                setAttribute("type", "playlist")
                setAttribute("value", baseId)
            })
            if (t.hasOutro && !outroId.isNullOrBlank()) {
                eventsElem.appendChild(doc.createElement("event").apply {
                    setAttribute("label", "ev_$outroId")
                    setAttribute("type", "playlist")
                    setAttribute("value", outroId)
                })
            }

            existingLabels.add(baseId)
            catNewIds.getOrPut(evName) { mutableListOf() }.add(baseId)
            addedCount++
        }

        for ((evName, newIds) in catNewIds) {
            for (catEvent in findTargetPlaylistEvents(eventsElem, evName)) {
                val oldVal = catEvent.getAttribute("value").trim()
                val oldList = if (oldVal.isEmpty()) emptyList() else oldVal.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                val combined = if (!keepOriginal) {
                    newIds
                } else {
                    oldList + newIds.filter { it !in oldList }
                }
                catEvent.setAttribute("value", combined.joinToString(","))
            }
        }

        val totalSounds = directChildElements(soundsElem, "sound").size
        val totalEvents = directChildElements(eventsElem, "event").size
        soundsElem.setAttribute("size", totalSounds.toString())
        eventsElem.setAttribute("size", totalEvents.toString())

        writeXml(doc, soundpackFile)
        return SoundpackPatchResult(
            added = addedCount,
            skipped = skippedCount,
            totalSounds = totalSounds,
            totalEvents = totalEvents,
        )
    }
}
