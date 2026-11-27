package com.lagradost.cloudstream3.desktop.ui.screens.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
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
import com.lagradost.cloudstream3.desktop.ui.components.LocalDesktopTheme
import com.lagradost.cloudstream3.desktop.ui.components.desktopInteractiveFocus
import com.lagradost.cloudstream3.desktop.ui.components.getTextShadow
import com.lagradost.cloudstream3.desktop.ui.theme.AppearanceConfig
import com.lagradost.cloudstream3.desktop.utils.ImageUtils
import com.lagradost.cloudstream3.desktop.utils.TitleUtils
import com.lagradost.cloudstream3.fixUrlNull
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private val SpotlightTransitionEasing = CubicBezierEasing(0.22f, 1.0f, 0.36f, 1.0f)

@Composable
internal fun HomeHeroGlassSpotlightStack(
    items: List<SearchResponse>,
    provider: MainAPI?,
    heroMetaMap: Map<String, HeroMeta>,
    onPrefetchHeroItem: (MainAPI?, SearchResponse) -> Unit,
    onHeroBackgroundChanged: (String?) -> Unit,
    onItemClick: (SearchResponse, String?, Boolean) -> Unit,
) {
    if (items.isEmpty()) return

    val displayItems = remember(items) { items.take(10) }
    val autoSlideDelay by AppearanceConfig.heroAutoSlideDelaySeconds.collectAsState()
    val performanceModeEnabled by AppearanceConfig.performanceModeEnabled.collectAsState()
    val dockPosition by AppearanceConfig.dockPosition.collectAsState()
    val theme = LocalDesktopTheme.current
    val isLightMode = theme.isLightMode
    val isAmoled = theme.isAmoled

    var currentIndex by rememberSaveable { mutableIntStateOf(0) }
    var userInteractionTrigger by remember { mutableIntStateOf(0) }
    var isSpotlightFocused by remember { mutableStateOf(false) }

    LaunchedEffect(displayItems.size) {
        if (currentIndex >= displayItems.size) {
            currentIndex = 0
        }
    }

    LaunchedEffect(displayItems.size, autoSlideDelay, userInteractionTrigger, isSpotlightFocused) {
        if (displayItems.size > 1 && autoSlideDelay > 0 && !isSpotlightFocused) {
            while (isActive) {
                delay(autoSlideDelay * 1000L)
                if (!isSpotlightFocused) {
                    currentIndex = (currentIndex + 1) % displayItems.size
                }
            }
        }
    }

    LaunchedEffect(currentIndex, displayItems) {
        if (displayItems.isEmpty()) return@LaunchedEffect
        val offsets = intArrayOf(0, 1, 2, 3, 4, -1)
        var fetched = 0
        for (rel in offsets) {
            val idx = HomeHeroCardsLayoutMath.safeWrapIndex(currentIndex + rel, displayItems.size)
            val target = displayItems.getOrNull(idx) ?: continue
            if (!heroMetaMap.containsKey(target.url)) {
                if (fetched > 0) delay(90L)
                fetched++
                onPrefetchHeroItem(provider, target)
            }
        }
    }

    LaunchedEffect(currentIndex, heroMetaMap, displayItems) {
        val active = displayItems.getOrNull(currentIndex)
        val meta = active?.let { heroMetaMap[it.url] }
        onHeroBackgroundChanged(meta?.backdropUrl ?: provider?.fixUrlNull(active?.posterUrl))
    }

    val windowInfo = LocalWindowInfo.current
    val density = LocalDensity.current
    val rawWidth = with(density) { windowInfo.containerSize.width.toDp() }
    val rawHeight = with(density) { windowInfo.containerSize.height.toDp() }
    val heroHeight = HomeHeroSpotlightStackMath.calculateHeroHeight(rawWidth, rawHeight)

    // Local HazeState isolated between the hero backdrop layer and the frosted glass card prevents ancestor capture cycles.
    val heroBackdropHazeState = remember { HazeState() }
    val safeArea = LocalSafeArea.current
    val safeBottom = safeArea.calculateBottomPadding()

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(heroHeight)
            .onFocusChanged { isSpotlightFocused = it.hasFocus },
    ) {
        val rootWidth = maxWidth
        val isCompact = rootWidth < 760.dp
        val rowHeight = HomeHeroSpotlightStackMath.calculateRowHeight(heroHeight, isCompact)
        val paddingStart = when {
            isCompact -> 16.dp
            dockPosition == DockPosition.LEFT -> 98.dp
            else -> 40.dp
        }
        val paddingEnd = when {
            isCompact -> 16.dp
            dockPosition == DockPosition.RIGHT -> 98.dp
            else -> 40.dp
        }

        val activeItem = displayItems.getOrNull(currentIndex) ?: return@BoxWithConstraints
        val activeMeta = heroMetaMap[activeItem.url]
        val activeBackdrop = remember(activeItem.url, activeMeta?.backdropUrl, activeItem.posterUrl) {
            val rawBg = activeMeta?.backdropUrl ?: provider?.fixUrlNull(activeItem.posterUrl)
            ImageUtils.enhanceBackdropUrl(rawBg)
        }
        val isBackdropNull = activeMeta?.backdropUrl == null

        // Layer 1: Full-Bleed Atmospheric Backdrop Source
        Box(
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(state = heroBackdropHazeState),
        ) {
            Crossfade(
                targetState = activeBackdrop,
                animationSpec = tween(durationMillis = 700, easing = SpotlightTransitionEasing),
                label = "spotlight_backdrop_crossfade",
                modifier = Modifier.fillMaxSize(),
            ) { targetUrl ->
                if (targetUrl != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalPlatformContext.current)
                            .data(targetUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = activeItem.name,
                        contentScale = ContentScale.Crop,
                        filterQuality = FilterQuality.High,
                        alignment = Alignment.TopCenter,
                        modifier = Modifier
                            .fillMaxSize()
                            .then(if (isBackdropNull && !performanceModeEnabled) Modifier.blur(20.dp) else Modifier)
                            .graphicsLayer { alpha = 0.99f }
                            .drawWithCache {
                                val fadeBase = if (isLightMode) Color.Transparent else Color.Black
                                val bottomMelt = Brush.verticalGradient(
                                    0.00f to Color.Black,
                                    0.84f to Color.Black,
                                    0.94f to Color.Black.copy(alpha = 0.42f),
                                    1.00f to Color.Transparent,
                                )
                                val subtleLowerScrim = Brush.verticalGradient(
                                    0.00f to Color.Transparent,
                                    0.45f to Color.Transparent,
                                    0.78f to fadeBase.copy(alpha = if (isLightMode) 0f else 0.42f),
                                    1.00f to fadeBase.copy(alpha = if (isLightMode) 0f else 0.78f),
                                )
                                val dockEdgeVignette = if (!isCompact && dockPosition == DockPosition.LEFT) {
                                    Brush.horizontalGradient(
                                        0.00f to fadeBase.copy(alpha = 0.48f),
                                        0.16f to Color.Transparent,
                                        1.00f to Color.Transparent,
                                    )
                                } else {
                                    null
                                }
                                onDrawWithContent {
                                    drawContent()
                                    drawRect(subtleLowerScrim)
                                    dockEdgeVignette?.let { drawRect(it) }
                                    drawRect(bottomMelt, blendMode = BlendMode.DstIn)
                                }
                            },
                    )
                }
            }
        }

        // Layer 2: Equal-Height Split Spotlight Row & Header Controls
        val maxQueueCount = if (rootWidth >= 1150.dp) 5 else 4
        val queuedIndices = remember(currentIndex, displayItems.size, maxQueueCount) {
            HomeHeroSpotlightStackMath.queueIndices(currentIndex, displayItems.size, maxQueueCount = maxQueueCount)
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(
                    start = paddingStart,
                    end = paddingEnd,
                    bottom = if (isCompact) 20.dp else safeBottom + 36.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Section Title + Subtle Indicator & Prev/Next Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Featured Spotlight",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        shadow = getTextShadow(),
                    ),
                    color = if (isLightMode) theme.TextPrimary else Color.White.copy(alpha = 0.92f),
                )

                if (displayItems.size > 1) {
                    SpotlightControlsRow(
                        totalItems = displayItems.size,
                        currentIndex = currentIndex,
                        isLightMode = isLightMode,
                        onSelectIndex = { targetIdx ->
                            currentIndex = targetIdx
                            userInteractionTrigger++
                        },
                        onPrev = {
                            currentIndex = HomeHeroCardsLayoutMath.safeWrapIndex(currentIndex - 1, displayItems.size)
                            userInteractionTrigger++
                        },
                        onNext = {
                            currentIndex = (currentIndex + 1) % displayItems.size
                            userInteractionTrigger++
                        },
                    )
                }
            }

            if (isCompact) {
                FrostedSpotlightCard(
                    item = activeItem,
                    meta = activeMeta,
                    currentIndex = currentIndex,
                    isCompact = true,
                    isLightMode = isLightMode,
                    isAmoled = isAmoled,
                    performanceModeEnabled = performanceModeEnabled,
                    hazeState = heroBackdropHazeState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(rowHeight),
                    onClick = { onItemClick(activeItem, activeMeta?.backdropUrl, false) },
                )
            } else {
                val posterWidth = HomeHeroSpotlightStackMath.calculatePosterWidth(rowHeight)
                val rowGap = 24.dp
                val usableWidth = (rootWidth - paddingStart - paddingEnd - rowGap).coerceAtLeast(400.dp)
                val maxStackAllocation = (usableWidth * 0.54f).coerceAtLeast(posterWidth)
                val step = HomeHeroSpotlightStackMath.calculateQueueStep(
                    posterWidth = posterWidth,
                    visibleQueueCount = queuedIndices.size,
                    availableStackWidth = maxStackAllocation,
                )
                val stackWidth = HomeHeroSpotlightStackMath.calculateTotalStackWidth(
                    posterWidth = posterWidth,
                    visibleQueueCount = queuedIndices.size,
                    step = step,
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(rowHeight),
                    horizontalArrangement = Arrangement.spacedBy(rowGap),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    FrostedSpotlightCard(
                        item = activeItem,
                        meta = activeMeta,
                        currentIndex = currentIndex,
                        isCompact = false,
                        isLightMode = isLightMode,
                        isAmoled = isAmoled,
                        performanceModeEnabled = performanceModeEnabled,
                        hazeState = heroBackdropHazeState,
                        modifier = Modifier
                            .weight(1f)
                            .height(rowHeight),
                        onClick = { onItemClick(activeItem, activeMeta?.backdropUrl, false) },
                    )

                    if (queuedIndices.isNotEmpty() && stackWidth > 0.dp) {
                        SpotlightPosterStack(
                            displayItems = displayItems,
                            queuedIndices = queuedIndices,
                            provider = provider,
                            posterWidth = posterWidth,
                            rowHeight = rowHeight,
                            stackWidth = stackWidth,
                            step = step,
                            performanceModeEnabled = performanceModeEnabled,
                            onPosterSelect = { targetIdx ->
                                currentIndex = targetIdx
                                userInteractionTrigger++
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FrostedSpotlightCard(
    item: SearchResponse,
    meta: HeroMeta?,
    currentIndex: Int,
    isCompact: Boolean,
    isLightMode: Boolean,
    isAmoled: Boolean,
    performanceModeEnabled: Boolean,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val theme = LocalDesktopTheme.current
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isFocused by interactionSource.collectIsFocusedAsState()
    val isActiveVisual = isHovered || isFocused
    val cardShape = RoundedCornerShape(20.dp)

    val frostBase = when {
        isLightMode -> theme.SurfaceElevated
        isAmoled -> Color(0xFF0A0A0D)
        else -> Color(0xFF0B0E16)
    }

    val hazeModifier = if (!performanceModeEnabled && !isAmoled) {
        Modifier.hazeEffect(
            state = hazeState,
            style = HazeStyle(
                backgroundColor = frostBase.copy(alpha = if (isLightMode) 0.72f else 0.56f),
                tint = HazeTint(frostBase.copy(alpha = if (isLightMode) 0.68f else 0.54f)),
                blurRadius = 26.dp,
                noiseFactor = 0f,
            ),
        )
    } else {
        Modifier
    }

    val borderBrush = Brush.linearGradient(
        colors = if (isLightMode) {
            listOf(
                Color.Black.copy(alpha = if (isActiveVisual) 0.28f else 0.14f),
                Color.Black.copy(alpha = 0.05f),
            )
        } else {
            listOf(
                Color.White.copy(alpha = if (isActiveVisual) 0.42f else 0.28f),
                Color.White.copy(alpha = if (isActiveVisual) 0.18f else 0.09f),
            )
        },
    )

    // Avoid Modifier.shadow() on translucent glass containers: Skia renders drop-shadow boxes behind/through alpha fills.
    Box(
        modifier = modifier
            .clip(cardShape)
            .then(hazeModifier)
            .background(
                frostBase.copy(
                    alpha = when {
                        !performanceModeEnabled && !isAmoled -> if (isLightMode) 0.44f else 0.48f
                        else -> if (isLightMode) 0.90f else 0.82f
                    },
                ),
            )
            .border(BorderStroke(1.dp, borderBrush), cardShape)
            .pointerHoverIcon(PointerIcon.Hand)
            .hoverable(interactionSource)
            .desktopInteractiveFocus(interactionSource = interactionSource, shape = cardShape)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
    ) {
        AnimatedContent(
            targetState = Triple(currentIndex, item, meta),
            transitionSpec = {
                fadeIn(tween(320, easing = SpotlightTransitionEasing)) togetherWith
                    fadeOut(tween(220, easing = SpotlightTransitionEasing))
            },
            label = "spotlight_card_content",
            modifier = Modifier.fillMaxSize(),
        ) { (_, targetItem, targetMeta) ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = if (isCompact) 18.dp else 28.dp, vertical = if (isCompact) 14.dp else 20.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                val scoreValue = targetMeta?.score?.toDoubleOrNull()?.takeIf { it > 0.0 }
                val ratingBadge = targetMeta?.contentRating?.takeIf { it.isNotBlank() }
                Box(modifier = Modifier.height(32.dp), contentAlignment = Alignment.CenterStart) {
                    if (scoreValue != null || ratingBadge != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (scoreValue != null) {
                                DesktopBadgeComponents.RatingGoldBadge(rating = scoreValue)
                            }
                            if (ratingBadge != null) {
                                DesktopBadgeComponents.ContentRatingBadge(
                                    rating = ratingBadge,
                                    isLarge = false,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))

                HeroLogoOrTitle(
                    item = targetItem,
                    meta = targetMeta,
                    isCompact = isCompact,
                    logoMinWidth = 110.dp,
                    logoMaxWidth = if (isCompact) 220.dp else 480.dp,
                    logoMaxHeight = if (isCompact) 48.dp else 82.dp,
                    fallbackFontSize = if (isCompact) 24.sp else 38.sp,
                    alignment = Alignment.CenterStart,
                )

                val metaParts = remember(targetMeta) {
                    buildList {
                        targetMeta?.year?.let { add(it.toString()) }
                        targetMeta?.duration?.takeIf { it > 0 }?.let { add(formatSpotlightDuration(it)) }
                        targetMeta?.tags?.filter { it.isNotBlank() }?.distinct()?.take(3)?.forEach { add(it) }
                    }
                }
                if (metaParts.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = metaParts.joinToString("  •  "),
                        color = if (isLightMode) theme.TextMuted else Color.White.copy(alpha = 0.80f),
                        fontSize = if (isCompact) 12.5.sp else 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                val displayPlot = remember(targetMeta?.plot) {
                    TitleUtils.cleanPlot(targetMeta?.plot)
                        ?.replace(Regex("""\s*\n+\s*"""), " ")
                }
                if (!displayPlot.isNullOrBlank()) {
                    Spacer(Modifier.height(7.dp))
                    Text(
                        text = displayPlot,
                        color = if (isLightMode) theme.TextPrimary.copy(alpha = 0.88f) else Color.White.copy(alpha = 0.86f),
                        fontSize = if (isCompact) 13.sp else 14.5.sp,
                        lineHeight = if (isCompact) 18.sp else 21.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun SpotlightPosterStack(
    displayItems: List<SearchResponse>,
    queuedIndices: List<Int>,
    provider: MainAPI?,
    posterWidth: Dp,
    rowHeight: Dp,
    stackWidth: Dp,
    step: Dp,
    performanceModeEnabled: Boolean,
    onPosterSelect: (Int) -> Unit,
) {
    val posterShape: Shape = RoundedCornerShape(14.dp)

    Box(
        modifier = Modifier
            .width(stackWidth)
            .height(rowHeight),
        contentAlignment = Alignment.BottomStart,
    ) {
        queuedIndices.forEachIndexed { queuePos, slideIdx ->
            val queuedItem = displayItems.getOrNull(slideIdx) ?: return@forEachIndexed
            key("${queuedItem.url}_$slideIdx") {
                val targetOffsetX = (step.value * queuePos).dp
                val targetScale = HomeHeroSpotlightStackMath.queueScaleForPosition(queuePos)
                val targetDim = HomeHeroSpotlightStackMath.queueDimAlphaForPosition(queuePos)
                val targetOpacity = HomeHeroSpotlightStackMath.queueOpacityForPosition(queuePos)
                val targetShadowAlpha = HomeHeroSpotlightStackMath.queueShadowAlphaForPosition(queuePos)
                val zVal = HomeHeroSpotlightStackMath.queueZIndexForPosition(queuePos)

                val animOffsetX by animateDpAsState(
                    targetValue = targetOffsetX,
                    animationSpec = tween(460, easing = SpotlightTransitionEasing),
                    label = "poster_stack_x",
                )
                val animScale by animateFloatAsState(
                    targetValue = targetScale,
                    animationSpec = tween(460, easing = SpotlightTransitionEasing),
                    label = "poster_stack_scale",
                )
                val animDim by animateFloatAsState(
                    targetValue = targetDim,
                    animationSpec = tween(420, easing = SpotlightTransitionEasing),
                    label = "poster_stack_dim",
                )
                val animOpacity by animateFloatAsState(
                    targetValue = targetOpacity,
                    animationSpec = tween(420, easing = SpotlightTransitionEasing),
                    label = "poster_stack_alpha",
                )
                val animShadowAlpha by animateFloatAsState(
                    targetValue = targetShadowAlpha,
                    animationSpec = tween(420, easing = SpotlightTransitionEasing),
                    label = "poster_stack_shadow",
                )

                val interactionSource = remember { MutableInteractionSource() }
                val isHovered by interactionSource.collectIsHoveredAsState()
                val isFocused by interactionSource.collectIsFocusedAsState()
                val isActiveVisual = isHovered || isFocused
                val posterUrl = provider?.fixUrlNull(queuedItem.posterUrl)

                Box(
                    modifier = Modifier
                        .zIndex(zVal)
                        .offset { IntOffset(animOffsetX.roundToPx(), 0) }
                        .width(posterWidth)
                        .height(rowHeight),
                ) {
                    if (!performanceModeEnabled) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .offset(x = 8.dp, y = 4.dp)
                                .blur(22.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
                                .background(
                                    Color.Black.copy(alpha = (0.46f * animShadowAlpha * animOpacity).coerceIn(0f, 1f)),
                                    posterShape,
                                ),
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .offset(x = 3.dp, y = 2.dp)
                                .blur(10.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
                                .background(
                                    Color.Black.copy(alpha = (0.36f * animShadowAlpha * animOpacity).coerceIn(0f, 1f)),
                                    posterShape,
                                ),
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = animScale
                                scaleY = animScale
                                alpha = animOpacity
                            }
                            .then(
                                if (performanceModeEnabled) {
                                    Modifier.shadow(
                                        elevation = HomeHeroSpotlightStackMath.queueElevationForPosition(queuePos).dp,
                                        shape = posterShape,
                                        spotColor = Color.Black.copy(alpha = 0.85f),
                                        ambientColor = Color.Black.copy(alpha = 0.65f),
                                    )
                                } else {
                                    Modifier
                                },
                            )
                            .clip(posterShape)
                            .background(Color(0xFF141722))
                            .border(
                                width = if (isActiveVisual) 1.5.dp else 1.dp,
                                color = when {
                                    isActiveVisual -> Color.White.copy(alpha = 0.55f)
                                    queuePos == 0 -> Color.White.copy(alpha = 0.24f)
                                    else -> Color.White.copy(alpha = 0.12f)
                                },
                                shape = posterShape,
                            )
                            .pointerHoverIcon(PointerIcon.Hand)
                            .hoverable(interactionSource)
                            .desktopInteractiveFocus(interactionSource = interactionSource, shape = posterShape)
                            .clickable(interactionSource = interactionSource, indication = null) {
                                onPosterSelect(slideIdx)
                            },
                    ) {
                        if (posterUrl != null) {
                            AsyncImage(
                                model = ImageRequest.Builder(LocalPlatformContext.current)
                                    .data(posterUrl)
                                    .crossfade(true)
                                    .build(),
                                contentDescription = queuedItem.name,
                                contentScale = ContentScale.Crop,
                                filterQuality = FilterQuality.Medium,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Box(
                                modifier = Modifier.fillMaxSize().padding(12.dp),
                                contentAlignment = Alignment.BottomStart,
                            ) {
                                Text(
                                    text = HeroRepository.cleanHeroTitle(queuedItem.name),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }

                        if (animDim > 0.01f && !isActiveVisual) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = animDim)),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SpotlightControlsRow(
    totalItems: Int,
    currentIndex: Int,
    isLightMode: Boolean,
    onSelectIndex: (Int) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    val fgColor = if (isLightMode) LocalDesktopTheme.current.TextPrimary else Color.White

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(totalItems) { index ->
                val isSelected = index == currentIndex
                val dashW by animateDpAsState(
                    targetValue = if (isSelected) 22.dp else 6.dp,
                    animationSpec = tween(260, easing = SpotlightTransitionEasing),
                    label = "spotlight_dash_w",
                )
                Box(
                    modifier = Modifier
                        .height(18.dp)
                        .focusProperties { canFocus = false }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onSelectIndex(index) }
                        .padding(horizontal = 2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .width(dashW)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    fgColor.copy(alpha = 0.32f)
                                },
                            ),
                    )
                }
            }
        }

        HeroStepControlsPill(
            isLightMode = isLightMode,
            onPrev = onPrev,
            onNext = onNext,
        )
    }
}

private fun formatSpotlightDuration(duration: Int): String {
    val mins = if (duration > 360) duration / 60 else duration
    return if (mins >= 60) {
        val h = mins / 60
        val m = mins % 60
        if (m > 0) "${h}h ${m}m" else "${h}h"
    } else {
        "${mins}m"
    }
}
