package com.lagradost.cloudstream3.desktop.updates

import com.lagradost.cloudstream3.desktop.AppConfig
import com.lagradost.common.storage.DesktopDataStore
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class UnifiedUpdateManagerE2ETest {

    private lateinit var tempDir: File
    private var prevDataDir: String? = null

    @BeforeEach
    fun setup() {
        prevDataDir = System.getProperty("cloudstream.data.dir")
        tempDir = Files.createTempDirectory("cs_updates_test_").toFile()
        System.setProperty("cloudstream.data.dir", tempDir.absolutePath)
        DesktopDataStore.resetForTesting()
        DesktopDataStore.init()
        UnifiedUpdateManager.resetForTesting()
    }

    @AfterEach
    fun teardown() {
        UnifiedUpdateManager.resetForTesting()
        DesktopDataStore.resetForTesting()
        if (prevDataDir != null) {
            System.setProperty("cloudstream.data.dir", prevDataDir)
        } else {
            System.clearProperty("cloudstream.data.dir")
        }
        tempDir.deleteRecursively()
    }

    @Test
    fun `checkAppUpdate checkTorrServerUpdate checkYtDlpUpdate and history query MockWebServer`() = runBlocking {
        val samplePayload = "installer-binary-content".toByteArray()
        val digestHex = UnifiedUpdateManager.computeSha256(
            File(tempDir, "temp-asset.exe").apply { writeBytes(samplePayload) },
        )

        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val p = request.path ?: ""
                return when {
                    p.startsWith("/repos/${AppConfig.GITHUB_REPO}/releases") -> MockResponse().setResponseCode(200).setBody(
                        """
                        [
                          {
                            "tag_name": "v99.1.0",
                            "name": "v99.1.0 Release",
                            "body": "Bug fixes",
                            "html_url": "https://github.com/${AppConfig.GITHUB_REPO}/releases/tag/v99.1.0",
                            "published_at": "2026-01-01T00:00:00Z",
                            "prerelease": false,
                            "draft": false,
                            "assets": [
                              {
                                "name": "CloudStreamDesktop-Setup-99.1.0.exe",
                                "browser_download_url": "${server.url("/dl/CloudStreamDesktop-Setup-99.1.0.exe")}",
                                "size": ${samplePayload.size}
                              },
                              {
                                "name": "CloudStreamDesktop-Setup-99.1.0.exe.sha256",
                                "browser_download_url": "${server.url("/dl/checksums.txt")}",
                                "size": 120
                              }
                            ]
                          }
                        ]
                        """.trimIndent(),
                    )
                    p == "/dl/checksums.txt" -> MockResponse().setResponseCode(200).setBody(
                        "$digestHex  CloudStreamDesktop-Setup-99.1.0.exe\n",
                    )
                    p.startsWith("/repos/YouROK/TorrServer/releases/latest") -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "tag_name": "MatriX.999.0",
                          "name": "MatriX.999.0",
                          "body": "Notes",
                          "html_url": "https://example.invalid/torr",
                          "published_at": "2026-01-02T00:00:00Z",
                          "assets": []
                        }
                        """.trimIndent(),
                    )
                    p.startsWith("/repos/yt-dlp/yt-dlp/releases/latest") -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "tag_name": "2099.01.01",
                          "name": "2099.01.01",
                          "body": "Extractor update",
                          "html_url": "https://example.invalid/ytdlp",
                          "published_at": "2026-01-03T00:00:00Z",
                          "assets": []
                        }
                        """.trimIndent(),
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        try {
            UnifiedUpdateManager.githubApiBaseUrl = server.url("/").toString().trimEnd('/')
            UnifiedUpdateManager.appVersionOverride = "1.0.0"

            val appUpdate = UnifiedUpdateManager.checkAppUpdate(force = true)
            assertNotNull(appUpdate)
            assertEquals("v99.1.0", appUpdate!!.newVersion)
            assertEquals(digestHex, appUpdate.expectedSha256)
            assertEquals("CloudStreamDesktop-Setup-99.1.0.exe", appUpdate.assetName)

            UnifiedUpdateManager.ignoreUpdate(appUpdate)
            assertTrue(UnifiedUpdateManager.isUpdateIgnored(appUpdate))
            assertNull(UnifiedUpdateManager.checkAppUpdate(force = false))
            assertNotNull(UnifiedUpdateManager.checkAppUpdate(force = true))

            val torrUpdate = UnifiedUpdateManager.checkTorrServerUpdate(force = true)
            assertNotNull(torrUpdate)
            assertEquals("MatriX.999.0", torrUpdate!!.newVersion)

            val ytdlpUpdate = UnifiedUpdateManager.checkYtDlpUpdate(force = true)
            assertNotNull(ytdlpUpdate)
            assertEquals("2099.01.01", ytdlpUpdate!!.newVersion)

            val history = UnifiedUpdateManager.fetchReleaseHistory(force = true)
            assertEquals(1, history.size)
            assertEquals("v99.1.0", history.first().tag_name)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `checksum parser version comparison downloaded verification and installer cleanup work`() {
        val sample = """
            deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef  other.zip
            aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa *CloudStream-99.1.0.msi
        """.trimIndent()
        assertEquals(
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            UnifiedUpdateManager.parseSha256(sample, "CloudStream-99.1.0.msi"),
        )
        assertNull(UnifiedUpdateManager.parseSha256(sample, "missing.exe"))

        assertTrue(UnifiedUpdateManager.compareSemVer("2.0.0", "1.9.9") > 0)
        assertEquals(0, UnifiedUpdateManager.compareSemVer("1.2.3", "1.2.3"))
        assertTrue(UnifiedUpdateManager.compareTorrVersions("MatriX.145.0", "MatriX.144.1") > 0)

        val updatesDir = File(tempDir, "cleanup-test").apply { mkdirs() }
        val oldInstaller = File(updatesDir, "CloudStream-Setup-v1.0.0.exe").apply { writeText("old") }
        val newerInstaller = File(updatesDir, "CloudStream-Setup-v2.0.0.exe").apply { writeText("new") }
        UnifiedUpdateManager.cleanCompletedInstallers(targetDirectory = updatesDir, currentVersion = "1.5.0")
        assertFalse(oldInstaller.exists())
        assertTrue(newerInstaller.exists())
    }
}
