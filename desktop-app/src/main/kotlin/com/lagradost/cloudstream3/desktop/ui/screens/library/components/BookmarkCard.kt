package com.lagradost.cloudstream3.desktop.ui.screens.library.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.lagradost.cloudstream3.desktop.tracking.TrackerDescriptor
import com.lagradost.cloudstream3.desktop.tracking.UnifiedLibraryEntry
import com.lagradost.cloudstream3.desktop.tracking.UnifiedWatchStatus
import com.lagradost.cloudstream3.desktop.ui.components.DesktopUi
import com.lagradost.cloudstream3.desktop.ui.components.desktopFocusableCard
import com.lagradost.cloudstream3.desktop.ui.components.posterHoverEffect
import com.lagradost.cloudstream3.desktop.ui.screens.library.contract.LocalBookmarkProgress
import com.lagradost.cloudstream3.desktop.ui.screens.library.contract.LocalTrackerLinkInfo
import com.lagradost.cloudstream3.desktop.ui.theme.AppearanceConfig
import com.lagradost.cloudstream3.desktop.utils.ImageUtils
import com.lagradost.common.storage.DesktopBookmark
import java.util.Locale

private val CARD_TITLE_SCRIM_BRUSH = Brush.verticalGradient(
    colorStops = arrayOf(
        0f to Color.Transparent,
        0.35f to Color.Black.copy(alpha = 0.74f),
        1f to Color.Black.copy(alpha = 0.95f),
    ),
)

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun LibraryPosterCardShell(
    itemKey: String,
    title: String,
    posterUrl: String?,
    accentOverride: Color? = null,
    isWarningState: Boolean = false,
    isSelectedForEdit: Boolean = false,
    onClick: () -> Unit,
    onSecondaryClick: (Rect) -> Unit,
    centerHoverContent: @Composable () -> Unit,
    topOverlayContent: @Composable BoxScope.(isHovered: Boolean) -> Unit,
    bottomOverlayContent: @Composable ColumnScope.(isHovered: Boolean) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isFocused by interactionSource.collectIsFocusedAsState()
    val isActiveVisual = isHovered || isFocused
    val posterCornerRadius by AppearanceConfig.posterRoundingDp.collectAsState()
    val shape = remember(posterCornerRadius) { RoundedCornerShape(posterCornerRadius.dp) }
    val primary = MaterialTheme.colorScheme.primary
    var bounds by remember { mutableStateOf(Rect.Zero) }

    Box(modifier = Modifier.fillMaxWidth()) {
        if (isActiveVisual || isSelectedForEdit) {
            val glowColor = when {
                isSelectedForEdit && accentOverride != null -> accentOverride.copy(alpha = 0.65f)
                isWarningState -> Color(0xFFE65100).copy(alpha = 0.65f)
                accentOverride != null && isActiveVisual -> accentOverride.copy(alpha = 0.55f)
                else -> primary.copy(alpha = 0.65f)
            }
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .blur(32.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
                    .background(glowColor, shape),
            )
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .posterHoverEffect(shape, isFocused = isFocused)
                .clip(shape)
                .hoverable(interactionSource)
                .onGloballyPositioned { coords -> bounds = coords.boundsInWindow() }
                .desktopFocusableCard(
                    interactionSource = interactionSource,
                    shape = shape,
                    showRing = false,
                    onClick = onClick,
                    onSecondaryClick = { onSecondaryClick(bounds) },
                )
                .pointerInput(itemKey) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type == PointerEventType.Release && event.changes.none { it.isConsumed }) {
                                if (event.button == PointerButton.Secondary) {
                                    onSecondaryClick(bounds)
                                } else if (event.button == PointerButton.Primary) {
                                    onClick()
                                }
                            }
                        }
                    }
                },
            shape = shape,
            color = DesktopUi.SurfaceCard,
            tonalElevation = if (isActiveVisual || isSelectedForEdit) 8.dp else 2.dp,
            border = when {
                isSelectedForEdit -> BorderStroke(2.dp, accentOverride ?: primary)
                isActiveVisual -> BorderStroke(
                    2.dp,
                    when {
                        isWarningState -> Color(0xFFFFA726)
                        accentOverride != null -> accentOverride
                        else -> primary
                    },
                )
                else -> null
            },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f),
            ) {
                if (!posterUrl.isNullOrBlank()) {
                    val enhancedPoster = remember(posterUrl) { ImageUtils.enhancePosterUrl(posterUrl) }
                    AsyncImage(
                        model = enhancedPoster,
                        contentDescription = title,
                        contentScale = ContentScale.Crop,
                        filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(DesktopUi.SurfaceElevated),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = title.take(2).uppercase(Locale.ROOT),
                            color = DesktopUi.Accent,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                AnimatedVisibility(
                    visible = isActiveVisual,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.40f)))
                }

                AnimatedVisibility(
                    visible = isActiveVisual,
                    enter = fadeIn(animationSpec = tween(200)) + scaleIn(initialScale = 0.8f, animationSpec = tween(200)),
                    exit = fadeOut(animationSpec = tween(200)) + scaleOut(targetScale = 0.8f, animationSpec = tween(200)),
                    modifier = Modifier.align(Alignment.Center),
                ) {
                    centerHoverContent()
                }

                topOverlayContent(isActiveVisual)

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(CARD_TITLE_SCRIM_BRUSH)
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    content = { bottomOverlayContent(isActiveVisual) },
                )
            }
        }
    }
}

@Composable
fun LocalBookmarkCard(
    bookmark: DesktopBookmark,
    localProgress: LocalBookmarkProgress?,
    linkedTrackers: List<LocalTrackerLinkInfo>,
    hasConnectedTrackers: Boolean,
    isProviderMissing: Boolean,
    onClick: () -> Unit,
    onSecondaryClick: (Rect) -> Unit,
) {
    LibraryPosterCardShell(
        itemKey = "local:${bookmark.id}",
        title = bookmark.name,
        posterUrl = bookmark.posterUrl,
        isWarningState = isProviderMissing,
        onClick = onClick,
        onSecondaryClick = onSecondaryClick,
        centerHoverContent = {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(if (isProviderMissing) Color(0xFFE65100).copy(alpha = 0.35f) else Color.White.copy(alpha = 0.16f))
                    .border(1.dp, if (isProviderMissing) Color(0xFFFFA726) else Color.White.copy(alpha = 0.42f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (isProviderMissing) Icons.Default.Sync else Icons.Default.PlayArrow,
                    contentDescription = if (isProviderMissing) "Re-link" else "Open",
                    tint = Color.White,
                    modifier = Modifier.size(30.dp),
                )
            }
        },
        topOverlayContent = { _ ->
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color.Black.copy(alpha = 0.78f),
                    border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.22f)),
                ) {
                    Text(
                        text = "LOCAL",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFE2E8F0),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (linkedTrackers.isNotEmpty()) {
                        linkedTrackers.take(2).forEach { link ->
                            val brandColor = Color(link.descriptor.accentColorHex)
                            val syncedColor = Color(link.descriptor.syncedColorHex)
                            val epSuffix = link.progress?.let { p ->
                                link.totalEpisodes?.takeIf { it > 0 }?.let { " $p/$it" } ?: " $p"
                            } ?: ""
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color(0xFF0A141E).copy(alpha = 0.88f),
                                border = BorderStroke(0.5.dp, brandColor.copy(alpha = 0.7f)),
                            ) {
                                Text(
                                    text = "${link.descriptor.shortTag}$epSuffix",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = syncedColor,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                )
                            }
                        }
                    } else if (hasConnectedTrackers) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color.Black.copy(alpha = 0.72f),
                        ) {
                            Text(
                                text = "Not Synced",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.72f),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                            )
                        }
                    }
                }
            }
        },
        bottomOverlayContent = { _ ->
            if (localProgress != null) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = localProgress.label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF93C5FD),
                        )
                        if (localProgress.isCompleted) {
                            Text(
                                text = "Completed",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF4ADE80),
                            )
                        }
                    }
                    localProgress.progressFraction?.let { frac ->
                        LinearProgressIndicator(
                            progress = { frac.coerceIn(0.05f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                            color = if (localProgress.isCompleted) Color(0xFF4ADE80) else MaterialTheme.colorScheme.primary,
                            trackColor = Color.White.copy(alpha = 0.18f),
                        )
                    }
                }
            }

            if (isProviderMissing) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFFE65100).copy(alpha = 0.90f))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = Color.White, modifier = Modifier.size(10.dp))
                        Text(
                            text = "${bookmark.apiName} (Missing)",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )
                    }
                }
            } else {
                Text(
                    text = bookmark.apiName,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.72f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Text(
                text = bookmark.name,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                lineHeight = 16.sp,
            )
        },
    )
}

@Composable
fun TrackerLibraryCard(
    entry: UnifiedLibraryEntry,
    descriptor: TrackerDescriptor?,
    linkedBookmark: DesktopBookmark?,
    isSelectedForEdit: Boolean,
    isIncrementing: Boolean,
    onClick: () -> Unit,
    onQuickEdit: () -> Unit,
    onIncrementEpisode: () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val brandColor = descriptor?.let { Color(it.accentColorHex) } ?: primary
    val syncedColor = descriptor?.let { Color(it.syncedColorHex) } ?: Color(0xFF38BDF8)
    val ceiling = entry.releaseBounds.ceilingEpisode
    val canIncrement = !entry.isMovie &&
        !entry.releaseBounds.isNotYetReleased &&
        (ceiling == null || entry.progress < ceiling)

    LibraryPosterCardShell(
        itemKey = entry.uniqueKey,
        title = entry.title,
        posterUrl = entry.posterUrl,
        accentOverride = brandColor,
        isSelectedForEdit = isSelectedForEdit,
        onClick = onClick,
        onSecondaryClick = { onQuickEdit() },
        centerHoverContent = {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(brandColor.copy(alpha = 0.30f))
                    .border(1.dp, syncedColor.copy(alpha = 0.65f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = if (linkedBookmark != null) "Open Linked" else "Find Stream",
                    tint = Color.White,
                    modifier = Modifier.size(30.dp),
                )
            }
        },
        topOverlayContent = { isHovered ->
            AnimatedVisibility(
                visible = isHovered || isSelectedForEdit,
                enter = fadeIn(tween(150)),
                exit = fadeOut(tween(150)),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
            ) {
                Surface(
                    onClick = onQuickEdit,
                    shape = RoundedCornerShape(8.dp),
                    color = Color.Black.copy(alpha = 0.72f),
                    border = BorderStroke(0.75.dp, Color.White.copy(alpha = 0.25f)),
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Quick Edit ${entry.title}",
                        tint = Color.White,
                        modifier = Modifier.padding(6.dp).size(14.dp),
                    )
                }
            }
        },
        bottomOverlayContent = { isHovered ->
            val showQuickActions = isHovered || isSelectedForEdit || isIncrementing
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val progressText = when {
                    entry.isMovie -> when (entry.status) {
                        UnifiedWatchStatus.COMPLETED -> "Watched"
                        UnifiedWatchStatus.PLAN_TO_WATCH -> "Plan to Watch"
                        UnifiedWatchStatus.DROPPED -> if (entry.progress >= 1) "Watched • Dropped" else "Dropped"
                        UnifiedWatchStatus.PAUSED -> "Paused"
                        UnifiedWatchStatus.WATCHING -> "Watching"
                        UnifiedWatchStatus.REWATCHING -> "Rewatching"
                        UnifiedWatchStatus.NONE -> if (entry.progress >= 1) "Watched" else "Unwatched"
                    }
                    entry.totalEpisodes != null && entry.totalEpisodes > 0 -> "Ep ${entry.progress} / ${entry.totalEpisodes}"
                    else -> "Ep ${entry.progress}"
                }
                Text(
                    text = progressText,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = syncedColor,
                )

                AnimatedVisibility(
                    visible = showQuickActions && !entry.isMovie,
                    enter = fadeIn(tween(140)),
                    exit = fadeOut(tween(140)),
                ) {
                    if (canIncrement) {
                        Surface(
                            onClick = onIncrementEpisode,
                            enabled = !isIncrementing,
                            shape = RoundedCornerShape(6.dp),
                            color = brandColor.copy(alpha = 0.85f),
                        ) {
                            if (isIncrementing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp).size(11.dp),
                                    strokeWidth = 1.8.dp,
                                    color = Color.White,
                                )
                            } else {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(11.dp))
                                    Text(
                                        text = "1",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                    )
                                }
                            }
                        }
                    } else if (entry.releaseBounds.isNotYetReleased || (ceiling != null && entry.progress >= ceiling && entry.releaseBounds.isAiringOrHiatus)) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Airing limit reached",
                            tint = Color.White.copy(alpha = 0.6f),
                            modifier = Modifier.size(12.dp),
                        )
                    }
                }
            }

            val total = entry.totalEpisodes
            if (!entry.isMovie && total != null && total > 0) {
                val fraction = (entry.progress.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().height(2.5.dp).clip(RoundedCornerShape(2.dp)),
                    color = syncedColor,
                    trackColor = Color.White.copy(alpha = 0.18f),
                )
            }

            val metaParts = buildList {
                entry.format?.let { add(it) }
                entry.seasonYear?.let { add(it.toString()) }
                if (entry.releaseBounds.isAiringOrHiatus) {
                    add("Airing")
                } else if (entry.releaseBounds.isNotYetReleased) {
                    add("Upcoming")
                }
            }
            if (metaParts.isNotEmpty() || entry.score100 > 0 || linkedBookmark != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (linkedBookmark != null) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF4ADE80)),
                        )
                    }
                    if (metaParts.isNotEmpty()) {
                        Text(
                            text = metaParts.joinToString(" • "),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.72f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    if (entry.score100 > 0) {
                        val scoreTen = String.format(Locale.US, "%.1f", entry.score100 / 10.0)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Icon(
                                Icons.Default.Star,
                                contentDescription = null,
                                tint = Color(0xFFFBBF24),
                                modifier = Modifier.size(10.dp),
                            )
                            Text(
                                text = scoreTen,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFFFBBF24),
                            )
                        }
                    }
                }
            }

            Text(
                text = entry.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                lineHeight = 16.sp,
            )
        },
    )
}
