package com.lagradost.cloudstream3.desktop.ui.screens.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.desktop.repo.HeroMeta
import com.lagradost.cloudstream3.desktop.ui.DockPosition
import com.lagradost.cloudstream3.desktop.ui.LocalSafeArea
import com.lagradost.cloudstream3.desktop.ui.components.LocalDesktopTheme
import com.lagradost.cloudstream3.desktop.ui.theme.AppearanceConfig
import com.lagradost.cloudstream3.desktop.ui.theme.HeroBannerStyle
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.common.storage.DesktopBookmark
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

data class HeroCarouselDimensions(
    val isCompact: Boolean,
    val dynamicHeight: Dp,
    val backdropHeight: Dp,
)

data class HeroLayoutPaddings(
    val paddingStart: Dp,
    val paddingEnd: Dp,
    val thumbnailHeight: Dp,
    val thumbnailsMaxWidth: Dp,
    val infoBlockBottomPadding: Dp,
    val infoBlockMaxWidth: Dp,
)

fun calculateHeroDimensions(
    rawWidth: Dp,
    rawHeight: Dp,
    heroBannerStyle: HeroBannerStyle,
): HeroCarouselDimensions {
    val isWindowCompact = rawWidth < 600.dp
    val dynamicHeight = if (isWindowCompact) {
        460.dp
    } else if (heroBannerStyle == HeroBannerStyle.CINEMA_PEEKING) {
        (rawHeight * 0.72f).coerceIn(480.dp, 680.dp)
    } else {
        rawHeight.coerceAtLeast(400.dp)
    }
    val backdropHeight = if (heroBannerStyle == HeroBannerStyle.CINEMA_PEEKING) {
        (dynamicHeight + 380.dp).coerceAtLeast(rawHeight)
    } else {
        rawHeight
    }
    return HeroCarouselDimensions(
        isCompact = isWindowCompact,
        dynamicHeight = dynamicHeight,
        backdropHeight = backdropHeight,
    )
}

fun calculateHeroLayoutPaddings(
    isCompact: Boolean,
    dockPosition: DockPosition,
    safeBottom: Dp,
    heroBannerStyle: HeroBannerStyle,
    maxWidth: Dp,
    maxHeight: Dp,
): HeroLayoutPaddings {
    val paddingStart = if (isCompact) {
        12.dp
    } else if (dockPosition == DockPosition.LEFT) {
        98.dp
    } else {
        32.dp
    }

    val paddingEnd = if (isCompact) {
        12.dp
    } else if (dockPosition == DockPosition.RIGHT) {
        98.dp
    } else {
        32.dp
    }

    val thumbnailHeight = (maxHeight * 0.22f).coerceIn(160.dp, 280.dp)
    val thumbnailsMaxWidth = if (isCompact) maxWidth - paddingStart - paddingEnd else maxWidth * 0.55f

    val infoBlockBottomPadding = if (isCompact) {
        16.dp
    } else if (heroBannerStyle == HeroBannerStyle.CINEMA_PEEKING) {
        safeBottom + 36.dp
    } else {
        safeBottom + (maxHeight * 0.05f) + thumbnailHeight + 48.dp
    }

    val infoBlockMaxWidth = if (isCompact) {
        (maxWidth - paddingStart - paddingEnd).coerceAtLeast(200.dp)
    } else {
        (maxWidth * 0.45f).coerceIn(400.dp, 750.dp)
    }

    return HeroLayoutPaddings(
        paddingStart = paddingStart,
        paddingEnd = paddingEnd,
        thumbnailHeight = thumbnailHeight,
        thumbnailsMaxWidth = thumbnailsMaxWidth,
        infoBlockBottomPadding = infoBlockBottomPadding,
        infoBlockMaxWidth = infoBlockMaxWidth,
    )
}

@Composable
fun HomeHeroCarousel(
    items: List<SearchResponse>,
    provider: MainAPI?,
    heroMetaMap: Map<String, HeroMeta>,
    allBookmarks: Map<String, DesktopBookmark>,
    onPrefetchHeroItem: (MainAPI?, SearchResponse) -> Unit,
    onHeroBackgroundChanged: (String?) -> Unit,
    onItemClick: (SearchResponse, String?, Boolean) -> Unit,
) {
    if (items.isEmpty()) return

    val displayItems = remember(items) { items.take(6) }

    val autoSlideDelay by AppearanceConfig.heroAutoSlideDelaySeconds.collectAsState()
    val heroBannerStyle by AppearanceConfig.heroBannerStyle.collectAsState()
    var currentIndex by rememberSaveable {
        mutableIntStateOf(0)
    }
    var userInteractionTrigger by remember { mutableIntStateOf(0) }
    var heroHasFocus by remember { mutableStateOf(false) }

    LaunchedEffect(displayItems.size) {
        if (displayItems.isEmpty() || currentIndex >= displayItems.size) {
            currentIndex = 0
        }
    }

    LaunchedEffect(displayItems.size, autoSlideDelay, userInteractionTrigger, heroHasFocus) {
        if (displayItems.isNotEmpty() && autoSlideDelay > 0 && !heroHasFocus) {
            while (isActive) {
                delay(autoSlideDelay * 1000L)
                currentIndex = (currentIndex + 1) % displayItems.size
            }
        }
    }

    LaunchedEffect(items) {
        // Eagerly prefetch only the first 2 slides to keep startup lightweight
        items.take(2).forEachIndexed { index, item ->
            if (index > 0) delay(400L)
            onPrefetchHeroItem(provider, item)
        }
    }

    // Lazily prefetch the next upcoming slide as carousel advances
    LaunchedEffect(currentIndex, items) {
        if (items.isNotEmpty()) {
            val nextIndex = (currentIndex + 1) % items.size
            items.getOrNull(nextIndex)?.let { nextItem ->
                if (!heroMetaMap.containsKey(nextItem.url)) {
                    onPrefetchHeroItem(provider, nextItem)
                }
            }
        }
    }

    LaunchedEffect(currentIndex) {
        val currentItem = displayItems.getOrNull(currentIndex)
        val currentMeta = currentItem?.let { heroMetaMap[it.url] }
        val colorSourceUrl = currentMeta?.backdropUrl ?: provider?.fixUrlNull(currentItem?.posterUrl)
        onHeroBackgroundChanged(colorSourceUrl)
    }

    val windowInfo = LocalWindowInfo.current
    val density = LocalDensity.current
    val rawHeight = with(density) { windowInfo.containerSize.height.toDp() }
    val rawWidth = with(density) { windowInfo.containerSize.width.toDp() }
    val dimensions = calculateHeroDimensions(rawWidth, rawHeight, heroBannerStyle)
    val dynamicHeight = dimensions.dynamicHeight
    val backdropHeight = dimensions.backdropHeight

    val isLightMode = LocalDesktopTheme.current.isLightMode
    val dockPosition by AppearanceConfig.dockPosition.collectAsState()

    if (heroBannerStyle == HeroBannerStyle.CARDS_CAROUSEL) {
        HomeHeroCardsCarousel(
            items = items,
            provider = provider,
            heroMetaMap = heroMetaMap,
            allBookmarks = allBookmarks,
            onPrefetchHeroItem = onPrefetchHeroItem,
            onHeroBackgroundChanged = onHeroBackgroundChanged,
            onItemClick = onItemClick,
        )
        return
    }

    if (heroBannerStyle == HeroBannerStyle.GLASS_SPOTLIGHT_STACK) {
        HomeHeroGlassSpotlightStack(
            items = items,
            provider = provider,
            heroMetaMap = heroMetaMap,
            onPrefetchHeroItem = onPrefetchHeroItem,
            onHeroBackgroundChanged = onHeroBackgroundChanged,
            onItemClick = onItemClick,
        )
        return
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(dynamicHeight)
            .onFocusChanged { heroHasFocus = it.hasFocus }
            .graphicsLayer { clip = false },
    ) {
        val safeArea = LocalSafeArea.current
        val safeBottom = safeArea.calculateBottomPadding()
        val isCompact = maxWidth < 600.dp
        val autoAdvanceIntervalMs = autoSlideDelay * 1000L

        val layoutPaddings = calculateHeroLayoutPaddings(
            isCompact = isCompact,
            dockPosition = dockPosition,
            safeBottom = safeBottom,
            heroBannerStyle = heroBannerStyle,
            maxWidth = maxWidth,
            maxHeight = maxHeight,
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .wrapContentHeight(Alignment.Top, unbounded = true),
        ) {
            AnimatedContent(
                targetState = currentIndex,
                transitionSpec = {
                    fadeIn(animationSpec = tween(1000)) togetherWith fadeOut(animationSpec = tween(1000))
                },
                modifier = Modifier.fillMaxWidth().requiredHeight(backdropHeight),
                label = "hero_fade",
            ) { page ->
                val item = displayItems.getOrNull(page)
                    ?: displayItems.getOrNull(currentIndex)
                    ?: displayItems.firstOrNull()
                    ?: return@AnimatedContent
                val posterUrl = provider?.fixUrlNull(item.posterUrl)
                val meta = heroMetaMap[item.url]
                val ambientBg = meta?.backdropUrl ?: posterUrl

                Box(modifier = Modifier.fillMaxSize()) {
                    HeroBackdropLayer(
                        ambientBg = ambientBg,
                        isBackdropNull = meta?.backdropUrl == null,
                        isLightMode = isLightMode,
                        heroBannerStyle = heroBannerStyle,
                        backdropHeight = backdropHeight,
                        dynamicHeight = dynamicHeight,
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .requiredHeight(dynamicHeight)
                            .align(Alignment.TopStart)
                            .padding(bottom = layoutPaddings.infoBlockBottomPadding),
                        contentAlignment = Alignment.BottomStart,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = layoutPaddings.paddingStart, end = layoutPaddings.paddingEnd),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Start,
                        ) {
                            Column(
                                modifier = Modifier.widthIn(max = layoutPaddings.infoBlockMaxWidth),
                            ) {
                                HeroMetadataLayer(
                                    item = item,
                                    meta = meta,
                                    isCompact = isCompact,
                                    heroBannerStyle = heroBannerStyle,
                                )

                                HeroActionButtons(
                                    item = item,
                                    meta = meta,
                                    isCompact = isCompact,
                                    displayItemsCount = displayItems.size,
                                    currentIndex = currentIndex,
                                    onSelect = { targetItem, backdrop ->
                                        onItemClick(targetItem, backdrop, false)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        if (!isCompact && heroBannerStyle == HeroBannerStyle.THUMBNAIL_STRIP) {
            HeroFilmstrip(
                displayItems = displayItems,
                provider = provider,
                heroMetaMap = heroMetaMap,
                currentIndex = currentIndex,
                userInteractionTrigger = userInteractionTrigger,
                thumbnailHeight = layoutPaddings.thumbnailHeight,
                thumbnailsMaxWidth = layoutPaddings.thumbnailsMaxWidth,
                paddingEnd = layoutPaddings.paddingEnd,
                safeBottom = safeBottom,
                autoAdvanceIntervalMs = autoAdvanceIntervalMs,
                onSelectThumb = { selectedIdx ->
                    currentIndex = selectedIdx
                    userInteractionTrigger++
                },
            )
        } else {
            HeroDashes(
                displayItems = displayItems,
                currentIndex = currentIndex,
                userInteractionTrigger = userInteractionTrigger,
                isCompact = isCompact,
                paddingEnd = layoutPaddings.paddingEnd,
                safeBottom = safeBottom,
                autoAdvanceIntervalMs = autoAdvanceIntervalMs,
                onSelectIndex = { targetIdx ->
                    currentIndex = targetIdx
                    userInteractionTrigger++
                },
            )
        }
    }
}
