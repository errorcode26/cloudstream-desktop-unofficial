package com.lagradost.cloudstream3.desktop.ui.screens.details

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.desktop.tracking.BindMode
import com.lagradost.cloudstream3.desktop.tracking.MatchResolution
import com.lagradost.cloudstream3.desktop.tracking.TrackerBindPolicy
import com.lagradost.cloudstream3.desktop.tracking.TrackerIds
import com.lagradost.cloudstream3.desktop.tracking.TrackerMediaCandidate
import com.lagradost.cloudstream3.desktop.tracking.TrackerRegistry
import com.lagradost.cloudstream3.desktop.tracking.UnifiedWatchStatus
import com.lagradost.cloudstream3.desktop.ui.badges.DesktopBadgeComponents
import com.lagradost.cloudstream3.desktop.ui.components.CloudstreamAlertDialog
import com.lagradost.cloudstream3.desktop.ui.components.DesktopButton
import com.lagradost.cloudstream3.desktop.ui.components.DesktopIconButton
import com.lagradost.cloudstream3.desktop.ui.components.DesktopOutlinedButton
import com.lagradost.cloudstream3.desktop.ui.components.DesktopTextButton
import com.lagradost.cloudstream3.desktop.ui.components.DesktopVerticalScrollbar
import com.lagradost.cloudstream3.desktop.ui.components.LocalDesktopTheme
import com.lagradost.cloudstream3.desktop.ui.screens.details.contract.DetailsTrackerEntryState
import com.lagradost.cloudstream3.desktop.ui.screens.details.contract.DetailsTrackersUiState
import com.lagradost.cloudstream3.desktop.ui.screens.details.contract.DetailsUiEvent
import com.lagradost.cloudstream3.desktop.ui.theme.LocalDesktopButtonShapes
import com.lagradost.cloudstream3.desktop.utils.ExternalLinkHandler
import java.util.Locale

@Composable
fun DetailsTrackerDrawerHost(
    isOpen: Boolean,
    showName: String,
    trackersState: DetailsTrackersUiState,
    currentSeasonEpisodes: List<Episode>,
    onEvent: (DetailsUiEvent) -> Unit,
    modifier: Modifier = Modifier,
    isMovieLike: Boolean = false,
) {
    Box(modifier = modifier.fillMaxSize().zIndex(90f)) {
        AnimatedVisibility(
            visible = isOpen,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onEvent(DetailsUiEvent.OnDismissTrackerDrawer) },
                    ),
            )
        }

        AnimatedVisibility(
            visible = isOpen,
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(top = 56.dp, end = 16.dp, bottom = 16.dp),
        ) {
            DetailsTrackerDrawerPanel(
                showName = showName,
                trackersState = trackersState,
                currentSeasonEpisodes = currentSeasonEpisodes,
                isMovieLike = isMovieLike,
                onEvent = onEvent,
            )
        }
    }
}

@Composable
private fun DetailsTrackerDrawerPanel(
    showName: String,
    trackersState: DetailsTrackersUiState,
    currentSeasonEpisodes: List<Episode>,
    onEvent: (DetailsUiEvent) -> Unit,
    modifier: Modifier = Modifier,
    isMovieLike: Boolean = false,
) {
    val theme = LocalDesktopTheme.current
    val chipShape = LocalDesktopButtonShapes.current.compact
    val available = trackersState.availableTrackers
    val entry = trackersState.activeEntry
    val bodyScroll = rememberScrollState()
    var showRemoveConfirm by remember(entry?.descriptor?.id) { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .widthIn(min = 396.dp, max = 456.dp)
            .fillMaxWidth(0.94f)
            .fillMaxHeight()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
        shape = RoundedCornerShape(20.dp),
        color = theme.SurfaceCard.copy(alpha = if (theme.isAmoled) 0.96f else 0.94f),
        border = BorderStroke(1.dp, if (theme.isLightMode) theme.Divider.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.14f)),
        shadowElevation = 20.dp,
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(18.dp)) {
            // Drawer Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                ) {
                    val accent = entry?.let { Color(it.descriptor.accentColorHex) } ?: theme.Accent
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = accent.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, accent.copy(alpha = 0.30f)),
                        modifier = Modifier.size(36.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            if (entry != null) {
                                DesktopBadgeComponents.TrackerBrandIcon(
                                    trackerId = entry.descriptor.id,
                                    size = 20.dp,
                                )
                            } else {
                                Icon(Icons.Default.Sync, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Media Tracking",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = theme.TextPrimary,
                        )
                        val activeIsMovie = isMovieLike ||
                            entry?.releaseBounds?.format.equals("MOVIE", ignoreCase = true) ||
                            entry?.boundEntry?.remoteMediaId?.startsWith("movie:", ignoreCase = true) == true
                        Text(
                            text = if (activeIsMovie) "$showName • Movie" else "$showName • Season ${entry?.mappedSeason ?: 1}",
                            style = MaterialTheme.typography.labelSmall,
                            color = theme.TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                DesktopIconButton(
                    icon = Icons.Default.Close,
                    contentDescription = "Close Tracker Drawer",
                    onClick = { onEvent(DetailsUiEvent.OnDismissTrackerDrawer) },
                )
            }

            // Equal-fit Segmented Branded Provider Tabs (no horizontal clipping)
            if (available.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (theme.isLightMode) theme.SurfaceElevated.copy(alpha = 0.7f) else Color.Black.copy(alpha = 0.36f),
                    border = BorderStroke(1.dp, theme.Divider.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        available.forEach { trackerEntry ->
                            val selected = trackerEntry.descriptor.id == entry?.descriptor?.id
                            val brandColor = Color(trackerEntry.descriptor.accentColorHex)
                            val shortName = when (trackerEntry.descriptor.id) {
                                TrackerIds.MAL -> "MAL"
                                TrackerIds.SIMKL -> "SIMKL"
                                else -> "AniList"
                            }
                            val tabIsMovie = isMovieLike ||
                                trackerEntry.releaseBounds.format.equals("MOVIE", ignoreCase = true) ||
                                trackerEntry.boundEntry?.remoteMediaId?.startsWith("movie:", ignoreCase = true) == true
                            val tabBadge = when {
                                trackerEntry.resolution is MatchResolution.BlockedByUser -> "Off"
                                trackerEntry.boundEntry != null -> {
                                    if (tabIsMovie) {
                                        trackerEntry.remoteStatus?.status?.takeIf { it != UnifiedWatchStatus.NONE }?.label
                                            ?: if (trackerEntry.editProgress >= 1) "Watched" else "Linked"
                                    } else {
                                        val prog = trackerEntry.remoteStatus?.progress ?: trackerEntry.editProgress
                                        val tot = trackerEntry.remoteStatus?.totalEpisodes ?: trackerEntry.boundEntry.expectedTotalEpisodes?.toInt()
                                        if (tot != null && tot > 0) "$prog/$tot" else "Ep $prog"
                                    }
                                }
                                trackerEntry.searchResults.isNotEmpty() -> "Match Found"
                                trackerEntry.bindPolicy == TrackerBindPolicy.MANUAL_PIN_ONLY -> "Manual"
                                else -> "Link"
                            }
                            val subColor = when {
                                !selected -> theme.TextMuted.copy(alpha = 0.78f)
                                trackerEntry.boundEntry != null -> Color(0xFF4ADE80)
                                trackerEntry.searchResults.isNotEmpty() -> Color(0xFF38BDF8)
                                else -> brandColor
                            }
                            Surface(
                                onClick = { onEvent(DetailsUiEvent.OnSelectTrackerTab(trackerEntry.descriptor.id)) },
                                shape = RoundedCornerShape(9.dp),
                                color = if (selected) theme.SurfaceCard.copy(alpha = 0.95f) else Color.Transparent,
                                border = if (selected) BorderStroke(1.dp, brandColor.copy(alpha = 0.55f)) else null,
                                modifier = Modifier.weight(1f),
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                                    ) {
                                        DesktopBadgeComponents.TrackerBrandIcon(
                                            trackerId = trackerEntry.descriptor.id,
                                            size = 13.dp,
                                        )
                                        Text(
                                            text = shortName,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (selected) theme.TextPrimary else theme.TextMuted,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Spacer(Modifier.height(2.dp))
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        if (trackerEntry.isAutoSyncEnabled && trackerEntry.resolution !is MatchResolution.BlockedByUser) {
                                            Box(
                                                modifier = Modifier
                                                    .size(5.dp)
                                                    .background(Color(0xFF4ADE80), CircleShape),
                                            )
                                        }
                                        Text(
                                            text = tabBadge,
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                            fontWeight = FontWeight.SemiBold,
                                            color = subColor,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = theme.Divider.copy(alpha = 0.4f))
            Spacer(Modifier.height(12.dp))

            if (entry == null) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No eligible connected trackers for this title. Connect a tracker under Settings -> Profiles & Accounts.",
                        style = MaterialTheme.typography.bodySmall,
                        color = theme.TextMuted,
                    )
                }
                return@Column
            }

            val providerId = entry.descriptor.id
            val brandColor = Color(entry.descriptor.accentColorHex)
            val bound = entry.boundEntry
            val bounds = entry.releaseBounds
            val remote = entry.remoteStatus
            val isInLibrary = remote?.status != null && remote.status != UnifiedWatchStatus.NONE

            entry.feedbackMessage?.let { msg ->
                val bannerColor = if (entry.feedbackIsError) MaterialTheme.colorScheme.error else Color(0xFF4ADE80)
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = bannerColor.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, bannerColor.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                ) {
                    Text(
                        text = msg,
                        color = bannerColor,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }

            if (bound != null && !entry.showSearchDrawer) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(bodyScroll)
                            .padding(end = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        TrackerMatchSummaryCard(
                            entry = entry,
                            brandColor = brandColor,
                            isInLibrary = isInLibrary,
                            onRefresh = { onEvent(DetailsUiEvent.OnRefreshTrackerState(providerId)) },
                            onChangeMatch = { onEvent(DetailsUiEvent.OnToggleTrackerSearch(providerId)) },
                        )

                        val isMovieEntry = isMovieLike ||
                            bounds.format.equals("MOVIE", ignoreCase = true) ||
                            bound.remoteMediaId.startsWith("movie:", ignoreCase = true)

                        // Watch Status grid
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Watch Status", style = MaterialTheme.typography.labelMedium, color = theme.TextMuted)
                            UnifiedWatchStatus.supportedStatusesFor(providerId, isMovieEntry).chunked(3).forEach { rowStatuses ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    rowStatuses.forEach { statusEnum ->
                                        val selected = entry.editStatus == statusEnum
                                        val isLockedCompleted = statusEnum == UnifiedWatchStatus.COMPLETED && !bounds.canMarkCompleted
                                        val fg = when {
                                            isLockedCompleted -> theme.TextMuted.copy(alpha = 0.45f)
                                            selected -> brandColor
                                            else -> theme.TextMuted
                                        }
                                        Surface(
                                            onClick = {
                                                if (!isLockedCompleted) {
                                                    onEvent(DetailsUiEvent.OnSetTrackerRemoteStatus(providerId, statusEnum))
                                                }
                                            },
                                            enabled = !isLockedCompleted && !entry.isSaving,
                                            shape = chipShape,
                                            color = if (selected) brandColor.copy(alpha = 0.16f) else theme.SurfaceElevated.copy(alpha = 0.55f),
                                            border = BorderStroke(1.dp, if (selected) brandColor else theme.Divider.copy(alpha = 0.4f)),
                                            modifier = Modifier.weight(1f),
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.Center,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 7.dp),
                                            ) {
                                                if (isLockedCompleted) {
                                                    Icon(Icons.Default.Lock, contentDescription = null, tint = fg, modifier = Modifier.size(11.dp))
                                                    Spacer(Modifier.width(3.dp))
                                                }
                                                Text(
                                                    text = statusEnum.label,
                                                    color = fg,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Progress Card
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = theme.SurfaceElevated.copy(alpha = 0.45f),
                            border = BorderStroke(1.dp, theme.Divider.copy(alpha = 0.35f)),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (isMovieEntry) {
                                    val isMovieWatched = entry.editProgress >= 1
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text("Movie Progress", style = MaterialTheme.typography.labelMedium, color = theme.TextMuted)
                                        Text(
                                            text = if (isMovieWatched) "Watched" else "Unwatched",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isMovieWatched) brandColor else theme.TextMuted,
                                        )
                                    }
                                    DesktopOutlinedButton(
                                        onClick = {
                                            val nextWatched = !isMovieWatched
                                            if (nextWatched) {
                                                if (entry.editStatus == UnifiedWatchStatus.PLAN_TO_WATCH) {
                                                    onEvent(DetailsUiEvent.OnSetTrackerRemoteStatus(providerId, UnifiedWatchStatus.COMPLETED))
                                                } else {
                                                    onEvent(DetailsUiEvent.OnSetTrackerRemoteProgress(providerId, 1))
                                                }
                                            } else {
                                                if (entry.editStatus == UnifiedWatchStatus.COMPLETED) {
                                                    onEvent(DetailsUiEvent.OnSetTrackerRemoteStatus(providerId, UnifiedWatchStatus.PLAN_TO_WATCH))
                                                } else {
                                                    onEvent(DetailsUiEvent.OnSetTrackerRemoteProgress(providerId, 0))
                                                }
                                            }
                                        },
                                        enabled = !entry.isSaving && (!bounds.isNotYetReleased || isMovieWatched),
                                        modifier = Modifier.fillMaxWidth().height(36.dp),
                                    ) {
                                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(15.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = if (isMovieWatched) "Mark as Unwatched" else "Mark as Watched",
                                            fontSize = 12.sp,
                                        )
                                    }
                                } else {
                                    val ceiling = bounds.ceilingEpisode
                                    val totalForBar = (bounds.totalEpisodes ?: bounds.ceilingEpisode ?: 0).coerceAtLeast(1)
                                    val targetFrac = if ((bounds.totalEpisodes ?: 0) > 0 || (bounds.ceilingEpisode ?: 0) > 0) {
                                        (entry.editProgress.toFloat() / totalForBar.toFloat()).coerceIn(0f, 1f)
                                    } else {
                                        if (entry.editProgress > 0) 0.5f else 0f
                                    }
                                    val animFrac by animateFloatAsState(
                                        targetValue = targetFrac,
                                        animationSpec = tween(260),
                                        label = "progressFill",
                                    )

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text("Episode Progress", style = MaterialTheme.typography.labelMedium, color = theme.TextMuted)
                                        val totalLabel = bounds.totalEpisodes?.let { " / $it" } ?: ""
                                        Text(
                                            text = "Ep ${entry.editProgress}$totalLabel",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = brandColor,
                                        )
                                    }

                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(6.dp)
                                            .clip(CircleShape)
                                            .background(theme.Divider.copy(alpha = 0.35f)),
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth(animFrac)
                                                .fillMaxHeight()
                                                .background(brandColor, CircleShape),
                                        )
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            DesktopOutlinedButton(
                                                onClick = { onEvent(DetailsUiEvent.OnSetTrackerRemoteProgress(providerId, entry.editProgress - 1)) },
                                                enabled = entry.editProgress > 0 && !entry.isSaving,
                                                contentPadding = PaddingValues(horizontal = 10.dp),
                                                modifier = Modifier.height(34.dp),
                                            ) {
                                                Icon(Icons.Default.Remove, contentDescription = "Decrease episode", modifier = Modifier.size(15.dp))
                                            }
                                            DesktopOutlinedButton(
                                                onClick = { onEvent(DetailsUiEvent.OnSetTrackerRemoteProgress(providerId, entry.editProgress + 1)) },
                                                enabled = !bounds.isNotYetReleased && (ceiling == null || entry.editProgress < ceiling) && !entry.isSaving,
                                                contentPadding = PaddingValues(horizontal = 10.dp),
                                                modifier = Modifier.height(34.dp),
                                            ) {
                                                Icon(Icons.Default.Add, contentDescription = "Increase episode", modifier = Modifier.size(15.dp))
                                            }
                                        }

                                        if (currentSeasonEpisodes.isNotEmpty() && !bounds.isNotYetReleased) {
                                            DesktopOutlinedButton(
                                                onClick = { onEvent(DetailsUiEvent.OnSyncReleasedEpisodesToTracker(providerId, currentSeasonEpisodes)) },
                                                enabled = !entry.isSaving,
                                                contentPadding = PaddingValues(horizontal = 10.dp),
                                                modifier = Modifier.height(34.dp),
                                            ) {
                                                Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(14.dp))
                                                Spacer(Modifier.width(5.dp))
                                                Text("Sync Aired", fontSize = 12.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Personal Score (0..10) + Favorite
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                val scoreDisplay = if (entry.editScore > 0) {
                                    String.format(Locale.US, "%.1f / 10", entry.editScore / 10.0)
                                } else {
                                    "Unrated"
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Icon(Icons.Default.Star, contentDescription = null, tint = Color(0xFFFBBF24), modifier = Modifier.size(15.dp))
                                    Text(
                                        text = "Score: $scoreDisplay",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = theme.TextPrimary,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                                if (entry.descriptor.supportsFavorites) {
                                    DesktopOutlinedButton(
                                        onClick = { onEvent(DetailsUiEvent.OnToggleTrackerFavorite(providerId)) },
                                        enabled = !entry.isSaving,
                                        contentPadding = PaddingValues(horizontal = 10.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = if (entry.isFavourite) Color(0xFFFB7185) else theme.TextPrimary,
                                        ),
                                        modifier = Modifier.height(34.dp),
                                    ) {
                                        Icon(
                                            imageVector = if (entry.isFavourite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                            contentDescription = "Favorite",
                                            modifier = Modifier.size(15.dp),
                                        )
                                        Spacer(Modifier.width(5.dp))
                                        Text(if (entry.isFavourite) "Favorited" else "Favorite", fontSize = 12.sp)
                                    }
                                }
                            }

                            val scoresTop = listOf(0, 1, 2, 3, 4, 5)
                            val scoresBottom = listOf(6, 7, 8, 9, 10)
                            listOf(scoresTop, scoresBottom).forEach { rowScores ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    rowScores.forEach { scoreTen ->
                                        val score100 = scoreTen * 10
                                        val active = entry.editScore == score100
                                        Surface(
                                            onClick = { onEvent(DetailsUiEvent.OnSetTrackerScore(providerId, score100)) },
                                            enabled = !entry.isSaving,
                                            shape = chipShape,
                                            color = if (active) Color(0xFFFBBF24).copy(alpha = 0.22f) else theme.SurfaceElevated.copy(alpha = 0.55f),
                                            border = BorderStroke(1.dp, if (active) Color(0xFFFBBF24) else theme.Divider.copy(alpha = 0.4f)),
                                            modifier = Modifier.weight(1f),
                                        ) {
                                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 6.dp)) {
                                                Text(
                                                    text = if (scoreTen == 0) "Clear" else "$scoreTen",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                                    color = if (active) Color(0xFFFBBF24) else theme.TextPrimary,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        TrackerAdvancedDrawerSection(
                            entry = entry,
                            isInLibrary = isInLibrary,
                            onOffsetChange = { next -> onEvent(DetailsUiEvent.OnSetTrackerOffset(providerId, next)) },
                            onUnlink = { onEvent(DetailsUiEvent.OnUnbindTrackerSeason(providerId)) },
                            onNeverTrack = { onEvent(DetailsUiEvent.OnBlockTrackerShow(providerId)) },
                            onRemoveRequest = { showRemoveConfirm = true },
                        )
                    }

                    DesktopVerticalScrollbar(
                        scrollState = bodyScroll,
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                    )
                }

                Spacer(Modifier.height(10.dp))
                DesktopButton(
                    onClick = { onEvent(DetailsUiEvent.OnPushTrackerRemoteUpdate(providerId)) },
                    enabled = !entry.isSaving,
                    modifier = Modifier.fillMaxWidth().height(40.dp),
                ) {
                    if (entry.isSaving) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                        Spacer(Modifier.width(8.dp))
                    } else {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text("Save to ${entry.descriptor.displayName}")
                }
            } else {
                TrackerSearchDrawerSection(
                    entry = entry,
                    brandColor = brandColor,
                    onEvent = onEvent,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = theme.Divider.copy(alpha = 0.35f))
            Spacer(Modifier.height(10.dp))

            // Quick Automation & Match Control Footer Strip
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val autoColor = if (entry.isAutoSyncEnabled) Color(0xFF4ADE80) else theme.TextMuted
                Surface(
                    onClick = { onEvent(DetailsUiEvent.OnSetTrackerAutoSync(providerId, !entry.isAutoSyncEnabled)) },
                    shape = chipShape,
                    color = if (entry.isAutoSyncEnabled) Color(0xFF4ADE80).copy(alpha = 0.14f) else theme.SurfaceElevated.copy(alpha = 0.6f),
                    border = BorderStroke(1.dp, if (entry.isAutoSyncEnabled) Color(0xFF4ADE80).copy(alpha = 0.45f) else theme.Divider.copy(alpha = 0.45f)),
                    modifier = Modifier.weight(1f),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
                    ) {
                        Box(modifier = Modifier.size(7.dp).background(autoColor, CircleShape))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (entry.isAutoSyncEnabled) "Auto-Track: ON" else "Auto-Track: OFF",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (entry.isAutoSyncEnabled) Color(0xFF4ADE80) else theme.TextMuted,
                            maxLines = 1,
                        )
                    }
                }

                val nextPolicy = when (entry.bindPolicy) {
                    TrackerBindPolicy.STRICT_AUTO_AND_PINNED -> TrackerBindPolicy.EXPLORE_OR_PINNED_ONLY
                    TrackerBindPolicy.EXPLORE_OR_PINNED_ONLY -> TrackerBindPolicy.MANUAL_PIN_ONLY
                    TrackerBindPolicy.MANUAL_PIN_ONLY -> TrackerBindPolicy.STRICT_AUTO_AND_PINNED
                }
                val policyLabel = when (entry.bindPolicy) {
                    TrackerBindPolicy.STRICT_AUTO_AND_PINNED -> "Match: Auto"
                    TrackerBindPolicy.EXPLORE_OR_PINNED_ONLY -> "Match: IDs Only"
                    TrackerBindPolicy.MANUAL_PIN_ONLY -> "Match: Manual"
                }
                val isAutoMatch = entry.bindPolicy == TrackerBindPolicy.STRICT_AUTO_AND_PINNED
                Surface(
                    onClick = { onEvent(DetailsUiEvent.OnSetTrackerBindPolicy(providerId, nextPolicy)) },
                    shape = chipShape,
                    color = if (isAutoMatch) Color(0xFF38BDF8).copy(alpha = 0.14f) else theme.SurfaceElevated.copy(alpha = 0.6f),
                    border = BorderStroke(1.dp, if (isAutoMatch) Color(0xFF38BDF8).copy(alpha = 0.45f) else theme.Divider.copy(alpha = 0.45f)),
                    modifier = Modifier.weight(1f),
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp)) {
                        Text(
                            text = policyLabel,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isAutoMatch) Color(0xFF38BDF8) else theme.TextPrimary,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }

    if (showRemoveConfirm && entry != null) {
        val providerName = entry.descriptor.displayName
        CloudstreamAlertDialog(
            show = true,
            onDismissRequest = { showRemoveConfirm = false },
            title = { Text("Remove from $providerName?") },
            text = {
                Text(
                    "Remove '${entry.boundEntry?.remoteTitle ?: showName}' from your $providerName list? Your local watch history stays untouched.",
                )
            },
            confirmButton = {
                DesktopButton(
                    onClick = {
                        showRemoveConfirm = false
                        onEvent(DetailsUiEvent.OnRemoveFromTracker(entry.descriptor.id))
                    },
                    modifier = Modifier.height(40.dp),
                ) {
                    Text("Remove Entry")
                }
            },
            dismissButton = {
                DesktopOutlinedButton(
                    onClick = { showRemoveConfirm = false },
                    modifier = Modifier.height(40.dp),
                ) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun TrackerMatchSummaryCard(
    entry: DetailsTrackerEntryState,
    brandColor: Color,
    isInLibrary: Boolean,
    onRefresh: () -> Unit,
    onChangeMatch: () -> Unit,
) {
    val theme = LocalDesktopTheme.current
    val chipShape = LocalDesktopButtonShapes.current.compact
    val bound = entry.boundEntry ?: return
    val trackerProvider = TrackerRegistry.get(entry.descriptor.id)
    val posterUrl = entry.remoteStatus?.posterUrl

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = theme.SurfaceElevated.copy(alpha = 0.52f),
        border = BorderStroke(1.dp, theme.Divider.copy(alpha = 0.45f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                TrackerPosterThumbnail(
                    posterUrl = posterUrl,
                    trackerId = entry.descriptor.id,
                    brandColor = brandColor,
                    modifier = Modifier.size(width = 48.dp, height = 68.dp),
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = bound.remoteTitle,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = theme.TextPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(end = 4.dp),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            trackerProvider?.webUrl(bound.remoteMediaId)?.let { webUrl ->
                                DesktopIconButton(
                                    icon = Icons.AutoMirrored.Filled.OpenInNew,
                                    contentDescription = "Open in ${entry.descriptor.displayName}",
                                    onClick = { ExternalLinkHandler.launchSystemBrowser(webUrl) },
                                )
                            }
                            DesktopIconButton(
                                icon = Icons.Default.Refresh,
                                contentDescription = "Refresh ${entry.descriptor.displayName}",
                                onClick = onRefresh,
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            shape = chipShape,
                            color = if (isInLibrary) Color(0xFF4ADE80).copy(alpha = 0.14f) else theme.SurfaceCard,
                            border = BorderStroke(1.dp, if (isInLibrary) Color(0xFF4ADE80).copy(alpha = 0.45f) else theme.Divider.copy(alpha = 0.45f)),
                        ) {
                            Text(
                                text = if (isInLibrary) "In List" else "Not in List",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isInLibrary) Color(0xFF4ADE80) else theme.TextMuted,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                            )
                        }
                        val modeLabel = if (bound.bindMode == BindMode.USER_PINNED) "Pinned" else "Auto-Matched"
                        Surface(
                            shape = chipShape,
                            color = brandColor.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, brandColor.copy(alpha = 0.35f)),
                        ) {
                            Text(
                                text = "${entry.descriptor.shortTag} #${bound.remoteMediaId} • S${entry.mappedSeason} • $modeLabel",
                                style = MaterialTheme.typography.labelSmall,
                                color = brandColor,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }

            entry.releaseBounds.statusBadgeText?.let { statusText ->
                Text(text = statusText, style = MaterialTheme.typography.labelSmall, color = Color(0xFFF59E0B))
            }

            DesktopTextButton(
                onClick = onChangeMatch,
                contentPadding = PaddingValues(horizontal = 6.dp),
                modifier = Modifier.height(28.dp),
            ) {
                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(5.dp))
                Text("Wrong season or title? Switch match", fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun TrackerAdvancedDrawerSection(
    entry: DetailsTrackerEntryState,
    isInLibrary: Boolean,
    onOffsetChange: (Int) -> Unit,
    onUnlink: () -> Unit,
    onNeverTrack: () -> Unit,
    onRemoveRequest: () -> Unit,
) {
    val theme = LocalDesktopTheme.current
    var expanded by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DesktopTextButton(
            onClick = { expanded = !expanded },
            contentPadding = PaddingValues(horizontal = 4.dp),
            modifier = Modifier.height(32.dp),
        ) {
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text("Match & Advanced Options", fontSize = 12.5.sp)
        }

        AnimatedVisibility(visible = expanded) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = theme.SurfaceElevated.copy(alpha = 0.4f),
                border = BorderStroke(1.dp, theme.Divider.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (entry.descriptor.supportsEpisodeOffset) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Episode Offset", style = MaterialTheme.typography.labelMedium, color = theme.TextPrimary)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                DesktopOutlinedButton(
                                    onClick = { onOffsetChange(entry.editOffset - 1) },
                                    contentPadding = PaddingValues(horizontal = 8.dp),
                                    modifier = Modifier.height(32.dp),
                                ) {
                                    Icon(Icons.Default.Remove, contentDescription = null, modifier = Modifier.size(14.dp))
                                }
                                Text(
                                    text = if (entry.editOffset >= 0) "+${entry.editOffset}" else "${entry.editOffset}",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = theme.TextPrimary,
                                )
                                DesktopOutlinedButton(
                                    onClick = { onOffsetChange(entry.editOffset + 1) },
                                    contentPadding = PaddingValues(horizontal = 8.dp),
                                    modifier = Modifier.height(32.dp),
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        DesktopOutlinedButton(
                            onClick = onUnlink,
                            enabled = !entry.isSaving,
                            modifier = Modifier.weight(1f).height(38.dp),
                        ) {
                            Icon(Icons.Default.LinkOff, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("Unlink", fontSize = 12.sp)
                        }
                        DesktopOutlinedButton(
                            onClick = onNeverTrack,
                            enabled = !entry.isSaving,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.weight(1f).height(38.dp),
                        ) {
                            Icon(Icons.Default.Block, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("Never Track", fontSize = 12.sp)
                        }
                    }

                    if (isInLibrary && entry.descriptor.supportsLibraryRemoval) {
                        DesktopOutlinedButton(
                            onClick = onRemoveRequest,
                            enabled = !entry.isSaving,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.fillMaxWidth().height(38.dp),
                        ) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Remove Entry from ${entry.descriptor.displayName}", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackerPosterThumbnail(
    posterUrl: String?,
    trackerId: String,
    brandColor: Color,
    modifier: Modifier = Modifier,
) {
    val context = LocalPlatformContext.current
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(brandColor.copy(alpha = 0.14f))
            .border(1.dp, Color.White.copy(alpha = 0.14f), shape),
        contentAlignment = Alignment.Center,
    ) {
        DesktopBadgeComponents.TrackerBrandIcon(trackerId = trackerId, size = 18.dp)
        if (!posterUrl.isNullOrBlank()) {
            AsyncImage(
                model = remember(posterUrl, context) {
                    ImageRequest.Builder(context)
                        .data(posterUrl)
                        .size(160, 240)
                        .crossfade(true)
                        .build()
                },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun TrackerSearchDrawerSection(
    entry: DetailsTrackerEntryState,
    brandColor: Color,
    onEvent: (DetailsUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalDesktopTheme.current
    val chipShape = LocalDesktopButtonShapes.current.compact
    val providerId = entry.descriptor.id
    val providerName = entry.descriptor.displayName
    val listState = rememberLazyListState()
    val candidates = entry.searchResults

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (entry.boundEntry != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Switch $providerName Season ${entry.mappedSeason}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = theme.TextPrimary,
                )
                DesktopTextButton(
                    onClick = { onEvent(DetailsUiEvent.OnToggleTrackerSearch(providerId)) },
                    modifier = Modifier.height(32.dp),
                ) {
                    Text("Back")
                }
            }
        } else if (entry.resolution is MatchResolution.BlockedByUser) {
            Text(
                text = "Tracking paused for Season ${entry.mappedSeason} on $providerName.",
                style = MaterialTheme.typography.bodySmall,
                color = theme.TextMuted,
            )
        } else if (candidates.isNotEmpty()) {
            val topCand = candidates.first()
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF38BDF8).copy(alpha = 0.12f),
                border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "Match Found on $providerName",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF38BDF8),
                        )
                        Text(
                            text = topCand.title,
                            style = MaterialTheme.typography.bodySmall,
                            color = theme.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Surface(
                        onClick = { onEvent(DetailsUiEvent.OnPinTrackerCandidate(providerId, topCand)) },
                        shape = chipShape,
                        color = Color(0xFF38BDF8),
                    ) {
                        Text(
                            text = "Quick Link",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF0A0E17),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        } else {
            val infoText = when (val res = entry.resolution) {
                is MatchResolution.NeedsConfirmation -> res.reason
                is MatchResolution.Skipped -> res.reason
                else -> "Search and pin exact $providerName entry for Season ${entry.mappedSeason}."
            }
            Text(text = infoText, style = MaterialTheme.typography.bodySmall, color = theme.TextMuted)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = entry.searchQuery,
                onValueChange = { onEvent(DetailsUiEvent.OnTrackerSearchQueryChange(providerId, it)) },
                placeholder = { Text("Search $providerName...") },
                singleLine = true,
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp), tint = theme.TextMuted)
                },
                modifier = Modifier.weight(1f),
            )
            DesktopOutlinedButton(
                onClick = { onEvent(DetailsUiEvent.OnSearchTrackerCandidates(providerId)) },
                enabled = !entry.isSearching && entry.searchQuery.isNotBlank(),
                border = BorderStroke(1.dp, if (theme.isLightMode) theme.Divider else Color.White.copy(alpha = 0.22f)),
                contentPadding = PaddingValues(horizontal = 14.dp),
                modifier = Modifier.height(48.dp),
            ) {
                if (entry.isSearching) {
                    CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp, color = brandColor)
                } else {
                    Text("Search", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = theme.TextPrimary)
                }
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (candidates.isEmpty() && !entry.isSearching) {
                val emptyHint = when {
                    entry.hasSearched && entry.searchQuery.isNotBlank() ->
                        "No $providerName matches found for '${entry.searchQuery}'. Try editing the title above."
                    providerId == TrackerIds.ANILIST || providerId == TrackerIds.MAL ->
                        "Search by English or Romaji title to pick the exact $providerName entry."
                    else ->
                        "Search by title to pick the exact $providerName entry."
                }
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = emptyHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = theme.TextMuted,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(end = 10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(candidates, key = { "${it.providerId}:${it.remoteMediaId}" }) { candidate ->
                        DrawerCandidateRow(
                            candidate = candidate,
                            brandColor = brandColor,
                            shortTag = entry.descriptor.shortTag,
                            isSelected = entry.boundEntry?.remoteMediaId == candidate.remoteMediaId,
                            onSelect = { onEvent(DetailsUiEvent.OnPinTrackerCandidate(providerId, candidate)) },
                        )
                    }
                }
                DesktopVerticalScrollbar(
                    listState = listState,
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                )
            }
        }

        if (entry.boundEntry == null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                if (entry.resolution is MatchResolution.BlockedByUser) {
                    DesktopOutlinedButton(
                        onClick = { onEvent(DetailsUiEvent.OnUnbindTrackerSeason(providerId)) },
                        modifier = Modifier.height(38.dp),
                    ) {
                        Text("Allow Tracking")
                    }
                } else {
                    DesktopTextButton(
                        onClick = { onEvent(DetailsUiEvent.OnBlockTrackerShow(providerId)) },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error.copy(alpha = 0.85f)),
                        modifier = Modifier.height(34.dp),
                    ) {
                        Icon(Icons.Default.Block, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("Don't track this title on $providerName", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawerCandidateRow(
    candidate: TrackerMediaCandidate,
    brandColor: Color,
    shortTag: String,
    isSelected: Boolean,
    onSelect: () -> Unit,
) {
    val theme = LocalDesktopTheme.current
    val chipShape = LocalDesktopButtonShapes.current.compact
    val pct = candidate.matchScore?.times(100)?.toInt()?.coerceIn(1, 100)
    Surface(
        onClick = onSelect,
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) brandColor.copy(alpha = 0.14f) else theme.SurfaceElevated.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, if (isSelected) brandColor else theme.Divider.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TrackerPosterThumbnail(
                posterUrl = candidate.posterUrl,
                trackerId = candidate.providerId,
                brandColor = brandColor,
                modifier = Modifier.size(width = 40.dp, height = 56.dp),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = candidate.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isSelected) brandColor else theme.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = buildList {
                    candidate.format?.let { add(it) }
                    candidate.seasonYear?.let { add(it.toString()) }
                    candidate.episodes?.let { add("$it eps") }
                    add("$shortTag #${candidate.remoteMediaId}")
                }.joinToString(" • ")
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (pct != null && pct >= 75) {
                    Surface(
                        shape = RoundedCornerShape(5.dp),
                        color = Color(0xFF4ADE80).copy(alpha = 0.14f),
                        border = BorderStroke(0.6.dp, Color(0xFF4ADE80).copy(alpha = 0.35f)),
                    ) {
                        Text(
                            text = "$pct% Match",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF4ADE80),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.5.dp),
                        )
                    }
                }
            }
            Surface(
                shape = chipShape,
                color = if (isSelected) brandColor else brandColor.copy(alpha = 0.14f),
                border = BorderStroke(1.dp, brandColor.copy(alpha = 0.45f)),
            ) {
                Text(
                    text = if (isSelected) "Pinned" else "Pin Match",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isSelected) Color.White else brandColor,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                )
            }
        }
    }
}
