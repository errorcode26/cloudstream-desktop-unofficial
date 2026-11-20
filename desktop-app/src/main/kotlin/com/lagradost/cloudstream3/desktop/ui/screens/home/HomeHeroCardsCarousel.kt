package com.lagradost.cloudstream3.desktop.ui.screens.home

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import coil3.request.crossfade
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.desktop.ui.theme.AppearanceConfig
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.common.storage.DesktopBookmark
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun HomeHeroCardsCarousel(
    items: List<SearchResponse>,
    provider: MainAPI?,
    heroMetaMap: Map<String, com.lagradost.cloudstream3.desktop.repo.HeroMeta>,
    allBookmarks: Map<String, DesktopBookmark>,
    onPrefetchHeroItem: (MainAPI?, SearchResponse) -> Unit,
    onHeroBackgroundChanged: (String?) -> Unit,
    onItemClick: (SearchResponse, String?, Boolean) -> Unit,
) {
    if (items.isEmpty()) return

    val displayItems = remember(items) { items.take(10) }
    val autoSlideDelay by AppearanceConfig.heroAutoSlideDelaySeconds.collectAsState()

    // Seamless infinite looping via virtual page buffer
    val pageMultiplier = 1000
    val totalPageCount = if (displayItems.size > 1) displayItems.size * pageMultiplier else displayItems.size
    val initialPage = if (displayItems.size > 1) (pageMultiplier / 2) * displayItems.size else 0

    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { totalPageCount })
    val coroutineScope = rememberCoroutineScope()

    var isDragging by remember { mutableStateOf(false) }
    var dragAccumulator by remember { mutableFloatStateOf(0f) }
    var dragStartPage by remember { mutableIntStateOf(0) }
    var isNavigating by remember { mutableStateOf(false) }

    fun navigateToPage(targetPage: Int) {
        if (isNavigating) return
        coroutineScope.launch {
            try {
                isNavigating = true
                pagerState.animateScrollToPage(
                    page = targetPage,
                    animationSpec = tween(
                        durationMillis = 450,
                        easing = FastOutSlowInEasing,
                    ),
                )
            } finally {
                isNavigating = false
            }
        }
    }

    // Eagerly prefetch first 2 slides on load
    LaunchedEffect(items) {
        items.take(2).forEachIndexed { index, item ->
            if (index > 0) delay(400L)
            onPrefetchHeroItem(provider, item)
        }
    }

    // Lazily prefetch upcoming slide as carousel advances
    LaunchedEffect(pagerState.currentPage, items) {
        if (items.isNotEmpty()) {
            val nextActualIndex = ((pagerState.currentPage + 1) % items.size)
            items.getOrNull(nextActualIndex)?.let { nextItem ->
                if (!heroMetaMap.containsKey(nextItem.url)) {
                    onPrefetchHeroItem(provider, nextItem)
                }
            }
        }
    }

    // Sync active ambient background blur with current page
    LaunchedEffect(pagerState.currentPage, heroMetaMap) {
        val currentActualIndex = HomeHeroCardsLayoutMath.safeWrapIndex(pagerState.currentPage, displayItems.size)
        val currentItem = displayItems.getOrNull(currentActualIndex)
        val currentMeta = currentItem?.let { heroMetaMap[it.url] }
        val colorSourceUrl = currentMeta?.backdropUrl ?: provider?.fixUrlNull(currentItem?.posterUrl)
        onHeroBackgroundChanged(colorSourceUrl)
    }

    // Continuous auto-advance loop with active drag and navigation suspension
    LaunchedEffect(displayItems.size, autoSlideDelay, isDragging, isNavigating) {
        if (displayItems.size > 1 && autoSlideDelay > 0 && !isDragging) {
            while (isActive) {
                delay(autoSlideDelay * 1000L)
                if (!isDragging && !isNavigating) {
                    navigateToPage(pagerState.currentPage + 1)
                }
            }
        }
    }

    val windowInfo = androidx.compose.ui.platform.LocalWindowInfo.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val rawHeight = with(density) { windowInfo.containerSize.height.toDp() }

    // Balanced 16:9 widescreen cinematic desktop scale
    val dynamicHeight = (rawHeight * 0.52f).coerceIn(440.dp, 520.dp)

    val carouselInteractionSource = remember { MutableInteractionSource() }
    val isCarouselHovered by carouselInteractionSource.collectIsHoveredAsState()

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(dynamicHeight)
            .hoverable(carouselInteractionSource),
    ) {
        val rootWidth = maxWidth
        val isCompact = rootWidth < 680.dp

        val cardWidth = if (isCompact) {
            (rootWidth - 32.dp).coerceAtLeast(300.dp)
        } else {
            (rootWidth * 0.58f).coerceIn(620.dp, 840.dp)
        }
        val cardHeight = (dynamicHeight - 44.dp).coerceIn(380.dp, 460.dp)
        val horizontalPeekPadding = ((rootWidth - cardWidth) / 2f).coerceAtLeast(0.dp)
        val stackStep = if (isCompact) 0.dp else (cardWidth * 0.28f).coerceIn(180.dp, 240.dp)

        val cardWidthPx = with(density) { cardWidth.toPx() }
        val stackStepPx = with(density) { stackStep.toPx() }

        // Horizontal Pager rendering 5-card stacked deck with seamless infinite looping
        HorizontalPager(
            state = pagerState,
            key = { page -> page },
            beyondViewportPageCount = 3,
            contentPadding = PaddingValues(horizontal = horizontalPeekPadding),
            pageSpacing = 0.dp,
            userScrollEnabled = false,
            modifier = Modifier
                .fillMaxWidth()
                .height(cardHeight)
                .align(Alignment.Center)
                .pointerInput(pagerState, displayItems.size) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            isDragging = true
                            dragAccumulator = 0f
                            dragStartPage = pagerState.currentPage
                        },
                        onDragEnd = {
                            isDragging = false
                            val pageThreshold = 45f
                            val targetPage = when {
                                dragAccumulator < -pageThreshold -> dragStartPage + 1
                                dragAccumulator > pageThreshold -> dragStartPage - 1
                                else -> dragStartPage
                            }
                            navigateToPage(targetPage)
                        },
                        onDragCancel = {
                            isDragging = false
                            navigateToPage(dragStartPage)
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            dragAccumulator += dragAmount
                            pagerState.dispatchRawDelta(-dragAmount)
                        },
                    )
                },
        ) { page ->
            val actualIndex = HomeHeroCardsLayoutMath.safeWrapIndex(page, displayItems.size)
            val item = displayItems.getOrNull(actualIndex) ?: return@HorizontalPager
            val meta = heroMetaMap[item.url]
            val posterUrl = provider?.fixUrlNull(item.posterUrl)
            val backdropUrl = meta?.backdropUrl ?: posterUrl
            val enhancedBackdrop = remember(backdropUrl) { com.lagradost.cloudstream3.desktop.utils.ImageUtils.enhanceBackdropUrl(backdropUrl) }

            val cardShape = RoundedCornerShape(18.dp)

            val pageOffset = ((page - pagerState.currentPage) - pagerState.currentPageOffsetFraction)
            val absOffset = kotlin.math.abs(pageOffset)

            val scale = HomeHeroCardsLayoutMath.calculateScale(absOffset)
            val alphaVal = HomeHeroCardsLayoutMath.calculateAlpha(absOffset)
            val zIndexVal = HomeHeroCardsLayoutMath.calculateZIndex(absOffset)
            val translationX = HomeHeroCardsLayoutMath.calculateTranslationX(pageOffset, stackStepPx, cardWidthPx, isCompact)
            val detailsAlpha = HomeHeroCardsLayoutMath.calculateDetailsAlpha(absOffset)
            val isCenter = absOffset <= 0.5f

            Box(
                modifier = Modifier
                    .zIndex(zIndexVal)
                    .width(cardWidth)
                    .height(cardHeight)
                    .graphicsLayer {
                        this.translationX = translationX
                        this.scaleX = scale
                        this.scaleY = scale
                        this.alpha = alphaVal
                    }
                    .shadow(
                        elevation = if (isCenter) 22.dp else if (absOffset <= 1.5f) 12.dp else 4.dp,
                        shape = cardShape,
                        spotColor = Color.Black.copy(alpha = 0.70f),
                        ambientColor = Color.Transparent,
                    )
                    .clip(cardShape)
                    .border(
                        BorderStroke(
                            width = 1.dp,
                            color = if (isCenter) Color.White.copy(alpha = 0.20f) else Color.White.copy(alpha = 0.08f),
                        ),
                        shape = cardShape,
                    )
                    .background(Color(0xFF0F0F14))
                    .clickable {
                        if (!isDragging && kotlin.math.abs(dragAccumulator) < 15f) {
                            if (!isCenter) {
                                navigateToPage(page)
                            } else {
                                onItemClick(item, meta?.backdropUrl, false)
                            }
                        }
                    },
            ) {
                // Card Backdrop Image
                if (enhancedBackdrop != null) {
                    AsyncImage(
                        model = coil3.request.ImageRequest.Builder(coil3.compose.LocalPlatformContext.current)
                            .data(enhancedBackdrop)
                            .crossfade(true)
                            .build(),
                        contentDescription = item.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                // Dual directional scrims: Left-to-right for typography & bottom-to-top for grounding
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.horizontalGradient(
                                0.00f to Color.Black.copy(alpha = 0.90f),
                                0.45f to Color.Black.copy(alpha = 0.65f),
                                0.80f to Color.Black.copy(alpha = 0.20f),
                                1.00f to Color.Transparent,
                            ),
                        ),
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0.00f to Color.Transparent,
                                0.40f to Color.Transparent,
                                1.00f to Color.Black.copy(alpha = 0.85f),
                            ),
                        ),
                )

                // Unified Content: Anchored at the bottom-left on ALL cards
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            start = if (isCompact) 18.dp else 28.dp,
                            end = if (isCompact) 18.dp else 28.dp,
                            top = 16.dp,
                            bottom = 22.dp,
                        ),
                    verticalArrangement = Arrangement.Bottom,
                    horizontalAlignment = Alignment.Start,
                ) {
                    // Title Logo or Bold Title (Always present, never jumps or teleports!)
                    val logoUrl = meta?.logoUrl
                    val displayTitle = meta?.title ?: com.lagradost.cloudstream3.desktop.repo.HeroRepository.cleanHeroTitle(item.name)

                    if (!logoUrl.isNullOrBlank()) {
                        var logoFailed by remember(logoUrl) { mutableStateOf(false) }
                        if (!logoFailed) {
                            AsyncImage(
                                model = coil3.request.ImageRequest.Builder(coil3.compose.LocalPlatformContext.current)
                                    .data(logoUrl)
                                    .crossfade(true)
                                    .build(),
                                contentDescription = displayTitle,
                                contentScale = ContentScale.Fit,
                                alignment = Alignment.CenterStart,
                                modifier = Modifier
                                    .heightIn(max = if (isCompact) 42.dp else 56.dp)
                                    .fillMaxWidth(if (isCompact) 0.85f else 0.70f),
                                onError = { logoFailed = true },
                            )
                        } else {
                            Text(
                                text = displayTitle,
                                style = MaterialTheme.typography.headlineMedium.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = if (isCompact) 20.sp else 24.sp,
                                ),
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    } else {
                        Text(
                            text = displayTitle,
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = if (isCompact) 20.sp else 24.sp,
                            ),
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    // Extra Details: Smoothly and continuously fades in when card is centered
                    if (detailsAlpha > 0.01f) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .graphicsLayer { alpha = detailsAlpha },
                        ) {
                            Spacer(Modifier.height(8.dp))

                            // Metadata Badges Row
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (meta?.score != null && meta.score.toDoubleOrNull()?.let { it > 0.0 } == true) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Default.Star,
                                            contentDescription = null,
                                            tint = Color(0xFFFFD700),
                                            modifier = Modifier.size(15.dp),
                                        )
                                        Spacer(Modifier.width(3.dp))
                                        Text(
                                            text = meta.score,
                                            color = Color.White,
                                            fontSize = 12.5.sp,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                }

                                if (meta?.year != null) {
                                    Text(
                                        text = meta.year.toString(),
                                        color = Color.White.copy(alpha = 0.85f),
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }

                                if (!meta?.contentRating.isNullOrBlank()) {
                                    com.lagradost.cloudstream3.desktop.ui.badges.DesktopBadgeComponents.ContentRatingBadge(
                                        rating = meta.contentRating,
                                        isLarge = false,
                                    )
                                }

                                if (meta?.duration != null && meta.duration > 0) {
                                    Text(
                                        text = "${meta.duration}m",
                                        color = Color.White.copy(alpha = 0.85f),
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }

                            // Plot Synopsis
                            val displayPlot = remember(meta?.plot) {
                                com.lagradost.cloudstream3.desktop.utils.TitleUtils.cleanPlot(meta?.plot)
                                    ?.replace(Regex("""\s*\n+\s*"""), " ")
                            }
                            if (!displayPlot.isNullOrBlank()) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = displayPlot,
                                    color = Color.White.copy(alpha = 0.80f),
                                    fontSize = 13.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    lineHeight = 17.sp,
                                    modifier = Modifier.fillMaxWidth(if (isCompact) 0.95f else 0.80f),
                                )
                            }

                            Spacer(Modifier.height(14.dp))

                            // Single Clean "Watch Now" Action Button directly opening details
                            Button(
                                onClick = { onItemClick(item, meta?.backdropUrl, false) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color.White,
                                    contentColor = Color.Black,
                                ),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                                modifier = Modifier.height(38.dp),
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(5.dp))
                                Text("Watch Now", fontWeight = FontWeight.ExtraBold, fontSize = 13.5.sp)
                            }
                        }
                    }
                }
            }
        }

        // Desktop Floating Navigation Chevrons (Visible on hover)
        val chevronAlpha by animateFloatAsState(
            targetValue = if (isCarouselHovered && displayItems.size > 1) 1f else 0f,
            animationSpec = tween(200),
            label = "chevron_fade",
        )

        if (chevronAlpha > 0f && !isCompact) {
            // Left Chevron (infinite left scroll)
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 16.dp)
                    .size(44.dp)
                    .graphicsLayer {
                        this.alpha = chevronAlpha
                        this.clip = true
                        this.shape = CircleShape
                    }
                    .clip(CircleShape)
                    .background(Color(0xFF14141A).copy(alpha = 0.80f * chevronAlpha))
                    .border(1.dp, Color.White.copy(alpha = 0.20f * chevronAlpha), CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        navigateToPage(pagerState.currentPage - 1)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.ChevronLeft,
                    contentDescription = "Previous Slide",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp),
                )
            }

            // Right Chevron (infinite right scroll)
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 16.dp)
                    .size(44.dp)
                    .graphicsLayer {
                        this.alpha = chevronAlpha
                        this.clip = true
                        this.shape = CircleShape
                    }
                    .clip(CircleShape)
                    .background(Color(0xFF14141A).copy(alpha = 0.80f * chevronAlpha))
                    .border(1.dp, Color.White.copy(alpha = 0.20f * chevronAlpha), CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        navigateToPage(pagerState.currentPage + 1)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = "Next Slide",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp),
                )
            }
        }

        // Bottom Page Indicator Dots / Dashes (Synced with infinite scroll index)
        if (displayItems.size > 1) {
            val currentActualIndex = HomeHeroCardsLayoutMath.safeWrapIndex(pagerState.currentPage, displayItems.size)
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(displayItems.size) { index ->
                    val isSelected = index == currentActualIndex
                    Box(
                        modifier = Modifier
                            .height(4.dp)
                            .width(if (isSelected) 22.dp else 6.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.35f),
                            )
                            .clickable {
                                val currentCycleStart = (pagerState.currentPage / displayItems.size) * displayItems.size
                                navigateToPage(currentCycleStart + index)
                            },
                    )
                }
            }
        }
    }
}
