package com.lagradost.cloudstream3.desktop.player

import com.lagradost.cloudstream3.desktop.ui.screens.player.PlayerState
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class MpvTrackManagerTest {

    @Test
    fun testTrackManagerReset() {
        val playerState = PlayerState()
        val manager = MpvTrackManager(playerState, libProvider = { null })

        manager.hasAutoSwitchedAudio = true
        manager.hasAutoSwitchedSub = true

        manager.reset()

        assertFalse(manager.hasAutoSwitchedAudio, "hasAutoSwitchedAudio must be false after reset")
        assertFalse(manager.hasAutoSwitchedSub, "hasAutoSwitchedSub must be false after reset")
    }

    @Test
    fun testTrackManagerDefaultConstruction_DoesNotEagerlyRequireNativeLibrary() {
        val manager = MpvTrackManager(PlayerState())
        manager.hasAutoSwitchedAudio = true
        manager.reset()
        assertFalse(manager.hasAutoSwitchedAudio)
    }

    @Test
    fun testTrackDisambiguationLogic() {
        val tracks = listOf(
            PlayerState.VideoTrack(1, "English", false),
            PlayerState.VideoTrack(2, "English", false),
            PlayerState.VideoTrack(3, "Spanish", false),
            PlayerState.VideoTrack(4, "English", false),
        )

        val nameCounts = tracks.groupingBy { it.name }.eachCount()
        val dupTracker = mutableMapOf<String, Int>()
        val disambiguated = tracks.map { track ->
            if ((nameCounts[track.name] ?: 0) > 1) {
                val idx = (dupTracker[track.name] ?: 0) + 1
                dupTracker[track.name] = idx
                track.copy(name = "${track.name} #$idx")
            } else {
                track
            }
        }

        assertEquals("English #1", disambiguated[0].name)
        assertEquals("English #2", disambiguated[1].name)
        assertEquals("Spanish", disambiguated[2].name)
        assertEquals("English #3", disambiguated[3].name)
    }
}
