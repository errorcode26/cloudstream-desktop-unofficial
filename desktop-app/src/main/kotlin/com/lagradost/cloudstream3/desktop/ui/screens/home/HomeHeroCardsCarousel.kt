package com.lagradost.cloudstream3.desktop.ui.screens.home

import androidx.compose.animation.core.CubicBezierEasing
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
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
import com.lagradost.cloudstream3.desktop.ui.components.HeroPrimaryCtaButton
import com.lagradost.cloudstream3.desktop.ui.theme.LocalDesktopButtonShapes
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.desktop.ui.LocalSafeArea
import com.lagradost.cloudstream3.desktop.ui.theme.AppearanceConfig
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.common.storage.DesktopBookmark
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val HeroParallaxEasing = CubicBezierEasing(0.22f, 1.0f, 0.36f, 1.0f)

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
                        durationMillis = 540,
                        easing = HeroParallaxEasing,
                    ),
                )
            } finally {
                isNavigating = false
            }
        }
    }

    // Prefetch active card and ±2 flanking cinema peeks so side cards load landscape backdrops
    LaunchedEffect(pagerState.currentPage, displayItems, items) {
        if (displayItems.isEmpty()) return@LaunchedEffect
        val seenUrls = HashSet<String>(5)
        val relativeOffsets = intArrayOf(0, 1, -1, 2, -2)
        var fetchedCount = 0
        for (rel in relativeOffsets) {
            val actualIndex = HomeHeroCardsLayoutMath.safeWrapIndex(pagerState.currentPage + rel, displayItems.size)
            val targetItem = displayItems.getOrNull(actualIndex) ?: continue
            if (!seenUrls.add(targetItem.url)) continue
            if (!heroMetaMap.containsKey(targetItem.url)) {
                if (fetchedCount > 0) delay(100L)
                fetchedCount++
                onPrefetchHeroItem(provider, targetItem)
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
    val rawWidth = with(density) { windowInfo.containerSize.width.toDp() }
    val isWindowCompact = rawWidth < 680.dp

    // Respect top header / top dock clearance with a guarded buffer for floating card heroes
    val safeTop = LocalSafeArea.current.calculateTopPadding()
    val topBuffer = if (isWindowCompact) {
        safeTop.coerceAtLeast(58.dp)
    } else {
        safeTop.coerceAtLeast(72.dp)
    }
    val bottomBuffer = 38.dp
    val cardHeight = if (isWindowCompact) {
        360.dp
    } else {
        ((rawHeight - topBuffer) * 0.46f).coerceIn(340.dp, 444.dp)
    }
    val totalHeroHeight = topBuffer + cardHeight + bottomBuffer

    val carouselInteractionSource = remember { MutableInteractionSource() }
    val isCarouselHovered by carouselInteractionSource.collectIsHoveredAsState()

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(totalHeroHeight)
            .hoverable(carouselInteractionSource),
    ) {
        val rootWidth = maxWidth
        val isCompact = rootWidth < 680.dp

        val cardWidth = if (isCompact) {
            (rootWidth - 32.dp).coerceAtLeast(300.dp)
        } else {
            (rootWidth * 0.50f).coerceIn(580.dp, 820.dp)
        }
        val cardGap = if (isCompact) 12.dp else 20.dp
        val horizontalPeekPadding = ((rootWidth - cardWidth) / 2f).coerceAtLeast(0.dp)
        val sideClearance = when {
            rootWidth >= 1440.dp -> 84.dp
            rootWidth >= 1100.dp -> 56.dp
            else -> 16.dp
        }

        val rootWidthPx = with(density) { rootWidth.toPx() }
        val cardWidthPx = with(density) { cardWidth.toPx() }
        val gapPx = with(density) { cardGap.toPx() }
        val sideClearancePx = with(density) { sideClearance.toPx() }

        val edgeFadeBrush = remember(rootWidthPx, cardWidthPx, gapPx, sideClearancePx, isCompact) {
            if (isCompact) {
                null
            } else {
                val stops = HomeHeroCardsLayoutMath.calculateEdgeFadeStops(
                    canvasWidthPx = rootWidthPx,
                    cardWidthPx = cardWidthPx,
                    gapPx = gapPx,
                    sideClearancePx = sideClearancePx,
                )
                Brush.horizontalGradient(
                    0.00f to Color.Transparent,
                    stops.leftFadeEnd to Color.Transparent,
                    stops.leftMidLow to Color.Black.copy(alpha = 0.28f),
                    stops.leftMidHigh to Color.Black.copy(alpha = 0.76f),
                    stops.leftFadeStart to Color.Black,
                    stops.rightFadeStart to Color.Black,
                    stops.rightMidHigh to Color.Black.copy(alpha = 0.76f),
                    stops.rightMidLow to Color.Black.copy(alpha = 0.28f),
                    stops.rightFadeEnd to Color.Transparent,
                    1.00f to Color.Transparent,
                )
            }
        }

        // Offscreen DstIn edge mask melts outer wings of flanking cinema peeks smoothly into backdrop
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (edgeFadeBrush != null) {
                        Modifier
                            .graphicsLayer {
                                compositingStrategy = CompositingStrategy.Offscreen
                            }
                            .drawWithContent {
                                drawContent()
                                drawRect(
                                    brush = edgeFadeBrush,
                                    blendMode = BlendMode.DstIn,
                                )
                            }
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.TopCenter,
        ) {
            // Spaced Cinema Pager: active center card flanked by clean gapped left/right peeks
            HorizontalPager(
                state = pagerState,
                key = { page -> page },
                beyondViewportPageCount = 2,
                contentPadding = PaddingValues(horizontal = horizontalPeekPadding),
                pageSpacing = cardGap,
                userScrollEnabled = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = topBuffer)
                    .height(cardHeight)
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

            val cardShape = RoundedCornerShape(20.dp)

            val pageOffset = ((page - pagerState.currentPage) - pagerState.currentPageOffsetFraction)
            val absOffset = kotlin.math.abs(pageOffset)

            val scale = HomeHeroCardsLayoutMath.calculateScale(absOffset)
            val alphaVal = HomeHeroCardsLayoutMath.calculateAlpha(absOffset)
            val dimAlpha = HomeHeroCardsLayoutMath.calculateDimAlpha(absOffset)
            val zIndexVal = HomeHeroCardsLayoutMath.calculateZIndex(absOffset)
            val detailsAlpha = HomeHeroCardsLayoutMath.calculateDetailsAlpha(absOffset)
            val backdropParallaxPx = HomeHeroCardsLayoutMath.calculateBackdropParallaxPx(pageOffset, cardWidthPx)
            val foregroundBaseParallaxPx = HomeHeroCardsLayoutMath.calculateForegroundParallaxPx(pageOffset, cardWidthPx)
            val isCenter = absOffset <= 0.5f

            val shadowElevation = (24f - (absOffset * 6f)).coerceIn(8f, 24f).dp

            Box(
                modifier = Modifier
                    .zIndex(zIndexVal)
                    .width(cardWidth)
                    .height(cardHeight)
                    .graphicsLayer {
                        this.scaleX = scale
                        this.scaleY = scale
                        this.alpha = alphaVal
                    }
                    .shadow(
                        elevation = shadowElevation,
                        shape = cardShape,
                        spotColor = Color.Black.copy(alpha = 0.72f),
                        ambientColor = Color.Transparent,
                    )
                    .clip(cardShape)
                    .background(Color(0xFF0F0F14))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        if (!isDragging && kotlin.math.abs(dragAccumulator) < 15f) {
                            if (!isCenter) {
                                navigateToPage(page)
                            } else {
                                onItemClick(item, meta?.backdropUrl, false)
                            }
                        }
                    },
            ) {
                // Card Backdrop Image (overscaled 1.08x inside card clip for edge-safe depth parallax)
                if (enhancedBackdrop != null) {
                    AsyncImage(
                        model = coil3.request.ImageRequest.Builder(coil3.compose.LocalPlatformContext.current)
                            .data(enhancedBackdrop)
                            .crossfade(true)
                            .build(),
                        contentDescription = item.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                this.scaleX = HomeHeroCardsLayoutMath.BACKDROP_PARALLAX_SCALE
                                this.scaleY = HomeHeroCardsLayoutMath.BACKDROP_PARALLAX_SCALE
                                this.translationX = backdropParallaxPx
                            },
                    )
                }

                // Symmetrical depth-dimming veil keeps cards solid while separating fanned layers
                if (dimAlpha > 0.005f) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = dimAlpha)),
                    )
                }

                // Center-only typography scrims so left/right peek cards stay vibrant and symmetrical
                if (detailsAlpha > 0.01f) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = detailsAlpha },
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.horizontalGradient(
                                        0.00f to Color.Black.copy(alpha = 0.72f),
                                        0.38f to Color.Black.copy(alpha = 0.34f),
                                        0.72f to Color.Transparent,
                                    ),
                                ),
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        0.00f to Color.Transparent,
                                        0.38f to Color.Transparent,
                                        0.72f to Color.Black.copy(alpha = 0.45f),
                                        1.00f to Color.Black.copy(alpha = 0.88f),
                                    ),
                                ),
                        )
                    }
                }

                // Active-card foreground overlay; hidden on peeking cards to prevent edge text collisions
                if (detailsAlpha > 0.01f) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = detailsAlpha }
                            .padding(
                                start = if (isCompact) 18.dp else 28.dp,
                                end = if (isCompact) 18.dp else 28.dp,
                                top = 16.dp,
                                bottom = 24.dp,
                            ),
                        verticalArrangement = Arrangement.Bottom,
                        horizontalAlignment = Alignment.Start,
                    ) {
                        val logoUrl = meta?.logoUrl
                        val displayTitle = meta?.title ?: com.lagradost.cloudstream3.desktop.repo.HeroRepository.cleanHeroTitle(item.name)

                        Box(
                            modifier = Modifier.graphicsLayer {
                                this.translationX = foregroundBaseParallaxPx * 0.75f
                            },
                        ) {
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
                                            .fillMaxWidth(if (isCompact) 0.85f else 0.65f),
                                        onError = { logoFailed = true },
                                    )
                                } else {
                                    Text(
                                        text = displayTitle,
                                        style = MaterialTheme.typography.headlineMedium.copy(
                                            fontWeight = FontWeight.ExtraBold,
                                            fontSize = if (isCompact) 20.sp else 26.sp,
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
                                        fontSize = if (isCompact) 20.sp else 26.sp,
                                    ),
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        // Metadata + Plot layer floats slightly ahead of title during scroll transitions
                        Column(
                            modifier = Modifier.graphicsLayer {
                                this.translationX = foregroundBaseParallaxPx
                            },
                        ) {
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
                                    color = Color.White.copy(alpha = 0.82f),
                                    fontSize = 13.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    lineHeight = 17.sp,
                                    modifier = Modifier.fillMaxWidth(if (isCompact) 0.95f else 0.74f),
                                )
                            }
                        }

                        Spacer(Modifier.height(14.dp))

                        HeroPrimaryCtaButton(
                            text = "Watch Now",
                            icon = Icons.Default.PlayArrow,
                            onClick = { onItemClick(item, meta?.backdropUrl, false) },
                            shape = LocalDesktopButtonShapes.current.primary,
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            iconSize = 18.dp,
                            fontSize = 13.5.sp,
                            iconSpacing = 5.dp,
                            modifier = Modifier
                                .height(38.dp)
                                .graphicsLayer {
                                    this.translationX = foregroundBaseParallaxPx * 1.25f
                                },
                        )
                    }
                }

                // Rendered last inside card bounds so rim highlights stay crisp over artwork and scrims
                val centerGlow = (1f - absOffset).coerceIn(0f, 1f)
                val rimTopAlpha = 0.22f + 0.06f * centerGlow
                val rimMidAlpha = 0.13f + 0.03f * centerGlow
                val rimBottomAlpha = 0.08f + 0.02f * centerGlow
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .border(
                            BorderStroke(
                                width = 1.dp,
                                brush = Brush.verticalGradient(
                                    0.00f to Color.White.copy(alpha = rimTopAlpha),
                                    0.40f to Color.White.copy(alpha = rimMidAlpha),
                                    1.00f to Color.White.copy(alpha = rimBottomAlpha),
                                ),
                            ),
                            shape = cardShape,
                        ),
                )
            }
            }
        }

        // Desktop Floating Navigation Chevrons (Visible on hover, placed inside active card bounds)
        val chevronAlpha by animateFloatAsState(
            targetValue = if (isCarouselHovered && displayItems.size > 1) 1f else 0f,
            animationSpec = tween(200),
            label = "chevron_fade",
        )

        if (chevronAlpha > 0f && !isCompact) {
            val chevronButtonSize = 34.dp
            val chevronCardOffset = horizontalPeekPadding + 14.dp

            val leftInteractionSource = remember { MutableInteractionSource() }
            val isLeftHovered by leftInteractionSource.collectIsHoveredAsState()

            val rightInteractionSource = remember { MutableInteractionSource() }
            val isRightHovered by rightInteractionSource.collectIsHoveredAsState()

            val chevronShape = LocalDesktopButtonShapes.current.icon

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = topBuffer)
                    .height(cardHeight),
            ) {
                HeroCarouselChevronButton(
                    icon = Icons.Default.ChevronLeft,
                    contentDescription = "Previous Slide",
                    isHovered = isLeftHovered,
                    interactionSource = leftInteractionSource,
                    chevronAlpha = chevronAlpha,
                    buttonSize = chevronButtonSize,
                    shape = chevronShape,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = chevronCardOffset),
                    onClick = { navigateToPage(pagerState.currentPage - 1) },
                )

                HeroCarouselChevronButton(
                    icon = Icons.Default.ChevronRight,
                    contentDescription = "Next Slide",
                    isHovered = isRightHovered,
                    interactionSource = rightInteractionSource,
                    chevronAlpha = chevronAlpha,
                    buttonSize = chevronButtonSize,
                    shape = chevronShape,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = chevronCardOffset),
                    onClick = { navigateToPage(pagerState.currentPage + 1) },
                )
            }
        }

        // Bottom Page Indicator Dots / Dashes (Synced with infinite scroll index)
        if (displayItems.size > 1) {
            val currentActualIndex = HomeHeroCardsLayoutMath.safeWrapIndex(pagerState.currentPage, displayItems.size)
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 10.dp),
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

@Composable
private fun HeroCarouselChevronButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    isHovered: Boolean,
    interactionSource: MutableInteractionSource,
    chevronAlpha: Float,
    buttonSize: androidx.compose.ui.unit.Dp,
    shape: androidx.compose.ui.graphics.Shape,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .size(buttonSize)
            .graphicsLayer {
                alpha = chevronAlpha
                clip = true
                this.shape = shape
            }
            .shadow(
                elevation = 8.dp,
                shape = shape,
                spotColor = Color.Black.copy(alpha = 0.50f),
                ambientColor = Color.Transparent,
            )
            .clip(shape)
            .background(
                if (isHovered) Color(0xFF22222E).copy(alpha = 0.92f * chevronAlpha)
                else Color(0xFF14141A).copy(alpha = 0.78f * chevronAlpha),
            )
            .border(
                1.dp,
                if (isHovered) Color.White.copy(alpha = 0.45f * chevronAlpha)
                else Color.White.copy(alpha = 0.20f * chevronAlpha),
                shape,
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(20.dp),
        )
    }
}
