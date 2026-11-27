package com.lagradost.cloudstream3.desktop.ui.screens.details

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import coil3.compose.SubcomposeAsyncImage
import coil3.request.crossfade
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.desktop.ui.badges.RatingBadgeUrlResolver
import com.lagradost.cloudstream3.desktop.ui.components.CloudstreamAlertDialog
import com.lagradost.cloudstream3.desktop.ui.components.LocalDesktopTheme
import com.lagradost.cloudstream3.desktop.ui.screens.details.contract.DetailsUiEvent
import com.lagradost.cloudstream3.desktop.ui.screens.details.contract.DetailsUiState
import com.lagradost.cloudstream3.desktop.ui.screens.details.contract.EnrichmentPhase
import com.lagradost.cloudstream3.desktop.utils.ExternalLinkHandler
import com.lagradost.cloudstream3.desktop.utils.ImageUtils
import com.lagradost.cloudstream3.desktop.utils.TitleUtils
import com.lagradost.common.storage.DesktopBookmark
import com.lagradost.common.storage.DesktopWatchType

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DetailsMetadataCompact(
    provider: MainAPI,
    data: LoadResponse,
    uiState: DetailsUiState?,
    isLoading: Boolean,
    enrichmentPhase: EnrichmentPhase,
    heroAction: @Composable (Modifier) -> Unit,
    downloadAction: (@Composable (Modifier) -> Unit)?,
    onPhotosClick: () -> Unit,
    onCastClick: () -> Unit,
    onActorClick: (ActorData) -> Unit,
    onTrailerClick: ((String) -> Unit)?,
    onEvent: (DetailsUiEvent) -> Unit,
) {
    val activeLogoUrl = remember(data, enrichmentPhase, uiState) {
        uiState?.enrichedLogoUrl?.takeIf { it.isNotBlank() }
            ?: data.logoUrl?.takeIf { it.isNotBlank() }
            ?: provider.fixUrlNull(data.logoUrl)
    }
    val displayName = data.name.takeIf { it.isNotBlank() } ?: uiState?.preloadedName ?: ""

    val bookmarkId = com.lagradost.common.storage.DesktopDataStore.bookmarkId(provider.name, data.url)
    val allBookmarks = uiState?.bookmarks ?: emptyMap()
    val currentBookmark = allBookmarks[bookmarkId]
    var isEditingStatus by remember { mutableStateOf(false) }

    val setWatchType: (DesktopWatchType) -> Unit = { type ->
        val newBookmark = DesktopBookmark(
            id = bookmarkId,
            name = data.name.takeIf { it.isNotBlank() } ?: uiState?.preloadedName ?: "",
            url = data.url,
            apiName = provider.name,
            posterUrl = data.posterUrl,
            watchType = type.id,
        )
        onEvent(DetailsUiEvent.OnAddBookmark(newBookmark))
        isEditingStatus = false
    }

    val removeBookmarkAction: () -> Unit = {
        onEvent(DetailsUiEvent.OnRemoveBookmark(bookmarkId))
        isEditingStatus = false
    }

    var isSynopsisExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 140.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 1. Logo or Title Text
        if (!activeLogoUrl.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .heightIn(min = 60.dp, max = 95.dp),
                contentAlignment = Alignment.Center,
            ) {
                var isDarkLogo by remember(activeLogoUrl) {
                    mutableStateOf(ImageUtils.isDarkLogoCached(activeLogoUrl) ?: false)
                }
                val platformContext = coil3.compose.LocalPlatformContext.current
                val logoRequest = remember(activeLogoUrl, platformContext) {
                    coil3.request.ImageRequest.Builder(platformContext)
                        .data(activeLogoUrl)
                        .size(1200, 600)
                        .crossfade(true)
                        .listener(
                            onSuccess = { _, result ->
                                val dark = ImageUtils.isDarkImage(result.image)
                                ImageUtils.cacheDarkLogo(activeLogoUrl, dark)
                                isDarkLogo = dark
                            },
                        )
                        .build()
                }
                SubcomposeAsyncImage(
                    model = logoRequest,
                    contentDescription = displayName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                    alignment = Alignment.Center,
                    colorFilter = if (isDarkLogo) androidx.compose.ui.graphics.ColorFilter.colorMatrix(ImageUtils.InvertColorMatrix) else null,
                    error = {
                        Text(
                            text = displayName,
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Black,
                                shadow = androidx.compose.ui.graphics.Shadow(
                                    color = Color.Black.copy(alpha = 0.85f),
                                    offset = androidx.compose.ui.geometry.Offset(0f, 2f),
                                    blurRadius = 12f,
                                ),
                            ),
                            color = Color.White,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }
        } else {
            Text(
                text = displayName,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Black,
                    shadow = androidx.compose.ui.graphics.Shadow(
                        color = Color.Black.copy(alpha = 0.85f),
                        offset = androidx.compose.ui.geometry.Offset(0f, 2f),
                        blurRadius = 12f,
                    ),
                ),
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // 2. Metadata tags: Year • Duration • Type • Rating
        val metaItems = mutableListOf<String>()
        val enrichedDate = uiState?.enrichedReleaseDate
        val yearSpan = if (data.type != TvType.Movie && data.type != TvType.AnimeMovie && !enrichedDate.isNullOrBlank() && enrichedDate.contains("–")) {
            enrichedDate
        } else {
            (uiState?.enrichedYear ?: data.year)?.toString()
        }
        yearSpan?.let { metaItems.add(it) }

        val finalDuration = uiState?.enrichedDuration ?: data.duration
        finalDuration?.takeIf { it > 0 }?.let { dur ->
            val mins = if (dur > 360) dur / 60 else dur
            val durationStr = if (mins >= 60) {
                val h = mins / 60
                val m = mins % 60
                if (m > 0) "${h}h ${m}m" else "${h}h"
            } else {
                "${mins}m"
            }
            metaItems.add(durationStr)
        }

        val typeStr = when (data.type) {
            TvType.TvSeries -> "TV Series"
            TvType.Anime -> "Anime"
            TvType.Movie -> "Movie"
            TvType.AnimeMovie -> "Anime Movie"
            TvType.OVA -> "OVA"
            TvType.Live -> "Live"
            TvType.Documentary -> "Documentary"
            TvType.Cartoon -> "Cartoon"
            TvType.AsianDrama -> "Asian Drama"
            else -> data.type.name
        }
        if (typeStr.isNotBlank()) metaItems.add(typeStr)

        val isSeriesType = data.type == TvType.TvSeries || data.type == TvType.Anime || data.type == TvType.AsianDrama
        com.lagradost.cloudstream3.desktop.ui.badges.DesktopBadgeComponents.normalizeContentRating(
            data.contentRating,
            isSeries = isSeriesType,
        )?.let { metaItems.add(it) }

        if (metaItems.isNotEmpty()) {
            Text(
                text = metaItems.joinToString("  •  "),
                color = Color.White.copy(alpha = 0.88f),
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // 3. Compact Scores Row + Genres
        val genres = data.tags ?: emptyList()
        com.lagradost.cloudstream3.desktop.ui.badges.DesktopBadgeComponents.MediaRatingsRow(
            data = data,
            tmdbId = uiState?.tmdbId,
            imdbScore = uiState?.enrichedImdbRating,
            tmdbScore = uiState?.enrichedTmdbRating,
            anilistScore = uiState?.enrichedAniListRating,
            isCompact = true,
            isCentered = false,
            onOpenUrl = ExternalLinkHandler::launchSystemBrowser,
        ) { _ ->
            genres.take(2).forEach { genre ->
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = Color.White.copy(alpha = 0.08f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                    modifier = Modifier.padding(horizontal = 1.dp),
                ) {
                    Text(
                        text = genre,
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }

        // 4. Action Buttons (Side by Side Play and Library)
        val isInLibrary = currentBookmark != null
        val activeWatchType = currentBookmark?.let { b ->
            DesktopWatchType.entries.find { it.id == b.watchType }
        } ?: DesktopWatchType.WATCHING

        val compactLibraryIcon = if (isInLibrary) {
            when (activeWatchType) {
                DesktopWatchType.WATCHING -> Icons.Default.PlayArrow
                DesktopWatchType.COMPLETED -> Icons.Default.Check
                DesktopWatchType.ONHOLD -> Icons.Default.Pause
                DesktopWatchType.DROPPED -> Icons.Default.Close
                DesktopWatchType.PLANTOWATCH -> Icons.Default.Bookmark
                DesktopWatchType.REWATCHING -> Icons.AutoMirrored.Filled.RotateRight
            }
        } else {
            Icons.Default.Add
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                heroAction(Modifier.fillMaxWidth())
            }
            val theme = LocalDesktopTheme.current
            val libraryTooltip = if (isInLibrary) "Change Library Status" else "Add to Library"
            TooltipArea(
                tooltip = {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = theme.SurfaceElevated,
                        border = BorderStroke(1.dp, theme.Divider),
                        shadowElevation = 6.dp,
                    ) {
                        Text(
                            text = libraryTooltip,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = theme.TextPrimary,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                },
                delayMillis = 400,
                tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, 16.dp)),
            ) {
                Surface(
                    onClick = { isEditingStatus = true },
                    shape = RoundedCornerShape(10.dp),
                    color = if (isInLibrary) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.12f),
                    border = BorderStroke(1.2.dp, if (isInLibrary) MaterialTheme.colorScheme.primary.copy(alpha = 0.65f) else Color.White.copy(alpha = 0.28f)),
                    modifier = if (isInLibrary) Modifier.height(44.dp).animateContentSize() else Modifier.size(44.dp),
                ) {
                    if (isInLibrary) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxHeight().padding(horizontal = 12.dp),
                        ) {
                            Icon(
                                imageVector = compactLibraryIcon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = activeWatchType.stringRes,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                maxLines = 1,
                            )
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = "Change Status",
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    } else Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            imageVector = compactLibraryIcon,
                            contentDescription = "Add to Library",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
            val fullWebUrl = remember(provider, data.url) {
                if (data.url.isBlank()) null
                else {
                    val fixed = provider.fixUrl(data.url)
                    if (fixed.startsWith("http://") || fixed.startsWith("https://")) fixed else null
                }
            }
            if (fullWebUrl != null) {
                TooltipArea(
                    tooltip = {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = theme.SurfaceElevated,
                            border = BorderStroke(1.dp, theme.Divider),
                            shadowElevation = 6.dp,
                        ) {
                            Text(
                                text = "Open source page in browser",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = theme.TextPrimary,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    },
                    delayMillis = 400,
                    tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, 16.dp)),
                ) {
                    Surface(
                        onClick = { ExternalLinkHandler.launchSystemBrowser(fullWebUrl) },
                        shape = RoundedCornerShape(10.dp),
                        color = Color.White.copy(alpha = 0.12f),
                        border = BorderStroke(1.2.dp, Color.White.copy(alpha = 0.28f)),
                        modifier = Modifier.size(44.dp).pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Icon(
                                imageVector = Icons.Default.Public,
                                contentDescription = "Open in Browser (${provider.name})",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }

        // 4b. Compact Tracker Pills (when eligible & connected)
        val trackersState = uiState?.trackers
        if (trackersState != null && trackersState.availableTrackers.isNotEmpty()) {
            DetailsTrackersHeroCluster(
                trackersState = trackersState,
                isButtonsNarrow = true,
                onOpenTracker = { pId -> onEvent(DetailsUiEvent.OnOpenTrackerDrawer(pId)) },
            )
        }

        // 5. Compact 2-Line Synopsis
        val rawPlot = remember(data.plot) { TitleUtils.cleanPlot(data.plot) }
        if (!rawPlot.isNullOrBlank()) {
            Text(
                text = rawPlot,
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 12.5.sp,
                lineHeight = 17.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
            )
        }

        // Library status popup dialog
        CloudstreamAlertDialog(
            show = isEditingStatus,
            onDismissRequest = { isEditingStatus = false },
            title = {
                Text(
                    text = if (currentBookmark != null) "Library Status" else "Add to Library",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    DesktopWatchType.entries.forEach { type ->
                        val isSelected = currentBookmark?.watchType == type.id
                        val icon = when (type) {
                            DesktopWatchType.WATCHING -> Icons.Default.PlayArrow
                            DesktopWatchType.COMPLETED -> Icons.Default.Check
                            DesktopWatchType.ONHOLD -> Icons.Default.Pause
                            DesktopWatchType.DROPPED -> Icons.Default.Close
                            DesktopWatchType.PLANTOWATCH -> Icons.Default.Bookmark
                            DesktopWatchType.REWATCHING -> Icons.AutoMirrored.Filled.RotateRight
                        }
                        Surface(
                            onClick = { setWatchType(type) },
                            shape = RoundedCornerShape(10.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.05f),
                            border = BorderStroke(
                                1.dp,
                                if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.1f),
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    tint = if (isSelected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.7f),
                                    modifier = Modifier.size(18.dp),
                                )
                                Text(
                                    text = type.stringRes,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else Color.White,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = 14.sp,
                                    modifier = Modifier.weight(1f),
                                )
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                    }

                    if (currentBookmark != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Surface(
                            onClick = removeBookmarkAction,
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f)),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp),
                                )
                                Text(
                                    text = "Remove from Library",
                                    color = MaterialTheme.colorScheme.error,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                com.lagradost.cloudstream3.desktop.ui.components.DesktopTextButton(onClick = { isEditingStatus = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}
