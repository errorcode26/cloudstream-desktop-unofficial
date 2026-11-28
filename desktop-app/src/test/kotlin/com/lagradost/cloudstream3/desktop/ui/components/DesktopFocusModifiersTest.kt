package com.lagradost.cloudstream3.desktop.ui.components

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.desktop.ui.screens.dev.DevStudioState
import com.lagradost.cloudstream3.desktop.ui.screens.dev.VisualFocusInspectorRegistry
import com.lagradost.cloudstream3.desktop.ui.screens.dev.VisualFocusOverlayHud
import kotlinx.coroutines.launch
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DesktopFocusModifiersTest {

    @Test
    fun coveredLayerButtonsCannotReceiveTabFocusWhenOverlayIsOpen() = runComposeUiTest {
        var overlayOpen by mutableStateOf(false)
        var backgroundClicks by mutableIntStateOf(0)
        var overlayClicks by mutableIntStateOf(0)

        setContent {
            MaterialTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    FocusLayerBoundary(
                        active = !overlayOpen,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Column {
                            val firstSource = remember { MutableInteractionSource() }
                            val secondSource = remember { MutableInteractionSource() }
                            Button(
                                onClick = { backgroundClicks++ },
                                interactionSource = firstSource,
                                modifier = Modifier
                                    .testTag("bg_first")
                                    .desktopInteractiveFocus(firstSource),
                            ) { Text("Bg First") }
                            Button(
                                onClick = { backgroundClicks++ },
                                interactionSource = secondSource,
                                modifier = Modifier
                                    .testTag("bg_second")
                                    .desktopInteractiveFocus(secondSource),
                            ) { Text("Bg Second") }
                        }
                    }

                    if (overlayOpen) {
                        val overlayRequester = remember { FocusRequester() }
                        FocusLayerBoundary(
                            active = true,
                            trapFocus = true,
                            requestFocusWhenActive = true,
                            focusRequester = overlayRequester,
                            modifier = Modifier
                                .fillMaxSize()
                                .handleEscapeKey { overlayOpen = false },
                        ) {
                            Column {
                                val overlayA = remember { MutableInteractionSource() }
                                val overlayB = remember { MutableInteractionSource() }
                                Button(
                                    onClick = { overlayClicks++ },
                                    interactionSource = overlayA,
                                    modifier = Modifier
                                        .testTag("overlay_a")
                                        .desktopInteractiveFocus(overlayA),
                                ) { Text("Overlay A") }
                                Button(
                                    onClick = { overlayClicks++ },
                                    interactionSource = overlayB,
                                    modifier = Modifier
                                        .testTag("overlay_b")
                                        .desktopInteractiveFocus(overlayB),
                                ) { Text("Overlay B") }
                            }
                        }
                    }
                }
            }
        }

        onNodeWithTag("bg_second").requestFocus()
        onNodeWithTag("bg_second").assertIsFocused()

        overlayOpen = true
        waitForIdle()

        onNodeWithTag("overlay_a").assertIsFocused()
        onNodeWithTag("bg_first").assertIsNotFocused()
        onNodeWithTag("bg_second").assertIsNotFocused()

        onNodeWithTag("overlay_a").performKeyInput { pressKey(Key.Tab) }
        onNodeWithTag("overlay_b").assertIsFocused()

        // Tab at end of trapped overlay must NOT escape into covered background layer
        onNodeWithTag("overlay_b").performKeyInput { pressKey(Key.Tab) }
        onNodeWithTag("bg_first").assertIsNotFocused()
        onNodeWithTag("bg_second").assertIsNotFocused()

        // Escape dismisses overlay cleanly and restores previous focus on bg_second
        onNodeWithTag("overlay_b").performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertEquals(false, overlayOpen)
        onNodeWithTag("bg_second").assertIsFocused()
        assertEquals(0, backgroundClicks)
        assertEquals(0, overlayClicks)
    }

    @Test
    fun focusableCardActivatesPrimaryOnEnterAndSecondaryOnContextKey() = runComposeUiTest {
        var primaryClicks by mutableIntStateOf(0)
        var secondaryClicks by mutableIntStateOf(0)
        var focused by mutableStateOf(false)

        setContent {
            MaterialTheme {
                val interaction = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .testTag("poster_card")
                        .onFocusChanged { focused = it.isFocused }
                        .desktopFocusableCard(
                            interactionSource = interaction,
                            shape = RoundedCornerShape(12.dp),
                            onClick = { primaryClicks++ },
                            onSecondaryClick = { secondaryClicks++ },
                        ),
                ) {
                    Text("Card")
                }
            }
        }

        onNodeWithTag("poster_card").requestFocus()
        onNodeWithTag("poster_card").assertIsFocused()
        assertEquals(true, focused)

        onNodeWithTag("poster_card").performKeyInput { pressKey(Key.Enter) }
        assertEquals(1, primaryClicks)
        assertEquals(0, secondaryClicks)

        onNodeWithTag("poster_card").performKeyInput {
            pressKey(Key.Spacebar)
            pressKey(Key.Menu)
        }
        assertEquals(2, primaryClicks)
        assertEquals(1, secondaryClicks)
    }

    @Test
    fun arrowNavigationMovesFocusBetweenButtonsWithoutBreakingTextFieldCursor() = runComposeUiTest {
        var fieldValue by mutableStateOf("abc")
        lateinit var inputRequester: FocusRequester

        setContent {
            inputRequester = remember { FocusRequester() }
            MaterialTheme {
                Column(modifier = Modifier.desktopDirectionalFocusNavigation()) {
                    val btn1 = remember { MutableInteractionSource() }
                    val btn2 = remember { MutableInteractionSource() }
                    Button(
                        onClick = {},
                        interactionSource = btn1,
                        modifier = Modifier
                            .testTag("btn_1")
                            .desktopInteractiveFocus(btn1),
                    ) { Text("Button 1") }
                    Button(
                        onClick = {},
                        interactionSource = btn2,
                        modifier = Modifier
                            .testTag("btn_2")
                            .desktopInteractiveFocus(btn2),
                    ) { Text("Button 2") }
                    OutlinedTextField(
                        value = fieldValue,
                        onValueChange = { fieldValue = it },
                        modifier = Modifier
                            .testTag("text_input")
                            .focusRequester(inputRequester),
                    )
                }
            }
        }

        onNodeWithTag("btn_1").requestFocus()
        onNodeWithTag("btn_1").performKeyInput { pressKey(Key.DirectionDown) }
        onNodeWithTag("btn_2").assertIsFocused()

        onNodeWithTag("btn_2").performKeyInput { pressKey(Key.DirectionUp) }
        onNodeWithTag("btn_1").assertIsFocused()

        inputRequester.requestFocus()
        onNodeWithTag("text_input").performKeyInput { pressKey(Key.DirectionLeft) }
        onNodeWithTag("text_input").assertIsFocused()
    }

    @Test
    fun nonFocusableTapScrimSkipsTabFocusWhileHandlingPointerTaps() = runComposeUiTest {
        var scrimTaps by mutableIntStateOf(0)

        setContent {
            MaterialTheme {
                Column {
                    DesktopButton(
                        onClick = {},
                        modifier = Modifier.testTag("btn_before"),
                    ) { Text("Before") }
                    Box(
                        modifier = Modifier
                            .testTag("scrim")
                            .size(40.dp)
                            .nonFocusableTap { scrimTaps++ },
                    )
                    DesktopButton(
                        onClick = {},
                        modifier = Modifier.testTag("btn_after"),
                    ) { Text("After") }
                }
            }
        }

        onNodeWithTag("btn_before").requestFocus()
        onNodeWithTag("btn_before").assertIsFocused()

        onNodeWithTag("btn_before").performKeyInput { pressKey(Key.Tab) }
        onNodeWithTag("btn_after").assertIsFocused()
        onNodeWithTag("scrim").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Focused))

        onNodeWithTag("scrim").performClick()
        assertEquals(1, scrimTaps)
    }

    @Test
    fun inactiveTransitionLayerBlocksFocusEvenWhenBothLayersAreMounted() = runComposeUiTest {
        var activeTab by mutableIntStateOf(1)

        setContent {
            MaterialTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    FocusLayerBoundary(
                        active = (activeTab == 0),
                        restoreFocus = false,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Column {
                            DesktopButton(
                                onClick = {},
                                modifier = Modifier.testTag("tab0_btn"),
                            ) { Text("Tab 0") }
                        }
                    }

                    FocusLayerBoundary(
                        active = (activeTab == 1),
                        restoreFocus = false,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Column {
                            DesktopButton(
                                onClick = {},
                                modifier = Modifier.testTag("tab1_a"),
                            ) { Text("Tab 1A") }
                            DesktopButton(
                                onClick = {},
                                modifier = Modifier.testTag("tab1_b"),
                            ) { Text("Tab 1B") }
                        }
                    }
                }
            }
        }

        onNodeWithTag("tab1_a").requestFocus()
        onNodeWithTag("tab1_a").assertIsFocused()

        onNodeWithTag("tab1_a").performKeyInput { pressKey(Key.Tab) }
        onNodeWithTag("tab1_b").assertIsFocused()

        onNodeWithTag("tab1_b").performKeyInput { pressKey(Key.Tab) }
        onNodeWithTag("tab0_btn").assertIsNotFocused()

        onNodeWithTag("tab0_btn").requestFocus()
        onNodeWithTag("tab0_btn").assertIsNotFocused()

        activeTab = 0
        waitForIdle()
        onNodeWithTag("tab0_btn").requestFocus()
        onNodeWithTag("tab0_btn").assertIsFocused()
    }

    @Test
    fun innerHandleEscapeKeyConsumesBeforeOuterBubbledEscapeKey() = runComposeUiTest {
        var outerEscapes by mutableIntStateOf(0)
        var innerEscapes by mutableIntStateOf(0)

        setContent {
            MaterialTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .handleBubbledEscapeKey { outerEscapes++ },
                ) {
                    DesktopButton(
                        onClick = {},
                        modifier = Modifier.testTag("outer_btn"),
                    ) { Text("Outer") }
                    Box(
                        modifier = Modifier.handleEscapeKey { innerEscapes++ },
                    ) {
                        DesktopButton(
                            onClick = {},
                            modifier = Modifier.testTag("inner_btn"),
                        ) { Text("Inner") }
                    }
                }
            }
        }

        onNodeWithTag("inner_btn").requestFocus()
        onNodeWithTag("inner_btn").performKeyInput { pressKey(Key.Escape) }
        assertEquals(1, innerEscapes)
        assertEquals(0, outerEscapes)

        onNodeWithTag("outer_btn").requestFocus()
        onNodeWithTag("outer_btn").performKeyInput { pressKey(Key.Escape) }
        assertEquals(1, innerEscapes)
        assertEquals(1, outerEscapes)
    }

    @Test
    fun desktopButtonsBackButtonAndFilterChipSupportFocusAndActivation() = runComposeUiTest {
        var primaryClicks by mutableIntStateOf(0)
        var outlinedClicks by mutableIntStateOf(0)
        var backClicks by mutableIntStateOf(0)
        var chipClicks by mutableIntStateOf(0)

        setContent {
            MaterialTheme {
                Column {
                    DesktopButton(
                        onClick = { primaryClicks++ },
                        modifier = Modifier.testTag("desktop_btn"),
                    ) { Text("Primary") }
                    DesktopOutlinedButton(
                        onClick = { outlinedClicks++ },
                        modifier = Modifier.testTag("outlined_btn"),
                    ) { Text("Outlined") }
                    DesktopBackButton(
                        onClick = { backClicks++ },
                        modifier = Modifier.testTag("back_btn"),
                    )
                    DesktopFilterChip(
                        text = "Chip",
                        isSelected = false,
                        onClick = { chipClicks++ },
                        modifier = Modifier.testTag("filter_chip"),
                    )
                }
            }
        }

        onNodeWithTag("desktop_btn").requestFocus()
        onNodeWithTag("desktop_btn").performKeyInput {
            pressKey(Key.Enter)
            pressKey(Key.Tab)
        }
        onNodeWithTag("outlined_btn").assertIsFocused()
        onNodeWithTag("outlined_btn").performKeyInput {
            pressKey(Key.Spacebar)
            pressKey(Key.Tab)
        }
        onNodeWithTag("back_btn").assertIsFocused()
        onNodeWithTag("back_btn").performKeyInput {
            pressKey(Key.Enter)
            pressKey(Key.Tab)
        }
        onNodeWithTag("filter_chip").assertIsFocused()
        onNodeWithTag("filter_chip").performKeyInput {
            pressKey(Key.Enter)
        }

        assertEquals(1, primaryClicks)
        assertEquals(1, outlinedClicks)
        assertEquals(1, backClicks)
        assertEquals(1, chipClicks)
    }

    @Test
    fun visualFocusInspectorTracksFocusedNodeMoveVectorAndSnapshot() = runComposeUiTest {
        DevStudioState.setVisualFocusHudEnabled(true)
        VisualFocusInspectorRegistry.clearAll()
        try {
            setContent {
                MaterialTheme {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .desktopDirectionalFocusNavigation(),
                        ) {
                            DesktopButton(
                                onClick = {},
                                modifier = Modifier.testTag("hud_btn_1"),
                            ) { Text("First") }
                            DesktopButton(
                                onClick = {},
                                modifier = Modifier.testTag("hud_btn_2"),
                            ) { Text("Second") }
                        }
                        VisualFocusOverlayHud(modifier = Modifier.fillMaxSize())
                    }
                }
            }

            onNodeWithTag("hud_btn_1").requestFocus()
            waitForIdle()

            onNodeWithTag("hud_btn_1").performKeyInput { pressKey(Key.DirectionDown) }
            waitForIdle()
            onNodeWithTag("hud_btn_2").assertIsFocused()

            val entries = VisualFocusInspectorRegistry.snapshotEntries()
            assertTrue(entries.size >= 2, "Expected tracked focusables in registry")
            assertTrue(entries.any { it.isFocused }, "Expected active focused entry")

            val traces = VisualFocusInspectorRegistry.snapshotTraces()
            assertTrue(traces.any { it.action == "ArrowDown" && it.outcome.startsWith("MOVED") })

            val snapshot = VisualFocusInspectorRegistry.buildSnapshot(1280, 720)
            assertTrue(snapshot.contains("FOCUSED:"))
            assertTrue(snapshot.contains("ArrowDown: MOVED"))
        } finally {
            DevStudioState.setVisualFocusHudEnabled(false)
            VisualFocusInspectorRegistry.clearAll()
        }
    }

    @Test
    fun categoryRowHeaderDownSkipsHeaderButtonsToCardsWhileTabStillReachesViewAll() = runComposeUiTest {
        var row1Clicks = 0
        var row2Clicks = 0
        var row2ViewAllClicks = 0
        val row1CardFocused = BooleanArray(4)
        val row2CardFocused = BooleanArray(4)

        setContent {
            MaterialTheme {
                Column(
                    modifier = Modifier
                        .width(800.dp)
                        .height(600.dp)
                        .desktopDirectionalFocusNavigation(),
                ) {
                    CategoryRowWithHeader(
                        title = "Row One",
                        itemCount = 4,
                        onViewAll = {},
                        trailingHeaderExtra = {
                            DesktopTextButton(
                                onClick = {},
                                modifier = Modifier.testTag("row1_clear"),
                            ) { Text("Clear") }
                        },
                    ) {
                        items(4) { idx ->
                            val interaction = remember { MutableInteractionSource() }
                            Box(
                                modifier = Modifier
                                    .size(width = 180.dp, height = 100.dp)
                                    .testTag("row1_card_$idx")
                                    .onFocusChanged { row1CardFocused[idx] = it.isFocused }
                                    .desktopFocusableCard(
                                        interactionSource = interaction,
                                        shape = RoundedCornerShape(8.dp),
                                        onClick = { row1Clicks++ },
                                    ),
                            ) { Text("R1-$idx") }
                        }
                    }

                    CategoryRowWithHeader(
                        title = "Row Two",
                        itemCount = 4,
                        onViewAll = { row2ViewAllClicks++ },
                    ) {
                        items(4) { idx ->
                            val interaction = remember { MutableInteractionSource() }
                            Box(
                                modifier = Modifier
                                    .size(width = 180.dp, height = 100.dp)
                                    .testTag("row2_card_$idx")
                                    .onFocusChanged { row2CardFocused[idx] = it.isFocused }
                                    .desktopFocusableCard(
                                        interactionSource = interaction,
                                        shape = RoundedCornerShape(8.dp),
                                        onClick = { row2Clicks++ },
                                    ),
                            ) { Text("R2-$idx") }
                        }
                    }
                }
            }
        }

        // First: pressing Down on Row 1's Clear header action must drop into Row 1 cards (not Row 2).
        onNodeWithTag("row1_clear").requestFocus()
        waitForIdle()
        onNodeWithTag("row1_clear").performKeyInput { pressKey(Key.DirectionDown) }
        waitForIdle()
        assertTrue(row1CardFocused.any { it }, "Expected Down from Row 1 header to enter Row 1 cards")
        assertTrue(row2CardFocused.none { it })

        // Second: start on rightmost visible card in Row 1 (x aligned near Row 2's View All button)
        onNodeWithTag("row1_card_3").requestFocus()
        waitForIdle()
        onNodeWithTag("row1_card_3").performKeyInput { pressKey(Key.DirectionDown) }
        waitForIdle()

        assertTrue(row2CardFocused.any { it }, "Expected Down from Row 1 card to enter Row 2 cards instead of View All")
        assertEquals(0, row2ViewAllClicks)

        // Third: after Row 1 restored-child state exists, Clear -> ArrowDown still enters Row 1 cards.
        onNodeWithTag("row1_clear").requestFocus()
        waitForIdle()
        onNodeWithTag("row1_clear").performKeyInput { pressKey(Key.DirectionDown) }
        waitForIdle()
        assertTrue(row1CardFocused.any { it }, "Expected Down from Row 1 header to re-enter Row 1 cards")
        assertTrue(row2CardFocused.none { it })

        // Fourth: Tab from Row 1 last card still advances forward out of Row 1 cards toward Row 2 header.
        onNodeWithTag("row1_card_3").requestFocus()
        waitForIdle()
        onNodeWithTag("row1_card_3").performKeyInput { pressKey(Key.Tab) }
        waitForIdle()
        onNodeWithTag("row1_card_3").assertIsNotFocused()
    }

    @Test
    fun delayedAsyncContentInsideActiveFocusLayerReceivesInitialFocus() = runComposeUiTest {
        var loaded by mutableStateOf(false)

        setContent {
            MaterialTheme {
                FocusLayerBoundary(
                    active = true,
                    restoreFocus = true,
                    requestFocusWhenActive = true,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (!loaded) {
                        Text("Loading...")
                    } else {
                        DesktopButton(
                            onClick = {},
                            modifier = Modifier.testTag("async_first_btn"),
                        ) { Text("Ready Action") }
                    }
                }
            }
        }

        waitForIdle()
        loaded = true
        waitForIdle()
        onNodeWithTag("async_first_btn").assertIsFocused()
    }

    @Test
    fun rootFocusCoordinatorOnlyRecoversOrEscapesWhenFocusIsNull() {
        var recoveredCount = 0
        var escapeCount = 0
        val recover = {
            recoveredCount++
            true
        }
        val escape = {
            escapeCount++
            true
        }

        RootFocusCoordinator.register(recover, escape)
        try {
            RootFocusCoordinator.notifyRootFocus(false)
            assertTrue(RootFocusCoordinator.recoverFocusIfNull())
            assertTrue(RootFocusCoordinator.triggerEscapeFallbackIfNull())
            assertEquals(1, recoveredCount)
            assertEquals(1, escapeCount)

            RootFocusCoordinator.notifyRootFocus(true)
            assertFalse(RootFocusCoordinator.recoverFocusIfNull())
            assertFalse(RootFocusCoordinator.triggerEscapeFallbackIfNull())
            assertEquals(1, recoveredCount)
            assertEquals(1, escapeCount)
        } finally {
            RootFocusCoordinator.clear(recover, escape)
        }
    }

    @Test
    fun categoryRowUpFromLowerPosterSkipsViewAllAndScrollChevronIsFocusable() = runComposeUiTest {
        var row1ViewAllClicks = 0
        var chevronClicks = 0
        val row1CardFocused = BooleanArray(4)
        val row2CardFocused = BooleanArray(4)

        setContent {
            MaterialTheme {
                Column(
                    modifier = Modifier
                        .width(800.dp)
                        .height(600.dp)
                        .desktopDirectionalFocusNavigation(),
                ) {
                    ScrollChevron(
                        enabled = true,
                        onClick = { chevronClicks++ },
                        icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        modifier = Modifier.testTag("scroll_chevron_right"),
                    )

                    CategoryRowWithHeader(
                        title = "Row One",
                        itemCount = 4,
                        onViewAll = { row1ViewAllClicks++ },
                    ) {
                        items(4) { idx ->
                            val interaction = remember { MutableInteractionSource() }
                            Box(
                                modifier = Modifier
                                    .size(width = 180.dp, height = 100.dp)
                                    .testTag("up_row1_card_$idx")
                                    .onFocusChanged { row1CardFocused[idx] = it.isFocused }
                                    .desktopFocusableCard(
                                        interactionSource = interaction,
                                        shape = RoundedCornerShape(8.dp),
                                        onClick = {},
                                    ),
                            ) { Text("R1-$idx") }
                        }
                    }

                    CategoryRowWithHeader(
                        title = "Row Two",
                        itemCount = 4,
                        onViewAll = {},
                    ) {
                        items(4) { idx ->
                            val interaction = remember { MutableInteractionSource() }
                            Box(
                                modifier = Modifier
                                    .size(width = 180.dp, height = 100.dp)
                                    .testTag("up_row2_card_$idx")
                                    .onFocusChanged { row2CardFocused[idx] = it.isFocused }
                                    .desktopFocusableCard(
                                        interactionSource = interaction,
                                        shape = RoundedCornerShape(8.dp),
                                        onClick = {},
                                    ),
                            ) { Text("R2-$idx") }
                        }
                    }
                }
            }
        }

        // ScrollChevron receives focus and activates with Enter
        onNodeWithTag("scroll_chevron_right").requestFocus()
        waitForIdle()
        onNodeWithTag("scroll_chevron_right").assertIsFocused()
        onNodeWithTag("scroll_chevron_right").performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(1, chevronClicks)

        // Pressing ArrowUp from rightmost Row 2 card moves directly to Row 1 cards, NOT Row 1 View All
        onNodeWithTag("up_row2_card_3").requestFocus()
        waitForIdle()
        assertTrue(row2CardFocused[3])
        onNodeWithTag("up_row2_card_3").performKeyInput { pressKey(Key.DirectionUp) }
        waitForIdle()
        assertTrue(row1CardFocused.any { it }, "Expected ArrowUp from Row 2 card to land in Row 1 cards")
        assertEquals(0, row1ViewAllClicks)
    }

    @Test
    fun lazyColumnBoundaryScrollRecoversHeroFocusBeforeExitingUpward() = runComposeUiTest {
        var topBarFocused by mutableStateOf(false)
        var heroFocused by mutableStateOf(false)
        var currentFocusedTag by mutableStateOf("")
        var scrollOffsetOk by mutableStateOf(false)
        var triggerScrollDown = {}

        setContent {
            MaterialTheme {
                val listState = rememberLazyListState()
                val heroRequester = remember { FocusRequester() }
                val coroutineScope = rememberCoroutineScope()
                triggerScrollDown = {
                    coroutineScope.launch {
                        listState.scrollToItem(5)
                        scrollOffsetOk = listState.firstVisibleItemIndex >= 4
                    }
                }

                Column(
                    modifier = Modifier
                        .width(600.dp)
                        .height(420.dp)
                        .desktopDirectionalFocusNavigation(),
                ) {
                    DesktopButton(
                        onClick = {},
                        modifier = Modifier
                            .testTag("top_bar_action")
                            .onFocusChanged {
                                topBarFocused = it.isFocused
                                if (it.isFocused) currentFocusedTag = "top_bar_action"
                            },
                    ) { Text("Top Bar") }

                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .desktopLazyColumnBoundaryScroll(
                                listState = listState,
                                topItemFocusRequester = heroRequester,
                            ),
                    ) {
                        item(key = "hero") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(260.dp)
                                    .focusRequester(heroRequester)
                                    .focusGroup(),
                            ) {
                                DesktopButton(
                                    onClick = {},
                                    modifier = Modifier
                                        .testTag("hero_watch_now")
                                        .onFocusChanged {
                                            heroFocused = it.isFocused
                                            if (it.isFocused) currentFocusedTag = "hero_watch_now"
                                        },
                                ) { Text("Watch Now") }
                            }
                        }

                        items(6) { idx ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(160.dp),
                            ) {
                                val tag = "row_action_$idx"
                                DesktopButton(
                                    onClick = {},
                                    modifier = Modifier
                                        .testTag(tag)
                                        .onFocusChanged { if (it.isFocused) currentFocusedTag = tag },
                                ) { Text("Row $idx") }
                            }
                        }
                    }
                }
            }
        }

        // Scroll past the Hero item so index 0 is unmounted, and focus row item 5
        triggerScrollDown()
        waitForIdle()
        assertTrue(scrollOffsetOk, "Expected LazyColumn to scroll down past Hero")
        onNodeWithTag("row_action_4").requestFocus()
        waitForIdle()

        var stepCount = 0
        var sawHeroFocused = false
        while (!topBarFocused && stepCount < 14) {
            if (heroFocused) {
                sawHeroFocused = true
                // While Hero still has scrolled content above, ArrowUp recovers scroll before exiting to TopBar.
                onNodeWithTag("hero_watch_now").performKeyInput { pressKey(Key.DirectionUp) }
                waitForIdle()
                if (heroFocused) sawHeroFocused = true
                if (topBarFocused) break
            } else {
                assertFalse(topBarFocused, "ArrowUp escaped to TopBar before recovering Hero focus")
                val targetTag = currentFocusedTag.ifEmpty { "row_action_4" }
                onNodeWithTag(targetTag).performKeyInput { pressKey(Key.DirectionUp) }
                waitForIdle()
                if (heroFocused) sawHeroFocused = true
            }
            stepCount++
        }

        assertTrue(sawHeroFocused, "Expected ArrowUp scroll recovery to land on Hero before reaching TopBar")
        assertTrue(topBarFocused, "Expected ArrowUp from top-most Hero at index 0 to exit to TopBar")
    }

    @Test
    fun desktopTextInputFocusPreservesHorizontalCaretAndExitsVerticallyAndOnEscape() = runComposeUiTest {
        var text by mutableStateOf("hello")

        setContent {
            MaterialTheme {
                Column(
                    modifier = Modifier
                        .width(500.dp)
                        .height(400.dp)
                        .desktopDirectionalFocusNavigation(),
                ) {
                    DesktopButton(
                        onClick = {},
                        modifier = Modifier.testTag("above_input_btn"),
                    ) { Text("Above") }

                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        modifier = Modifier
                            .testTag("text_input")
                            .desktopTextInputFocus(
                                shape = RoundedCornerShape(10.dp),
                                singleLine = true,
                            ) {
                                if (text.isNotEmpty()) {
                                    text = ""
                                    true
                                } else {
                                    false
                                }
                            },
                    )

                    DesktopButton(
                        onClick = {},
                        modifier = Modifier.testTag("below_input_btn"),
                    ) { Text("Below") }
                }
            }
        }

        onNodeWithTag("text_input").requestFocus()
        waitForIdle()
        onNodeWithTag("text_input").assertIsFocused()

        // Horizontal arrow keeps focus inside text input for caret movement
        onNodeWithTag("text_input").performKeyInput { pressKey(Key.DirectionLeft) }
        waitForIdle()
        onNodeWithTag("text_input").assertIsFocused()

        // Down arrow exits vertically to the control below
        onNodeWithTag("text_input").performKeyInput { pressKey(Key.DirectionDown) }
        waitForIdle()
        onNodeWithTag("below_input_btn").assertIsFocused()

        // Up arrow exits vertically to the control above
        onNodeWithTag("text_input").requestFocus()
        waitForIdle()
        onNodeWithTag("text_input").performKeyInput { pressKey(Key.DirectionUp) }
        waitForIdle()
        onNodeWithTag("above_input_btn").assertIsFocused()

        // Escape clears non-empty text first, then exits focus when empty
        text = "abc"
        onNodeWithTag("text_input").requestFocus()
        waitForIdle()
        onNodeWithTag("text_input").performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertEquals("", text)
        onNodeWithTag("text_input").assertIsFocused()

        onNodeWithTag("text_input").performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        onNodeWithTag("text_input").assertIsNotFocused()
    }

    @Test
    fun cloudstreamCustomDialogTrapsFocusSupportsArrowNavAndDismissesOnEscape() = runComposeUiTest {
        DevStudioState.setVisualFocusHudEnabled(true)
        VisualFocusInspectorRegistry.clearAll()
        try {
        var dialogOpen by mutableStateOf(true)
        var selected by mutableStateOf(setOf("Movies"))
        var saveFocused by mutableStateOf(false)

        setContent {
            MaterialTheme {
                Box(modifier = Modifier.width(800.dp).height(600.dp)) {
                    FocusLayerBoundary(active = !dialogOpen, modifier = Modifier.fillMaxSize()) {
                        DesktopButton(
                            onClick = {},
                            modifier = Modifier.testTag("background_btn"),
                        ) { Text("Background") }
                    }

                    CloudstreamCustomDialog(
                        show = dialogOpen,
                        onDismissRequest = { dialogOpen = false },
                        modifier = Modifier.width(500.dp).height(300.dp),
                    ) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            CategoryFilterChips(
                                categories = listOf("Movies", "Series"),
                                selected = selected,
                                onToggle = { label ->
                                    selected = if (label in selected) selected - label else selected + label
                                },
                            )
                            DesktopButton(
                                onClick = { dialogOpen = false },
                                modifier = Modifier
                                    .testTag("dialog_save_btn")
                                    .onFocusChanged { saveFocused = it.isFocused },
                            ) { Text("Save & Close") }
                        }
                    }
                }
            }
        }

        waitForIdle()
        val snap = VisualFocusInspectorRegistry.buildSnapshot(800, 600)
        onNodeWithTag("background_btn").assertIsNotFocused()
        assertFalse(saveFocused, "Expected initial dialog focus on top CategoryFilterChips instead of bottom Save button.\n$snap")
        onNodeWithText("Movies").assertIsFocused()

        // Navigate horizontally between FilterChips and vertically down to Save button inside dialog
        onNodeWithText("Movies").performKeyInput { pressKey(Key.DirectionRight) }
        waitForIdle()
        onNodeWithText("Series").assertIsFocused()

        onNodeWithText("Series").performKeyInput { pressKey(Key.DirectionDown) }
        waitForIdle()
        assertTrue(saveFocused, "Expected ArrowDown inside dialog to focus Save button")

        onNodeWithTag("dialog_save_btn").performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertFalse(dialogOpen, "Expected Escape inside CloudstreamCustomDialog to dismiss dialog")
        } finally {
            DevStudioState.setVisualFocusHudEnabled(false)
            VisualFocusInspectorRegistry.clearAll()
        }
    }
}
