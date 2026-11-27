package com.lagradost.cloudstream3.desktop.ui.screens.details

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.compose.SubcomposeAsyncImage
import com.lagradost.cloudstream3.desktop.ui.components.desktopInteractiveFocus
import com.lagradost.cloudstream3.desktop.ui.components.nonFocusableTap
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.desktop.ui.DesktopDimens
import com.lagradost.cloudstream3.desktop.ui.badges.DesktopBadgeComponents
import com.lagradost.cloudstream3.desktop.ui.badges.RatingBadgeUrlResolver
import com.lagradost.cloudstream3.desktop.ui.components.CinematicTitle
import com.lagradost.cloudstream3.desktop.ui.components.shimmerBackground
import com.lagradost.cloudstream3.desktop.ui.screens.details.contract.DetailsUiEvent
import com.lagradost.cloudstream3.desktop.ui.screens.details.contract.DetailsUiState
import com.lagradost.cloudstream3.desktop.ui.screens.details.contract.EnrichmentPhase
import com.lagradost.cloudstream3.desktop.utils.ExternalLinkHandler
import com.lagradost.cloudstream3.desktop.utils.ImageUtils
import com.lagradost.cloudstream3.desktop.utils.TitleUtils
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.common.storage.DesktopBookmark
import com.lagradost.common.storage.DesktopDataStore
import com.lagradost.common.storage.DesktopWatchType
import java.util.Locale

@Composable
fun AdaptiveMetadataLayout(
    isNarrow: Boolean,
    modifier: Modifier = Modifier,
    mainContent: @Composable () -> Unit,
    sideContent: @Composable () -> Unit,
) {
    if (isNarrow) {
        Column(
            modifier = modifier,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            mainContent()
        }
    } else {
        Row(
            modifier = modifier,
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                mainContent()
            }
            Spacer(modifier = Modifier.width(64.dp))
            Box {
                sideContent()
            }
        }
    }
}

@Composable
fun DetailsMetadata(
    provider: MainAPI,
    data: LoadResponse,
    heroAction: @Composable (Modifier) -> Unit = {},
    downloadAction: (@Composable (Modifier) -> Unit)? = null,
    enrichmentPhase: EnrichmentPhase,
    isLoading: Boolean = false,
    uiState: DetailsUiState? = null,
    screenshots: List<String>? = null,
    onPhotosClick: () -> Unit = {},
    onCastClick: () -> Unit = {},
    onActorClick: (ActorData) -> Unit = {},
    onTrailerClick: ((String) -> Unit)? = null,
    onEvent: (DetailsUiEvent) -> Unit = {},
) {
    var isRightColumnHovered by remember { mutableStateOf(false) }
    val isRightColumnPinned = uiState?.isRightColumnPinned ?: false
    val rightColumnAlpha by animateFloatAsState(
        targetValue = if (isRightColumnPinned || isRightColumnHovered) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
    )

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomStart,
    ) {
        if (maxWidth < 600.dp) {
            DetailsMetadataCompact(
                provider = provider,
                data = data,
                uiState = uiState,
                isLoading = isLoading,
                enrichmentPhase = enrichmentPhase,
                heroAction = heroAction,
                downloadAction = downloadAction,
                onPhotosClick = onPhotosClick,
                onCastClick = onCastClick,
                onActorClick = onActorClick,
                onTrailerClick = onTrailerClick,
                onEvent = onEvent,
            )
        } else {
            val actualMaxHeight = if (maxHeight == Dp.Infinity) 800.dp else maxHeight

            val isNarrow = maxWidth < 1100.dp
            val isButtonsNarrow = false
            val isCompactHeight = actualMaxHeight < 550.dp
            val isMediumHeight = actualMaxHeight < 750.dp

            val responsiveLogoMaxWidth = minOf(600.dp, maxWidth * if (isNarrow) 0.7f else 0.42f)
            val responsivePlotMaxWidth = minOf(580.dp, maxWidth * if (isNarrow) 0.85f else 0.46f)

            val responsiveLogoMaxHeight = when {
                isCompactHeight -> 96.dp
                isMediumHeight -> 130.dp
                else -> 165.dp
            }
            val responsiveTopPadding = when {
                isCompactHeight -> 48.dp
                isMediumHeight -> if (isNarrow) 56.dp else 64.dp
                else -> if (isNarrow) 64.dp else 72.dp
            }

            val isMovie = data.type == TvType.Movie || data.type == TvType.AnimeMovie || data.type == TvType.Live
            val responsiveBottomPadding = when {
                isCompactHeight -> 14.dp
                isMovie -> 36.dp
                else -> 28.dp
            }

            AdaptiveMetadataLayout(
                isNarrow = isNarrow,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = if (isNarrow) 24.dp else 64.dp,
                        end = if (isNarrow) 24.dp else 64.dp,
                        bottom = responsiveBottomPadding,
                        top = responsiveTopPadding,
                    ),
                mainContent = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = if (isNarrow) Alignment.CenterHorizontally else Alignment.Start,
                    ) {
                        Crossfade(
                            targetState = isLoading,
                            animationSpec = tween(350),
                            label = "header_loading_crossfade",
                        ) { loading ->
                            if (loading) {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(14.dp),
                                    horizontalAlignment = if (isNarrow) Alignment.CenterHorizontally else Alignment.Start,
                                ) {
                                    Box(modifier = Modifier.fillMaxWidth(0.45f).height(48.dp).clip(RoundedCornerShape(8.dp)).shimmerBackground())
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Box(modifier = Modifier.width(56.dp).height(24.dp).clip(RoundedCornerShape(6.dp)).shimmerBackground())
                                        Box(modifier = Modifier.width(48.dp).height(24.dp).clip(RoundedCornerShape(6.dp)).shimmerBackground())
                                        Box(modifier = Modifier.width(64.dp).height(24.dp).clip(RoundedCornerShape(6.dp)).shimmerBackground())
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Box(modifier = Modifier.fillMaxWidth(0.7f).height(14.dp).clip(RoundedCornerShape(4.dp)).shimmerBackground())
                                    Box(modifier = Modifier.fillMaxWidth(0.55f).height(14.dp).clip(RoundedCornerShape(4.dp)).shimmerBackground())
                                }
                            } else {
                                val currentPhase = enrichmentPhase
                                val activeLogoUrl = remember(data, currentPhase, uiState) {
                                    uiState?.enrichedLogoUrl?.takeIf { it.isNotBlank() }
                                        ?: data.logoUrl?.takeIf { it.isNotBlank() }
                                        ?: provider.fixUrlNull(data.logoUrl)
                                }
                                val displayName = data.name.takeIf { it.isNotBlank() } ?: uiState?.preloadedName ?: ""
                                val hasLogo = !activeLogoUrl.isNullOrBlank()
                                val titleContainerMinHeight = when {
                                    isCompactHeight -> 80.dp
                                    isNarrow -> 80.dp
                                    else -> 110.dp
                                }

                                Box(
                                    modifier = Modifier
                                        .widthIn(min = DesktopDimens.HeroLogoMinWidth, max = responsiveLogoMaxWidth)
                                        .heightIn(min = titleContainerMinHeight, max = responsiveLogoMaxHeight),
                                    contentAlignment = if (isNarrow) Alignment.Center else Alignment.BottomStart,
                                ) {
                                    Crossfade(
                                        targetState = hasLogo,
                                        animationSpec = tween(350),
                                        label = "title_logo_crossfade",
                                        modifier = Modifier.fillMaxSize(),
                                    ) { showLogo ->
                                        if (showLogo) {
                                            Box(
                                                modifier = Modifier.fillMaxSize(),
                                                contentAlignment = if (isNarrow) Alignment.Center else Alignment.BottomStart,
                                            ) {
                                                var isDarkLogo by remember(activeLogoUrl) {
                                                    mutableStateOf(ImageUtils.isDarkLogoCached(activeLogoUrl) ?: false)
                                                }
                                                val platformContext = LocalPlatformContext.current

                                                val logoRequest = remember(activeLogoUrl, platformContext) {
                                                    ImageRequest.Builder(platformContext)
                                                        .data(activeLogoUrl)
                                                        .size(1600, 800)
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
                                                AsyncImage(
                                                    model = logoRequest,
                                                    contentDescription = null,
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .offset(
                                                            x = DesktopDimens.LogoShadowOffsetX,
                                                            y = DesktopDimens.LogoShadowOffsetY,
                                                        )
                                                        .blur(
                                                            DesktopDimens.LogoShadowBlur,
                                                            edgeTreatment = androidx.compose.ui.draw.BlurredEdgeTreatment.Unbounded,
                                                        ),
                                                    contentScale = ContentScale.Fit,
                                                    filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
                                                    alignment = if (isNarrow) Alignment.Center else Alignment.BottomStart,
                                                    colorFilter = DesktopDimens.LogoShadowFilter,
                                                )
                                                SubcomposeAsyncImage(
                                                    model = logoRequest,
                                                    contentDescription = displayName,
                                                    contentScale = ContentScale.Fit,
                                                    filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
                                                    modifier = Modifier.fillMaxSize(),
                                                    alignment = if (isNarrow) Alignment.Center else Alignment.BottomStart,
                                                    colorFilter = if (isDarkLogo) ColorFilter.colorMatrix(ImageUtils.InvertColorMatrix) else null,
                                                    error = {
                                                        CinematicTitle(
                                                            text = displayName,
                                                            fontSize = if (displayName.length > 28) 34.sp else 42.sp,
                                                            textAlign = if (isNarrow) TextAlign.Center else TextAlign.Start,
                                                            modifier = Modifier
                                                                .widthIn(max = responsivePlotMaxWidth)
                                                                .padding(bottom = 2.dp),
                                                        )
                                                    },
                                                )
                                            }
                                        } else {
                                            Box(
                                                modifier = Modifier.fillMaxSize(),
                                                contentAlignment = if (isNarrow) Alignment.Center else Alignment.BottomStart,
                                            ) {
                                                CinematicTitle(
                                                    text = displayName,
                                                    fontSize = if (displayName.length > 28) 34.sp else 42.sp,
                                                    textAlign = if (isNarrow) TextAlign.Center else TextAlign.Start,
                                                    modifier = Modifier
                                                        .widthIn(max = responsivePlotMaxWidth)
                                                        .padding(bottom = 2.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        val activeTagline = uiState?.enrichedTagline?.takeIf { it.isNotBlank() }
                        if (activeTagline != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "\"$activeTagline\"",
                                style = MaterialTheme.typography.titleMedium.copy(fontStyle = FontStyle.Italic),
                                color = Color.White.copy(alpha = 0.85f),
                                fontWeight = FontWeight.Normal,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .widthIn(max = responsivePlotMaxWidth)
                                    .padding(start = if (isNarrow) 0.dp else 4.dp),
                                textAlign = if (isNarrow) TextAlign.Center else TextAlign.Start,
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        if (!isLoading) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = if (isNarrow) Arrangement.Center else Arrangement.spacedBy(10.dp),
                                modifier = if (isNarrow) Modifier.fillMaxWidth() else Modifier,
                            ) {
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
                                if (typeStr.isNotBlank()) {
                                    metaItems.add(typeStr)
                                }

                                if (metaItems.isNotEmpty()) {
                                    Text(
                                        text = metaItems.joinToString("   •   "),
                                        color = Color.White.copy(alpha = 0.88f),
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }

                                data.contentRating?.takeIf { it.isNotBlank() }?.let { rating ->
                                    DesktopBadgeComponents.ContentRatingBadge(
                                        rating = rating,
                                        isLarge = true,
                                        isSeries = data.type == TvType.TvSeries || data.type == TvType.Anime || data.type == TvType.AsianDrama,
                                    )
                                }
                            }

                            val finalTags = uiState?.enrichedTags ?: data.tags
                            val hasFallbackScore = data.score?.toFloat(10)?.let { it > 0.0f } == true
                            val hasAnyScore = (uiState?.enrichedImdbRating ?: 0.0) > 0.0 ||
                                (uiState?.enrichedTmdbRating ?: 0.0) > 0.0 ||
                                (uiState?.enrichedAniListRating ?: 0.0) > 0.0 ||
                                hasFallbackScore

                            if (hasAnyScore || !finalTags.isNullOrEmpty()) {
                                Spacer(modifier = Modifier.height(12.dp))
                                DesktopBadgeComponents.MediaRatingsRow(
                                    data = data,
                                    tmdbId = uiState?.tmdbId,
                                    imdbScore = uiState?.enrichedImdbRating,
                                    tmdbScore = uiState?.enrichedTmdbRating,
                                    anilistScore = uiState?.enrichedAniListRating,
                                    isCompact = false,
                                    isCentered = isNarrow,
                                    onOpenUrl = ExternalLinkHandler::launchSystemBrowser,
                                ) { hasRatings ->
                                    if (!finalTags.isNullOrEmpty()) {
                                        Text(
                                            text = (if (hasRatings) "•   " else "") + finalTags.take(4).joinToString(", "),
                                            color = Color.White.copy(alpha = 0.72f),
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                        }

                        val cleanedPlot = remember(data.plot) { TitleUtils.cleanPlot(data.plot) }
                        if (!isLoading && !cleanedPlot.isNullOrBlank()) {
                            var plotExpanded by remember(cleanedPlot) { mutableStateOf(false) }
                            var canExpandPlot by remember(cleanedPlot) { mutableStateOf(false) }

                            Column(
                                modifier = Modifier
                                    .widthIn(max = responsivePlotMaxWidth)
                                    .animateContentSize(tween(250))
                                    .then(
                                        if (canExpandPlot) {
                                            Modifier
                                                .pointerHoverIcon(PointerIcon.Hand)
                                                .nonFocusableTap { plotExpanded = !plotExpanded }
                                        } else {
                                            Modifier
                                        },
                                    ),
                                horizontalAlignment = if (isNarrow) Alignment.CenterHorizontally else Alignment.Start,
                            ) {
                                Text(
                                    text = cleanedPlot,
                                    color = Color.White.copy(alpha = 0.88f),
                                    fontSize = 16.sp,
                                    lineHeight = 24.sp,
                                    maxLines = if (plotExpanded) Int.MAX_VALUE else 3,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = if (isNarrow) TextAlign.Center else TextAlign.Start,
                                    onTextLayout = { result ->
                                        if (!plotExpanded) {
                                            canExpandPlot = result.hasVisualOverflow
                                        }
                                    },
                                )
                                if (canExpandPlot) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    val toggleInteraction = remember { MutableInteractionSource() }
                                    val toggleShape = RoundedCornerShape(6.dp)
                                    Text(
                                        text = if (plotExpanded) "Show less" else "Show more",
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier
                                            .clip(toggleShape)
                                            .pointerHoverIcon(PointerIcon.Hand)
                                            .desktopInteractiveFocus(interactionSource = toggleInteraction, shape = toggleShape)
                                            .clickable(
                                                interactionSource = toggleInteraction,
                                                indication = null,
                                            ) { plotExpanded = !plotExpanded }
                                            .padding(horizontal = 4.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        val bookmarkId = DesktopDataStore.bookmarkId(provider.name, data.url)
                        val allBookmarks = uiState?.bookmarks ?: emptyMap()
                        val currentBookmark = allBookmarks[bookmarkId]
                        var isEditingStatus by remember { mutableStateOf(false) }

                        val activeWatchType = currentBookmark?.let { b ->
                            DesktopWatchType.entries.find { it.id == b.watchType }
                        } ?: DesktopWatchType.WATCHING

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

                        val libraryButton: @Composable (Modifier) -> Unit = { mod ->
                            DetailsHeroLibraryButton(
                                modifier = mod,
                                currentBookmark = currentBookmark,
                                activeWatchType = activeWatchType,
                                isButtonsNarrow = isButtonsNarrow,
                                onEditStatus = { isEditingStatus = true },
                            )
                        }

                        DetailsLibraryStatusDialog(
                            show = isEditingStatus,
                            onDismissRequest = { isEditingStatus = false },
                            currentBookmark = currentBookmark,
                            onSetWatchType = setWatchType,
                            onRemoveBookmark = removeBookmarkAction,
                        )

                        val trackersUiState = uiState?.trackers

                        val webButton: @Composable (Modifier) -> Unit = { modifier ->
                            DetailsHeroWebButton(
                                modifier = modifier,
                                isButtonsNarrow = isButtonsNarrow,
                                provider = provider,
                                url = data.url,
                            )
                        }

                        var isActionsExpanded by remember { mutableStateOf(false) }
                        val toggleRotation by animateFloatAsState(
                            targetValue = if (isActionsExpanded) 90f else 0f,
                            animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow),
                            label = "actionsToggleRotation",
                        )

                        val moreActionsToggle: @Composable (Boolean) -> Unit = { isNarrowBtn ->
                            com.lagradost.cloudstream3.desktop.ui.components.HeroSecondaryButton(
                                onClick = { isActionsExpanded = !isActionsExpanded },
                                modifier = Modifier.size(if (isNarrowBtn) 44.dp else 52.dp),
                                isActive = isActionsExpanded,
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                    Icon(
                                        imageVector = Icons.Default.MoreVert,
                                        contentDescription = if (isActionsExpanded) "Hide secondary actions" else "Show more actions",
                                        tint = Color.White,
                                        modifier = Modifier
                                            .size(if (isNarrowBtn) 20.dp else 22.dp)
                                            .graphicsLayer { rotationZ = toggleRotation },
                                    )
                                }
                            }
                        }

                        val bloomingCluster: @Composable (Boolean) -> Unit = { isNarrowBtn ->
                            AnimatedVisibility(
                                visible = isActionsExpanded,
                                enter = fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) +
                                    expandHorizontally(
                                        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
                                        expandFrom = Alignment.End,
                                    ) +
                                    scaleIn(
                                        initialScale = 0.86f,
                                        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
                                    ),
                                exit = fadeOut(tween(140)) +
                                    shrinkHorizontally(
                                        animationSpec = tween(170),
                                        shrinkTowards = Alignment.End,
                                    ) +
                                    scaleOut(
                                        targetScale = 0.86f,
                                        animationSpec = tween(170),
                                    ),
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(if (isNarrowBtn) 10.dp else 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(
                                        start = 6.dp,
                                        top = 4.dp,
                                        bottom = 4.dp,
                                        end = if (isNarrowBtn) 10.dp else 14.dp,
                                    ),
                                ) {
                                    libraryButton(Modifier)
                                    downloadAction?.invoke(Modifier)
                                    webButton(Modifier)
                                }
                            }
                        }

                        val showTrackerPills = trackersUiState != null && trackersUiState.availableTrackers.isNotEmpty()

                        if (isButtonsNarrow) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .widthIn(max = 520.dp)
                                    .fillMaxWidth()
                                    .align(if (isNarrow) Alignment.CenterHorizontally else Alignment.Start),
                            ) {
                                Box(modifier = Modifier.weight(1f)) {
                                    heroAction(Modifier.fillMaxWidth())
                                }

                                if (showTrackerPills) {
                                    DetailsTrackersHeroCluster(
                                        trackersState = trackersUiState,
                                        isButtonsNarrow = true,
                                        onOpenTracker = { pId -> onEvent(DetailsUiEvent.OnOpenTrackerDrawer(pId)) },
                                    )
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    bloomingCluster(true)
                                    moreActionsToggle(true)
                                }
                            }
                        } else {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.align(if (isNarrow) Alignment.CenterHorizontally else Alignment.Start),
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    heroAction(Modifier.wrapContentWidth())
                                    if (showTrackerPills) {
                                        DetailsTrackersHeroCluster(
                                            trackersState = trackersUiState,
                                            isButtonsNarrow = false,
                                            onOpenTracker = { pId -> onEvent(DetailsUiEvent.OnOpenTrackerDrawer(pId)) },
                                        )
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        bloomingCluster(false)
                                        moreActionsToggle(false)
                                    }
                                }
                            }
                        }
                    }
                },
                sideContent = {
                    if (!isLoading) {
                        DetailsSidebarStats(
                            provider = provider,
                            data = data,
                            uiState = uiState,
                            isNarrow = isNarrow,
                            isRightColumnPinned = isRightColumnPinned,
                            rightColumnAlpha = rightColumnAlpha,
                            isRightColumnHovered = isRightColumnHovered,
                            onHoverChange = { isRightColumnHovered = it },
                            onTogglePin = { onEvent(DetailsUiEvent.OnTogglePinRightColumn) },
                        )
                    }
                },
            )
        }
    }
}
