package com.lagradost.cloudstream3.desktop.ui.screens.details

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.desktop.tracking.MatchResolution
import com.lagradost.cloudstream3.desktop.ui.badges.DesktopBadgeComponents
import com.lagradost.cloudstream3.desktop.ui.components.CloudstreamAlertDialog
import com.lagradost.cloudstream3.desktop.ui.components.LocalDesktopTheme
import com.lagradost.cloudstream3.desktop.utils.ExternalLinkHandler
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.common.storage.DesktopBookmark
import com.lagradost.common.storage.DesktopWatchType

@Composable
fun DetailsLibraryStatusDialog(
    show: Boolean,
    onDismissRequest: () -> Unit,
    currentBookmark: DesktopBookmark?,
    onSetWatchType: (DesktopWatchType) -> Unit,
    onRemoveBookmark: () -> Unit,
) {
    if (!show) return

    CloudstreamAlertDialog(
        show = show,
        onDismissRequest = onDismissRequest,
        title = {
            Text(
                text = if (currentBookmark != null) "Library Status" else "Add to Library",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
            )
        },
        text = {
            val statusItems = listOf(
                DesktopWatchType.WATCHING to Icons.Default.PlayArrow,
                DesktopWatchType.COMPLETED to Icons.Default.CheckCircle,
                DesktopWatchType.PLANTOWATCH to Icons.Default.Bookmark,
                DesktopWatchType.ONHOLD to Icons.Default.PauseCircle,
                DesktopWatchType.REWATCHING to Icons.AutoMirrored.Filled.RotateRight,
                DesktopWatchType.DROPPED to Icons.Default.Cancel,
            )
            val primaryColor = MaterialTheme.colorScheme.primary

            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                statusItems.chunked(3).forEach { rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        rowItems.forEach { (type, icon) ->
                            val isSelected = currentBookmark?.watchType == type.id
                            val itemInteraction = remember { MutableInteractionSource() }
                            val isHovered by itemInteraction.collectIsHoveredAsState()

                            Surface(
                                onClick = { onSetWatchType(type) },
                                shape = RoundedCornerShape(14.dp),
                                color = if (isSelected) {
                                    primaryColor.copy(alpha = 0.18f)
                                } else if (isHovered) {
                                    Color.White.copy(alpha = 0.08f)
                                } else {
                                    Color.White.copy(alpha = 0.04f)
                                },
                                border = BorderStroke(
                                    if (isSelected) 1.5.dp else 1.dp,
                                    if (isSelected) {
                                        primaryColor
                                    } else if (isHovered) {
                                        Color.White.copy(alpha = 0.25f)
                                    } else {
                                        Color.White.copy(alpha = 0.10f)
                                    },
                                ),
                                interactionSource = itemInteraction,
                                modifier = Modifier.weight(1f).height(84.dp),
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 8.dp),
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxSize(),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center,
                                    ) {
                                        Surface(
                                            shape = CircleShape,
                                            color = if (isSelected) primaryColor.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.08f),
                                            modifier = Modifier.size(32.dp),
                                        ) {
                                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                                Icon(
                                                    imageVector = icon,
                                                    contentDescription = null,
                                                    tint = if (isSelected) primaryColor else Color.White.copy(alpha = 0.85f),
                                                    modifier = Modifier.size(17.dp),
                                                )
                                            }
                                        }
                                        Spacer(Modifier.height(6.dp))
                                        Text(
                                            text = type.stringRes,
                                            color = if (isSelected) primaryColor else Color.White.copy(alpha = 0.85f),
                                            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }

                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = primaryColor,
                                            modifier = Modifier.size(14.dp).align(Alignment.TopEnd),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (currentBookmark != null) {
                com.lagradost.cloudstream3.desktop.ui.components.DesktopTextButton(
                    onClick = onRemoveBookmark,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Remove from Library", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
            }
        },
        dismissButton = {
            com.lagradost.cloudstream3.desktop.ui.components.DesktopTextButton(onClick = onDismissRequest) {
                Text("Cancel", fontSize = 13.sp)
            }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DetailsHeroLibraryButton(
    modifier: Modifier = Modifier,
    currentBookmark: DesktopBookmark?,
    activeWatchType: DesktopWatchType,
    isButtonsNarrow: Boolean,
    onEditStatus: () -> Unit,
) {
    val isInLibrary = currentBookmark != null
    val libraryIcon = if (isInLibrary) {
        when (activeWatchType) {
            DesktopWatchType.WATCHING -> Icons.Outlined.PlayArrow
            DesktopWatchType.COMPLETED -> Icons.Outlined.Check
            DesktopWatchType.ONHOLD -> Icons.Outlined.Pause
            DesktopWatchType.DROPPED -> Icons.Outlined.Close
            DesktopWatchType.PLANTOWATCH -> Icons.Outlined.BookmarkAdded
            DesktopWatchType.REWATCHING -> Icons.AutoMirrored.Filled.RotateRight
        }
    } else {
        Icons.Outlined.BookmarkAdd
    }
    val tooltipText = if (isInLibrary) "Change Library Status" else "Add to Library"
    HeroActionTooltip(tooltipText = tooltipText) {
        com.lagradost.cloudstream3.desktop.ui.components.HeroSecondaryButton(
            onClick = onEditStatus,
            modifier = modifier.let { mod ->
                if (isInLibrary) mod.height(if (isButtonsNarrow) 44.dp else 52.dp)
                else mod.size(if (isButtonsNarrow) 44.dp else 52.dp)
            },
            isActive = isInLibrary,
        ) {
            if (isInLibrary) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxHeight()
                        .animateContentSize()
                        .padding(horizontal = if (isButtonsNarrow) 12.dp else 16.dp),
                ) {
                    Icon(
                        imageVector = libraryIcon,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(if (isButtonsNarrow) 18.dp else 20.dp),
                    )
                    Spacer(Modifier.width(if (isButtonsNarrow) 6.dp else 8.dp))
                    Text(
                        text = activeWatchType.stringRes,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = if (isButtonsNarrow) 13.sp else 14.sp,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Change Status",
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(if (isButtonsNarrow) 16.dp else 18.dp),
                    )
                }
            } else {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Icon(
                        imageVector = libraryIcon,
                        contentDescription = "Add to Library",
                        tint = Color.White,
                        modifier = Modifier.size(if (isButtonsNarrow) 22.dp else 26.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DetailsHeroWebButton(
    modifier: Modifier = Modifier,
    isButtonsNarrow: Boolean,
    provider: MainAPI,
    url: String?,
) {
    val fullWebUrl = remember(provider, url) {
        if (url.isNullOrBlank()) null
        else {
            val fixed = provider.fixUrl(url)
            if (fixed.startsWith("http://") || fixed.startsWith("https://")) fixed else null
        }
    } ?: return

    HeroActionTooltip(tooltipText = "Open source page in browser") {
        com.lagradost.cloudstream3.desktop.ui.components.HeroSecondaryButton(
            onClick = { ExternalLinkHandler.launchSystemBrowser(fullWebUrl) },
            modifier = modifier.size(if (isButtonsNarrow) 44.dp else 52.dp),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    imageVector = Icons.Default.Public,
                    contentDescription = "Open in Browser (${provider.name})",
                    tint = Color.White,
                    modifier = Modifier.size(if (isButtonsNarrow) 18.dp else 20.dp),
                )
            }
        }
    }
}

@Composable
fun DetailsHeroTrailerButton(
    modifier: Modifier = Modifier,
    isButtonsNarrow: Boolean,
    onClick: () -> Unit,
) {
    com.lagradost.cloudstream3.desktop.ui.components.HeroSecondaryButton(
        onClick = onClick,
        modifier = modifier.height(if (isButtonsNarrow) 44.dp else 52.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = if (isButtonsNarrow) 12.dp else 18.dp),
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "Trailer",
                tint = Color.White,
                modifier = Modifier.size(if (isButtonsNarrow) 18.dp else 20.dp),
            )
            Spacer(Modifier.width(if (isButtonsNarrow) 6.dp else 8.dp))
            Text(
                text = "Trailer",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = if (isButtonsNarrow) 13.sp else 14.sp,
            )
        }
    }
}

@Composable
fun DetailsTrackersHeroCluster(
    trackersState: com.lagradost.cloudstream3.desktop.ui.screens.details.contract.DetailsTrackersUiState,
    isButtonsNarrow: Boolean,
    onOpenTracker: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val available = trackersState.availableTrackers
    if (available.isEmpty()) return

    val needsConfirmEntries = available.filter {
        it.resolution is MatchResolution.NeedsConfirmation
    }
    val boundEntries = available.filter { it.boundEntry != null }
    val blockedEntries = available.filter {
        it.resolution is MatchResolution.BlockedByUser
    }

    val primaryTarget = needsConfirmEntries.firstOrNull()
        ?: boundEntries.firstOrNull()
        ?: available.firstOrNull { it !in blockedEntries }
        ?: available.first()

    val isAllBlocked = blockedEntries.size == available.size && boundEntries.isEmpty()
    val hasNeedsConfirm = needsConfirmEntries.isNotEmpty()
    val firstBound = boundEntries.firstOrNull()
    val hasCandidatesReady = needsConfirmEntries.any { it.searchResults.isNotEmpty() }

    val stackedIds = remember(boundEntries, needsConfirmEntries, available) {
        (boundEntries + needsConfirmEntries).ifEmpty { available }
            .map { it.descriptor.id }
            .distinct()
            .take(3)
    }

    val pillLabel = when {
        firstBound != null -> {
            val remote = firstBound.remoteStatus
            val bound = firstBound.boundEntry
            val isMovie = firstBound.releaseBounds.format.equals("MOVIE", ignoreCase = true) ||
                bound?.remoteMediaId?.startsWith("movie:", ignoreCase = true) == true
            val total = remote?.totalEpisodes ?: bound?.expectedTotalEpisodes?.toInt()
            val prog = remote?.progress ?: firstBound.editProgress
            val tag = firstBound.descriptor.shortTag
            if (boundEntries.size > 1) {
                if (isMovie) {
                    val statusLabel = remote?.status?.takeIf { it != com.lagradost.cloudstream3.desktop.tracking.UnifiedWatchStatus.NONE }?.label
                    if (statusLabel != null) "${boundEntries.size} Trackers · $statusLabel" else "${boundEntries.size} Trackers"
                } else if (total != null && total > 0) {
                    "${boundEntries.size} Trackers · $prog/$total"
                } else {
                    "${boundEntries.size} Trackers · Ep $prog"
                }
            } else {
                when {
                    isMovie -> {
                        val statusLabel = remote?.status?.takeIf { it != com.lagradost.cloudstream3.desktop.tracking.UnifiedWatchStatus.NONE }?.label
                        if (statusLabel != null) "$tag · $statusLabel" else firstBound.descriptor.displayName
                    }
                    total != null && total > 0 -> "$tag $prog/$total"
                    prog > 0 -> "$tag Ep $prog"
                    else -> firstBound.descriptor.displayName
                }
            }
        }
        hasCandidatesReady -> "Match Found"
        hasNeedsConfirm -> if (available.size == 1) "Link ${available.first().descriptor.displayName}" else "Track Title"
        isAllBlocked -> "Tracking Off"
        else -> "Track Title"
    }

    val autoTrackedEntries = boundEntries.filter { it.isAutoSyncEnabled }
    val statusBadgeText = when {
        firstBound != null && hasCandidatesReady -> "Match Found"
        autoTrackedEntries.size == 1 && boundEntries.size > 1 -> "Auto: ${autoTrackedEntries.first().descriptor.shortTag}"
        autoTrackedEntries.size in 2 until boundEntries.size -> "Auto: ${autoTrackedEntries.joinToString(", ") { it.descriptor.shortTag }}"
        autoTrackedEntries.isNotEmpty() -> "Auto-Track"
        hasCandidatesReady && needsConfirmEntries.size == 1 -> needsConfirmEntries.first().descriptor.shortTag
        else -> null
    }
    val statusBadgeColor = when {
        statusBadgeText?.startsWith("Auto") == true -> Color(0xFF4ADE80)
        statusBadgeText != null -> Color(0xFF38BDF8)
        else -> Color.White
    }

    val tooltipText = buildList {
        boundEntries.forEach { entry ->
            val bound = entry.boundEntry ?: return@forEach
            val remote = entry.remoteStatus
            val isMovie = entry.releaseBounds.format.equals("MOVIE", ignoreCase = true) ||
                bound.remoteMediaId.startsWith("movie:", ignoreCase = true)
            val total = remote?.totalEpisodes ?: bound.expectedTotalEpisodes?.toInt()
            val prog = remote?.progress ?: entry.editProgress
            val progText = if (isMovie) {
                remote?.status?.takeIf { it != com.lagradost.cloudstream3.desktop.tracking.UnifiedWatchStatus.NONE }?.label
                    ?: if (prog >= 1) "Watched" else "Unwatched"
            } else if (total != null && total > 0) {
                "$prog/$total"
            } else {
                "Ep $prog"
            }
            val autoTag = if (entry.isAutoSyncEnabled) "Auto-Track" else "Manual"
            add("${entry.descriptor.displayName}: ${bound.remoteTitle} ($progText · $autoTag)")
        }
        needsConfirmEntries.forEach { entry ->
            val topCand = entry.searchResults.firstOrNull()
            if (topCand != null) {
                add("${entry.descriptor.displayName}: Match found — '${topCand.title}'")
            } else {
                add("${entry.descriptor.displayName}: Open drawer to link entry")
            }
        }
        if (isEmpty()) {
            add(
                if (isAllBlocked) "Tracking disabled for this show"
                else "Manage trackers (${available.joinToString(", ") { it.descriptor.displayName }})",
            )
        }
    }.joinToString("\n")

    Box(modifier = modifier) {
        HeroActionTooltip(tooltipText = tooltipText) {
            com.lagradost.cloudstream3.desktop.ui.components.HeroSecondaryButton(
                onClick = { onOpenTracker(primaryTarget.descriptor.id) },
                modifier = Modifier.height(if (isButtonsNarrow) 44.dp else 52.dp),
                isActive = boundEntries.isNotEmpty() || hasCandidatesReady,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(if (isButtonsNarrow) 7.dp else 9.dp),
                    modifier = Modifier
                        .fillMaxHeight()
                        .animateContentSize()
                        .padding(horizontal = if (isButtonsNarrow) 12.dp else 15.dp),
                ) {
                    if (isAllBlocked) {
                        Icon(
                            imageVector = Icons.Default.Block,
                            contentDescription = pillLabel,
                            tint = Color.White.copy(alpha = 0.65f),
                            modifier = Modifier.size(if (isButtonsNarrow) 16.dp else 18.dp),
                        )
                    } else {
                        DesktopBadgeComponents.TrackerStackedIcons(
                            trackerIds = stackedIds,
                            iconSize = if (isButtonsNarrow) 14.dp else 16.dp,
                        )
                    }
                    Text(
                        text = pillLabel,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = if (isButtonsNarrow) 13.sp else 14.sp,
                        maxLines = 1,
                    )
                    if (!isButtonsNarrow && statusBadgeText != null) {
                        Box(
                            modifier = Modifier
                                .background(statusBadgeColor.copy(alpha = 0.16f), CircleShape)
                                .border(0.8.dp, statusBadgeColor.copy(alpha = 0.45f), CircleShape)
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = statusBadgeText,
                                color = statusBadgeColor,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HeroActionTooltip(
    tooltipText: String,
    content: @Composable () -> Unit,
) {
    val theme = LocalDesktopTheme.current
    TooltipArea(
        tooltip = {
            Surface(
                modifier = Modifier.widthIn(max = 220.dp),
                shape = RoundedCornerShape(6.dp),
                color = theme.SurfaceElevated,
                border = BorderStroke(1.dp, theme.Divider),
                shadowElevation = 6.dp,
            ) {
                Text(
                    text = tooltipText,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.TextPrimary,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        delayMillis = 400,
        tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, 16.dp)),
        content = content,
    )
}
