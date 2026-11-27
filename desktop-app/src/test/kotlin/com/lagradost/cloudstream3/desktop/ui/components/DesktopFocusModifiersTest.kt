package com.lagradost.cloudstream3.desktop.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

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
}
