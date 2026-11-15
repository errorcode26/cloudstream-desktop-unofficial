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
