package com.lagradost.cloudstream3.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Size
import com.lagradost.cloudstream3.desktop.ui.theme.LocalDesktopAppearance
import com.lagradost.cloudstream3.desktop.ui.theme.parseHexColor
import java.io.File

@Composable
internal fun DesktopShellBackground(
    modifier: Modifier = Modifier,
) {
    val appearance = LocalDesktopAppearance.current
    val ambientGlowEnabled = appearance.ambientGlowEnabled
    val ambientGlowIntensity = appearance.ambientGlowIntensity
    val ambientGlowPositions = appearance.ambientGlowPositions

    val isLightMode = appearance.isLightMode
    val amoledMode = appearance.amoledMode
    val primaryColor = MaterialTheme.colorScheme.primary
    val backgroundColor = MaterialTheme.colorScheme.background
    val surfaceColor = MaterialTheme.colorScheme.surface
    val backgroundGradientEnabled = appearance.backgroundGradientEnabled
    val backgroundGradientType = appearance.backgroundGradientType
    val backgroundGradientIntensity = appearance.backgroundGradientIntensity

    val bgImagePath = appearance.backgroundImagePath
    val bgImageBlur = appearance.backgroundImageBlur
    val bgImageBrightness = appearance.backgroundImageBrightness
    val bgImageOpacity = appearance.backgroundImageOpacity
    val bgImageSaturation = appearance.backgroundImageSaturation
    val bgImageVignetteEnabled = appearance.backgroundImageVignetteEnabled
    val bgImageVignetteIntensity = appearance.backgroundImageVignetteIntensity
    val bgImageTintEnabled = appearance.backgroundImageTintEnabled
    val bgImageTintColor = appearance.backgroundImageTintColor
    val bgImageTintAlpha = appearance.backgroundImageTintAlpha

    Box(
        modifier = modifier
            .fillMaxSize()
            .then(
                Modifier.drawWithCache {
                    val radius = size.width.coerceAtLeast(size.height) * 0.8f

                    // 1. Base Ambient Glows (from positions)
                    val glowBrushes = if (ambientGlowEnabled && !isLightMode && !amoledMode) {
                        ambientGlowPositions.map { position ->
                            val yOffset = 0f
                            val centerOffset = when (position) {
                                "Top" -> Offset(size.width / 2f, yOffset)
                                "Bottom" -> Offset(size.width / 2f, size.height)
                                "Left" -> Offset(0f, size.height / 2f)
                                "Right" -> Offset(size.width, size.height / 2f)
                                "Top Left" -> Offset(0f, yOffset)
                                "Top Right" -> Offset(size.width, yOffset)
                                "Bottom Left" -> Offset(0f, size.height)
                                "Bottom Right" -> Offset(size.width, size.height)
                                else -> Offset(size.width / 2f, size.height / 2f)
                            }
                            Brush.radialGradient(
                                colorStops = arrayOf(
                                    0.0f to primaryColor.copy(alpha = ambientGlowIntensity),
                                    0.3f to primaryColor.copy(alpha = ambientGlowIntensity * 0.53f),
                                    0.6f to primaryColor.copy(alpha = ambientGlowIntensity * 0.2f),
                                    1.0f to Color.Transparent,
                                ),
                                center = centerOffset,
                                radius = radius,
                            )
                        }
                    } else {
                        emptyList()
                    }

                    // Background gradient
                    val bgGradientBrush = if (backgroundGradientEnabled && !amoledMode) {
                        val gradientAlpha = backgroundGradientIntensity
                        if (isLightMode) {
                            val startColor = Color.White.copy(alpha = gradientAlpha * 0.35f)
                            val endColor = Color.Transparent

                            when (backgroundGradientType) {
                                "Radial" -> Brush.radialGradient(
                                    colors = listOf(startColor, endColor),
                                    center = Offset(size.width * 0.5f, size.height * 0.25f),
                                    radius = size.width.coerceAtLeast(size.height) * 0.95f,
                                )
                                "Linear" -> Brush.linearGradient(
                                    colors = listOf(startColor, endColor),
                                    start = Offset(0f, 0f),
                                    end = Offset(size.width, size.height),
                                )
                                else -> null
                            }
                        } else {
                            val endColor = Color.Black.copy(alpha = gradientAlpha)
                            val startColor = surfaceColor

                            when (backgroundGradientType) {
                                "Radial" -> Brush.radialGradient(
                                    colors = listOf(startColor, endColor),
                                    center = Offset(size.width * 0.5f, size.height * 0.25f),
                                    radius = size.width.coerceAtLeast(size.height) * 0.95f,
                                )
                                "Linear" -> Brush.linearGradient(
                                    colors = listOf(startColor, endColor),
                                    start = Offset(0f, 0f),
                                    end = Offset(size.width, size.height),
                                )
                                else -> null
                            }
                        }
                    } else {
                        null
                    }

                    onDrawBehind {
                        drawRect(color = backgroundColor)
                        if (bgGradientBrush != null) {
                            drawRect(brush = bgGradientBrush)
                        }
                        glowBrushes.forEach { drawRect(brush = it) }
                    }
                },
            ),
        contentAlignment = Alignment.TopCenter,
    ) {
        // Background image layer (rendered below all other content)
        if (bgImagePath.isNotEmpty()) {
            val blurDp = bgImageBlur.dp
            val scrimAlpha = 1f - bgImageBrightness
            val tintColor = parseHexColor(bgImageTintColor, Color(0xFF7C6BFF))

            // Build saturation ColorMatrix: lerp between grayscale (0) and identity (1)
            val colorFilter = remember(bgImageSaturation) {
                if (bgImageSaturation < 0.999f) {
                    val s = bgImageSaturation
                    val invS = 1f - s
                    val rw = 0.213f
                    val gw = 0.715f
                    val bw = 0.072f
                    ColorFilter.colorMatrix(
                        ColorMatrix(
                            floatArrayOf(
                                rw * invS + s, gw * invS, bw * invS, 0f, 0f,
                                rw * invS, gw * invS + s, bw * invS, 0f, 0f,
                                rw * invS, gw * invS, bw * invS + s, 0f, 0f,
                                0f, 0f, 0f, 1f, 0f,
                            ),
                        ),
                    )
                } else {
                    null
                }
            }

            Box(modifier = Modifier.fillMaxSize().then(if (bgImageOpacity < 0.999f) Modifier.alpha(bgImageOpacity) else Modifier)) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalPlatformContext.current)
                        .data(File(bgImagePath))
                        .size(Size(1920, 1080))
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    colorFilter = colorFilter,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (blurDp > 0.dp) Modifier.blur(blurDp, edgeTreatment = BlurredEdgeTreatment.Rectangle) else Modifier),
                )
                // Brightness scrim (black)
                if (scrimAlpha > 0.01f) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = scrimAlpha.coerceIn(0f, 0.95f))),
                    )
                }
                // Color tint overlay
                if (bgImageTintEnabled && bgImageTintAlpha > 0.01f) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(tintColor.copy(alpha = bgImageTintAlpha.coerceIn(0f, 0.95f))),
                    )
                }
                // Vignette (radial gradient: transparent center → black edges)
                if (bgImageVignetteEnabled) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawRect(
                            brush = Brush.radialGradient(
                                colorStops = arrayOf(
                                    0.0f to Color.Transparent,
                                    0.55f to Color.Transparent,
                                    1.0f to Color.Black.copy(alpha = bgImageVignetteIntensity),
                                ),
                                center = Offset(size.width / 2f, size.height / 2f),
                                radius = (size.width.coerceAtLeast(size.height)) * 0.75f,
                            ),
                        )
                    }
                }
            }
        }
    }
}
