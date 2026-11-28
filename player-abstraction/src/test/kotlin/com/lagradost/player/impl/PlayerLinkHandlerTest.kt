package com.lagradost.player.impl

import com.lagradost.cloudstream3.newAudioFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkPlayList
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.PlayListItem
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Base64

class PlayerLinkHandlerTest {

    private val tempFiles = mutableListOf<File>()

    @AfterEach
    fun cleanupFiles() {
        tempFiles.forEach { runCatching { it.delete() } }
        tempFiles.clear()
    }

    @Test
    fun `validate handles empty urls magnet torrents progressives hls and playlists`() = runBlocking {
        val emptyLink = newExtractorLink("Src", "Empty", "   ", ExtractorLinkType.VIDEO)
        assertTrue(PlayerLinkHandler.validate(emptyLink).isFailure)

        val ftpLink = newExtractorLink("Src", "Ftp", "ftp://unsupported.invalid/file.mp4", ExtractorLinkType.VIDEO)
        assertTrue(PlayerLinkHandler.validate(ftpLink).isFailure)

        val magnetLink = newExtractorLink("Src", "Mag", "magnet:?xt=urn:btih:0123456789ABCDEF", ExtractorLinkType.MAGNET)
        assertTrue(PlayerLinkHandler.validate(magnetLink).isSuccess)

        val videoLink = newExtractorLink("Src", "1080p\nStream", "https://cdn.example.invalid/movie.mp4", ExtractorLinkType.VIDEO) {
            referer = "https://ref.example.invalid/"
            headers = mapOf("X-Custom" to "val1")
        }
        val videoRes = PlayerLinkHandler.validate(videoLink, explicitTitle = "Custom\tTitle\n\r")
        assertTrue(videoRes.isSuccess)
        val validated = videoRes.getOrThrow()
        assertEquals(PlayerLinkHandler.StreamKind.PROGRESSIVE, validated.streamKind)
        assertEquals("Custom\tTitle", validated.displayTitle)
        assertEquals("https://ref.example.invalid/", validated.headers.entries.first { it.key.equals("Referer", ignoreCase = true) }.value)
        assertEquals("val1", validated.headers["X-Custom"])

        val playlistLink = ExtractorLinkPlayList(
            source = "Src",
            name = "Playlist",
            playlist = listOf(
                PlayListItem("https://cdn.example.invalid/part1.ts", 5_000_000L),
                PlayListItem("https://cdn.example.invalid/part2.ts", 7_000_000L),
            ),
            referer = "",
            quality = 1080,
            type = ExtractorLinkType.VIDEO,
        )
        val playlistRes = PlayerLinkHandler.validate(playlistLink).getOrThrow()
        assertFalse(playlistRes.useUrlFile)
        val edlFile = File(playlistRes.url)
        tempFiles.add(edlFile)
        assertTrue(edlFile.exists())
        val edlText = edlFile.readText()
        assertTrue(edlText.contains("# mpv EDL v0"))
        assertTrue(edlText.contains("%5000000%https://cdn.example.invalid/part1.ts"))
        assertTrue(edlText.contains("%7000000%https://cdn.example.invalid/part2.ts"))
    }

    @Test
    fun `config cli headers vlc headers and mpv preference build deterministically`() = runBlocking {
        val audio = newAudioFile("https://cdn.example.invalid/jpn.m4a")
        val headers = linkedMapOf(
            "User-Agent" to "CustomAgent/1.0",
            "Referer" to "https://ref.example.invalid/page",
            "Authorization" to "Bearer abc,def\r\nnewline",
        )
        val conf = PlayerLinkHandler.buildMpvConfigContent(
            headers = headers,
            subtitles = listOf("C:\\subs\\movie\"test\".srt", "   "),
            audioTracks = listOf(audio),
        )
        assertTrue(conf.contains("user-agent=\"CustomAgent/1.0\""))
        assertTrue(conf.contains("referrer=\"https://ref.example.invalid/page\""))
        assertTrue(conf.contains("sub-files-append=\"C:\\\\subs\\\\movie\\\"test\\\".srt\""))
        assertTrue(conf.contains("audio-file-append=\"https://cdn.example.invalid/jpn.m4a\""))

        val writtenConf = PlayerLinkHandler.writeMpvConfig(headers, listOf("sub.vtt"), listOf(audio))
        tempFiles.add(writtenConf)
        assertTrue(writtenConf.exists())

        val cliArgs = PlayerLinkHandler.buildHeadersCliArg(headers)
        assertEquals(2, cliArgs.size)
        assertTrue(cliArgs[0].startsWith("--demuxer-lavf-o-append=headers=%"))
        assertFalse(cliArgs[1].contains("\n"))
        assertTrue(cliArgs[1].contains("abc\\,def"))

        val vlc = PlayerLinkHandler.buildVlcExtraHeaders(mapOf("X-Test" to "123\r\n"))
        assertEquals("X-Test: 123", vlc)

        assertTrue(PlayerLinkHandler.buildHeadersCliArg(emptyMap()).isEmpty())

        val extraHeaderLink = newExtractorLink("Src", "WithAuth", "https://cdn.example.invalid/v.mp4", ExtractorLinkType.VIDEO) {
            this.headers = mapOf("Authorization" to "Bearer token")
        }
        assertTrue(PlayerLinkHandler.shouldPreferMpv(extraHeaderLink))

        val plainLink = newExtractorLink("Src", "Plain", "https://cdn.example.invalid/v.mp4", ExtractorLinkType.VIDEO)
        assertFalse(PlayerLinkHandler.shouldPreferMpv(plainLink))
    }

    @Test
    fun `resume calculation completion detection title sanitization and hex key normalization`() {
        assertEquals("Movie \tName", PlayerLinkHandler.sanitizeDisplayTitle("  Movie\n\tName \r "))
        assertEquals("CloudStream", PlayerLinkHandler.sanitizeDisplayTitle("   "))

        assertEquals(0L, PlayerLinkHandler.resumeStartSeconds(5L, 20L)) // too short (<30s)
        assertEquals(0L, PlayerLinkHandler.resumeStartSeconds(950L, 1000L)) // >=85%
        assertEquals(0L, PlayerLinkHandler.resumeStartSeconds(850L, 1000L)) // exact 85% boundary
        assertEquals(835L, PlayerLinkHandler.resumeStartSeconds(840L, 1000L)) // just under 85%
        assertEquals(295L, PlayerLinkHandler.resumeStartSeconds(300L, 1000L)) // 5s rewind context buffer
        assertTrue(PlayerLinkHandler.isCompleted(910L, 1000L))
        assertTrue(PlayerLinkHandler.isCompleted(850L, 1000L))
        assertFalse(PlayerLinkHandler.isCompleted(849L, 1000L))
        assertFalse(PlayerLinkHandler.isCompleted(500L, 1000L))
        assertFalse(PlayerLinkHandler.isCompleted(100L, 0L))

        val hex = "00112233445566778899aabbccddeeff"
        assertEquals(hex, PlayerLinkHandler.normalizeHexKey("00112233445566778899AABBCCDDEEFF"))

        val rawBytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val b64Url = Base64.getUrlEncoder().withoutPadding().encodeToString(rawBytes)
        assertEquals(hex, PlayerLinkHandler.normalizeHexKey(b64Url))
        assertNull(PlayerLinkHandler.normalizeHexKey(""))
    }

    @Test
    fun `writeUrlListFile writes valid m3u`() {
        val m3u = PlayerLinkHandler.writeUrlListFile("test_m3u_", "Sample Movie", "https://cdn.example.invalid/m.mp4")
        tempFiles.add(m3u)
        val lines = m3u.readLines()
        assertEquals("#EXTM3U", lines[0])
        assertEquals("#EXTINF:-1,Sample Movie", lines[1])
        assertEquals("https://cdn.example.invalid/m.mp4", lines[2])
    }
}
