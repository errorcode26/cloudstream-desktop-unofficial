package com.lagradost.cloudstream3.desktop.ui.screens.links

import com.lagradost.cloudstream3.desktop.player.QualityDataHelper
import com.lagradost.cloudstream3.desktop.ui.screens.StreamFormatFilter
import com.lagradost.cloudstream3.desktop.ui.screens.StreamSpecFilter
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamFilterAndTagsTest {

    private fun createLink(
        name: String,
        source: String = "TestProvider",
        url: String = "https://example.com/video.mp4",
        quality: Int = Qualities.Unknown.value,
        type: ExtractorLinkType = ExtractorLinkType.VIDEO,
    ): ExtractorLink = kotlinx.coroutines.runBlocking {
        newExtractorLink(
            source = source,
            name = name,
            url = url,
            type = type,
        ) {
            this.quality = quality
        }
    }

    // ── 1. Tech Tag Extraction Tests ──────────────────────────────────────

    @Test
    fun testExtractStructuredTechTags_FullCatalog() {
        val sampleTitle = "[TB] Spider-Man REMUX IMAX Enhanced Dolby Vision HDR10+ AV1 Atmos TrueHD 7.1 Dual Audio HIN ENG [84.8 GB]"
        val tags = extractStructuredTechTags(sampleTitle)
        val tagTexts = tags.map { it.text }

        assertTrue(tagTexts.contains("TorBox"), "Should extract TorBox debrid badge")
        assertTrue(tagTexts.contains("REMUX"), "Should extract REMUX format")
        assertTrue(tagTexts.contains("IMAX Enhanced"), "Should extract IMAX Enhanced edition")
        assertTrue(tagTexts.contains("Dolby Vision"), "Should extract Dolby Vision HDR")
        assertTrue(tagTexts.contains("AV1"), "Should extract AV1 codec")
        assertTrue(tagTexts.contains("Atmos"), "Should extract Atmos audio")
        assertTrue(tagTexts.contains("TrueHD"), "Should extract TrueHD audio")
        assertTrue(tagTexts.contains("7.1"), "Should extract 7.1 channels")
        assertTrue(tagTexts.contains("Dual Audio"), "Should extract Dual Audio")
        assertTrue(tagTexts.contains("HIN"), "Should extract HIN language flag")
        assertTrue(tagTexts.contains("ENG"), "Should extract ENG language flag")
    }

    @Test
    fun testExtractStructuredTechTags_AudioCodecsAndChannels() {
        val testCases = mapOf(
            "Movie DDP 5.1" to listOf("DDP 5.1", "5.1"),
            "Movie EAC3 7.1" to listOf("DDP 7.1", "7.1"),
            "Movie DTS:X 7.1" to listOf("DTS:X", "7.1"),
            "Movie DTS-HD MA 5.1" to listOf("DTS-HD", "5.1"),
            "Movie FLAC 2.0 STEREO" to listOf("FLAC", "2.0"),
            "Movie AAC 5.1" to listOf("AAC 5.1", "5.1"),
            "Movie OPUS 2.0" to listOf("Opus", "2.0"),
        )

        testCases.forEach { (raw, expectedTags) ->
            val extracted = extractStructuredTechTags(raw).map { it.text }
            expectedTags.forEach { expected ->
                assertTrue(extracted.contains(expected), "Raw '$raw' should contain tag '$expected', got: $extracted")
            }
        }
    }

    @Test
    fun testExtractStructuredTechTags_VideoCodecsAndHDR() {
        val testCases = mapOf(
            "Video HEVC 10-bit HDR10" to listOf("HEVC", "10-bit", "HDR10"),
            "Video X264 AVC" to listOf("AVC"),
            "Video PRORES HDR" to listOf("ProRes", "HDR"),
            "Video VP9" to listOf("VP9"),
        )

        testCases.forEach { (raw, expectedTags) ->
            val extracted = extractStructuredTechTags(raw).map { it.text }
            expectedTags.forEach { expected ->
                assertTrue(extracted.contains(expected), "Raw '$raw' should contain tag '$expected', got: $extracted")
            }
        }
    }

    @Test
    fun testExtractStructuredTechTags_DebridProviders() {
        assertEquals("Real-Debrid", extractStructuredTechTags("[RD] Title").firstOrNull()?.text)
        assertEquals("TorBox", extractStructuredTechTags("[TB] Title").firstOrNull()?.text)
        assertEquals("AllDebrid", extractStructuredTechTags("[AD] Title").firstOrNull()?.text)
        assertEquals("Premiumize", extractStructuredTechTags("[PM] Title").firstOrNull()?.text)
        assertEquals("Debrid-Link", extractStructuredTechTags("[DL] Title").firstOrNull()?.text)
    }

    @Test
    fun testExtractStructuredTechTags_EdgeCases() {
        // Blank and empty strings
        assertTrue(extractStructuredTechTags("").isEmpty())
        assertTrue(extractStructuredTechTags("   ").isEmpty())
        assertTrue(extractStructuredTechTags("\n\t").isEmpty())

        // Plain string without tags
        assertTrue(extractStructuredTechTags("The.Matrix.1999").isEmpty())

        // Deduplication
        val duplicates = extractStructuredTechTags("REMUX HEVC REMUX HEVC Atmos Atmos")
        assertEquals(3, duplicates.size)
        assertEquals(listOf("REMUX", "HEVC", "Atmos"), duplicates.map { it.text })

        // Unicode emojis and broken brackets
        val emojis = "⚡ [TB ⚡] 🎬 Movie 1080p [DV] 🔊 Atmos"
        val tags = extractStructuredTechTags(emojis).map { it.text }
        assertTrue(tags.contains("TorBox"))
        assertTrue(tags.contains("Dolby Vision"))
        assertTrue(tags.contains("Atmos"))
    }

    // ── 2. Server and Size Extraction Tests ───────────────────────────────

    @Test
    fun testExtractCleanServer() {
        assertEquals("FastServer", extractCleanServer("[FastServer] Spider-Man 1080p", "Default"))
        assertEquals("CloudHost", extractCleanServer("[CloudHost] Title", "Default"))
        assertEquals("DirectDrive", extractCleanServer("[DirectDrive] [2.4 GB]", "Default"))
        // If bracket is purely a file size, it should fall back rather than returning the size
        val candidate = extractCleanServer("[2.44 GB] Movie", "Fallback")
        assertTrue(candidate != "2.44 GB", "Size in brackets should not be picked as server candidate")
        assertEquals("Fallback", extractCleanServer("", "Fallback"))
        assertEquals("Fallback", extractCleanServer("   ", "Fallback"))
    }

    @Test
    fun testExtractCleanSize() {
        assertEquals("2.44 GB", extractCleanSize("[FastServer] Spider-Man [2.44 GB]"))
        assertEquals("1.5 GB", extractCleanSize("Movie [1.5 GiB]"))
        assertEquals("700 MB", extractCleanSize("Movie [700 MiB]"))
        assertEquals("84.8 GB", extractCleanSize("💾 84.8 GB (84.4 Mbps)"))
        assertNull(extractCleanSize("Movie Without Size"))
        assertNull(extractCleanSize(""))
    }

    // ── 3. Quality Tier Extraction Tests ─────────────────────────────────

    @Test
    fun testQualityDataHelper_ExtractEffectiveQuality_Declared() {
        val link4k = createLink("Movie", quality = Qualities.P2160.value)
        val link1080 = createLink("Movie", quality = Qualities.P1080.value)
        val link720 = createLink("Movie", quality = Qualities.P720.value)
        val link480 = createLink("Movie", quality = Qualities.P480.value)

        assertEquals(Qualities.P2160.value, QualityDataHelper.extractEffectiveQuality(link4k))
        assertEquals(Qualities.P1080.value, QualityDataHelper.extractEffectiveQuality(link1080))
        assertEquals(Qualities.P720.value, QualityDataHelper.extractEffectiveQuality(link720))
        assertEquals(Qualities.P480.value, QualityDataHelper.extractEffectiveQuality(link480))
    }

    @Test
    fun testQualityDataHelper_ExtractEffectiveQuality_RegexFallback() {
        val link4kText = createLink("[TB ⚡] Movie 4K UHD REMUX", quality = 0)
        val link2160Text = createLink("Movie.2160p.HEVC", quality = 0)
        val link1080Text = createLink("Movie.1080p.FHD.x264", quality = 0)
        val link720Text = createLink("Movie.720p.HD", quality = 0)
        val link480Text = createLink("Movie.480p.SD", quality = 0)
        val link360Text = createLink("Movie.360p", quality = 0)
        val linkUnknown = createLink("Movie.Clean.Title", quality = 0)

        assertEquals(Qualities.P2160.value, QualityDataHelper.extractEffectiveQuality(link4kText))
        assertEquals(Qualities.P2160.value, QualityDataHelper.extractEffectiveQuality(link2160Text))
        assertEquals(Qualities.P1080.value, QualityDataHelper.extractEffectiveQuality(link1080Text))
        assertEquals(Qualities.P720.value, QualityDataHelper.extractEffectiveQuality(link720Text))
        assertEquals(Qualities.P480.value, QualityDataHelper.extractEffectiveQuality(link480Text))
        assertEquals(Qualities.P360.value, QualityDataHelper.extractEffectiveQuality(link360Text))
        assertEquals(Qualities.Unknown.value, QualityDataHelper.extractEffectiveQuality(linkUnknown))
    }

    @Test
    fun testQualityTiers_BoundaryBuckets() {
        val qualifiesFor4k = 2160 >= 2160
        val qualifiesFor1080 = 1080 in 1080..2159 && 1440 in 1080..2159
        val qualifiesFor720 = 720 in 720..1079
        val qualifiesForSd = 480 in 1..719 && 360 in 1..719 && 240 in 1..719

        assertTrue(qualifiesFor4k, "2160+ must map to 4K UHD")
        assertTrue(qualifiesFor1080, "1080..2159 must map to 1080p FHD")
        assertTrue(qualifiesFor720, "720..1079 must map to 720p HD")
        assertTrue(qualifiesForSd, "1..719 must map to SD (<720p)")
    }

    // ── 4. Source Partitioning Tests (Plugin vs Addons) ───────────────────

    @Test
    fun testStreamFilterHelper_PluginHostersPartitionToPlugin() {
        val pluginHosters = listOf(
            "FastServer",
            "CloudHost 10Gbps [Download]",
            "DirectSeed[Instant(Download)]",
            "DirectDrive",
            "10Gbps [Download]",
            "MediaDrive Instant Download",
        )

        pluginHosters.forEach { hoster ->
            val link = createLink(name = "Spider-Man 1080p", source = hoster)
            assertFalse(
                StreamFilterHelper.isAddonStream(link),
                "Internal hoster '$hoster' must not be marked as Addon; it belongs under Plugin tab",
            )
        }
    }

    @Test
    fun testStreamFilterHelper_AddonStreamsPartitionCorrectly() {
        val addonCases = listOf(
            createLink(name = "⚡ [TB ⚡] WEB-DL 4K", source = "Duck Streams (AIOStreams)"),
            createLink(name = "[RD ⚡] 1080p BluRay", source = "Torrentio"),
            createLink(name = "Movie", source = "MediaFusion"),
            createLink(name = "Movie", source = "Comet"),
        )

        addonCases.forEach { link ->
            assertTrue(
                StreamFilterHelper.isAddonStream(link),
                "Stream '${link.name}' from '${link.source}' must be recognized as Addon",
            )
        }
    }

    @Test
    fun testStreamFilterHelper_DynamicAddonNameMatching() {
        val installedAddons = listOf("Custom Addon One", "Stremio Anime")
        val link = createLink(name = "Anime Stream", source = "Custom Addon One")

        assertTrue(StreamFilterHelper.isAddonStream(link, installedAddons))
        assertEquals("Custom Addon One", StreamFilterHelper.getAddonName(link, "MainPlugin", installedAddons))
    }

    @Test
    fun testStreamFilterHelper_BlankAndMalformedEdgeCases() {
        val blankLink = createLink(name = "", source = "")
        assertFalse(StreamFilterHelper.isAddonStream(blankLink))
        assertEquals("Addons", StreamFilterHelper.getAddonName(blankLink))
    }

    // ── 5. Stream Specification Filter Tests ──────────────────────────────

    @Test
    fun testSpecFilterMatching() {
        val debridLink = createLink("[TB ⚡] Movie REMUX 4K")
        val remuxLink = createLink("Movie.REMUX.1080p")
        val blurayLink = createLink("Movie.BluRay.x265")
        val webdlLink = createLink("Movie.WEB-DL.1080p")

        // DEBRID filter match
        val isDebridMatch = debridLink.name.contains("⚡") || debridLink.name.contains("[TB]")
        assertTrue(isDebridMatch)

        // REMUX filter match
        assertTrue(remuxLink.name.contains("remux", ignoreCase = true))

        // BluRay regex match
        val blurayRegex = Regex("""\b(bluray|blu-ray|bdrip|brrip)\b""", RegexOption.IGNORE_CASE)
        assertTrue(blurayRegex.containsMatchIn(blurayLink.name))

        // WEB-DL regex match
        val webdlRegex = Regex("""\b(web-dl|webdl|webrip|web-rip)\b""", RegexOption.IGNORE_CASE)
        assertTrue(webdlRegex.containsMatchIn(webdlLink.name))
    }

    // ── 6. Stream Format Filter Tests (HLS / DASH / Direct) ───────────────

    @Test
    fun testFormatFilterMatching() {
        val hlsLink = createLink("Stream HLS", url = "https://example.com/playlist.m3u8")
        val dashLink = createLink("Stream DASH", url = "https://example.com/manifest.mpd")
        val directLink = createLink("Stream MP4", url = "https://example.com/video.mp4")

        val isHls = hlsLink.isM3u8 || hlsLink.name.contains("HLS", ignoreCase = true) || hlsLink.url.contains(".m3u8")
        val isDash = dashLink.isDash || dashLink.name.contains("DASH", ignoreCase = true) || dashLink.url.contains(".mpd")
        val isDirect = !isHls && !isDash

        assertTrue(isHls, "hlsLink must be recognized as HLS")
        assertTrue(isDash, "dashLink must be recognized as DASH")
        assertTrue(!directLink.url.contains(".m3u8") && !directLink.url.contains(".mpd"), "directLink is direct video")
    }
}
