package com.lagradost.cloudstream3.desktop.ui.screens.dev

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lagradost.cloudstream3.desktop.ui.components.AppToastManager
import com.lagradost.cloudstream3.desktop.ui.components.DesktopButton
import com.lagradost.cloudstream3.desktop.ui.components.DesktopFilledTonalButton
import com.lagradost.cloudstream3.desktop.ui.components.DesktopOutlinedButton
import com.lagradost.cloudstream3.desktop.ui.components.DesktopVerticalScrollbar
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

enum class VisualFocusHudMode(val label: String) {
    FULL("Rects + Beams + Layers"),
    COMPACT("Focused + Active Layers"),
    LABELS("Rects + Tag Badges"),
}

enum class FocusInspectKind(val shortLabel: String) {
    LAYER("LAYER"),
    INTERACTIVE("BTN"),
    CARD("CARD"),
}

data class FocusInspectEntry(
    val id: Any,
    val kind: FocusInspectKind,
    val bounds: Rect,
    val isFocused: Boolean,
    val hasFocus: Boolean,
    val canFocus: Boolean,
    val layerActive: Boolean,
    val trapFocus: Boolean,
    val restoreFocus: Boolean,
    val sourceTag: String,
    val seq: Int,
)

data class FocusTraceEvent(
    val timestampMs: Long,
    val action: String,
    val outcome: String,
    val fromLabel: String?,
    val toLabel: String?,
    val fromBounds: Rect?,
    val toBounds: Rect?,
    val focusWasNull: Boolean,
)

data class FocusJumpVector(
    val fromBounds: Rect?,
    val toBounds: Rect?,
    val succeeded: Boolean,
    val focusWasNull: Boolean,
    val label: String,
    val timestampMs: Long,
)

object VisualFocusInspectorRegistry {
    private const val MAX_TRACE_EVENTS = 40
    private val entries = ConcurrentHashMap<Any, FocusInspectEntry>()
    private val traces = CopyOnWriteArrayList<FocusTraceEvent>()
    private var nextSeq = java.util.concurrent.atomic.AtomicInteger(1)

    internal var drawVersion by mutableLongStateOf(0L)
        private set

    internal var traceVersion by mutableLongStateOf(0L)
        private set

    @Volatile
    internal var lastJumpVector: FocusJumpVector? = null
        private set

    @Volatile
    internal var lastWindowSizePx: Pair<Int, Int> = 0 to 0
        private set

    fun captureCallerTag(): String {
        val frames = Throwable().stackTrace
        val matched = frames.firstOrNull { frame ->
            val cls = frame.className
            !cls.startsWith("com.lagradost.cloudstream3.desktop.ui.components.DesktopFocusModifiers") &&
                !cls.startsWith("com.lagradost.cloudstream3.desktop.ui.screens.dev.VisualFocus") &&
                !cls.startsWith("androidx.") &&
                !cls.startsWith("kotlin.") &&
                !cls.startsWith("java.") &&
                frame.lineNumber > 0
        }
        return if (matched != null) {
            val file = matched.fileName?.removeSuffix(".kt")
                ?: matched.className.substringAfterLast('.').substringBefore('$')
            "$file:${matched.lineNumber}"
        } else {
            "unknown"
        }
    }

    fun upsertNode(
        id: Any,
        kind: FocusInspectKind,
        bounds: Rect,
        isFocused: Boolean = false,
        hasFocus: Boolean = false,
        canFocus: Boolean = true,
        layerActive: Boolean = true,
        trapFocus: Boolean = false,
        restoreFocus: Boolean = false,
        sourceTag: String = "",
    ) {
        val existing = entries[id]
        val seq = existing?.seq ?: nextSeq.getAndIncrement()
        val updated = FocusInspectEntry(
            id = id,
            kind = kind,
            bounds = bounds,
            isFocused = isFocused,
            hasFocus = hasFocus,
            canFocus = canFocus,
            layerActive = layerActive,
            trapFocus = trapFocus,
            restoreFocus = restoreFocus,
            sourceTag = sourceTag.ifBlank { existing?.sourceTag.orEmpty() },
            seq = seq,
        )
        if (existing != null && entriesCloseEqual(existing, updated)) {
            return
        }
        entries[id] = updated
        drawVersion++
        if (existing?.isFocused != updated.isFocused) {
            traceVersion++
        }
    }

    fun updateFocusFlags(
        id: Any,
        isFocused: Boolean,
        hasFocus: Boolean,
        sourceTag: String = "",
    ) {
        val existing = entries[id]
        if (existing == null) {
            entries[id] = FocusInspectEntry(
                id = id,
                kind = FocusInspectKind.INTERACTIVE,
                bounds = Rect.Zero,
                isFocused = isFocused,
                hasFocus = hasFocus,
                canFocus = true,
                layerActive = true,
                trapFocus = false,
                restoreFocus = false,
                sourceTag = sourceTag,
                seq = nextSeq.getAndIncrement(),
            )
            drawVersion++
            traceVersion++
            return
        }
        if (existing.isFocused == isFocused && existing.hasFocus == hasFocus) return
        entries[id] = existing.copy(
            isFocused = isFocused,
            hasFocus = hasFocus,
            sourceTag = sourceTag.ifBlank { existing.sourceTag },
        )
        drawVersion++
        traceVersion++
    }

    fun removeNode(id: Any) {
        val removed = entries.remove(id) ?: return
        drawVersion++
        if (removed.isFocused) {
            traceVersion++
        }
    }

    fun clearAll() {
        entries.clear()
        traces.clear()
        lastJumpVector = null
        drawVersion++
        traceVersion++
    }

    fun clearTraces() {
        traces.clear()
        lastJumpVector = null
        drawVersion++
        traceVersion++
    }

    fun currentFocusedEntry(): FocusInspectEntry? =
        entries.values.firstOrNull { it.kind != FocusInspectKind.LAYER && it.isFocused }

    fun snapshotEntries(): List<FocusInspectEntry> =
        entries.values
            .filter { !it.bounds.isEmpty }
            .sortedWith(compareBy({ it.kind.ordinal }, { it.bounds.top }, { it.bounds.left }, { it.seq }))

    fun snapshotTraces(): List<FocusTraceEvent> = traces.toList()

    fun recordMove(
        action: String,
        succeeded: Boolean,
        fromEntry: FocusInspectEntry?,
        toEntry: FocusInspectEntry?,
        outcomeOverride: String? = null,
    ) {
        val now = System.currentTimeMillis()
        val focusWasNull = fromEntry == null
        val outcome = outcomeOverride ?: when {
            succeeded && toEntry != null -> "MOVED"
            succeeded -> "MOVED(untracked target)"
            focusWasNull -> "DROPPED(focus=null)"
            else -> "NO TARGET"
        }
        val fromLabel = fromEntry?.let { "${it.sourceTag}#${it.seq}" }
        val toLabel = toEntry?.let { "${it.sourceTag}#${it.seq}" }
        traces.add(
            0,
            FocusTraceEvent(
                timestampMs = now,
                action = action,
                outcome = outcome,
                fromLabel = fromLabel,
                toLabel = toLabel,
                fromBounds = fromEntry?.bounds,
                toBounds = toEntry?.bounds,
                focusWasNull = focusWasNull,
            ),
        )
        while (traces.size > MAX_TRACE_EVENTS) {
            traces.removeAt(traces.lastIndex)
        }
        lastJumpVector = FocusJumpVector(
            fromBounds = fromEntry?.bounds,
            toBounds = toEntry?.bounds,
            succeeded = succeeded,
            focusWasNull = focusWasNull,
            label = "$action -> $outcome",
            timestampMs = now,
        )
        drawVersion++
        traceVersion++
    }

    fun recordKeyTrace(
        action: String,
        outcome: String,
        focusWasNull: Boolean = currentFocusedEntry() == null,
    ) {
        val now = System.currentTimeMillis()
        val active = currentFocusedEntry()
        traces.add(
            0,
            FocusTraceEvent(
                timestampMs = now,
                action = action,
                outcome = outcome,
                fromLabel = active?.let { "${it.sourceTag}#${it.seq}" },
                toLabel = null,
                fromBounds = active?.bounds,
                toBounds = null,
                focusWasNull = focusWasNull,
            ),
        )
        while (traces.size > MAX_TRACE_EVENTS) {
            traces.removeAt(traces.lastIndex)
        }
        drawVersion++
        traceVersion++
    }

    fun updateWindowSize(widthPx: Int, heightPx: Int) {
        if (widthPx > 0 && heightPx > 0) {
            lastWindowSizePx = widthPx to heightPx
        }
    }

    fun buildSnapshot(
        windowWidthPx: Int = lastWindowSizePx.first,
        windowHeightPx: Int = lastWindowSizePx.second,
    ): String {
        val all = snapshotEntries()
        val layers = all.filter { it.kind == FocusInspectKind.LAYER }
        val focusables = all.filter { it.kind != FocusInspectKind.LAYER }
        val focused = focusables.firstOrNull { it.isFocused }
        val recentTraces = snapshotTraces().take(15)

        return buildString {
            appendLine("=== CloudStream Visual Focus Snapshot ===")
            appendLine("WindowPx: ${windowWidthPx}x${windowHeightPx}")
            appendLine("HUD Mode: ${DevStudioState.visualFocusHudMode.value.name}")
            if (focused != null) {
                appendLine("FOCUSED: ${focused.kind.shortLabel} #${focused.seq} [${focused.sourceTag}] rect=${formatRect(focused.bounds)} canFocus=${focused.canFocus}")
            } else {
                appendLine("FOCUSED: <NULL> (No tracked node currently holds focus)")
            }
            appendLine()
            appendLine("Layers (${layers.size}):")
            layers.forEach { layer ->
                val stateTag = buildList {
                    add(if (layer.layerActive) "ACTIVE" else "BLOCKED")
                    if (layer.hasFocus) add("hasFocus")
                    if (layer.trapFocus) add("TRAP")
                    if (layer.restoreFocus) add("RESTORE")
                }.joinToString(",")
                appendLine("  - LAYER #${layer.seq} [$stateTag] (${layer.sourceTag}) rect=${formatRect(layer.bounds)}")
            }
            appendLine()
            appendLine("Focusables (${focusables.size}):")
            focusables.forEach { node ->
                val badge = when {
                    node.isFocused -> "FOCUSED"
                    node.canFocus -> "OK"
                    else -> "BLOCKED"
                }
                appendLine("  - [$badge] ${node.kind.shortLabel} #${node.seq} (${node.sourceTag}) rect=${formatRect(node.bounds)}")
            }
            appendLine()
            appendLine("Recent Key / Focus Traces (${recentTraces.size}):")
            recentTraces.forEach { ev ->
                val route = listOfNotNull(ev.fromLabel, ev.toLabel).joinToString(" -> ")
                appendLine("  - ${ev.action}: ${ev.outcome}${if (route.isNotBlank()) " [$route]" else ""}")
            }
        }
    }

    fun copySnapshotToClipboard(showToast: Boolean = true): String {
        val snapshot = buildSnapshot()
        runCatching {
            val selection = StringSelection(snapshot)
            Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
        }
        if (showToast) {
            AppToastManager.showInfo("Copied Focus HUD snapshot to clipboard.")
        }
        return snapshot
    }

    private fun entriesCloseEqual(a: FocusInspectEntry, b: FocusInspectEntry): Boolean {
        if (a.isFocused != b.isFocused ||
            a.hasFocus != b.hasFocus ||
            a.canFocus != b.canFocus ||
            a.layerActive != b.layerActive ||
            a.trapFocus != b.trapFocus ||
            a.restoreFocus != b.restoreFocus ||
            a.kind != b.kind ||
            a.sourceTag != b.sourceTag
        ) {
            return false
        }
        return kotlin.math.abs(a.bounds.left - b.bounds.left) < 0.5f &&
            kotlin.math.abs(a.bounds.top - b.bounds.top) < 0.5f &&
            kotlin.math.abs(a.bounds.right - b.bounds.right) < 0.5f &&
            kotlin.math.abs(a.bounds.bottom - b.bounds.bottom) < 0.5f
    }

    private fun formatRect(rect: Rect): String =
        "[${rect.left.roundToInt()},${rect.top.roundToInt()}..${rect.right.roundToInt()},${rect.bottom.roundToInt()} ${rect.width.roundToInt()}x${rect.height.roundToInt()}]"
}

@Composable
fun VisualFocusOverlayHud(modifier: Modifier = Modifier) {
    val enabled by DevStudioState.isVisualFocusHudEnabled.collectAsState()
    if (!enabled) return

    val mode by DevStudioState.visualFocusHudMode.collectAsState()
    val traceTick = VisualFocusInspectorRegistry.traceVersion
    val textMeasurer = rememberTextMeasurer()
    val snapshotEntries = VisualFocusInspectorRegistry.snapshotEntries()
    val focusedEntry = snapshotEntries.firstOrNull { it.kind != FocusInspectKind.LAYER && it.isFocused }
    val layerCount = snapshotEntries.count { it.kind == FocusInspectKind.LAYER }
    val eligibleCount = snapshotEntries.count { it.kind != FocusInspectKind.LAYER && it.canFocus }
    val blockedCount = snapshotEntries.count { it.kind != FocusInspectKind.LAYER && !it.canFocus }
    val recentTraces = VisualFocusInspectorRegistry.snapshotTraces().take(5)

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Observe drawVersion strictly inside DrawScope so positioning updates never trigger relayout.
            @Suppress("UNUSED_VARIABLE")
            val version = VisualFocusInspectorRegistry.drawVersion
            VisualFocusInspectorRegistry.updateWindowSize(size.width.roundToInt(), size.height.roundToInt())

            val allEntries = VisualFocusInspectorRegistry.snapshotEntries()
            val layers = allEntries.filter { it.kind == FocusInspectKind.LAYER }
            val focusables = allEntries.filter { it.kind != FocusInspectKind.LAYER }
            val activeFocused = focusables.firstOrNull { it.isFocused }

            val dashed = PathEffect.dashPathEffect(floatArrayOf(10f, 6f), 0f)
            val fineDashed = PathEffect.dashPathEffect(floatArrayOf(5f, 5f), 0f)

            layers.forEach { layer ->
                if (!layer.layerActive && mode == VisualFocusHudMode.COMPACT) return@forEach
                val color = when {
                    layer.hasFocus -> Color(0xFF00E5FF)
                    layer.layerActive -> Color(0xFF38BDF8).copy(alpha = 0.6f)
                    else -> Color(0xFFEF4444).copy(alpha = 0.35f)
                }
                drawRect(
                    color = color,
                    topLeft = layer.bounds.topLeft,
                    size = layer.bounds.size,
                    style = Stroke(width = if (layer.hasFocus) 2.5f else 1.5f, pathEffect = dashed),
                )
            }

            if (mode != VisualFocusHudMode.COMPACT) {
                focusables.forEach { node ->
                    if (node.isFocused) return@forEach
                    val color = if (node.canFocus) Color(0xFFF59E0B).copy(alpha = 0.85f) else Color(0xFFEF4444).copy(alpha = 0.50f)
                    drawRect(
                        color = color,
                        topLeft = node.bounds.topLeft,
                        size = node.bounds.size,
                        style = Stroke(width = 1.6f, pathEffect = fineDashed),
                    )
                    if (mode == VisualFocusHudMode.LABELS && node.bounds.width >= 20f && node.bounds.height >= 16f) {
                        drawSeqBadge(
                            textMeasurer = textMeasurer,
                            text = "#${node.seq}",
                            anchor = node.bounds.topLeft,
                            bgColor = color.copy(alpha = 0.90f),
                            textColor = Color.Black,
                        )
                    }
                }
            }

            if (activeFocused != null) {
                val rect = activeFocused.bounds
                if (mode != VisualFocusHudMode.COMPACT) {
                    drawBeamGuides(rect, Color(0xFF22C55E).copy(alpha = 0.25f), dashed)
                }
                drawRect(
                    color = Color(0xFF22C55E).copy(alpha = 0.14f),
                    topLeft = rect.topLeft,
                    size = rect.size,
                )
                drawRect(
                    color = Color(0xFF22C55E),
                    topLeft = rect.topLeft,
                    size = rect.size,
                    style = Stroke(width = 3.5f),
                )
                if (mode == VisualFocusHudMode.LABELS) {
                    drawSeqBadge(
                        textMeasurer = textMeasurer,
                        text = "#${activeFocused.seq} ${activeFocused.sourceTag}",
                        anchor = rect.topLeft,
                        bgColor = Color(0xFF22C55E),
                        textColor = Color.Black,
                    )
                }
            }

            val jump = VisualFocusInspectorRegistry.lastJumpVector
            if (jump != null && (System.currentTimeMillis() - jump.timestampMs) <= 2_500L) {
                val vectorColor = if (jump.succeeded) Color(0xFF22C55E) else Color(0xFFEF4444)
                val from = jump.fromBounds
                val to = jump.toBounds
                if (from != null && to != null && from != to) {
                    drawArrowVector(from.center, to.center, vectorColor)
                } else if (from != null && !jump.succeeded) {
                    drawRect(
                        color = Color(0xFFEF4444).copy(alpha = 0.28f),
                        topLeft = from.topLeft,
                        size = from.size,
                    )
                }
            }
        }

        // Click-through corner telemetry badge (uses traceTick cleanly, no focusable nodes).
        @Suppress("UNUSED_EXPRESSION")
        traceTick

        Surface(
            color = Color(0xD90E1117),
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(
                1.dp,
                if (focusedEntry == null) Color(0xFFEF4444).copy(alpha = 0.85f) else Color(0xFF00E5FF).copy(alpha = 0.55f),
            ),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 68.dp, end = 16.dp)
                .widthIn(min = 290.dp, max = 390.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "FOCUS HUD (${mode.name}) • F9 off • Shift+F9 mode",
                        color = Color(0xFF00E5FF),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                    )
                }

                Text(
                    text = if (focusedEntry != null) {
                        "FOCUS: #${focusedEntry.seq} ${focusedEntry.sourceTag} (${focusedEntry.bounds.width.roundToInt()}x${focusedEntry.bounds.height.roundToInt()})"
                    } else {
                        "FOCUS: NULL (press Arrow/Tab or Ctrl+Shift+F9 snapshot)"
                    },
                    color = if (focusedEntry != null) Color(0xFF4ADE80) else Color(0xFFF87171),
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Text(
                    text = "Layers: $layerCount | Eligible: $eligibleCount | Blocked: $blockedCount | Ctrl+Shift+F9 copy",
                    color = Color(0xFFCBD5E1),
                    fontSize = 9.5.sp,
                    fontFamily = FontFamily.Monospace,
                )

                recentTraces.forEach { ev ->
                    val isError = ev.outcome.contains("NO TARGET") || ev.outcome.contains("DROPPED")
                    Text(
                        text = "${ev.action} -> ${ev.outcome}${ev.toLabel?.let { " [$it]" } ?: ""}",
                        color = if (isError) Color(0xFFFCA5A5) else Color(0xFF94A3B8),
                        fontSize = 9.5.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawBeamGuides(rect: Rect, color: Color, pathEffect: PathEffect) {
    drawLine(color, Offset(rect.left, 0f), Offset(rect.left, size.height), strokeWidth = 1f, pathEffect = pathEffect)
    drawLine(color, Offset(rect.right, 0f), Offset(rect.right, size.height), strokeWidth = 1f, pathEffect = pathEffect)
    drawLine(color, Offset(0f, rect.top), Offset(size.width, rect.top), strokeWidth = 1f, pathEffect = pathEffect)
    drawLine(color, Offset(0f, rect.bottom), Offset(size.width, rect.bottom), strokeWidth = 1f, pathEffect = pathEffect)
}

private fun DrawScope.drawSeqBadge(
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    text: String,
    anchor: Offset,
    bgColor: Color,
    textColor: Color,
) {
    val result = textMeasurer.measure(
        text = text,
        style = TextStyle(
            color = textColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
        ),
    )
    val padX = 4f
    val padY = 2f
    val boxW = result.size.width + padX * 2f
    val boxH = result.size.height + padY * 2f
    val topLeft = Offset(
        anchor.x.coerceIn(0f, (size.width - boxW).coerceAtLeast(0f)),
        anchor.y.coerceIn(0f, (size.height - boxH).coerceAtLeast(0f)),
    )
    drawRect(color = bgColor, topLeft = topLeft, size = Size(boxW, boxH))
    drawText(
        textLayoutResult = result,
        topLeft = Offset(topLeft.x + padX, topLeft.y + padY),
    )
}

private fun DrawScope.drawArrowVector(from: Offset, to: Offset, color: Color) {
    drawLine(color = color, start = from, end = to, strokeWidth = 3f)
    val angle = atan2((to.y - from.y).toDouble(), (to.x - from.x).toDouble())
    val headLength = 14f
    val spread = Math.toRadians(28.0)
    val p1 = Offset(
        (to.x - headLength * cos(angle - spread)).toFloat(),
        (to.y - headLength * sin(angle - spread)).toFloat(),
    )
    val p2 = Offset(
        (to.x - headLength * cos(angle + spread)).toFloat(),
        (to.y - headLength * sin(angle + spread)).toFloat(),
    )
    drawLine(color = color, start = to, end = p1, strokeWidth = 3f)
    drawLine(color = color, start = to, end = p2, strokeWidth = 3f)
}

@Composable
internal fun FocusHudTabContent(
    onToast: (String) -> Unit,
) {
    val enabled by DevStudioState.isVisualFocusHudEnabled.collectAsState()
    val mode by DevStudioState.visualFocusHudMode.collectAsState()
    @Suppress("UNUSED_VARIABLE")
    val traceTick = VisualFocusInspectorRegistry.traceVersion
    @Suppress("UNUSED_VARIABLE")
    val drawTick = VisualFocusInspectorRegistry.drawVersion

    val entries = VisualFocusInspectorRegistry.snapshotEntries()
    val traces = VisualFocusInspectorRegistry.snapshotTraces()
    val focused = entries.firstOrNull { it.kind != FocusInspectKind.LAYER && it.isFocused }
    val compactShape = com.lagradost.cloudstream3.desktop.ui.theme.LocalDesktopButtonShapes.current.compact
    val listState = rememberLazyListState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DevBgDark)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Controls Toolbar
        Surface(
            color = DevSurfaceDark,
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, DevBorderDark),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DesktopButton(
                    onClick = { DevStudioState.toggleVisualFocusHud() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (enabled) Color(0xFF16A34A) else DevCardDark,
                    ),
                    shape = compactShape,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp),
                ) {
                    Icon(Icons.Default.Visibility, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (enabled) "Visual HUD: ON (F9)" else "Visual HUD: OFF (F9)",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                DesktopFilledTonalButton(
                    onClick = { DevStudioState.cycleVisualFocusHudMode() },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = DevCardDark,
                        contentColor = DevAccentCyan,
                    ),
                    shape = compactShape,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp),
                ) {
                    Icon(Icons.Default.Layers, contentDescription = null, tint = DevAccentCyan, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Mode: ${mode.label} (Shift+F9)", fontSize = 11.sp)
                }

                DesktopButton(
                    onClick = {
                        VisualFocusInspectorRegistry.copySnapshotToClipboard(showToast = false)
                        onToast("Focus snapshot copied (${entries.size} nodes, ${traces.size} traces)")
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DevAccentCyan.copy(alpha = 0.2f)),
                    shape = compactShape,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp),
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, tint = DevAccentCyan, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Copy Focus Snapshot (Ctrl+Shift+F9)", color = DevAccentCyan, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }

                DesktopOutlinedButton(
                    onClick = { VisualFocusInspectorRegistry.clearTraces() },
                    border = BorderStroke(1.dp, DevBorderDark),
                    shape = compactShape,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp),
                ) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = Color.LightGray, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Clear Trace", color = Color.LightGray, fontSize = 11.sp)
                }
            }
        }

        // Focused Status Banner
        Surface(
            color = DevSurfaceDark,
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(
                1.dp,
                if (focused != null) Color(0xFF22C55E).copy(alpha = 0.45f) else DevLevelError.copy(alpha = 0.45f),
            ),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Default.CenterFocusStrong,
                    contentDescription = null,
                    tint = if (focused != null) Color(0xFF4ADE80) else DevLevelError,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = if (focused != null) {
                        "Active Focus: #${focused.seq} ${focused.kind.shortLabel} @ ${focused.sourceTag} " +
                            "[${focused.bounds.left.roundToInt()},${focused.bounds.top.roundToInt()} " +
                            "${focused.bounds.width.roundToInt()}x${focused.bounds.height.roundToInt()}]"
                    } else if (!enabled) {
                        "Visual HUD is OFF. Press F9 or click 'Visual HUD: OFF' above to start tracking focus rects & moves."
                    } else {
                        "No tracked focusable currently holds focus (focused == null)."
                    },
                    color = if (focused != null) Color(0xFF86EFAC) else Color(0xFFFCA5A5),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Split Pane: Trace Events + Tracked Bounds
        Row(
            modifier = Modifier.fillMaxSize().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                color = DevSurfaceDark,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, DevBorderDark),
                modifier = Modifier.weight(1f).fillMaxHeight(),
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(10.dp)) {
                    Text(
                        text = "Key & Move Trace (${traces.size})",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        itemsIndexed(traces) { _, ev ->
                            val failed = ev.outcome.contains("NO TARGET") || ev.outcome.contains("DROPPED")
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(DevBgDark, RoundedCornerShape(4.dp))
                                    .border(1.dp, if (failed) DevLevelError.copy(alpha = 0.4f) else DevBorderDark, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 8.dp, vertical = 5.dp),
                            ) {
                                Text(
                                    text = "${ev.action} -> ${ev.outcome} (${ev.fromLabel ?: "null"} -> ${ev.toLabel ?: "-"})",
                                    color = if (failed) Color(0xFFFCA5A5) else Color(0xFFCBD5E1),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                    }
                }
            }

            Surface(
                color = DevSurfaceDark,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, DevBorderDark),
                modifier = Modifier.weight(1f).fillMaxHeight(),
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(10.dp)) {
                    Text(
                        text = "Tracked Boundaries & Targets (${entries.size})",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    Box(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            itemsIndexed(entries) { _, item ->
                                val tagColor = when {
                                    item.kind == FocusInspectKind.LAYER && item.hasFocus -> Color(0xFF38BDF8)
                                    item.kind == FocusInspectKind.LAYER -> Color(0xFF64748B)
                                    item.isFocused -> Color(0xFF4ADE80)
                                    item.canFocus -> Color(0xFFFBBF24)
                                    else -> Color(0xFFF87171)
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(DevBgDark, RoundedCornerShape(4.dp))
                                        .border(1.dp, tagColor.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 8.dp, vertical = 5.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = "#${item.seq} ${item.kind.shortLabel} ${item.sourceTag}",
                                        color = tagColor,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        text = "${item.bounds.left.roundToInt()},${item.bounds.top.roundToInt()} ${item.bounds.width.roundToInt()}x${item.bounds.height.roundToInt()}",
                                        color = Color(0xFF94A3B8),
                                        fontSize = 10.5.sp,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                            }
                        }
                        DesktopVerticalScrollbar(
                            listState = listState,
                            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                        )
                    }
                }
            }
        }
    }
}
