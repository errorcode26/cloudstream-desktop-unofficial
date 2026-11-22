package com.lagradost.cloudstream3.desktop.ui.screens.player

import com.lagradost.cloudstream3.utils.ExtractorLink
import java.net.URI
import java.time.Instant
import java.util.Locale

object PlayerErrorDiagnostics {

    fun isFatalPlaybackError(reason: String): Boolean {
        val r = reason.lowercase(Locale.US)
        return r.contains("unsupported stream format") ||
            r.contains("format unsupported") ||
            r.contains("no audio/video") ||
            r.contains("torrent stream error") ||
            r.contains("p2p torrent streaming is disabled") ||
            r.contains("torrserver engine is not installed") ||
            r.contains("400") ||
            r.contains("401") ||
            r.contains("403") ||
            r.contains("404") ||
            r.contains("405") ||
            r.contains("410") ||
            r.contains("428") ||
            r.contains("500") ||
            r.contains("502") ||
            r.contains("503") ||
            r.contains("504") ||
            r.contains("server error") ||
            r.contains("bad gateway") ||
            r.contains("service unavailable") ||
            r.contains("empty / eof") ||
            r.contains("connection refused") ||
            r.contains("resolution failed") ||
            r.contains("timed out") ||
            r.contains("code -13") ||
            r.contains("error=-13") ||
            r.contains("demuxer") ||
            r.contains("immediate exit requested") ||
            r.contains("drm") ||
            r.contains("cenc") ||
            r.contains("device removed") ||
            r.contains("dxgi") ||
            r.contains("encrypted") ||
            r.contains("corrupt stream")
    }

    fun sanitizePlaybackErrorReason(reason: String): String {
        return when {
            reason.contains("403", ignoreCase = true) -> "403 Forbidden"
            reason.contains("404", ignoreCase = true) -> "404 Not Found"
            reason.contains("timed out", ignoreCase = true) || reason.contains("timeout", ignoreCase = true) -> "Connection Timed Out"
            reason.contains("refused", ignoreCase = true) -> "Connection Refused"
            reason.contains("encrypted", ignoreCase = true) || reason.contains("cenc", ignoreCase = true) || reason.contains("drm", ignoreCase = true) -> "Encrypted Stream (DRM)"
            reason.contains("device reset", ignoreCase = true) || reason.contains("dxgi", ignoreCase = true) -> "Hardware Decoder Error"
            reason.isNotBlank() -> reason.take(30)
            else -> "Playback Error"
        }
    }

    fun buildDiagnosticsReport(
        showName: String,
        epNum: String,
        providerName: String,
        reason: String,
        links: List<ExtractorLink>,
        failedLinks: Map<String, String>,
    ): String {
        val totalCandidates = links.size
        return buildString {
            appendLine("=== Playback Diagnostics Report ===")
            appendLine("Title: $showName $epNum".trim())
            appendLine("Provider: $providerName")
            appendLine("Total Sources Discovered: $totalCandidates")
            appendLine("Terminal Reason: $reason")
            appendLine("Failed Candidates Breakdown:")
            links.forEachIndexed { i, link ->
                val failure = failedLinks[link.url] ?: "Skipped"
                val host = try {
                    URI(link.url).host ?: "unknown-host"
                } catch (_: Throwable) {
                    "link-$i"
                }
                val q = if (link.quality > 0) "${link.quality}p" else "unknown"
                appendLine("  [${i + 1}] ${link.name} ($q, host: $host) -> $failure")
            }
            appendLine("Generated At: ${Instant.now()}")
        }
    }

    fun buildScrapeExceptionReport(
        providerName: String,
        ex: Throwable?,
    ): String {
        return buildString {
            appendLine("=== Scrape Exception ===")
            appendLine("Provider: $providerName")
            appendLine("Message: ${ex?.message ?: "Unknown error"}")
            if (ex != null) appendLine("Type: ${ex.javaClass.simpleName}")
            appendLine("Timestamp: ${Instant.now()}")
        }
    }
}
