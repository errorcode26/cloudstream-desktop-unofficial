package com.lagradost.cloudstream3.desktop.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.common.storage.WatchHistory
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalTestApi::class)
class ContextMenuAndKeyboardFocusTest {

    private val dummyApi = object : MainAPI() {
        override var name = "TestApi"
        override var mainUrl = "https://example.com"
    }

    @BeforeEach
    fun resetStateBefore() {
        GlobalContextMenuState.dismiss()
        GlobalContextMenuState.clear()
        DesktopKeyboardFocusState.resetForTest()
    }

    @AfterEach
    fun resetStateAfter() {
        GlobalContextMenuState.dismiss()
        GlobalContextMenuState.clear()
        DesktopKeyboardFocusState.resetForTest()
    }

    @Test
    fun keyboardFocusHighlightActivatesOnlyOnKeyboardNavAndClearsOnMouseUse() = runComposeUiTest {
        var btn1Highlight = false
        var btn2Highlight = false

        setContent {
            MaterialTheme {
                Box(
                    modifier = Modifier
                        .size(800.dp, 600.dp)
                        .testTag("root_tracker")
                        .desktopTrackInputMode()
                        .desktopDirectionalFocusNavigation(),
                ) {
                    Column {
                        val s1 = remember { MutableInteractionSource() }
                        val s2 = remember { MutableInteractionSource() }
                        val f1 by s1.collectIsKeyboardFocusedAsState()
                        val f2 by s2.collectIsKeyboardFocusedAsState()
                        btn1Highlight = f1
                        btn2Highlight = f2

                        Button(
                            onClick = {},
                            interactionSource = s1,
                            modifier = Modifier
                                .testTag("btn_1")
                                .desktopInteractiveFocus(interactionSource = s1),
                        ) { Text("Button 1") }

                        Button(
                            onClick = {},
                            interactionSource = s2,
                            modifier = Modifier
                                .testTag("btn_2")
                                .desktopInteractiveFocus(interactionSource = s2),
                        ) { Text("Button 2") }
                    }
                }
            }
        }

        onNodeWithTag("btn_1").requestFocus()
        waitForIdle()
        onNodeWithTag("btn_1").assertIsFocused()
        assertFalse(DesktopKeyboardFocusState.isKeyboardModeActive)
        assertFalse(btn1Highlight)

        // Escape and F5 must not activate focus highlights when not navigating
        onNodeWithTag("btn_1").performKeyInput {
            pressKey(Key.Escape)
            pressKey(Key.F5)
        }
        waitForIdle()
        assertFalse(DesktopKeyboardFocusState.isKeyboardModeActive)
        assertFalse(btn1Highlight)

        // Tab activates keyboard focus mode cleanly
        onNodeWithTag("btn_1").performKeyInput { pressKey(Key.Tab) }
        waitForIdle()
        onNodeWithTag("btn_2").assertIsFocused()
        assertTrue(DesktopKeyboardFocusState.isKeyboardModeActive)
        assertFalse(btn1Highlight)
        assertTrue(btn2Highlight)

        // Stationary pointer coordinates (e.g., synthetic hover re-sync on scroll) keep keyboard mode active
        val stationarySource = Any()
        DesktopKeyboardFocusState.onPointerActivity(
            source = stationarySource,
            type = PointerEventType.Move,
            position = Offset(400f, 300f),
        )
        DesktopKeyboardFocusState.onPointerActivity(
            source = stationarySource,
            type = PointerEventType.Move,
            position = Offset(400f, 300f),
        )
        waitForIdle()
        assertTrue(DesktopKeyboardFocusState.isKeyboardModeActive)
        assertTrue(btn2Highlight)

        // Actual mouse movement clears keyboard focus highlights immediately
        onNodeWithTag("root_tracker").performMouseInput {
            moveTo(Offset(10f, 10f))
            moveBy(Offset(24f, 12f))
        }
        waitForIdle()
        assertFalse(DesktopKeyboardFocusState.isKeyboardModeActive)
        assertFalse(btn2Highlight)

        // Directional navigation brings keyboard highlight back
        onNodeWithTag("btn_2").performKeyInput { pressKey(Key.DirectionUp) }
        waitForIdle()
        onNodeWithTag("btn_1").assertIsFocused()
        assertTrue(DesktopKeyboardFocusState.isKeyboardModeActive)
        assertTrue(btn1Highlight)

        // Mouse click clears focus highlight immediately
        onNodeWithTag("root_tracker").performMouseInput {
            click(Offset(600f, 500f))
        }
        waitForIdle()
        assertFalse(DesktopKeyboardFocusState.isKeyboardModeActive)
        assertFalse(btn1Highlight)
        assertFalse(btn2Highlight)
    }

    @Test
    fun episodeContextMenuPlacesPlayAndResumeAtTopAndAlignsWidths() = runComposeUiTest {
        val episode = with(dummyApi) {
            newEpisode("ep_2") {
                this.name = "A Shadow of the Past"
                this.season = 1
                this.episode = 2
                this.runTime = 66
                this.description =
                    "Galadriel is disturbed by signs of an ancient evil's return; Arondir makes an unsettling discovery; Elrond is presented with a new venture."
            }
        }
        val loadResponse = with(dummyApi) {
            newTvSeriesLoadResponse(
                name = "Test Series",
                url = "https://example.com/series",
                type = TvType.TvSeries,
                episodes = listOf(episode),
            )
        }
        var currentHistory by mutableStateOf(
            WatchHistory(
                parentId = "parent_1",
                showName = "Test Series",
                showUrl = "https://example.com/series",
                apiName = "TestApi",
                posterUrl = null,
                episodeThumbnailUrl = null,
                screenshotUrl = null,
                season = 1,
                episode = 2,
                episodeId = "ep_2",
                position = 0L,
                duration = 1_000_000L,
                updateTime = 0L,
                episodeName = episode.name,
                episodeDescription = null,
            ),
        )

        setContent {
            MaterialTheme {
                Box(
                    modifier = Modifier
                        .size(1280.dp, 800.dp)
                        .desktopTrackInputMode(),
                ) {
                    ContextMenuOverlay()
                }
            }
        }

        GlobalContextMenuState.showForEpisode(
            episode = episode,
            loadResponse = loadResponse,
            history = currentHistory,
            provider = dummyApi,
            enableDownloadButtons = true,
            onPlay = {},
            onDownload = {},
            onToggleWatched = { _, _ -> },
            onRemoveEpisodeWatched = {},
            onMarkPreviousWatched = {},
        )
        waitForIdle()

        val playBounds = onNodeWithText("Play episode").getBoundsInRoot()
        val watchedBounds = onNodeWithText("Mark as watched").getBoundsInRoot()
        val markPrevBounds = onNodeWithText("Mark previous as watched").getBoundsInRoot()
        val downloadBounds = onNodeWithText("Download episode").getBoundsInRoot()

        assertTrue(playBounds.top.value < watchedBounds.top.value)
        assertTrue(watchedBounds.top.value < markPrevBounds.top.value)
        assertTrue(markPrevBounds.top.value < downloadBounds.top.value)

        val thumbBounds = onNodeWithTag("context_menu_thumbnail").getBoundsInRoot()
        val synopsisBounds = onNodeWithTag("context_menu_synopsis").getBoundsInRoot()
        val actionsBounds = onNodeWithTag("context_menu_actions_card").getBoundsInRoot()

        val thumbWidth = thumbBounds.right.value - thumbBounds.left.value
        val synopsisWidth = synopsisBounds.right.value - synopsisBounds.left.value
        val actionsWidth = actionsBounds.right.value - actionsBounds.left.value
        assertTrue(abs(thumbWidth - synopsisWidth) <= 1f)
        assertTrue(abs(synopsisWidth - actionsWidth) <= 1f)
        assertTrue(abs(thumbBounds.left.value - synopsisBounds.left.value) <= 1f)
        assertTrue(abs(synopsisBounds.left.value - actionsBounds.left.value) <= 1f)

        // Updating progress switches top button cleanly to "Resume episode" while staying first
        val resumedHistory = currentHistory.copy(position = 400_000L)
        currentHistory = resumedHistory
        GlobalContextMenuState.watchHistory = resumedHistory
        waitForIdle()

        val resumeBounds = onNodeWithText("Resume episode").getBoundsInRoot()
        val watchedAfterBounds = onNodeWithText("Mark as watched").getBoundsInRoot()
        assertTrue(resumeBounds.top.value < watchedAfterBounds.top.value)
        assertEquals(false, DesktopKeyboardFocusState.isKeyboardModeActive)
    }
}
