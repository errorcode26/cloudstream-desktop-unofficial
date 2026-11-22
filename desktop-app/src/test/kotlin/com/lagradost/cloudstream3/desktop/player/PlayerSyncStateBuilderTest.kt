package com.lagradost.cloudstream3.desktop.player

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PlayerSyncStateBuilderTest {

    @Test
    fun `formatByteSize returns null for bytes under one megabyte`() {
        assertNull(PlayerSyncStateBuilder.formatByteSize(0L))
        assertNull(PlayerSyncStateBuilder.formatByteSize(512L * 1024L))
        assertNull(PlayerSyncStateBuilder.formatByteSize(1024L * 1024L - 1L))
    }

    @Test
    fun `formatByteSize formats megabytes accurately`() {
        val result = PlayerSyncStateBuilder.formatByteSize(50L * 1024L * 1024L)
        assertNotNull(result)
        assertEquals("50 MB", result)
    }

    @Test
    fun `formatByteSize formats gigabytes accurately`() {
        val result = PlayerSyncStateBuilder.formatByteSize((2.5 * 1024.0 * 1024.0 * 1024.0).toLong())
        assertNotNull(result)
        assertEquals("2.5 GB", result)
    }

    @Test
    fun `buildAppStateUpdatePayload maps basic properties accurately`() {
        val payload = PlayerSyncStateBuilder.buildAppStateUpdatePayload(
            volume = 75f,
            isMuted = false,
            isBuffering = false,
            isLoading = true,
            loadingStatusText = "Probing streams...",
            fileSizeBytes = 1024L * 1024L * 100L,
        )

        assertEquals("app_state_update", payload.type)
        assertEquals(75f, payload.volume)
        assertFalse(payload.isMuted)
        assertTrue(payload.isAppLoading)
        assertEquals("Probing streams...", payload.loadingStatusText)
        assertEquals("100 MB", payload.fileSize)
    }
}
