package com.lagradost.cloudstream3.desktop.ui.components

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.input.InputMode
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
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.desktop.ui.screens.dev.DevStudioState
import com.lagradost.cloudstream3.desktop.ui.screens.dev.FocusInspectKind
import com.lagradost.cloudstream3.desktop.ui.screens.dev.VisualFocusInspectorRegistry
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Tracks whether the enclosing UI layer is currently eligible to receive keyboard focus.
 * Overlays set this to false on covered layers behind them.
 */
val LocalFocusLayerActive = compositionLocalOf { true }

internal val LocalIsCategoryRowHeader = compositionLocalOf { false }

internal object DirectionalFocusFilter {
    var skipRowHeadersDuringVerticalMove by mutableStateOf(false)
}

internal inline fun <T> withDirectionalFocusFilter(
    direction: FocusDirection,
    block: () -> T,
): T {
    val isVertical = direction == FocusDirection.Up || direction == FocusDirection.Down
    val prev = DirectionalFocusFilter.skipRowHeadersDuringVerticalMove
    if (isVertical) {
        DirectionalFocusFilter.skipRowHeadersDuringVerticalMove = true
    }
    return try {
        block()
    } finally {
        DirectionalFocusFilter.skipRowHeadersDuringVerticalMove = prev
    }
}

/**
 * Coordinates window-level key recovery when Compose has no currently focused descendant
 * (focused == null), ensuring directional/Tab/Escape presses work immediately after navigation.
 */
object RootFocusCoordinator {
    @Volatile
    private var rootHasFocusedDescendant: Boolean = false

    @Volatile
    private var recoverFocusAction: (() -> Boolean)? = null

    @Volatile
    private var escapeFallbackAction: (() -> Boolean)? = null

    fun notifyRootFocus(hasFocus: Boolean) {
        rootHasFocusedDescendant = hasFocus
    }

    fun isAnyNodeFocused(): Boolean = rootHasFocusedDescendant

    fun register(
        recoverFocus: () -> Boolean,
        escapeFallback: () -> Boolean,
    ) {
        recoverFocusAction = recoverFocus
        escapeFallbackAction = escapeFallback
    }

    fun clear(
        recoverFocus: () -> Boolean,
        escapeFallback: () -> Boolean,
    ) {
        if (recoverFocusAction === recoverFocus) recoverFocusAction = null
        if (escapeFallbackAction === escapeFallback) escapeFallbackAction = null
        rootHasFocusedDescendant = false
    }

    fun recoverFocusIfNull(): Boolean {
        if (rootHasFocusedDescendant) return false
        return recoverFocusAction?.invoke() ?: false
    }

    fun triggerEscapeFallbackIfNull(): Boolean {
        if (rootHasFocusedDescendant) return false
        return escapeFallbackAction?.invoke() ?: false
    }
}

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
    initialFocusRequester: FocusRequester? = null,
    requestFocusWhenActive: Boolean = false,
    up: FocusRequester? = null,
    down: FocusRequester? = null,
    left: FocusRequester? = null,
    right: FocusRequester? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val groupRequester = focusRequester ?: remember { FocusRequester() }
    val hudEnabled by DevStudioState.isVisualFocusHudEnabled.collectAsState()
    val inspectKey = remember { Any() }
    val callerTag = remember(hudEnabled) {
        if (hudEnabled) VisualFocusInspectorRegistry.captureCallerTag() else ""
    }
    var wasBlocked by remember { mutableStateOf(false) }
    var hadFocusBeforeBlock by remember { mutableStateOf(false) }
    var layerHasFocus by remember { mutableStateOf(false) }
    var seedAttemptCompleted by remember { mutableStateOf(false) }
    var initialSeedStarted by remember { mutableStateOf(false) }

    fun requestInitialOrGroupFocus(): Boolean {
        val preferred = initialFocusRequester?.let { req ->
            runCatching { req.requestFocus() }.getOrDefault(false)
        } ?: false
        if (preferred) return true
        return runCatching { groupRequester.requestFocus() }.getOrDefault(false)
    }

    DisposableEffect(hudEnabled, inspectKey) {
        onDispose {
            VisualFocusInspectorRegistry.removeNode(inspectKey)
        }
    }

    LaunchedEffect(active, requestFocusWhenActive) {
        if (!active) {
            wasBlocked = true
            seedAttemptCompleted = false
            initialSeedStarted = false
        } else {
            val resumingFromOverlay = wasBlocked
            wasBlocked = false
            if (requestFocusWhenActive) {
                initialSeedStarted = false
                var seeded = if (resumingFromOverlay && restoreFocus && hadFocusBeforeBlock) {
                    hadFocusBeforeBlock = false
                    runCatching { groupRequester.restoreFocusedChild() }.getOrDefault(false) ||
                        requestInitialOrGroupFocus()
                } else {
                    if (initialFocusRequester == null) {
                        // Two frame ticks cross composition AND the first measure/layout pass so
                        // subcomposed LazyRow/LazyVerticalGrid items beat bottom dialog footer actions.
                        withFrameNanos { }
                        withFrameNanos { }
                    }
                    requestInitialOrGroupFocus()
                }
                initialSeedStarted = true
                var retryFrames = 0
                while (isActive && !seeded && !layerHasFocus && retryFrames < 8) {
                    withFrameNanos { }
                    seeded = requestInitialOrGroupFocus()
                    retryFrames++
                }
                seedAttemptCompleted = seeded
            } else if (resumingFromOverlay && restoreFocus && hadFocusBeforeBlock) {
                initialSeedStarted = true
                hadFocusBeforeBlock = false
                val restored = runCatching { groupRequester.restoreFocusedChild() }.getOrDefault(false)
                if (!restored) {
                    runCatching { groupRequester.requestFocus() }
                }
            } else {
                initialSeedStarted = true
                hadFocusBeforeBlock = false
                seedAttemptCompleted = false
            }
        }
    }

    val restorerModifier = if (restoreFocus) {
        Modifier.focusRestorer()
    } else {
        Modifier
    }

    val needsPositionTracking = hudEnabled || (active && requestFocusWhenActive && !seedAttemptCompleted)
    val positionedModifier = if (needsPositionTracking) {
        Modifier.onGloballyPositioned { coordinates ->
            if (!coordinates.isAttached) return@onGloballyPositioned
            if (active && requestFocusWhenActive && initialSeedStarted && !hadFocusBeforeBlock && !seedAttemptCompleted) {
                seedAttemptCompleted = requestInitialOrGroupFocus()
            }
            if (hudEnabled) {
                VisualFocusInspectorRegistry.upsertNode(
                    id = inspectKey,
                    kind = FocusInspectKind.LAYER,
                    bounds = coordinates.boundsInWindow(),
                    hasFocus = layerHasFocus,
                    canFocus = active,
                    layerActive = active,
                    trapFocus = trapFocus,
                    restoreFocus = restoreFocus,
                    sourceTag = callerTag,
                )
            }
        }
    } else {
        Modifier
    }

    val exitGuard = remember { FocusZoneBoundaryGuard() }

    Box(
        modifier = modifier
            .then(positionedModifier)
            .onFocusChanged { focusState ->
                layerHasFocus = focusState.hasFocus
                exitGuard.hasFocus = focusState.hasFocus
                if (active && focusState.hasFocus) {
                    hadFocusBeforeBlock = true
                    seedAttemptCompleted = true
                    if (restoreFocus) {
                        runCatching { groupRequester.saveFocusedChild() }
                    }
                } else if (active && !focusState.hasFocus && !wasBlocked) {
                    hadFocusBeforeBlock = false
                }
                if (hudEnabled) {
                    VisualFocusInspectorRegistry.updateFocusFlags(
                        id = inspectKey,
                        isFocused = false,
                        hasFocus = focusState.hasFocus,
                        sourceTag = callerTag,
                    )
                }
            }
            .directionalZoneBoundaryFallback(
                boundaryGuard = exitGuard,
                up = up,
                down = down,
                left = left,
                right = right,
            )
            .focusProperties {
                if (!active) {
                    onEnter = { cancelFocusChange() }
                }
                if ((trapFocus && active) || exitGuard.blockExternalExit) {
                    onExit = {
                        if (exitGuard.blockExternalExit) {
                            exitGuard.exitWasBlocked = true
                        }
                        cancelFocusChange()
                    }
                }
            }
            .focusGroup()
            .focusRequester(groupRequester)
            .then(restorerModifier)
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
    val isCategoryRowHeader = LocalIsCategoryRowHeader.current
    val isFocused by interactionSource.collectIsFocusedAsState()
    val effectiveEnabled = enabled && layerActive
    val hudEnabled by DevStudioState.isVisualFocusHudEnabled.collectAsState()
    val base = this
        .focusProperties {
            canFocus = effectiveEnabled && !(isCategoryRowHeader && DirectionalFocusFilter.skipRowHeadersDuringVerticalMove)
        }
        .desktopFocusRing(
            isFocused = isFocused && effectiveEnabled && showRing,
            shape = shape,
            ringColor = ringColor,
            ringWidth = ringWidth,
        )
    if (!hudEnabled) return base

    val inspectKey = remember { Any() }
    val callerTag = remember(hudEnabled) { VisualFocusInspectorRegistry.captureCallerTag() }
    var nodeFocused by remember { mutableStateOf(false) }

    DisposableEffect(inspectKey) {
        onDispose { VisualFocusInspectorRegistry.removeNode(inspectKey) }
    }

    return base
        .onFocusChanged { focusState ->
            nodeFocused = focusState.isFocused
            VisualFocusInspectorRegistry.updateFocusFlags(
                id = inspectKey,
                isFocused = focusState.isFocused,
                hasFocus = focusState.hasFocus,
                sourceTag = callerTag,
            )
        }
        .onGloballyPositioned { coordinates ->
            if (!coordinates.isAttached) return@onGloballyPositioned
            VisualFocusInspectorRegistry.upsertNode(
                id = inspectKey,
                kind = FocusInspectKind.INTERACTIVE,
                bounds = coordinates.boundsInWindow(),
                isFocused = nodeFocused || (isFocused && effectiveEnabled),
                hasFocus = nodeFocused,
                canFocus = effectiveEnabled,
                layerActive = layerActive,
                sourceTag = callerTag,
            )
        }
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
    val hudEnabled by DevStudioState.isVisualFocusHudEnabled.collectAsState()
    var pressedActivationKey by remember { mutableStateOf<Key?>(null) }

    val inspectKey = remember { Any() }
    val callerTag = remember(hudEnabled) {
        if (hudEnabled) VisualFocusInspectorRegistry.captureCallerTag() else ""
    }
    var nodeFocused by remember { mutableStateOf(false) }

    DisposableEffect(hudEnabled, inspectKey) {
        onDispose { VisualFocusInspectorRegistry.removeNode(inspectKey) }
    }

    val focusObserverModifier = if (hudEnabled) {
        Modifier.onFocusChanged { focusState ->
            nodeFocused = focusState.isFocused
            VisualFocusInspectorRegistry.updateFocusFlags(
                id = inspectKey,
                isFocused = focusState.isFocused,
                hasFocus = focusState.hasFocus,
                sourceTag = callerTag,
            )
        }
    } else {
        Modifier
    }

    val positionedModifier = if (hudEnabled) {
        Modifier.onGloballyPositioned { coordinates ->
            if (!coordinates.isAttached) return@onGloballyPositioned
            VisualFocusInspectorRegistry.upsertNode(
                id = inspectKey,
                kind = FocusInspectKind.CARD,
                bounds = coordinates.boundsInWindow(),
                isFocused = nodeFocused || (isFocused && effectiveEnabled),
                hasFocus = nodeFocused,
                canFocus = effectiveEnabled,
                layerActive = layerActive,
                sourceTag = callerTag,
            )
        }
    } else {
        Modifier
    }

    return this
        .then(focusObserverModifier)
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
                            if (hudEnabled) {
                                VisualFocusInspectorRegistry.recordKeyTrace("ContextKey", "OPENED")
                            }
                            onSecondaryClick.invoke()
                        } else if (isPrimaryActivationKey && noNavModifiers) {
                            if (hudEnabled) {
                                VisualFocusInspectorRegistry.recordKeyTrace("Activate(Card)", "CLICK")
                            }
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
        .then(positionedModifier)
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
                            if (DevStudioState.isVisualFocusHudEnabled.value) {
                                VisualFocusInspectorRegistry.recordKeyTrace(
                                    action = if (preview) "Esc(Preview)" else "Esc(Bubbled)",
                                    outcome = "HANDLED",
                                )
                            }
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

private fun mapArrowDirection(event: androidx.compose.ui.input.key.KeyEvent): FocusDirection? {
    if (event.type != KeyEventType.KeyDown) return null
    if (event.isCtrlPressed || event.isAltPressed || event.isShiftPressed) return null
    return when (event.key) {
        Key.DirectionLeft -> FocusDirection.Left
        Key.DirectionRight -> FocusDirection.Right
        Key.DirectionUp -> FocusDirection.Up
        Key.DirectionDown -> FocusDirection.Down
        else -> null
    }
}

private fun directionTraceLabel(direction: FocusDirection): String = when (direction) {
    FocusDirection.Left -> "ArrowLeft"
    FocusDirection.Right -> "ArrowRight"
    FocusDirection.Up -> "ArrowUp"
    FocusDirection.Down -> "ArrowDown"
    else -> direction.toString()
}

private class FocusZoneBoundaryGuard {
    var hasFocus: Boolean = false
    var blockExternalExit: Boolean = false
    var exitWasBlocked: Boolean = false
}

@Composable
private fun Modifier.directionalZoneBoundaryFallback(
    boundaryGuard: FocusZoneBoundaryGuard,
    up: FocusRequester? = null,
    down: FocusRequester? = null,
    left: FocusRequester? = null,
    right: FocusRequester? = null,
): Modifier {
    if (up == null && down == null && left == null && right == null) return this
    val focusManager = LocalFocusManager.current
    val inputModeManager = LocalInputModeManager.current
    return this.onKeyEvent { event ->
        val direction = mapArrowDirection(event) ?: return@onKeyEvent false
        val target = when (direction) {
            FocusDirection.Up -> up
            FocusDirection.Down -> down
            FocusDirection.Left -> left
            FocusDirection.Right -> right
            else -> null
        } ?: return@onKeyEvent false

        runCatching { inputModeManager.requestInputMode(InputMode.Keyboard) }
        val hudEnabled = DevStudioState.isVisualFocusHudEnabled.value
        val fromEntry = if (hudEnabled) VisualFocusInspectorRegistry.currentFocusedEntry() else null
        // Scope moveFocus to intra-zone navigation first so Compose cannot skip over a target
        // LazyColumn/layer and land on an unrelated sibling zone.
        boundaryGuard.blockExternalExit = true
        boundaryGuard.exitWasBlocked = false
        val movedNatively = try {
            withDirectionalFocusFilter(direction) {
                focusManager.moveFocus(direction)
            }
        } finally {
            boundaryGuard.blockExternalExit = false
        }
        val movedInsideZone = movedNatively && !boundaryGuard.exitWasBlocked && boundaryGuard.hasFocus
        val entered = if (movedInsideZone) {
            true
        } else {
            runCatching { target.requestFocus() }.getOrDefault(false)
        }
        if (entered && hudEnabled) {
            val toEntry = VisualFocusInspectorRegistry.currentFocusedEntry()
            VisualFocusInspectorRegistry.recordMove(directionTraceLabel(direction), true, fromEntry, toEntry)
        }
        entered
    }
}

/**
 * Establishes a named focus zone with optional child restoration and deterministic boundary
 * handoffs when Compose 2D search cannot cross an overlapping scroll or layer container.
 */
@Composable
fun Modifier.desktopFocusZone(
    zoneRequester: FocusRequester,
    restoreFocus: Boolean = true,
    up: FocusRequester? = null,
    down: FocusRequester? = null,
    left: FocusRequester? = null,
    right: FocusRequester? = null,
): Modifier {
    val boundaryGuard = remember { FocusZoneBoundaryGuard() }
    return this
        .onFocusChanged { focusState ->
            boundaryGuard.hasFocus = focusState.hasFocus
        }
        .directionalZoneBoundaryFallback(
            boundaryGuard = boundaryGuard,
            up = up,
            down = down,
            left = left,
            right = right,
        )
        .focusProperties {
            onExit = {
                if (boundaryGuard.blockExternalExit) {
                    boundaryGuard.exitWasBlocked = true
                    cancelFocusChange()
                }
            }
        }
        .focusGroup()
        .focusRequester(zoneRequester)
        .then(if (restoreFocus) Modifier.focusRestorer() else Modifier)
        .focusGroup()
}

/**
 * Keeps vertical arrow navigation inside a [LazyColumn] while off-screen items (such as the top Hero)
 * can still be scrolled back into view, focusing [topItemFocusRequester] when reaching index 0.
 */
@Composable
fun Modifier.desktopLazyColumnBoundaryScroll(
    listState: LazyListState,
    topItemFocusRequester: FocusRequester? = null,
): Modifier {
    val boundaryGuard = remember { FocusZoneBoundaryGuard() }
    val focusManager = LocalFocusManager.current
    val inputModeManager = LocalInputModeManager.current
    val coroutineScope = rememberCoroutineScope()
    var scrollJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    return this
        .onFocusChanged { focusState ->
            boundaryGuard.hasFocus = focusState.hasFocus
        }
        .onKeyEvent { event ->
            val direction = mapArrowDirection(event) ?: return@onKeyEvent false
            if (direction != FocusDirection.Up && direction != FocusDirection.Down) return@onKeyEvent false

            runCatching { inputModeManager.requestInputMode(InputMode.Keyboard) }
            val hudEnabled = DevStudioState.isVisualFocusHudEnabled.value
            val fromEntry = if (hudEnabled) VisualFocusInspectorRegistry.currentFocusedEntry() else null

            boundaryGuard.blockExternalExit = true
            boundaryGuard.exitWasBlocked = false
            val movedNatively = try {
                withDirectionalFocusFilter(direction) {
                    focusManager.moveFocus(direction)
                }
            } finally {
                boundaryGuard.blockExternalExit = false
            }
            val movedInsideList = movedNatively && !boundaryGuard.exitWasBlocked && boundaryGuard.hasFocus
            if (movedInsideList) {
                if (hudEnabled) {
                    val toEntry = VisualFocusInspectorRegistry.currentFocusedEntry()
                    VisualFocusInspectorRegistry.recordMove(directionTraceLabel(direction), true, fromEntry, toEntry)
                }
                return@onKeyEvent true
            }

            if (direction == FocusDirection.Up && listState.canScrollBackward) {
                val targetIndex = (listState.firstVisibleItemIndex - 1).coerceAtLeast(0)
                scrollJob?.cancel()
                scrollJob = coroutineScope.launch {
                    listState.animateScrollToItem(targetIndex, 0)
                    withFrameNanos { }
                    if (targetIndex == 0 && topItemFocusRequester != null) {
                        val focusedHero = runCatching { topItemFocusRequester.requestFocus() }.getOrDefault(false)
                        if (!focusedHero) {
                            withDirectionalFocusFilter(FocusDirection.Up) {
                                focusManager.moveFocus(FocusDirection.Up)
                            }
                        }
                    } else {
                        withDirectionalFocusFilter(FocusDirection.Up) {
                            focusManager.moveFocus(FocusDirection.Up)
                        }
                    }
                    if (hudEnabled) {
                        val toEntry = VisualFocusInspectorRegistry.currentFocusedEntry()
                        VisualFocusInspectorRegistry.recordMove("ArrowUp(Scroll)", toEntry != fromEntry, fromEntry, toEntry)
                    }
                }
                return@onKeyEvent true
            }

            false
        }
        .focusProperties {
            onExit = {
                if (boundaryGuard.blockExternalExit) {
                    boundaryGuard.exitWasBlocked = true
                    cancelFocusChange()
                }
            }
        }
        .focusGroup()
}

/**
 * Adds a visible focus ring plus vertical/Escape keyboard exits for single-line text inputs so
 * users are never trapped inside a text field while preserving horizontal caret editing.
 */
@Composable
fun Modifier.desktopTextInputFocus(
    shape: Shape = RoundedCornerShape(10.dp),
    singleLine: Boolean = true,
    enabled: Boolean = true,
    showRing: Boolean = true,
    ringColor: Color = MaterialTheme.colorScheme.primary,
    ringWidth: Dp = 2.dp,
    upRequester: FocusRequester? = null,
    downRequester: FocusRequester? = null,
    onEscapeExit: (() -> Boolean)? = null,
): Modifier {
    val layerActive = LocalFocusLayerActive.current
    val effectiveEnabled = enabled && layerActive
    val hudEnabled by DevStudioState.isVisualFocusHudEnabled.collectAsState()
    val focusManager = LocalFocusManager.current
    val inputModeManager = LocalInputModeManager.current
    var isFocused by remember { mutableStateOf(false) }
    var hasFocus by remember { mutableStateOf(false) }
    var escapePressed by remember { mutableStateOf(false) }

    val inspectKey = remember { Any() }
    val callerTag = remember(hudEnabled) {
        if (hudEnabled) VisualFocusInspectorRegistry.captureCallerTag() else ""
    }

    DisposableEffect(hudEnabled, inspectKey) {
        onDispose { VisualFocusInspectorRegistry.removeNode(inspectKey) }
    }

    val positionedModifier = if (hudEnabled) {
        Modifier.onGloballyPositioned { coordinates ->
            if (!coordinates.isAttached) return@onGloballyPositioned
            VisualFocusInspectorRegistry.upsertNode(
                id = inspectKey,
                kind = FocusInspectKind.INTERACTIVE,
                bounds = coordinates.boundsInWindow(),
                isFocused = isFocused && effectiveEnabled,
                hasFocus = hasFocus,
                canFocus = effectiveEnabled,
                layerActive = layerActive,
                sourceTag = callerTag,
            )
        }
    } else {
        Modifier
    }

    return this
        .onFocusChanged { focusState ->
            isFocused = focusState.isFocused
            hasFocus = focusState.hasFocus
            if (!focusState.hasFocus) {
                escapePressed = false
            }
            if (hudEnabled) {
                VisualFocusInspectorRegistry.updateFocusFlags(
                    id = inspectKey,
                    isFocused = focusState.isFocused,
                    hasFocus = focusState.hasFocus,
                    sourceTag = callerTag,
                )
            }
        }
        .onPreviewKeyEvent { event ->
            if (!effectiveEnabled) return@onPreviewKeyEvent false
            val noNavModifiers = !event.isCtrlPressed && !event.isAltPressed && !event.isShiftPressed
            if (!noNavModifiers) return@onPreviewKeyEvent false

            if (event.key == Key.Escape) {
                when (event.type) {
                    KeyEventType.KeyDown -> {
                        escapePressed = true
                        return@onPreviewKeyEvent true
                    }
                    KeyEventType.KeyUp -> {
                        if (escapePressed) {
                            escapePressed = false
                            if (onEscapeExit?.invoke() == true) {
                                return@onPreviewKeyEvent true
                            }
                            runCatching { inputModeManager.requestInputMode(InputMode.Keyboard) }
                            val exited = withDirectionalFocusFilter(FocusDirection.Down) {
                                focusManager.moveFocus(FocusDirection.Down) ||
                                    focusManager.moveFocus(FocusDirection.Next) ||
                                    focusManager.moveFocus(FocusDirection.Up)
                            }
                            if (!exited) {
                                focusManager.clearFocus()
                            }
                            return@onPreviewKeyEvent true
                        }
                    }
                    else -> Unit
                }
                return@onPreviewKeyEvent false
            }

            if (singleLine && event.type == KeyEventType.KeyDown &&
                (event.key == Key.DirectionUp || event.key == Key.DirectionDown)
            ) {
                val direction = if (event.key == Key.DirectionUp) FocusDirection.Up else FocusDirection.Down
                runCatching { inputModeManager.requestInputMode(InputMode.Keyboard) }
                val fromEntry = if (hudEnabled) VisualFocusInspectorRegistry.currentFocusedEntry() else null
                val movedNatively = withDirectionalFocusFilter(direction) {
                    focusManager.moveFocus(direction)
                }
                val moved = if (movedNatively) {
                    true
                } else {
                    val explicitTarget = if (direction == FocusDirection.Up) upRequester else downRequester
                    val requestedExplicit = explicitTarget?.let {
                        runCatching { it.requestFocus() }.getOrDefault(false)
                    } ?: false
                    if (requestedExplicit) {
                        true
                    } else if (direction == FocusDirection.Down) {
                        focusManager.moveFocus(FocusDirection.Next)
                    } else {
                        false
                    }
                }
                if (hudEnabled) {
                    val toEntry = VisualFocusInspectorRegistry.currentFocusedEntry()
                    VisualFocusInspectorRegistry.recordMove(directionTraceLabel(direction), moved, fromEntry, toEntry)
                }
                return@onPreviewKeyEvent moved
            }
            false
        }
        .desktopFocusRing(
            isFocused = (isFocused || hasFocus) && effectiveEnabled && showRing,
            shape = shape,
            ringColor = ringColor,
            ringWidth = ringWidth,
        )
        .then(positionedModifier)
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
    val inputModeManager = LocalInputModeManager.current
    return this.onKeyEvent { event ->
        if (!enabled) return@onKeyEvent false
        val direction = mapArrowDirection(event) ?: return@onKeyEvent false

        runCatching { inputModeManager.requestInputMode(InputMode.Keyboard) }
        val hudEnabled = DevStudioState.isVisualFocusHudEnabled.value
        val fromEntry = if (hudEnabled) VisualFocusInspectorRegistry.currentFocusedEntry() else null
        val moved = withDirectionalFocusFilter(direction) {
            focusManager.moveFocus(direction)
        }
        if (hudEnabled) {
            val toEntry = VisualFocusInspectorRegistry.currentFocusedEntry()
            VisualFocusInspectorRegistry.recordMove(directionTraceLabel(direction), moved, fromEntry, toEntry)
        }
        moved
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
