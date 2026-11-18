package com.lagradost.cloudstream3.desktop.player

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.desktop.player.ipc.PlayerInboundEvent
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlayerInboundEventTest {

    private val mapper = jacksonObjectMapper()

    private fun parseEvent(rawJson: String): PlayerInboundEvent {
        val rootNode = mapper.readTree(rawJson)
        return PlayerInboundEvent.fromJson(rootNode, rawJson)
    }

    // --- 1. Video Processing & Deband IPC ---

    @Test
    fun testParse_SetDebandQuality() {
        listOf("off", "light", "medium", "strong").forEach { level ->
            val json = """{"type":"setDebandQuality","value":"$level"}"""
            val event = parseEvent(json)
            assertIs<PlayerInboundEvent.SetDebandQuality>(event)
            assertEquals(level, event.quality)
        }
    }

    @Test
    fun testParse_ToggleDeband() {
        val jsonTrue = """{"type":"toggleDeband","value":"true"}"""
        val eventTrue = parseEvent(jsonTrue)
        assertIs<PlayerInboundEvent.ToggleDeband>(eventTrue)
        assertTrue(eventTrue.enabled)
    }

    // --- 2. MPV Property Dispatch IPC ---

    @Test
    fun testParse_SetMpvProperty_HdrToneMapping() {
        val json = """{"type":"setMpvProperty","value":"tone-mapping:bt.2446a"}"""
        val event = parseEvent(json)
        assertIs<PlayerInboundEvent.SetMpvProperty>(event)
        assertEquals("tone-mapping", event.property)
        assertEquals("bt.2446a", event.value)
    }

    @Test
    fun testParse_SetMpvProperty_HdrComputePeak() {
        val json = """{"type":"setMpvProperty","value":"hdr-compute-peak:yes"}"""
        val event = parseEvent(json)
        assertIs<PlayerInboundEvent.SetMpvProperty>(event)
        assertEquals("hdr-compute-peak", event.property)
        assertEquals("yes", event.value)
    }

    @Test
    fun testParse_SetMpvProperty_Dithering() {
        val json = """{"type":"setMpvProperty","value":"dither:ordered"}"""
        val event = parseEvent(json)
        assertIs<PlayerInboundEvent.SetMpvProperty>(event)
        assertEquals("dither", event.property)
        assertEquals("ordered", event.value)
    }

    @Test
    fun testParse_SetSubtitlePosition() {
        val json = """{"type":"setSubtitlePosition","value":"85"}"""
        val event = parseEvent(json)
        assertIs<PlayerInboundEvent.SetSubtitlePosition>(event)
        assertEquals(85, event.pos)
    }

    @Test
    fun testParse_RequestSeekThumbnail() {
        val json = """{"type":"requestSeekThumbnail","timeMs":45000}"""
        val event = parseEvent(json)
        assertIs<PlayerInboundEvent.RequestSeekThumbnail>(event)
        assertEquals(45000L, event.timeMs)

        val jsonValueFallback = """{"type":"requestSeekThumbnail","value":"12000"}"""
        val eventFallback = parseEvent(jsonValueFallback)
        assertIs<PlayerInboundEvent.RequestSeekThumbnail>(eventFallback)
        assertEquals(12000L, eventFallback.timeMs)

        val jsonDecimalValue = """{"type":"requestSeekThumbnail","value":"12000.85"}"""
        val eventDecimal = parseEvent(jsonDecimalValue)
        assertIs<PlayerInboundEvent.RequestSeekThumbnail>(eventDecimal)
        assertEquals(12000L, eventDecimal.timeMs)
    }

    // --- 3. AppStateUpdatePayload Rendering Defaults ---

    @Test
    fun testAppStateUpdatePayload_Defaults() {
        val payload = AppStateUpdatePayload(
            volume = 100f,
            isMuted = false,
            isAppLoading = false,
            loadingStatusText = null,
        )

        assertEquals("spline", payload.hdrToneMapping)
        assertTrue(payload.hdrComputePeak)
        assertEquals("off", payload.debandQuality)
        assertEquals("fruit", payload.dithering)

        val jsonStr = mapper.writeValueAsString(payload)
        val tree = mapper.readTree(jsonStr)
        assertEquals("spline", tree.get("hdrToneMapping").asText())
        assertTrue(tree.get("hdrComputePeak").asBoolean())
        assertEquals("off", tree.get("debandQuality").asText())
        assertEquals("fruit", tree.get("dithering").asText())
    }

    // --- 4. Unknown Event Fallback ---

    @Test
    fun testParse_UnknownEvent() {
        val json = """{"type":"unsupported_dummy_action","value":"payload"}"""
        val event = parseEvent(json)
        assertIs<PlayerInboundEvent.Unknown>(event)
        assertEquals("unsupported_dummy_action", event.type)
        assertEquals("payload", event.rawValue)
    }
}
