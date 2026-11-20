package com.pythonnxd.a8ostpatcher.engine

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {
    const val REPO_GITHUB_URL = "https://github.com/PythonnXD/A8-Custom-OST"
    const val RELEASES_API_URL = "https://api.github.com/repos/PythonnXD/A8-Custom-OST/releases?per_page=30"
    const val ANDROID_TAG_PREFIX = "android-v"

    val LOCAL_CHANGELOG: Map<String, List<ReleaseNoteSection>> = linkedMapOf(
        "1.0.2" to listOf(
            ReleaseNoteSection(
                title = "Fixed",
                items = listOf(
                    "Fixed choppy/distorted audio on the 2nd+ imported song or outro by keeping the native Musepack SV8 filterbank table immutable and resetting encoder state cleanly between tracks.",
                    "Fixed swipe-left Outro and Remove buttons bleeding through translucent track cards when not swiped.",
                    "Fixed Outro Editor preview button feedback and cancellation errors, added a live waveform playhead and decoded PCM caching, and rendered bottom sheets with live GPU frosted blur.",
                    "Status banners now auto-dismiss cleanly and support 1:1 swipe up or sideways to dismiss.",
                ),
            ),
            ReleaseNoteSection(
                title = "Changed",
                items = listOf(
                    "Newly added songs start unselected; hold any song to enter selection mode and reveal checkboxes.",
                    "Replaced the tall batch-edit panel with a compact selection bar and a three-dots menu (Set artist, Set category, Remove).",
                    "Made the floating Now Playing bar slimmer with a Settings switch to enable or disable it.",
                    "Added soft edge fades on horizontally scrolling button, category, and preset rows.",
                ),
            ),
        ),
        "1.0.1" to listOf(
            ReleaseNoteSection(
                title = "Direct /Android/data/ Access & UI Polish",
                items = listOf(
                    "Added Storage Access Mode choice in Settings: Standard folder, Shizuku (recommended for direct /Android/data/ patching with no root), and optional Root (su) fallback.",
                    "Frosted Apple Liquid Glass interface with real-time GPU backdrop blur across the floating Pill Nav Bar, Now Playing Bar, cards, sheets, and banners.",
                    "Draggable Liquid Glass Pill Nav Bar highlight: grab and slide across tabs with 1:1 tracking, rubber-band edge resistance, live tick haptics, and momentum spring settle.",
                    "Resized floating Pill Nav Bar and upgraded universal DPI typography & touch targets for comfortable reading on all phones and tablets.",
                    "Animated sliding category filter pills, lively spring press feedback, spatial tab transitions, and smooth list item animations (all respecting Reduce motion).",
                ),
            ),
        ),
        "1.0.0" to listOf(
            ReleaseNoteSection(
                title = "Added",
                items = listOf(
                    "Full Android Asphalt 8 custom soundtrack patching with native in-process Musepack SV8 (libmpcenc & libmpcdec) encoding and decoding.",
                    "Adaptive Phone and Tablet interface with floating glass navigation bar, swipe track actions, draggable sheets, waveform outro trim editor, and hold-to-delete backups.",
                    "Material You Light, Material You Dark, Dark, and Light themes plus a Reduce motion option in Settings to disable animations.",
                    "Locked Now Playing mini-player that stays pinned to the active song when selecting or editing other tracks.",
                    "Backups, Health Check & Repair, MOD Profiles, portable .zip Mod Pack import/export, unsaved playlist warning with Don't show this again, and automatic Android release update checks.",
                ),
            ),
        ),
    )

    val OPEN_SOURCE_ACKNOWLEDGEMENTS: List<OpenSourceAck> = listOf(
        OpenSourceAck(
            name = "Musepack SV8 (libmpcenc & libmpcdec)",
            role = "Native Musepack Stream Version 8 audio encoder and decoder",
            license = "BSD / LGPL",
            displayUrl = "musepack.net",
            url = "https://www.musepack.net/",
        ),
        OpenSourceAck(
            name = "Kotlin & Kotlinx Coroutines",
            role = "Application language runtime and structured asynchronous I/O",
            license = "Apache-2.0",
            displayUrl = "kotlinlang.org",
            url = "https://kotlinlang.org/",
        ),
        OpenSourceAck(
            name = "Jetpack Compose & Material 3",
            role = "Adaptive Phone & Tablet UI toolkit and Material You dynamic color theming",
            license = "Apache-2.0",
            displayUrl = "developer.android.com/compose",
            url = "https://developer.android.com/compose",
        ),
        OpenSourceAck(
            name = "Haze (Chris Banes)",
            role = "Real-time GPU backdrop blur and frosted glass rendering for Jetpack Compose",
            license = "Apache-2.0",
            displayUrl = "github.com/chrisbanes/haze",
            url = "https://github.com/chrisbanes/haze",
        ),
        OpenSourceAck(
            name = "Shizuku API (RikkaApps)",
            role = "Privileged UserService IPC for direct /Android/data/ access without root",
            license = "MIT",
            displayUrl = "github.com/RikkaApps/Shizuku-API",
            url = "https://github.com/RikkaApps/Shizuku-API",
        ),
        OpenSourceAck(
            name = "AndroidX Core, Activity, Lifecycle & DocumentFile",
            role = "Edge-to-edge windowing, storage pickers, and lifecycle state management",
            license = "Apache-2.0",
            displayUrl = "developer.android.com/jetpack",
            url = "https://developer.android.com/jetpack",
        ),
        OpenSourceAck(
            name = "Android MediaCodec & MediaExtractor",
            role = "Hardware and software audio decoding for MP3, OGG, FLAC, M4A, AAC, and WAV",
            license = "Apache-2.0",
            displayUrl = "source.android.com",
            url = "https://source.android.com/",
        ),
    )

    fun parseSemver(versionStr: String): List<Int> {
        val nums = Regex("\\d+").findAll(versionStr).map { it.value.toInt() }.take(4).toMutableList()
        while (nums.size < 3) nums.add(0)
        return nums
    }

    fun compareSemver(a: String, b: String): Int {
        val pa = parseSemver(a)
        val pb = parseSemver(b)
        val len = maxOf(pa.size, pb.size)
        for (i in 0 until len) {
            val va = pa.getOrElse(i) { 0 }
            val vb = pb.getOrElse(i) { 0 }
            if (va != vb) return va.compareTo(vb)
        }
        return 0
    }

    fun isNewerVersion(candidate: String, current: String): Boolean =
        compareSemver(candidate, current) > 0

    fun isAllowedHttpsUrl(rawUrl: String): Boolean {
        return try {
            val u = URL(rawUrl.trim())
            u.protocol.equals("https", ignoreCase = true) && u.host.isNotBlank()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Parses the GitHub releases array and selects the newest non-draft Android release
     * matching the separate `android-v*` tag namespace (ignoring PC `v1.4.x` releases).
     */
    fun parseAndroidReleaseList(rawJson: String, currentVersion: String): UpdateInfo? {
        val arr = JSONArray(rawJson)
        var best: UpdateInfo? = null
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optBoolean("draft", false)) continue
            val tag = obj.optString("tag_name", "").trim()
            if (!tag.lowercase().startsWith(ANDROID_TAG_PREFIX)) continue

            val ver = tag.substring(ANDROID_TAG_PREFIX.length).trim()
            if (ver.isEmpty()) continue

            val assets = obj.optJSONArray("assets")
            var apkUrl = ""
            var apkSize = 0L
            if (assets != null) {
                for (j in 0 until assets.length()) {
                    val asset = assets.optJSONObject(j) ?: continue
                    val name = asset.optString("name", "").lowercase()
                    val dl = asset.optString("browser_download_url", "").trim()
                    if (name.endsWith(".apk") && isAllowedHttpsUrl(dl)) {
                        apkUrl = dl
                        apkSize = asset.optLong("size", 0L)
                        break
                    }
                }
            }
            val candidate = UpdateInfo(
                currentVersion = currentVersion,
                latestVersion = ver,
                tagName = tag,
                updateAvailable = isNewerVersion(ver, currentVersion),
                downloadUrl = apkUrl,
                releaseUrl = obj.optString("html_url", "$REPO_GITHUB_URL/releases/tag/$tag"),
                sizeBytes = apkSize,
                publishedAt = obj.optString("published_at", ""),
                notes = obj.optString("body", "").trim(),
            )
            if (best == null || compareSemver(candidate.latestVersion, best.latestVersion) > 0) {
                best = candidate
            }
        }
        return best
    }

    fun checkForAndroidUpdate(currentVersion: String, apiUrl: String = RELEASES_API_URL): Result<UpdateInfo> {
        return try {
            val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("User-Agent", "A8CustomOSTPatcherAndroid/$currentVersion")
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                val msg = when (code) {
                    403, 429 -> "GitHub update check is rate-limited right now. Try again later."
                    404 -> "No published Android release was found yet."
                    else -> "Update server returned HTTP $code."
                }
                return Result.failure(IllegalStateException(msg))
            }
            val raw = conn.inputStream.bufferedReader().use { it.readText() }
            val info = parseAndroidReleaseList(raw, currentVersion)
                ?: UpdateInfo(
                    currentVersion = currentVersion,
                    latestVersion = currentVersion,
                    tagName = "$ANDROID_TAG_PREFIX$currentVersion",
                    updateAvailable = false,
                    downloadUrl = "",
                    releaseUrl = "$REPO_GITHUB_URL/releases",
                    sizeBytes = 0L,
                    publishedAt = "",
                    notes = "",
                )
            Result.success(info)
        } catch (e: Exception) {
            Result.failure(IllegalStateException("Could not reach GitHub updates: ${e.message ?: "check your connection"}"))
        }
    }

    fun downloadApk(
        downloadUrl: String,
        destFile: File,
        currentVersion: String,
        onProgress: (Float) -> Unit,
    ): File {
        require(isAllowedHttpsUrl(downloadUrl)) { "Download URL must use HTTPS." }
        destFile.parentFile?.mkdirs()
        val conn = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 45_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "A8CustomOSTPatcherAndroid/$currentVersion")
        }
        val code = conn.responseCode
        if (code !in 200..299) throw IllegalStateException("Download failed with HTTP $code")
        val total = conn.contentLengthLong
        var read = 0L
        val buf = ByteArray(32768)
        conn.inputStream.use { inp ->
            FileOutputStream(destFile).use { out ->
                while (true) {
                    val n = inp.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    read += n
                    if (total > 0) onProgress((read.toFloat() / total.toFloat()).coerceIn(0f, 1f))
                }
            }
        }
        onProgress(1f)
        return destFile
    }
}
