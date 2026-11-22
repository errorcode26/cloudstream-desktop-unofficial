package com.lagradost.cloudstream3.desktop.ui.screens.player

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerErrorDiagnosticsTest {

    @Test
    fun testIsFatalPlaybackError() {
        assertTrue(PlayerErrorDiagnostics.isFatalPlaybackError("Server returned 404 Not Found"))
        assertTrue(PlayerErrorDiagnostics.isFatalPlaybackError("HTTP 403 Forbidden"))
        assertTrue(PlayerErrorDiagnostics.isFatalPlaybackError("Demuxer failed to parse header"))
        assertTrue(PlayerErrorDiagnostics.isFatalPlaybackError("Encrypted stream (CENC/DRM)"))
        assertTrue(PlayerErrorDiagnostics.isFatalPlaybackError("dxgi device removed"))

        assertFalse(PlayerErrorDiagnostics.isFatalPlaybackError("Packet jitter detected"))
        assertFalse(PlayerErrorDiagnostics.isFatalPlaybackError("Buffer underrun, seeking forward"))
    }

    @Test
    fun testSanitizePlaybackErrorReason() {
        assertEquals("403 Forbidden", PlayerErrorDiagnostics.sanitizePlaybackErrorReason("HTTP 403 response"))
        assertEquals("404 Not Found", PlayerErrorDiagnostics.sanitizePlaybackErrorReason("Resource was 404"))
        assertEquals("Connection Timed Out", PlayerErrorDiagnostics.sanitizePlaybackErrorReason("Read timed out after 10000ms"))
        assertEquals("Connection Refused", PlayerErrorDiagnostics.sanitizePlaybackErrorReason("Connection refused by peer"))
        assertEquals("Encrypted Stream (DRM)", PlayerErrorDiagnostics.sanitizePlaybackErrorReason("DRM key required"))
        assertEquals("Hardware Decoder Error", PlayerErrorDiagnostics.sanitizePlaybackErrorReason("dxgi device reset"))
    }

    @Test
    fun testBuildDiagnosticsReport() {
        @Suppress("DEPRECATION")
        val link = ExtractorLink(
            source = "Direct",
            name = "1080p Stream",
            url = "https://example.com/stream.m3u8",
            referer = "",
            quality = Qualities.P1080.value,
            type = ExtractorLinkType.M3U8,
        )
        val failed = mapOf(link.url to "404 Not Found")
        val report = PlayerErrorDiagnostics.buildDiagnosticsReport(
            showName = "Test Show",
            epNum = "E1",
            providerName = "TestProvider",
            reason = "All candidates failed",
            links = listOf(link),
            failedLinks = failed,
        )

        assertTrue(report.contains("=== Playback Diagnostics Report ==="))
        assertTrue(report.contains("Title: Test Show E1"))
        assertTrue(report.contains("Provider: TestProvider"))
        assertTrue(report.contains("Total Sources Discovered: 1"))
        assertTrue(report.contains("1080p Stream (1080p, host: example.com) -> 404 Not Found"))
    }
}
