package com.pythonnxd.a8ostpatcher

import com.pythonnxd.a8ostpatcher.engine.*
import com.pythonnxd.a8ostpatcher.privileged.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EngineUnitTests {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun createSampleSoundpack(file: File) {
        file.parentFile?.mkdirs()
        file.writeText(
            """<?xml version="1.0" encoding="UTF-8"?>
<soundpack>
  <sounds size="2">
    <sound bank="MUSIC" filename="m_stock_rock.mpc" format="mpc" group="m_radio" label="m_stock_rock" loadingflags="none" loop="no" priority="1.0" />
    <sound bank="MUSIC" filename="m_stock_menu.mpc" format="mpc" group="m_ui" label="m_stock_menu" loadingflags="none" loop="no" priority="1.0" />
  </sounds>
  <events size="4">
    <event label="ev_m_stock_rock" type="playlist" value="m_stock_rock" />
    <event label="ev_m_stock_menu" type="playlist" value="m_stock_menu" />
    <event label="ev_radio_rock" type="random" value="m_stock_rock" />
    <event label="ev_menu_music" type="random" value="m_stock_menu" />
  </events>
</soundpack>
"""
        )
    }

    @Test
    fun textsBinaryRoundtripAndSongTitles() {
        val dir = tmp.newFolder("text")
        val en = File(dir, "en.texts")
        TextsManager.writeTexts(
            en,
            listOf(
                TextEntry("STR_EXISTING".toByteArray(), "Hello World".toByteArray()),
                TextEntry("STR_SONG_m_stock_rock".toByteArray(), "\"Stock Rock\" - Band".toByteArray()),
            ),
        )
        val readBack = TextsManager.readTexts(en)
        assertEquals(2, readBack.size)
        assertEquals("STR_EXISTING", readBack[0].keyString())
        assertEquals("Hello World", readBack[0].valueString())

        val updated = TextsManager.addSongTitles(
            dir,
            listOf(
                SongTitleUpdate("m_seisyun", "Seisyun Complex", "Kessoku Band"),
                SongTitleUpdate("m_stock_rock", "Ignored", "SkipMe"),
            ),
            onConflict = ConflictPolicy.SKIP,
        )
        assertEquals(1, updated)
        val after = TextsManager.readTexts(en).associate { it.keyString() to it.valueString() }
        assertEquals("\"Seisyun Complex\" - Kessoku Band", after["STR_SONG_m_seisyun"])
        assertEquals("\"Stock Rock\" - Band", after["STR_SONG_m_stock_rock"])
    }

    @Test
    fun gamePathResolverHandlesAndroidLayouts() {
        val androidFiles = tmp.newFolder("com.gameloft.android.ANMP.GloftA8HM")
        val sp = File(androidFiles, "files/soundpack/soundpack.xml")
        createSampleSoundpack(sp)
        File(androidFiles, "files/music").mkdirs()
        File(androidFiles, "files/text").mkdirs()

        assertTrue(GamePathResolver.isValidGameDir(androidFiles.absolutePath))
        val resolved = GamePathResolver.resolve(androidFiles.absolutePath)!!
        assertEquals(sp.canonicalPath, resolved.soundpackFile.canonicalPath)
    }

    @Test
    fun soundpackPatchBackupHealthAndProfiles() {
        val appFiles = tmp.newFolder("app_files")
        val game = tmp.newFolder("a8_android")
        val sp = File(game, "soundpack/soundpack")
        createSampleSoundpack(sp)
        val musicDir = File(game, "music").apply { mkdirs() }
        val textDir = File(game, "text").apply { mkdirs() }
        File(musicDir, "m_stock_rock.mpc").writeBytes(byteArrayOf(0x4D, 0x50, 0x43, 0x4B))
        File(musicDir, "m_stock_menu.mpc").writeBytes(byteArrayOf(0x4D, 0x50, 0x43, 0x4B))
        File(musicDir, "m_orphan_old.mpc").writeBytes(byteArrayOf(1, 2, 3, 4))
        TextsManager.writeTexts(
            File(textDir, "en.texts"),
            listOf(TextEntry("STR_SONG_m_stock_rock".toByteArray(), "\"Stock Rock\" - A8".toByteArray())),
        )

        val res = SoundpackManager.patchSoundpack(
            sp,
            listOf(
                SoundpackTrackSpec(
                    baseId = "m_that_band",
                    mainFile = "m_that_band.mpc",
                    category = StationCategory.ROCK,
                    hasOutro = true,
                    outroId = "m_outro_that_band",
                    outroFile = "m_outro_that_band.mpc",
                )
            ),
            keepOriginal = true,
        )
        assertEquals(1, res.added)
        File(musicDir, "m_that_band.mpc").writeBytes(byteArrayOf(0x4D, 0x50, 0x43, 0x4B))
        File(musicDir, "m_outro_that_band.mpc").writeBytes(byteArrayOf(0x4D, 0x50, 0x43, 0x4B))

        // Health audit detects orphan .mpc and missing STR_SONG entries
        val audit = LibraryManager.auditHealth(game.absolutePath)
        assertFalse(audit.healthy)
        assertEquals(1, audit.orphanFiles.size)
        assertTrue(audit.missingTexts.any { it.baseId == "m_that_band" })

        val repair = LibraryManager.repairHealth(
            appFilesDir = appFiles,
            gameDirPath = game.absolutePath,
            fixTexts = true,
            fixEvents = true,
            pruneMissing = false,
            deleteOrphans = true,
            autoBackup = true,
        )
        assertTrue(repair.auditAfter.healthy)
        assertEquals(1, repair.orphansDeleted)

        val backups = LibraryManager.listBackups(appFiles, game.absolutePath)
        assertEquals(1, backups.size)

        val profile = LibraryManager.saveProfile(appFiles, game.absolutePath, "Rock Set")
        assertEquals("Rock Set", profile.name)
        assertEquals(1, LibraryManager.listProfiles(appFiles).size)
    }

    @Test
    fun updateCheckerFiltersSeparateAndroidReleaseVersion() {
        val sampleReleasesJson = """
        [
          {
            "tag_name": "v1.4.3.1",
            "draft": false,
            "html_url": "https://github.com/PythonnXD/A8-Custom-OST/releases/tag/v1.4.3.1",
            "assets": [
              {"name": "A8_Custom_OST_Patcher.zip", "size": 15263470, "browser_download_url": "https://github.com/PythonnXD/A8-Custom-OST/releases/download/v1.4.3.1/A8_Custom_OST_Patcher.zip"}
            ]
          },
          {
            "tag_name": "android-v1.0.1",
            "draft": false,
            "html_url": "https://github.com/PythonnXD/A8-Custom-OST/releases/tag/android-v1.0.1",
            "body": "Android fix",
            "assets": [
              {"name": "A8_Custom_OST_Patcher-Android-v1.0.1.apk", "size": 9500000, "browser_download_url": "https://github.com/PythonnXD/A8-Custom-OST/releases/download/android-v1.0.1/A8_Custom_OST_Patcher-Android-v1.0.1.apk"}
            ]
          }
        ]
        """.trimIndent()

        // Must ignore PC v1.4.3.1 and detect android-v1.0.1 > 1.0.0
        val info = UpdateChecker.parseAndroidReleaseList(sampleReleasesJson, "1.0.0")!!
        assertEquals("1.0.1", info.latestVersion)
        assertEquals("android-v1.0.1", info.tagName)
        assertTrue(info.updateAvailable)
        assertTrue(info.downloadUrl.endsWith(".apk"))

        // Same version must not flag update
        val same = UpdateChecker.parseAndroidReleaseList(sampleReleasesJson, "1.0.1")!!
        assertFalse(same.updateAvailable)

        // Ensure user GitHub page is NOT inside open-source licenses
        assertTrue(UpdateChecker.OPEN_SOURCE_ACKNOWLEDGEMENTS.none { it.url.contains("PythonnXD") })
    }

    @Test
    fun privilegedBridgeMirrorsAndPushesAndroidDataChanges() {
        assertTrue(PrivilegedAccessManager.isRestrictedAndroidDataPath("/storage/emulated/0/Android/data/com.gameloft.android.ANMP.GloftA8HM/files"))
        assertFalse(PrivilegedAccessManager.isRestrictedAndroidDataPath("/storage/emulated/0/Download/Asphalt8_Loose"))
        assertEquals("'hello'\\''world'", PrivilegedAccessManager.shellQuote("hello'world"))
        val parsed = PrivilegedAccessManager.parseEntryLine("F|m_track.mpc|12345")!!
        assertEquals("m_track.mpc", parsed.name)
        assertFalse(parsed.isDirectory)
        assertEquals(12345L, parsed.sizeBytes)

        // Simulate a restricted /Android/data/com.gameloft.android.ANMP.GloftA8HM/files tree via fake backend
        val fakeStorage = tmp.newFolder("fake_storage")
        val remoteFiles = File(fakeStorage, "Android/data/com.gameloft.android.ANMP.GloftA8HM/files").apply { mkdirs() }
        createSampleSoundpack(File(remoteFiles, "soundpack/soundpack"))
        val remoteMusic = File(remoteFiles, "music").apply { mkdirs() }
        val remoteText = File(remoteFiles, "text").apply { mkdirs() }
        File(remoteMusic, "m_stock_rock.mpc").writeBytes(byteArrayOf(0x4D, 0x50, 0x43, 0x4B))
        File(remoteMusic, "m_stock_menu.mpc").writeBytes(byteArrayOf(0x4D, 0x50, 0x43, 0x4B))
        File(remoteMusic, "m_orphan_remote.mpc").writeBytes(byteArrayOf(9, 8, 7, 6, 5))
        TextsManager.writeTexts(
            File(remoteText, "en.texts"),
            listOf(TextEntry("STR_SONG_m_stock_rock".toByteArray(), "\"Stock Rock\" - A8".toByteArray())),
        )

        val fakeBackend = object : PrivilegedFileBackend {
            override val mode: GameAccessMode = GameAccessMode.SHIZUKU
            override fun exists(path: String): Boolean = File(path).exists()
            override fun isDirectory(path: String): Boolean = File(path).isDirectory
            override fun isFile(path: String): Boolean = File(path).isFile
            override fun listEntries(dirPath: String): List<RemoteFileEntry> =
                File(dirPath).listFiles().orEmpty().map { f ->
                    RemoteFileEntry(f.name, f.isDirectory, if (f.isFile) f.length() else 0L)
                }
            override fun mkdirs(path: String): Boolean = File(path).let { it.isDirectory || it.mkdirs() }
            override fun deletePath(path: String): Boolean = File(path).delete()
            override fun testWrite(dirPath: String): Boolean = File(dirPath).isDirectory
            override fun copyFromRemote(remotePath: String, localDst: File): Boolean {
                val src = File(remotePath)
                if (!src.isFile) return false
                localDst.parentFile?.mkdirs()
                src.copyTo(localDst, overwrite = true)
                return true
            }
            override fun copyToRemote(localSrc: File, remotePath: String): Boolean {
                if (!localSrc.isFile) return false
                val dst = File(remotePath)
                dst.parentFile?.mkdirs()
                localSrc.copyTo(dst, overwrite = true)
                return true
            }
        }

        val detected = PrivilegedGameBridge.detectRestrictedGameDirs(fakeBackend, fakeStorage.absolutePath)
        assertEquals(1, detected.size)
        val layout = PrivilegedGameBridge.resolveRemoteLayout(fakeBackend, detected.first())!!
        val mirrorDir = tmp.newFolder("mirror")
        val appFiles = tmp.newFolder("app_files_priv")

        val ws = PrivilegedGameBridge.pullMirror(fakeBackend, layout, mirrorDir)
        assertEquals(3, ws.remoteMusicIndex.size)
        // Stock .mpc files must NOT be copied into mirrorDir/music
        assertEquals(0, File(mirrorDir, "music").listFiles().orEmpty().size)

        val tracks = LibraryManager.listInstalledTracks(ws.mirrorDir.absolutePath, ws.remoteMusicIndex)
        assertEquals(2, tracks.size)
        assertTrue(tracks.all { it.mainExists })

        val audit = LibraryManager.auditHealth(ws.mirrorDir.absolutePath, ws.remoteMusicIndex)
        assertEquals(1, audit.orphanFiles.size)
        assertEquals("m_orphan_remote.mpc", audit.orphanFiles.first().filename)
        assertEquals(5L, audit.orphanTotalBytes)

        // Repair orphans via mirror and push deletions back to simulated /Android/data/
        val orphansToDelete = mutableListOf<String>()
        val rep = LibraryManager.repairHealth(
            appFilesDir = appFiles,
            gameDirPath = ws.mirrorDir.absolutePath,
            fixTexts = true,
            fixEvents = true,
            pruneMissing = false,
            deleteOrphans = true,
            autoBackup = true,
            remoteMusicIndex = ws.remoteMusicIndex,
            backupKeyDir = detected.first(),
            deletedOrphanNamesOut = orphansToDelete,
        )
        assertEquals(1, rep.orphansDeleted)
        assertEquals(listOf("m_orphan_remote.mpc"), orphansToDelete)
        PrivilegedGameBridge.pushMirrorChanges(
            backend = fakeBackend,
            workspace = ws,
            pushSoundpack = false,
            pushTexts = rep.textsFixed > 0,
            pushStagedMusic = false,
            deleteRemoteMusicFiles = orphansToDelete,
        )
        assertFalse(File(remoteMusic, "m_orphan_remote.mpc").exists())
        // Backup must be indexed under real remote gameDir key
        assertEquals(1, LibraryManager.listBackups(appFiles, detected.first()).size)
    }
}
