package com.lagradost.cloudstream3.desktop.ui.screens.home

import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.desktop.repo.HeroMeta
import com.lagradost.cloudstream3.desktop.repo.HeroRepository
import com.lagradost.cloudstream3.desktop.ui.DockPosition
import com.lagradost.cloudstream3.desktop.ui.LocalSafeArea
import com.lagradost.cloudstream3.desktop.ui.badges.DesktopBadgeComponents
import com.lagradost.cloudstream3.desktop.ui.components.HeroPrimaryCtaButton
import com.lagradost.cloudstream3.desktop.ui.components.collectIsKeyboardFocusedAsState
import com.lagradost.cloudstream3.desktop.ui.components.desktopInteractiveFocus
import com.lagradost.cloudstream3.desktop.ui.theme.AppearanceConfig
import com.lagradost.cloudstream3.desktop.ui.theme.LocalDesktopButtonShapes
import com.lagradost.cloudstream3.desktop.utils.ImageUtils
import com.lagradost.cloudstream3.desktop.utils.TitleUtils
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.common.storage.DesktopBookmark
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round
import kotlin.math.roundToInt

private val HeroStackEasing = CubicBezierEasing(0.22f, 1.0f, 0.36f, 1.0f)

@Composable
fun HomeHeroCardsCarousel(
    items: List<SearchResponse>,
    provider: MainAPI?,
    heroMetaMap: Map<String, HeroMeta>,
    @Suppress("UNUSED_PARAMETER") allBookmarks: Map<String, DesktopBookmark>,
    onPrefetchHeroItem: (MainAPI?, SearchResponse) -> Unit,
    onHeroBackgroundChanged: (String?) -> Unit,
    onItemClick: (SearchResponse, String?, Boolean) -> Unit,
) {
    if (items.isEmpty()) return

    val displayItems = remember(items) { items.take(12) }
    val totalItems = displayItems.size
    val autoSlideDelay by AppearanceConfig.heroAutoSlideDelaySeconds.collectAsState()

    var settledIndex by rememberSaveable { mutableIntStateOf(0) }
    val animatedPosition = remember(totalItems) {
        Animatable(settledIndex.coerceIn(0, maxOf(0, totalItems - 1)).toFloat())
    }
    val coroutineScope = rememberCoroutineScope()

    var isDragging by remember { mutableStateOf(false) }
    var dragAccumulator by remember { mutableFloatStateOf(0f) }
    var dragStartPos by remember { mutableFloatStateOf(0f) }
    var activeNavJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(totalItems) {
        val clamped = settledIndex.coerceIn(0, maxOf(0, totalItems - 1))
        settledIndex = clamped
        if (abs(animatedPosition.value - clamped.toFloat()) > 0.001f && !animatedPosition.isRunning) {
            animatedPosition.snapTo(clamped.toFloat())
        }
    }

    fun animateToPosition(targetFloat: Float, durationMillis: Int = 520) {
        if (totalItems <= 1) return
        val wrapped = HomeHeroCardsLayoutMath.safeWrapIndex(targetFloat.roundToInt(), totalItems)
        settledIndex = wrapped
        activeNavJob?.cancel()
        activeNavJob = coroutineScope.launch {
            animatedPosition.animateTo(
                targetValue = targetFloat,
                animationSpec = tween(durationMillis = durationMillis, easing = HeroStackEasing),
            )
            // Keep cursor normalized around 0..totalItems once settled to prevent float drift
            animatedPosition.snapTo(wrapped.toFloat())
        }
    }

    fun stepRelative(deltaSlides: Int) {
        if (totalItems <= 1) return
        val base = round(animatedPosition.value)
        animateToPosition(base + deltaSlides)
    }

    fun navigateToActualIndex(targetIndex: Int) {
        if (totalItems <= 1) return
        val base = round(animatedPosition.value)
        val delta = HomeHeroCardsLayoutMath.shortestCircularDelta(targetIndex, base, totalItems)
        animateToPosition(base + delta)
    }

    // Prioritize metadata prefetch center-outwards across all 4 peeking layers per side
    LaunchedEffect(settledIndex, displayItems) {
        if (displayItems.isEmpty()) return@LaunchedEffect
        val seenUrls = HashSet<String>(9)
        val relativeOffsets = intArrayOf(0, 1, -1, 2, -2, 3, -3, 4, -4)
        var fetchedCount = 0
        for (rel in relativeOffsets) {
            val actualIndex = HomeHeroCardsLayoutMath.safeWrapIndex(settledIndex + rel, displayItems.size)
            val targetItem = displayItems.getOrNull(actualIndex) ?: continue
            if (!seenUrls.add(targetItem.url)) continue
            if (!heroMetaMap.containsKey(targetItem.url)) {
                if (fetchedCount > 0) delay(90L)
                fetchedCount++
                onPrefetchHeroItem(provider, targetItem)
            }
        }
    }

    // Sync home ambient background blur with the active centered card
    LaunchedEffect(settledIndex, heroMetaMap, displayItems) {
        val currentItem = displayItems.getOrNull(settledIndex)
        val currentMeta = currentItem?.let { heroMetaMap[it.url] }
        val colorSourceUrl = currentMeta?.backdropUrl ?: provider?.fixUrlNull(currentItem?.posterUrl)
        onHeroBackgroundChanged(colorSourceUrl)
    }

    val carouselInteractionSource = remember { MutableInteractionSource() }
    val isCarouselHovered by carouselInteractionSource.collectIsHoveredAsState()
    var isCarouselFocused by remember { mutableStateOf(false) }

    LaunchedEffect(totalItems, autoSlideDelay, isDragging, isCarouselHovered, isCarouselFocused) {
        if (totalItems > 1 && autoSlideDelay > 0 && !isDragging && !isCarouselHovered && !isCarouselFocused) {
            while (isActive) {
                delay(autoSlideDelay * 1000L)
                if (!isDragging && !isCarouselHovered && !isCarouselFocused && !animatedPosition.isRunning) {
                    stepRelative(1)
                }
            }
        }
    }

    val windowInfo = LocalWindowInfo.current
    val density = LocalDensity.current
    val rawHeight = with(density) { windowInfo.containerSize.height.toDp() }
    val rawWidth = with(density) { windowInfo.containerSize.width.toDp() }
    val isWindowCompact = rawWidth < 680.dp

    val dockPosition by AppearanceConfig.dockPosition.collectAsState()
    val safeArea = LocalSafeArea.current
    val layoutDirection = LocalLayoutDirection.current
    val safeTop = safeArea.calculateTopPadding()
    val safeLeft = safeArea.calculateStartPadding(layoutDirection)
    val safeRight = safeArea.calculateEndPadding(layoutDirection)

    val effectiveStart = when {
        isWindowCompact -> safeLeft.coerceAtLeast(8.dp)
        dockPosition == DockPosition.LEFT -> 88.dp
        else -> safeLeft.coerceAtLeast(22.dp)
    }
    val effectiveEnd = when {
        isWindowCompact -> safeRight.coerceAtLeast(8.dp)
        dockPosition == DockPosition.RIGHT -> 88.dp
        else -> safeRight.coerceAtLeast(22.dp)
    }

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

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(totalHeroHeight)
            .onFocusChanged { isCarouselFocused = it.hasFocus }
            .hoverable(carouselInteractionSource),
    ) {
        val stageWidth = (maxWidth - effectiveStart - effectiveEnd).coerceAtLeast(260.dp)
        val isCompact = maxWidth < 680.dp

        val cardWidth = if (isCompact) {
            (stageWidth - 24.dp).coerceAtLeast(260.dp)
        } else {
            (stageWidth * 0.48f).coerceIn(500.dp, 860.dp)
        }

        val effectivePeeks = remember(totalItems, isCompact) {
            val maxCap = if (isCompact) 1 else HomeHeroCardsLayoutMath.MAX_PEEK_CARDS_PER_SIDE
            HomeHeroCardsLayoutMath.effectivePeeksPerSide(totalItems, maxCap)
        }

        val outerMargin = if (isCompact) 8.dp else 0.dp
        val availableSideDp = ((stageWidth - cardWidth) * 0.5f - outerMargin).coerceAtLeast(0.dp)
        val cardWidthPx = with(density) { cardWidth.toPx() }
        val availableSidePx = with(density) { availableSideDp.toPx() }
        val cardShape = RoundedCornerShape(20.dp)

        // Centered Stacked Deck Viewport aligned inside dock-safe shelf bounds
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = effectiveStart, end = effectiveEnd, top = topBuffer)
                .height(cardHeight)
                .pointerInput(totalItems, cardWidthPx) {
                    if (totalItems <= 1) return@pointerInput
                    val dragSpanPx = (cardWidthPx * 0.42f).coerceAtLeast(160f)
                    detectHorizontalDragGestures(
                        onDragStart = {
                            activeNavJob?.cancel()
                            isDragging = true
                            dragAccumulator = 0f
                            dragStartPos = animatedPosition.value
                        },
                        onDragEnd = {
                            isDragging = false
                            val dragTotal = dragAccumulator
                            dragAccumulator = 0f
                            val swipeThreshold = 45f
                            val targetSlide = when {
                                dragTotal < -swipeThreshold -> floor(dragStartPos).toInt() + 1
                                dragTotal > swipeThreshold -> ceil(dragStartPos).toInt() - 1
                                else -> round(dragStartPos).toInt()
                            }
                            animateToPosition(targetSlide.toFloat(), durationMillis = 440)
                        },
                        onDragCancel = {
                            isDragging = false
                            dragAccumulator = 0f
                            animateToPosition(round(dragStartPos), durationMillis = 360)
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            dragAccumulator += dragAmount
                            coroutineScope.launch {
                                animatedPosition.snapTo(animatedPosition.value - (dragAmount / dragSpanPx))
                            }
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            displayItems.forEachIndexed { index, item ->
                key(index, item.url) {
                    val isVisibleSlot by remember(index, totalItems, effectivePeeks) {
                        derivedStateOf {
                            val d = abs(
                                HomeHeroCardsLayoutMath.shortestCircularDelta(
                                    index = index,
                                    currentPosition = animatedPosition.value,
                                    totalItems = totalItems,
                                ),
                            )
                            totalItems <= 2 || d <= (effectivePeeks + 0.95f)
                        }
                    }

                    if (isVisibleSlot) {
                        val stackZIndex by remember(index, totalItems) {
                            derivedStateOf {
                                val d = abs(
                                    HomeHeroCardsLayoutMath.shortestCircularDelta(
                                        index = index,
                                        currentPosition = animatedPosition.value,
                                        totalItems = totalItems,
                                    ),
                                )
                                (200 - (d * 20f).toInt()).coerceAtLeast(1).toFloat()
                            }
                        }
                        val isCenterSlot by remember(index, totalItems) {
                            derivedStateOf {
                                val d = abs(
                                    HomeHeroCardsLayoutMath.shortestCircularDelta(
                                        index = index,
                                        currentPosition = animatedPosition.value,
                                        totalItems = totalItems,
                                    ),
                                )
                                d < 0.5f
                            }
                        }
                        val showCenterDetails by remember(index, totalItems) {
                            derivedStateOf {
                                val d = abs(
                                    HomeHeroCardsLayoutMath.shortestCircularDelta(
                                        index = index,
                                        currentPosition = animatedPosition.value,
                                        totalItems = totalItems,
                                    ),
                                )
                                d < 0.65f
                            }
                        }

                        val meta = heroMetaMap[item.url]
                        val posterUrl = provider?.fixUrlNull(item.posterUrl)

                        HeroStackedCard(
                            index = index,
                            totalItems = totalItems,
                            effectivePeeks = effectivePeeks,
                            item = item,
                            meta = meta,
                            rawPosterUrl = posterUrl,
                            cardWidth = cardWidth,
                            cardHeight = cardHeight,
                            cardWidthPx = cardWidthPx,
                            availableSidePx = availableSidePx,
                            cardShape = cardShape,
                            isCompact = isCompact,
                            stackZIndex = stackZIndex,
                            isCenterSlot = isCenterSlot,
                            showCenterDetails = showCenterDetails,
                            positionProvider = { animatedPosition.value },
                            onCardClick = {
                                if (!isDragging && abs(dragAccumulator) < 15f) {
                                    if (!isCenterSlot) {
                                        navigateToActualIndex(index)
                                    } else {
                                        onItemClick(item, meta?.backdropUrl, false)
                                    }
                                }
                            },
                            onWatchClick = {
                                if (isCenterSlot) {
                                    onItemClick(item, meta?.backdropUrl, false)
                                } else {
                                    navigateToActualIndex(index)
                                }
                            },
                        )
                    }
                }
            }

            // Desktop Hover / Focus Navigation Chevrons flanking active center card
            val chevronAlpha by animateFloatAsState(
                targetValue = if ((isCarouselHovered || isCarouselFocused) && totalItems > 1) 1f else 0f,
                animationSpec = tween(200),
                label = "chevron_fade",
            )

            if (chevronAlpha > 0f && !isCompact) {
                val chevronButtonSize = 34.dp
                val horizontalCardInset = ((stageWidth - cardWidth) * 0.5f + 14.dp).coerceAtLeast(14.dp)

                val leftInteractionSource = remember { MutableInteractionSource() }
                val isLeftHovered by leftInteractionSource.collectIsHoveredAsState()

                val rightInteractionSource = remember { MutableInteractionSource() }
                val isRightHovered by rightInteractionSource.collectIsHoveredAsState()

                val chevronShape = LocalDesktopButtonShapes.current.icon

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(300f),
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
                            .padding(start = horizontalCardInset),
                        onClick = { stepRelative(-1) },
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
                            .padding(end = horizontalCardInset),
                        onClick = { stepRelative(1) },
                    )
                }
            }
        }

        // Bottom Page Indicator Dashes aligned to the safe stage center
        if (totalItems > 1) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = effectiveStart, end = effectiveEnd, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(totalItems) { index ->
                    val isSelected = index == settledIndex
                    Box(
                        modifier = Modifier
                            .height(4.dp)
                            .width(if (isSelected) 22.dp else 6.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.35f),
                            )
                            .pointerHoverIcon(PointerIcon.Hand)
                            .focusProperties { canFocus = false }
                            .clickable { navigateToActualIndex(index) },
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroStackedCard(
    index: Int,
    totalItems: Int,
    effectivePeeks: Int,
    item: SearchResponse,
    meta: HeroMeta?,
    rawPosterUrl: String?,
    cardWidth: Dp,
    cardHeight: Dp,
    cardWidthPx: Float,
    availableSidePx: Float,
    cardShape: Shape,
    isCompact: Boolean,
    stackZIndex: Float,
    isCenterSlot: Boolean,
    showCenterDetails: Boolean,
    positionProvider: () -> Float,
    onCardClick: () -> Unit,
    onWatchClick: () -> Unit,
) {
    val backdropUrl = meta?.backdropUrl ?: rawPosterUrl
    val enhancedBackdrop = remember(backdropUrl) { ImageUtils.enhanceBackdropUrl(backdropUrl) }
    val shadowElevation = if (isCenterSlot) 22.dp else 10.dp

    Box(
        modifier = Modifier
            .zIndex(stackZIndex)
            .width(cardWidth)
            .height(cardHeight)
            .graphicsLayer {
                val signedDelta = HomeHeroCardsLayoutMath.shortestCircularDelta(
                    index = index,
                    currentPosition = positionProvider(),
                    totalItems = totalItems,
                )
                val absDelta = abs(signedDelta)
                this.translationX = HomeHeroCardsLayoutMath.calculateStackTranslationXPx(
                    signedOffset = signedDelta,
                    cardWidthPx = cardWidthPx,
                    availableSidePx = availableSidePx,
                    effectivePeeks = effectivePeeks,
                )
                val s = HomeHeroCardsLayoutMath.calculateScale(absDelta)
                this.scaleX = s
                this.scaleY = s
                this.alpha = HomeHeroCardsLayoutMath.calculateAlpha(
                    absOffset = absDelta,
                    effectivePeeks = effectivePeeks,
                    totalItems = totalItems,
                )
            }
            .shadow(
                elevation = shadowElevation,
                shape = cardShape,
                spotColor = Color.Black.copy(alpha = 0.72f),
                ambientColor = Color.Transparent,
            )
            .clip(cardShape)
            .background(Color(0xFF0F0F14))
            .pointerHoverIcon(PointerIcon.Hand)
            .focusProperties { canFocus = false }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onCardClick,
            ),
    ) {
        if (enhancedBackdrop != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalPlatformContext.current)
                    .data(enhancedBackdrop)
                    .crossfade(true)
                    .build(),
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val signedDelta = HomeHeroCardsLayoutMath.shortestCircularDelta(
                            index = index,
                            currentPosition = positionProvider(),
                            totalItems = totalItems,
                        )
                        this.scaleX = HomeHeroCardsLayoutMath.BACKDROP_PARALLAX_SCALE
                        this.scaleY = HomeHeroCardsLayoutMath.BACKDROP_PARALLAX_SCALE
                        this.translationX = HomeHeroCardsLayoutMath.calculateBackdropParallaxPx(
                            pageOffset = signedDelta,
                            cardWidthPx = cardWidthPx,
                        )
                    },
            )
        }

        // Depth-dimming veil driven in the draw layer to separate stacked slices cleanly
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val absDelta = abs(
                        HomeHeroCardsLayoutMath.shortestCircularDelta(
                            index = index,
                            currentPosition = positionProvider(),
                            totalItems = totalItems,
                        ),
                    )
                    alpha = HomeHeroCardsLayoutMath.calculateDimAlpha(absDelta)
                }
                .background(Color.Black),
        )

        if (showCenterDetails) {
            HeroCardDetailsOverlay(
                index = index,
                totalItems = totalItems,
                item = item,
                meta = meta,
                cardWidthPx = cardWidthPx,
                isCompact = isCompact,
                positionProvider = positionProvider,
                onWatchClick = onWatchClick,
            )
        }

        // Crisp border rim separates overlapping stacked cards against dark backdrops
        val rimTopAlpha = if (isCenterSlot) 0.28f else 0.22f
        val rimMidAlpha = if (isCenterSlot) 0.16f else 0.13f
        val rimBottomAlpha = if (isCenterSlot) 0.10f else 0.08f
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

@Composable
private fun HeroCardDetailsOverlay(
    index: Int,
    totalItems: Int,
    item: SearchResponse,
    meta: HeroMeta?,
    cardWidthPx: Float,
    isCompact: Boolean,
    positionProvider: () -> Float,
    onWatchClick: () -> Unit,
) {
    val logoUrl = meta?.logoUrl
    val displayTitle = meta?.title ?: HeroRepository.cleanHeroTitle(item.name)
    val displayPlot = remember(meta?.plot) {
        TitleUtils.cleanPlot(meta?.plot)?.replace(Regex("""\s*\n+\s*"""), " ")
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                val absDelta = abs(
                    HomeHeroCardsLayoutMath.shortestCircularDelta(
                        index = index,
                        currentPosition = positionProvider(),
                        totalItems = totalItems,
                    ),
                )
                alpha = HomeHeroCardsLayoutMath.calculateDetailsAlpha(absDelta)
            },
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

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = if (isCompact) 18.dp else 28.dp,
                    end = if (isCompact) 18.dp else 28.dp,
                    top = 16.dp,
                    bottom = 24.dp,
                ),
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.Start,
        ) {
            Box(
                modifier = Modifier.graphicsLayer {
                    val delta = HomeHeroCardsLayoutMath.shortestCircularDelta(index, positionProvider(), totalItems)
                    translationX = HomeHeroCardsLayoutMath.calculateForegroundParallaxPx(delta, cardWidthPx) * 0.75f
                },
            ) {
                if (!logoUrl.isNullOrBlank()) {
                    var logoFailed by remember(logoUrl) { mutableStateOf(false) }
                    if (!logoFailed) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalPlatformContext.current)
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
                        HeroTitleFallbackText(displayTitle = displayTitle, isCompact = isCompact)
                    }
                } else {
                    HeroTitleFallbackText(displayTitle = displayTitle, isCompact = isCompact)
                }
            }

            Spacer(Modifier.height(8.dp))

            Column(
                modifier = Modifier.graphicsLayer {
                    val delta = HomeHeroCardsLayoutMath.shortestCircularDelta(index, positionProvider(), totalItems)
                    translationX = HomeHeroCardsLayoutMath.calculateForegroundParallaxPx(delta, cardWidthPx)
                },
            ) {
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
                        val heroIsSeries = meta.type == com.lagradost.cloudstream3.TvType.TvSeries ||
                            meta.type == com.lagradost.cloudstream3.TvType.Anime ||
                            meta.type == com.lagradost.cloudstream3.TvType.AsianDrama
                        DesktopBadgeComponents.ContentRatingBadge(
                            rating = meta.contentRating,
                            isLarge = false,
                            isSeries = heroIsSeries,
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
                onClick = onWatchClick,
                shape = LocalDesktopButtonShapes.current.primary,
                contentPadding = PaddingValues(horizontal = 20.dp),
                iconSize = 18.dp,
                fontSize = 13.5.sp,
                iconSpacing = 5.dp,
                modifier = Modifier
                    .height(38.dp)
                    .graphicsLayer {
                        val delta = HomeHeroCardsLayoutMath.shortestCircularDelta(index, positionProvider(), totalItems)
                        translationX = HomeHeroCardsLayoutMath.calculateForegroundParallaxPx(delta, cardWidthPx) * 1.25f
                    },
            )
        }
    }
}

@Composable
private fun HeroTitleFallbackText(
    displayTitle: String,
    isCompact: Boolean,
) {
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

@Composable
private fun HeroCarouselChevronButton(
    icon: ImageVector,
    contentDescription: String,
    isHovered: Boolean,
    interactionSource: MutableInteractionSource,
    chevronAlpha: Float,
    buttonSize: Dp,
    shape: Shape,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val isFocused by interactionSource.collectIsKeyboardFocusedAsState()
    val isHighlighted = isHovered || isFocused
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
                if (isHighlighted) Color(0xFF22222E).copy(alpha = 0.92f * chevronAlpha)
                else Color(0xFF14141A).copy(alpha = 0.78f * chevronAlpha),
            )
            .border(
                1.dp,
                if (isHighlighted) Color.White.copy(alpha = 0.45f * chevronAlpha)
                else Color.White.copy(alpha = 0.20f * chevronAlpha),
                shape,
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .desktopInteractiveFocus(interactionSource = interactionSource, shape = shape)
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
