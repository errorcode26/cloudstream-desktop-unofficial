package com.lagradost.common.platform

import com.lagradost.common.storage.DesktopDataStore
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PlatformPathsTest {

    private val createdDirs = mutableListOf<File>()
    private var prevDataDir: String? = null
    private var prevIsolated: String? = null

    @BeforeTest
    fun setUp() {
        prevDataDir = System.getProperty("cloudstream.data.dir")
        prevIsolated = System.getProperty("cloudstream.test.isolated")
        DesktopDataStore.resetForTesting()
    }

    @AfterTest
    fun tearDown() {
        DesktopDataStore.resetForTesting()
        if (prevDataDir != null) {
            System.setProperty("cloudstream.data.dir", prevDataDir)
        } else {
            System.clearProperty("cloudstream.data.dir")
        }
        if (prevIsolated != null) {
            System.setProperty("cloudstream.test.isolated", prevIsolated)
        } else {
            System.clearProperty("cloudstream.test.isolated")
        }
        createdDirs.forEach { it.deleteRecursively() }
        createdDirs.clear()
    }

    @Test
    fun `all storage subdirectories dynamically track cloudstream data dir property changes`() {
        val dirA = Files.createTempDirectory("cs_paths_a_").toFile().also { createdDirs.add(it) }
        val dirB = Files.createTempDirectory("cs_paths_b_").toFile().also { createdDirs.add(it) }

        System.setProperty("cloudstream.data.dir", dirA.absolutePath)
        assertEquals(dirA.canonicalPath, PlatformPaths.appDataDir.canonicalPath)
        assertEquals(File(dirA, "plugins").parentFile?.let { File(dirA, "cache/plugins").canonicalPath }, PlatformPaths.pluginCacheDir.canonicalPath)
        assertEquals(File(dirA, "Sandbox").canonicalPath, PlatformPaths.sandboxDir.canonicalPath)
        assertEquals(File(dirA, "Extensions").canonicalPath, PlatformPaths.extensionsDir.canonicalPath)
        assertEquals(File(dirA, "shared_prefs").canonicalPath, PlatformPaths.sharedPrefsDir.canonicalPath)
        assertEquals(File(dirA, "logs").canonicalPath, PlatformPaths.logsDir.canonicalPath)
        assertEquals(File(dirA, "shaders").canonicalPath, PlatformPaths.shadersDir.canonicalPath)
        assertEquals(File(dirA, "fonts").canonicalPath, PlatformPaths.fontsDir.canonicalPath)

        // Switching cloudstream.data.dir in the same JVM must immediately redirect all derived folders
        System.setProperty("cloudstream.data.dir", dirB.absolutePath)
        assertEquals(dirB.canonicalPath, PlatformPaths.appDataDir.canonicalPath)
        assertEquals(File(dirB, "cache/plugins").canonicalPath, PlatformPaths.pluginCacheDir.canonicalPath)
        assertEquals(File(dirB, "Sandbox").canonicalPath, PlatformPaths.sandboxDir.canonicalPath)
        assertEquals(File(dirB, "Extensions").canonicalPath, PlatformPaths.extensionsDir.canonicalPath)
        assertEquals(File(dirB, "shared_prefs").canonicalPath, PlatformPaths.sharedPrefsDir.canonicalPath)
        assertNotEquals(File(dirA, "cache/plugins").canonicalPath, PlatformPaths.pluginCacheDir.canonicalPath)
        assertTrue(PlatformPaths.pluginCacheDir.exists())
        assertTrue(PlatformPaths.sandboxDir.exists())

        // Custom screenshot directory override via DesktopDataStore
        val shotsDir = File(dirB, "custom_shots")
        DesktopDataStore.isWorkerMode = true
        DesktopDataStore.setKey("player_screenshot_dir", shotsDir.absolutePath)
        assertEquals(shotsDir.canonicalPath, PlatformPaths.screenshotsDir.canonicalPath)
        assertTrue(shotsDir.exists())
    }

    @Test
    fun `appDataDir fails closed when cloudstream test isolated is true and data dir is cleared`() {
        System.setProperty("cloudstream.test.isolated", "true")
        System.clearProperty("cloudstream.data.dir")

        assertFailsWith<IllegalStateException> {
            PlatformPaths.appDataDir
        }
    }
}
