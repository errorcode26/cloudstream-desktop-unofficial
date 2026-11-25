package com.lagradost.cloudstream3.desktop.ui.screens.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.desktop.repo.HeroMeta
import com.lagradost.cloudstream3.desktop.ui.components.DesktopIconButton
import com.lagradost.cloudstream3.desktop.ui.components.LocalDesktopTheme
import com.lagradost.cloudstream3.desktop.ui.theme.LocalDesktopButtonShapes
import com.lagradost.cloudstream3.fixUrlNull

@Composable
internal fun BoxScope.HeroFilmstrip(
    displayItems: List<SearchResponse>,
    provider: MainAPI?,
    heroMetaMap: Map<String, HeroMeta>,
    currentIndex: Int,
    userInteractionTrigger: Int,
    thumbnailHeight: Dp,
    thumbnailsMaxWidth: Dp,
    paddingEnd: Dp,
    safeBottom: Dp,
    autoAdvanceIntervalMs: Long,
    onSelectThumb: (Int) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { clip = false }
            .padding(bottom = safeBottom + 32.dp),
        contentAlignment = Alignment.BottomEnd,
    ) {
        val listState = rememberLazyListState()
        LaunchedEffect(currentIndex) {
            listState.animateScrollToItem(maxOf(0, currentIndex - 1))
        }

        LazyRow(
            state = listState,
            modifier = Modifier.widthIn(max = thumbnailsMaxWidth + paddingEnd).graphicsLayer { clip = false },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom,
            contentPadding = PaddingValues(end = paddingEnd),
        ) {
            items(displayItems.size, key = { "${displayItems[it].url}_$it" }) { index ->
                val item = displayItems.getOrNull(index) ?: return@items
                val posterUrl = provider?.fixUrlNull(item.posterUrl)
                val thumbUrl = posterUrl ?: heroMetaMap[item.url]?.backdropUrl
                val isSelected = index == currentIndex

                if (thumbUrl != null) {
                    val posterHeight by animateDpAsState(
                        targetValue = if (isSelected) thumbnailHeight else thumbnailHeight * 0.72f,
                        animationSpec = tween(350),
                        label = "poster_height",
                    )
                    val thumbAlpha by animateFloatAsState(
                        targetValue = if (isSelected) 1f else 0.45f,
                        animationSpec = tween(350),
                        label = "thumb_alpha",
                    )

                    val progress = remember { Animatable(0f) }
                    LaunchedEffect(isSelected, currentIndex, userInteractionTrigger) {
                        if (isSelected && autoAdvanceIntervalMs > 0) {
                            progress.snapTo(0f)
                            progress.animateTo(
                                targetValue = 1f,
                                animationSpec = tween(
                                    durationMillis = autoAdvanceIntervalMs.toInt(),
                                    easing = LinearEasing,
                                ),
                            )
                        } else {
                            progress.snapTo(0f)
                        }
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                        modifier = Modifier.alpha(thumbAlpha),
                    ) {
                        Box(
                            modifier = Modifier
                                .height(posterHeight)
                                .aspectRatio(2f / 3f),
                        ) {
                            AsyncImage(
                                model = ImageRequest.Builder(LocalPlatformContext.current)
                                    .data(thumbUrl)
                                    .size(240, 360)
                                    .build(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(8.dp))
                                    .then(
                                        if (isSelected) {
                                            Modifier.border(
                                                width = 1.5.dp,
                                                brush = Brush.verticalGradient(
                                                    listOf(
                                                        Color.White.copy(alpha = 0.95f),
                                                        Color.White.copy(alpha = 0.25f),
                                                    ),
                                                ),
                                                shape = RoundedCornerShape(8.dp),
                                            )
                                        } else {
                                            Modifier
                                        },
                                    )
                                    .clickable { onSelectThumb(index) },
                            )
                        }

                        val posterWidth = posterHeight * (2f / 3f)
                        Box(
                            modifier = Modifier
                                .width(posterWidth)
                                .height(2.dp)
                                .clip(RoundedCornerShape(1.dp))
                                .background(Color.White.copy(alpha = if (isSelected) 0.2f else 0.08f)),
                        ) {
                            if (isSelected) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth(progress.value)
                                        .clip(RoundedCornerShape(1.dp))
                                        .background(Color.White),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun BoxScope.HeroDashes(
    displayItems: List<SearchResponse>,
    currentIndex: Int,
    userInteractionTrigger: Int,
    isCompact: Boolean,
    paddingEnd: Dp,
    safeBottom: Dp,
    autoAdvanceIntervalMs: Long,
    onSelectIndex: (Int) -> Unit,
) {
    val isLightMode = LocalDesktopTheme.current.isLightMode

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = safeBottom + 32.dp, end = if (isCompact) 0.dp else paddingEnd),
        contentAlignment = if (isCompact) Alignment.BottomCenter else Alignment.BottomEnd,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                displayItems.forEachIndexed { index, _ ->
                    val isSelected = index == currentIndex
                    val dashWidth by animateDpAsState(
                        targetValue = if (isSelected) 36.dp else 12.dp,
                        animationSpec = spring(
                            stiffness = Spring.StiffnessLow,
                            dampingRatio = Spring.DampingRatioNoBouncy,
                        ),
                        label = "dash_width",
                    )
                    val dashAlpha by animateFloatAsState(
                        targetValue = if (isSelected) 1f else 0.4f,
                        animationSpec = spring(
                            stiffness = Spring.StiffnessLow,
                            dampingRatio = Spring.DampingRatioNoBouncy,
                        ),
                        label = "dash_alpha",
                    )

                    // Invisible Hit-Box (Massive touch target)
                    Box(
                        modifier = Modifier
                            .height(24.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { onSelectIndex(index) }
                            .padding(horizontal = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        // Visual Dash
                        Box(
                            modifier = Modifier
                                .width(dashWidth)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(Color.White.copy(alpha = dashAlpha)),
                        )
                    }
                }
            }

            if (displayItems.size > 1 && !isCompact) {
                HeroStepControlsPill(
                    isLightMode = isLightMode,
                    onPrev = {
                        onSelectIndex(HomeHeroCardsLayoutMath.safeWrapIndex(currentIndex - 1, displayItems.size))
                    },
                    onNext = {
                        onSelectIndex((currentIndex + 1) % displayItems.size)
                    },
                )
            }
        }
    }
}

@Composable
internal fun HeroStepControlsPill(
    isLightMode: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pillShape = LocalDesktopButtonShapes.current.compact
    val fgColor = if (isLightMode) LocalDesktopTheme.current.TextPrimary else Color.White

    Surface(
        shape = pillShape,
        color = if (isLightMode) Color.Black.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.14f),
        border = BorderStroke(
            1.dp,
            if (isLightMode) Color.Black.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.18f),
        ),
        modifier = modifier,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DesktopIconButton(
                onClick = onPrev,
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.ChevronLeft,
                    contentDescription = "Previous spotlight item",
                    tint = fgColor,
                    modifier = Modifier.size(16.dp),
                )
            }
            DesktopIconButton(
                onClick = onNext,
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = "Next spotlight item",
                    tint = fgColor,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
