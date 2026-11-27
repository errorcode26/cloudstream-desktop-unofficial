package com.lagradost.cloudstream3.desktop.ui.components

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Tracks whether the enclosing UI layer is currently eligible to receive keyboard focus.
 * Overlays set this to false on covered layers behind them.
 */
val LocalFocusLayerActive = compositionLocalOf { true }

/**
 * Isolates a UI layer (active screen, modal panel, drawer, or transitioning pane) so hidden or
 * covered controls cannot steal keyboard focus, while active layers restore focus on return.
 */
@Composable
fun FocusLayerBoundary(
    active: Boolean,
    modifier: Modifier = Modifier,
    trapFocus: Boolean = false,
    restoreFocus: Boolean = true,
    focusRequester: FocusRequester? = null,
    requestFocusWhenActive: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val groupRequester = focusRequester ?: remember { FocusRequester() }
    var wasBlocked by remember { mutableStateOf(false) }
    var hadFocusBeforeBlock by remember { mutableStateOf(false) }

    LaunchedEffect(active, requestFocusWhenActive) {
        if (!active) {
            wasBlocked = true
        } else {
            val resumingFromOverlay = wasBlocked
            wasBlocked = false
            if (requestFocusWhenActive) {
                runCatching { groupRequester.requestFocus() }
            } else if (resumingFromOverlay && restoreFocus && hadFocusBeforeBlock) {
                hadFocusBeforeBlock = false
                val restored = runCatching { groupRequester.restoreFocusedChild() }.getOrDefault(false)
                if (!restored) {
                    runCatching { groupRequester.requestFocus() }
                }
            } else {
                hadFocusBeforeBlock = false
            }
        }
    }

    val restorerModifier = if (restoreFocus) {
        Modifier.focusRestorer()
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .onFocusChanged { focusState ->
                if (active && focusState.hasFocus) {
                    hadFocusBeforeBlock = true
                    if (restoreFocus) {
                        runCatching { groupRequester.saveFocusedChild() }
                    }
                } else if (active && !focusState.hasFocus && !wasBlocked) {
                    hadFocusBeforeBlock = false
                }
            }
            .focusRequester(groupRequester)
            .then(restorerModifier)
            .focusProperties {
                if (!active) {
                    onEnter = { cancelFocusChange() }
                }
                if (trapFocus && active) {
                    onExit = { cancelFocusChange() }
                }
            }
            .focusGroup(),
    ) {
        CompositionLocalProvider(LocalFocusLayerActive provides active) {
            content()
        }
    }
}

/**
 * Draws an overlay focus outline matching [shape] without altering component measurement or padding.
 */
@Composable
fun Modifier.desktopFocusRing(
    isFocused: Boolean,
    shape: Shape = RoundedCornerShape(8.dp),
    ringColor: Color = MaterialTheme.colorScheme.primary,
    innerContrastColor: Color = Color.White.copy(alpha = 0.72f),
    ringWidth: Dp = 2.dp,
): Modifier {
    if (!isFocused) return this
    return this.drawWithContent {
        drawContent()
        val strokePx = ringWidth.toPx()
        val outline = shape.createOutline(size, layoutDirection, this)
        drawOutline(
            outline = outline,
            color = innerContrastColor,
            style = Stroke(width = (strokePx * 0.5f).coerceAtLeast(1f)),
        )
        drawOutline(
            outline = outline,
            color = ringColor,
            style = Stroke(width = strokePx),
        )
    }
}

/**
 * Coordinates layer focus gating and visible focus rings on composables that already own a
 * clickable/selectable [FocusTargetNode] backed by [interactionSource].
 */
@Composable
fun Modifier.desktopInteractiveFocus(
    interactionSource: MutableInteractionSource,
    shape: Shape = RoundedCornerShape(8.dp),
    enabled: Boolean = true,
    showRing: Boolean = true,
    ringColor: Color = MaterialTheme.colorScheme.primary,
    ringWidth: Dp = 2.dp,
): Modifier {
    val layerActive = LocalFocusLayerActive.current
    val isFocused by interactionSource.collectIsFocusedAsState()
    return this
        .focusProperties { canFocus = enabled && layerActive }
        .desktopFocusRing(
            isFocused = isFocused && enabled && layerActive && showRing,
            shape = shape,
            ringColor = ringColor,
            ringWidth = ringWidth,
        )
}

/**
 * Adds a single focus target plus Enter/Space primary activation and Menu/Shift+F10 context-menu
 * triggers for cards that handle mouse primary/secondary clicks through raw `pointerInput`.
 */
@Composable
fun Modifier.desktopFocusableCard(
    interactionSource: MutableInteractionSource,
    shape: Shape,
    enabled: Boolean = true,
    showRing: Boolean = true,
    ringColor: Color = MaterialTheme.colorScheme.primary,
    ringWidth: Dp = 2.dp,
    onClick: () -> Unit,
    onSecondaryClick: (() -> Unit)? = null,
): Modifier {
    val layerActive = LocalFocusLayerActive.current
    val isFocused by interactionSource.collectIsFocusedAsState()
    val effectiveEnabled = enabled && layerActive
    var pressedActivationKey by remember { mutableStateOf<Key?>(null) }

    return this
        .focusProperties { canFocus = effectiveEnabled }
        .focusable(enabled = effectiveEnabled, interactionSource = interactionSource)
        .onPreviewKeyEvent { event ->
            if (!effectiveEnabled) return@onPreviewKeyEvent false
            val noNavModifiers = !event.isCtrlPressed && !event.isAltPressed && !event.isShiftPressed
            val isPrimaryActivationKey =
                event.key == Key.Enter || event.key == Key.NumPadEnter || event.key == Key.Spacebar
            val isContextKey =
                onSecondaryClick != null &&
                    (event.key == Key.Menu || (event.isShiftPressed && !event.isCtrlPressed && !event.isAltPressed && event.key == Key.F10))

            when (event.type) {
                KeyEventType.KeyDown -> {
                    if ((isPrimaryActivationKey && noNavModifiers) || isContextKey) {
                        if (pressedActivationKey == null) {
                            pressedActivationKey = event.key
                        }
                        true
                    } else {
                        false
                    }
                }
                KeyEventType.KeyUp -> {
                    if (pressedActivationKey == event.key) {
                        pressedActivationKey = null
                        if (isContextKey) {
                            onSecondaryClick.invoke()
                        } else if (isPrimaryActivationKey && noNavModifiers) {
                            onClick()
                        }
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }
        .desktopFocusRing(
            isFocused = isFocused && effectiveEnabled && showRing,
            shape = shape,
            ringColor = ringColor,
            ringWidth = ringWidth,
        )
}

/**
 * Intercepts Escape on KeyDown->KeyUp in preview phase so fullscreen/window-level KeyDown
 * listeners do not pre-empt screen or overlay dismissal.
 */
@Composable
fun Modifier.handleEscapeKey(
    enabled: Boolean = true,
    onEscape: () -> Unit,
): Modifier = handleEscapeKeyInternal(enabled = enabled, preview = true, onEscape = onEscape)

/**
 * Fallback Escape handler in bubbling phase so child inputs/overlays/subscreens handle Escape
 * first before parent/root back navigation triggers.
 */
@Composable
fun Modifier.handleBubbledEscapeKey(
    enabled: Boolean = true,
    onEscape: () -> Unit,
): Modifier = handleEscapeKeyInternal(enabled = enabled, preview = false, onEscape = onEscape)

@Composable
private fun Modifier.handleEscapeKeyInternal(
    enabled: Boolean,
    preview: Boolean,
    onEscape: () -> Unit,
): Modifier {
    var escapePressed by remember { mutableStateOf(false) }
    val handler: (androidx.compose.ui.input.key.KeyEvent) -> Boolean = { event ->
        if (!enabled) {
            escapePressed = false
            false
        } else {
            val plainEscape = event.key == Key.Escape &&
                !event.isCtrlPressed &&
                !event.isAltPressed &&
                !event.isShiftPressed
            if (!plainEscape) {
                false
            } else {
                when (event.type) {
                    KeyEventType.KeyDown -> {
                        escapePressed = true
                        true
                    }
                    KeyEventType.KeyUp -> {
                        if (escapePressed) {
                            escapePressed = false
                            onEscape()
                            true
                        } else {
                            false
                        }
                    }
                    else -> false
                }
            }
        }
    }
    return if (preview) this.onPreviewKeyEvent(handler) else this.onKeyEvent(handler)
}

/**
 * Translates unconsumed directional arrow key presses into 2D focus navigation on desktop.
 * Must run in bubbling [onKeyEvent] so inputs, sliders, and carousels keep priority.
 */
@Composable
fun Modifier.desktopDirectionalFocusNavigation(
    enabled: Boolean = true,
): Modifier {
    val focusManager = LocalFocusManager.current
    return this.onKeyEvent { event ->
        if (!enabled || event.type != KeyEventType.KeyDown) return@onKeyEvent false
        if (event.isCtrlPressed || event.isAltPressed || event.isShiftPressed) return@onKeyEvent false
        val direction = when (event.key) {
            Key.DirectionLeft -> FocusDirection.Left
            Key.DirectionRight -> FocusDirection.Right
            Key.DirectionUp -> FocusDirection.Up
            Key.DirectionDown -> FocusDirection.Down
            else -> null
        } ?: return@onKeyEvent false

        focusManager.moveFocus(direction)
    }
}

/**
 * Non-focusable pointer backdrop tap detector for modal scrims and overlay dismissal.
 */
fun Modifier.nonFocusableTap(
    key: Any? = Unit,
    onTap: () -> Unit,
): Modifier = this.pointerInput(key) {
    detectTapGestures { onTap() }
}
