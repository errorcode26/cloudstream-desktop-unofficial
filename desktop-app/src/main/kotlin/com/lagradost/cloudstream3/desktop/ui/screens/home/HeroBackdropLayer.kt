package com.lagradost.cloudstream3.desktop.ui.screens.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.lagradost.cloudstream3.desktop.ui.theme.AppearanceConfig
import com.lagradost.cloudstream3.desktop.ui.theme.HeroBannerStyle
import com.lagradost.cloudstream3.desktop.utils.ImageUtils

@Composable
internal fun HeroBackdropLayer(
    ambientBg: String?,
    isBackdropNull: Boolean,
    isLightMode: Boolean,
    heroBannerStyle: HeroBannerStyle = HeroBannerStyle.CINEMA_PEEKING,
    backdropHeight: Dp,
    dynamicHeight: Dp,
) {
    val performanceModeEnabled by AppearanceConfig.performanceModeEnabled.collectAsState()
    val isPeeking = heroBannerStyle == HeroBannerStyle.CINEMA_PEEKING
    val fadeStartRatio = if (backdropHeight.value > 0f) (dynamicHeight.value / backdropHeight.value).coerceIn(0f, 0.9f) else 0.65f

    Box(modifier = Modifier.fillMaxSize()) {
        val enhancedAmbientBg = remember(ambientBg) { ImageUtils.enhanceBackdropUrl(ambientBg) }
        if (enhancedAmbientBg != null) {
            // Layer 1: Ambient Progressive Blur Bed (extends behind Continue Watching)
            if (!isBackdropNull && !performanceModeEnabled) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalPlatformContext.current)
                        .data(enhancedAmbientBg)
                        .size(320, 180)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.Low,
                    alignment = Alignment.TopCenter,
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(32.dp)
                        .graphicsLayer { alpha = 0.99f }
                        .drawWithCache {
                            val ambientFade = if (isPeeking) {
                                Brush.verticalGradient(
                                    0.00f to Color.Transparent,
                                    (fadeStartRatio * 0.40f) to Color.Transparent,
                                    fadeStartRatio to Color.Black.copy(alpha = 0.85f),
                                    0.88f to Color.Black.copy(alpha = 0.35f),
                                    1.00f to Color.Transparent,
                                )
                            } else {
                                Brush.verticalGradient(
                                    0.00f to Color.Transparent,
                                    0.25f to Color.Transparent,
                                    0.55f to Color.Black.copy(alpha = 0.80f),
                                    0.85f to Color.Black.copy(alpha = 0.35f),
                                    1.00f to Color.Transparent,
                                )
                            }
                            onDrawWithContent {
                                drawContent()
                                drawRect(ambientFade, blendMode = BlendMode.DstIn)
                            }
                        },
                )
            }

            // Layer 2: Foreground Image with Scrims & Unified Easing Dissolve (DetailsBackdrop pattern)
            AsyncImage(
                model = ImageRequest.Builder(LocalPlatformContext.current)
                    .data(enhancedAmbientBg)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.High,
                alignment = Alignment.TopCenter,
                modifier = Modifier
                    .fillMaxSize()
                    .run { if (isBackdropNull && !performanceModeEnabled) this.blur(24.dp) else this }
                    .graphicsLayer { alpha = 0.99f }
                    .drawWithCache {
                        val verticalFade = if (isPeeking) {
                            Brush.verticalGradient(
                                0.00f to Color.Black,
                                (fadeStartRatio * 0.85f) to Color.Black,
                                fadeStartRatio to Color.Black.copy(alpha = 0.80f),
                                (fadeStartRatio + (1f - fadeStartRatio) * 0.40f) to Color.Black.copy(alpha = 0.35f),
                                (fadeStartRatio + (1f - fadeStartRatio) * 0.78f) to Color.Black.copy(alpha = 0.08f),
                                1.00f to Color.Transparent,
                            )
                        } else {
                            Brush.verticalGradient(
                                0.00f to Color.Black,
                                0.35f to Color.Black,
                                0.60f to Color.Black.copy(alpha = 0.85f),
                                0.80f to Color.Black.copy(alpha = 0.40f),
                                0.94f to Color.Black.copy(alpha = 0.08f),
                                1.00f to Color.Transparent,
                            )
                        }
                        val scrimBase = if (isLightMode) Color.Transparent else Color.Black
                        val logoVignette = Brush.horizontalGradient(
                            0.00f to scrimBase.copy(alpha = 0.85f),
                            0.08f to scrimBase.copy(alpha = 0.80f),
                            0.18f to scrimBase.copy(alpha = 0.70f),
                            0.30f to scrimBase.copy(alpha = 0.55f),
                            0.42f to scrimBase.copy(alpha = 0.35f),
                            0.54f to scrimBase.copy(alpha = 0.18f),
                            0.64f to scrimBase.copy(alpha = 0.06f),
                            0.72f to Color.Transparent,
                            1.00f to Color.Transparent,
                        )
                        val bottomScrim = if (isPeeking) {
                            Brush.verticalGradient(
                                0.00f to Color.Transparent,
                                (fadeStartRatio * 0.50f) to Color.Transparent,
                                fadeStartRatio to scrimBase.copy(alpha = if (isLightMode) 0f else 0.50f),
                                1.00f to Color.Transparent,
                            )
                        } else {
                            Brush.verticalGradient(
                                0.00f to Color.Transparent,
                                0.35f to Color.Transparent,
                                0.65f to scrimBase.copy(alpha = 0.50f),
                                0.85f to scrimBase.copy(alpha = 0.88f),
                                1.00f to Color.Transparent,
                            )
                        }
                        onDrawWithContent {
                            drawContent()
                            drawRect(bottomScrim)
                            drawRect(logoVignette)
                            drawRect(verticalFade, blendMode = BlendMode.DstIn)
                        }
                    },
            )
        }
    }
}
