package com.lagradost.cloudstream3.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.lagradost.cloudstream3.desktop.ui.components.DesktopBackButton
import com.lagradost.cloudstream3.desktop.ui.components.FocusLayerBoundary
import com.lagradost.cloudstream3.desktop.ui.components.desktopDirectionalFocusNavigation
import com.lagradost.cloudstream3.desktop.ui.components.desktopFocusZone
import com.lagradost.cloudstream3.desktop.ui.components.desktopInteractiveFocus
import com.lagradost.cloudstream3.desktop.ui.screens.dev.VisualFocusInspectorRegistry
import com.lagradost.cloudstream3.desktop.ui.screens.settings.SettingsNavigationItem
import com.lagradost.cloudstream3.desktop.ui.screens.settings.SettingsNavigationRow
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalTestApi::class)
class ShellAndDetailsFocusNavigationTest {
    @BeforeEach
    fun resetInspector() {
        VisualFocusInspectorRegistry.clearAll()
    }

    @AfterEach
    fun cleanupInspector() {
        VisualFocusInspectorRegistry.clearAll()
    }

    @Test
    fun detailsBackButtonAndPlayLayerZonesRoundTrip() = runComposeUiTest {
        setContent {
            MaterialTheme {
                val topActionsRequester = remember { FocusRequester() }
                val detailsContentRequester = remember { FocusRequester() }
                val playFocusRequester = remember { FocusRequester() }

                Box(
                    modifier = Modifier
                        .size(1280.dp, 720.dp)
                        .desktopDirectionalFocusNavigation(),
                ) {
                    FocusLayerBoundary(
                        active = true,
                        restoreFocus = true,
                        requestFocusWhenActive = true,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            FocusLayerBoundary(
                                active = true,
                                restoreFocus = true,
                                focusRequester = detailsContentRequester,
                                initialFocusRequester = playFocusRequester,
                                requestFocusWhenActive = true,
                                up = topActionsRequester,
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(bottom = 32.dp),
                                ) {
                                    item(key = "HeroAndTabs") {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(min = 480.dp),
                                            contentAlignment = Alignment.BottomStart,
                                        ) {
                                            Column(modifier = Modifier.padding(start = 64.dp, bottom = 32.dp)) {
                                                TestFocusButton("details_tmdb", "7.7", Modifier.size(64.dp, 28.dp))
                                                Spacer(Modifier.height(16.dp))
                                                TestFocusButton(
                                                    tag = "details_play",
                                                    label = "Play",
                                                    modifier = Modifier
                                                        .size(180.dp, 48.dp)
                                                        .focusRequester(playFocusRequester),
                                                )
                                            }
                                        }
                                    }
                                    item(key = "Cast") {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 64.dp, vertical = 24.dp),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        ) {
                                            TestFocusButton("cast_0", "Cast 0", Modifier.size(110.dp, 140.dp))
                                        }
                                    }
                                }
                            }

                            FocusLayerBoundary(
                                active = true,
                                restoreFocus = false,
                                focusRequester = topActionsRequester,
                                down = detailsContentRequester,
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .fillMaxWidth()
                                    .zIndex(100f),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 16.dp, end = 16.dp, top = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    DesktopBackButton(
                                        onClick = {},
                                        modifier = Modifier.testTag("details_back"),
                                    )
                                    TestFocusButton("details_fullscreen", "FS", Modifier.size(42.dp))
                                }
                            }
                        }
                    }
                }
            }
        }

        waitForIdle()
        // Initial focus lands straight on Play CTA instead of null or corner chrome.
        onNodeWithTag("details_play").assertIsFocused()

        // ArrowUp navigates to TMDB pill above Play, then ArrowUp crosses into Floating Top Bar Back button.
        onNodeWithTag("details_play").performKeyInput { pressKey(Key.DirectionUp) }
        waitForIdle()
        onNodeWithTag("details_tmdb").assertIsFocused()

        onNodeWithTag("details_tmdb").performKeyInput { pressKey(Key.DirectionUp) }
        waitForIdle()
        onNodeWithTag("details_back").assertIsFocused()

        // Horizontal movement inside top bar works, and ArrowDown from either Back or Fullscreen returns into DetailsContent.
        onNodeWithTag("details_back").performKeyInput { pressKey(Key.DirectionRight) }
        waitForIdle()
        onNodeWithTag("details_fullscreen").assertIsFocused()

        onNodeWithTag("details_fullscreen").performKeyInput { pressKey(Key.DirectionLeft) }
        waitForIdle()
        onNodeWithTag("details_back").assertIsFocused()

        onNodeWithTag("details_back").performKeyInput { pressKey(Key.DirectionDown) }
        waitForIdle()
        onNodeWithTag("details_tmdb").assertIsFocused()

        onNodeWithTag("details_tmdb").performKeyInput { pressKey(Key.DirectionDown) }
        waitForIdle()
        onNodeWithTag("details_play").assertIsFocused()

        onNodeWithTag("details_play").performKeyInput { pressKey(Key.DirectionDown) }
        waitForIdle()
        onNodeWithTag("cast_0").assertIsFocused()
    }

    @Test
    fun homeShellDockContentAndTopBarZonesRoundTrip() = runComposeUiTest {
        setContent {
            MaterialTheme {
                val topBarRequester = remember { FocusRequester() }
                val dockRequester = remember { FocusRequester() }
                val contentRequester = remember { FocusRequester() }

                Box(
                    modifier = Modifier
                        .size(1280.dp, 720.dp)
                        .desktopDirectionalFocusNavigation(),
                ) {
                    FocusLayerBoundary(
                        active = true,
                        restoreFocus = true,
                        requestFocusWhenActive = true,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .desktopFocusZone(
                                        zoneRequester = contentRequester,
                                        restoreFocus = true,
                                        up = topBarRequester,
                                        left = dockRequester,
                                    ),
                            ) {
                                LazyColumn(modifier = Modifier.fillMaxSize()) {
                                    item(key = "hero") {
                                        Box(modifier = Modifier.padding(start = 88.dp, top = 280.dp)) {
                                            TestFocusButton("watch_now", "Watch Now", Modifier.size(140.dp, 44.dp))
                                        }
                                    }
                                    item(key = "continue") {
                                        Box(modifier = Modifier.padding(start = 88.dp, top = 120.dp)) {
                                            TestFocusButton("continue_card", "Alchemy", Modifier.size(220.dp, 120.dp))
                                        }
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(top = 16.dp, end = 24.dp)
                                    .desktopFocusZone(
                                        zoneRequester = topBarRequester,
                                        restoreFocus = true,
                                        down = contentRequester,
                                        left = dockRequester,
                                    ),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                TestFocusButton("top_bell", "Bell", Modifier.size(40.dp))
                                TestFocusButton("top_fs", "FS", Modifier.size(42.dp))
                            }

                            // Reproduces seamless NavigationDock: 0.52f passive vignette outside dockFocusModifier bounds.
                            Box(
                                modifier = Modifier.align(Alignment.CenterStart),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth(0.52f),
                                )
                                Column(
                                    modifier = Modifier
                                        .width(64.dp)
                                        .fillMaxHeight()
                                        .desktopFocusZone(
                                            zoneRequester = dockRequester,
                                            restoreFocus = true,
                                            up = topBarRequester,
                                            right = contentRequester,
                                        ),
                                    verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                                ) {
                                    TestFocusButton("dock_home", "H", Modifier.fillMaxWidth().height(44.dp))
                                    TestFocusButton("dock_settings", "Set", Modifier.fillMaxWidth().height(44.dp))
                                }
                            }
                        }
                    }
                }
            }
        }

        waitForIdle()
        onNodeWithTag("dock_settings").requestFocus()
        waitForIdle()
        onNodeWithTag("dock_settings").assertIsFocused()

        // Dock bottom item exits Right cleanly into Home content despite dead Y-gap between Watch Now and card row.
        onNodeWithTag("dock_settings").performKeyInput { pressKey(Key.DirectionRight) }
        waitForIdle()
        onNodeWithTag("watch_now").assertIsFocused()

        onNodeWithTag("watch_now").performKeyInput { pressKey(Key.DirectionDown) }
        waitForIdle()
        onNodeWithTag("continue_card").assertIsFocused()

        onNodeWithTag("continue_card").performKeyInput { pressKey(Key.DirectionUp) }
        waitForIdle()
        onNodeWithTag("watch_now").assertIsFocused()

        // Exiting Left from Watch Now returns to Left Dock and restores the last focused Dock item.
        onNodeWithTag("watch_now").performKeyInput { pressKey(Key.DirectionLeft) }
        waitForIdle()
        onNodeWithTag("dock_settings").assertIsFocused()

        // Exiting Up from top of Dock jumps to TopBar; Down from TopBar restores Watch Now in Content.
        onNodeWithTag("dock_settings").performKeyInput { pressKey(Key.DirectionUp) }
        waitForIdle()
        onNodeWithTag("dock_home").assertIsFocused()

        onNodeWithTag("dock_home").performKeyInput { pressKey(Key.DirectionUp) }
        waitForIdle()
        onNodeWithTag("top_bell").assertIsFocused()

        onNodeWithTag("top_bell").performKeyInput { pressKey(Key.DirectionRight) }
        waitForIdle()
        onNodeWithTag("top_fs").assertIsFocused()

        onNodeWithTag("top_fs").performKeyInput { pressKey(Key.DirectionDown) }
        waitForIdle()
        onNodeWithTag("watch_now").assertIsFocused()

        // Exiting Up from Watch Now returns to TopBar and restores the last focused TopBar item.
        onNodeWithTag("watch_now").performKeyInput { pressKey(Key.DirectionUp) }
        waitForIdle()
        onNodeWithTag("top_fs").assertIsFocused()
    }

    @Test
    fun settingsNavigationRowsAcceptKeyboardFocusAndActivation() = runComposeUiTest {
        var clickedItem = ""
        var clickedRow = ""

        setContent {
            MaterialTheme {
                val leftPaneRequester = remember { FocusRequester() }
                val rightPaneRequester = remember { FocusRequester() }

                Box(
                    modifier = Modifier
                        .size(1000.dp, 640.dp)
                        .desktopDirectionalFocusNavigation(),
                ) {
                    Row(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                        Column(
                            modifier = Modifier
                                .width(200.dp)
                                .fillMaxHeight()
                                .desktopFocusZone(
                                    zoneRequester = leftPaneRequester,
                                    restoreFocus = true,
                                    right = rightPaneRequester,
                                ),
                        ) {
                            TestFocusButton("sidebar_about", "About", Modifier.fillMaxWidth().height(40.dp))
                        }

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState())
                                .desktopFocusZone(
                                    zoneRequester = rightPaneRequester,
                                    restoreFocus = false,
                                    left = leftPaneRequester,
                                ),
                        ) {
                            SettingsNavigationItem(
                                label = "Community Discord",
                                subtitle = "Support chat",
                                onClick = { clickedItem = "discord" },
                            )
                            SettingsNavigationRow(
                                title = "Desktop Source Code",
                                subtitle = "View repo",
                                onClick = { clickedRow = "source" },
                            )
                        }
                    }
                }
            }
        }

        waitForIdle()
        onNodeWithTag("sidebar_about").requestFocus()
        waitForIdle()
        onNodeWithTag("sidebar_about").performKeyInput { pressKey(Key.DirectionRight) }
        waitForIdle()
        onNodeWithText("Community Discord").assertIsFocused()

        onNodeWithText("Community Discord").performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals("discord", clickedItem)

        onNodeWithText("Community Discord").performKeyInput { pressKey(Key.DirectionDown) }
        waitForIdle()
        onNodeWithText("Desktop Source Code").assertIsFocused()

        onNodeWithText("Desktop Source Code").performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals("source", clickedRow)

        onNodeWithText("Desktop Source Code").performKeyInput { pressKey(Key.DirectionLeft) }
        waitForIdle()
        onNodeWithTag("sidebar_about").assertIsFocused()
    }
}

@Composable
private fun TestFocusButton(
    tag: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        shape = RoundedCornerShape(8.dp),
        modifier = modifier
            .testTag(tag)
            .desktopInteractiveFocus(interactionSource = interactionSource)
            .clickable(interactionSource = interactionSource, indication = null) {},
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label)
        }
    }
}
