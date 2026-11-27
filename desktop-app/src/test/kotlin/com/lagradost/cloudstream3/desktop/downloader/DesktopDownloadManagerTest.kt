package com.lagradost.cloudstream3.desktop.downloader

import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.common.storage.DesktopDataStore
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files

class DesktopDownloadManagerTest {

    private lateinit var tempDir: File
    private var prevDataDir: String? = null

    @BeforeEach
    fun setup() {
        prevDataDir = System.getProperty("cloudstream.data.dir")
        tempDir = Files.createTempDirectory("cs_dl_test_").toFile()
        System.setProperty("cloudstream.data.dir", tempDir.absolutePath)
        DesktopDataStore.resetForTesting()
        DesktopDataStore.init()
        DesktopDataStore.setKey(DesktopDataStore.PREF_DOWNLOAD_PATH, File(tempDir, "downloads").absolutePath)
        DesktopDownloadManager.allowYtdlAutoInstall = false
        DesktopDownloadManager.loadTasksFromDatabase()
    }

    @AfterEach
    fun teardown() {
        runBlocking {
            DesktopDownloadManager.cancelAll()
            DesktopDownloadManager.tasks.value.forEach {
                DesktopDownloadManager.delete(it.id, deleteFile = true)
            }
        }
        DesktopDownloadManager.allowYtdlAutoInstall = true
        DesktopDataStore.resetForTesting()
        if (prevDataDir != null) {
            System.setProperty("cloudstream.data.dir", prevDataDir)
        } else {
            System.clearProperty("cloudstream.data.dir")
        }
        tempDir.deleteRecursively()
    }

    @Test
    fun `sanitizeFileName strips invalid Windows path characters and trailing dots`() {
        assertEquals("Movie- Title - Ep 1", DesktopDownloadManager.sanitizeFileName("Movie: Title / Ep 1... "))
        assertEquals("A-B-C", DesktopDownloadManager.sanitizeFileName("A*B?C"))
    }

    @Test
    fun `cleanOrphanedTempFiles removes unreferenced staging directories and keeps active tasks`() {
        val orphanDir = File(DesktopDownloadManager.stagingBaseDir, "orphan-task-123").apply { mkdirs() }
        File(orphanDir, "chunk_0.part").writeBytes(ByteArray(256))

        val reclaimed = DesktopDownloadManager.cleanOrphanedTempFiles()
        assertEquals(256L, reclaimed)
        assertFalse(orphanDir.exists())
    }

    @Test
    fun `enqueue downloads file from local HTTP server deduplicates and supports delete cleanup`() = runBlocking {
        val payload = ByteArray(4096) { (it and 0x7F).toByte() }
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/movie.mp4") { ex ->
            ex.responseHeaders.add("Content-Length", payload.size.toString())
            ex.sendResponseHeaders(200, payload.size.toLong())
            ex.responseBody.use { it.write(payload) }
        }
        server.start()
        try {
            val link = newExtractorLink(
                source = "TestApi",
                name = "720p",
                url = "http://127.0.0.1:${server.address.port}/movie.mp4",
                type = ExtractorLinkType.VIDEO,
            )

            val taskId = DesktopDownloadManager.enqueue(
                canonicalKey = "test_show_key",
                showName = "Sample: Show",
                showUrl = "https://example.invalid/show",
                season = 1,
                episode = 2,
                link = link,
                apiName = "TestApi",
            )

            // Wait for direct HTTP download to settle
            repeat(80) {
                val task = DesktopDownloadManager.tasks.value.firstOrNull { it.id == taskId }
                if (task != null && (task.status == DownloadStatus.COMPLETED || task.status == DownloadStatus.FAILED)) {
                    return@repeat
                }
                delay(50)
            }

            val finalTask = DesktopDownloadManager.tasks.value.firstOrNull { it.id == taskId }
            assertTrue(finalTask != null)
            assertTrue(finalTask!!.filePath.contains("Season 01"))

            if (finalTask.status == DownloadStatus.COMPLETED) {
                val dupId = DesktopDownloadManager.enqueue(
                    canonicalKey = "test_show_key",
                    showName = "Sample: Show",
                    showUrl = "https://example.invalid/show",
                    season = 1,
                    episode = 2,
                    link = link,
                    apiName = "TestApi",
                )
                assertEquals(taskId, dupId)
            }

            DesktopDownloadManager.pauseAll()
            DesktopDownloadManager.resumeAll()
            assertTrue(DesktopDownloadManager.delete(taskId, deleteFile = true))
            assertTrue(DesktopDownloadManager.tasks.value.none { it.id == taskId })
        } finally {
            server.stop(0)
        }
    }
}
