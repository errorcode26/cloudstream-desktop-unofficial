package com.lagradost.cloudstream3.desktop.ui.screens.details

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.desktop.ui.screens.details.contract.DetailsUiState
import com.lagradost.cloudstream3.desktop.ui.screens.details.contract.EnrichmentPhase
import com.lagradost.cloudstream3.desktop.utils.ImageUtils
import com.lagradost.cloudstream3.fixUrlNull

@Composable
fun DetailsBackdrop(
    provider: MainAPI,
    data: LoadResponse,
    scrollState: LazyListState,
    enrichmentPhase: EnrichmentPhase,
    modifier: Modifier = Modifier,
    dynamicColorEnabled: Boolean = false,
    uiState: DetailsUiState? = null,
    activeBgUrl: String? = null,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                if (scrollState.firstVisibleItemIndex == 0) {
                    val scrollOffset = scrollState.firstVisibleItemScrollOffset.toFloat()
                    translationY = -scrollOffset * 0.5f
                    alpha = 1f - (scrollOffset / (size.height * 0.8f)).coerceIn(0f, 1f)
                } else {
                    alpha = 0f
                }
            },
    ) {
        val baseBgUrl = remember(data.backgroundPosterUrl, data.posterUrl, uiState?.enrichedBackdropUrl) {
            uiState?.enrichedBackdropUrl?.takeIf { it.isNotBlank() }
                ?: provider.fixUrlNull(data.backgroundPosterUrl)?.takeIf { it.isNotBlank() }
                ?: provider.fixUrlNull(data.posterUrl)?.takeIf { it.isNotBlank() }
        }

        val bgUrl = activeBgUrl ?: baseBgUrl
        val isFallback = remember(data.backgroundPosterUrl, data.posterUrl, uiState?.enrichedBackdropUrl) {
            if (!uiState?.enrichedBackdropUrl.isNullOrBlank()) return@remember false
            data.backgroundPosterUrl.isNullOrBlank() || data.backgroundPosterUrl == data.posterUrl
        }

        if (bgUrl != null) {
            Crossfade(
                targetState = bgUrl,
                animationSpec = tween(2000),
                label = "backdrop_crossfade",
                modifier = Modifier
                    .fillMaxSize()
                    .run { if (isFallback) this.blur(18.dp) else this }
                    .graphicsLayer { alpha = 0.99f }
                    .drawWithCache {
                        val verticalFade = Brush.verticalGradient(
                            0.00f to Color.Black,
                            0.35f to Color.Black,
                            0.60f to Color.Black.copy(alpha = 0.85f),
                            0.80f to Color.Black.copy(alpha = 0.40f),
                            0.94f to Color.Black.copy(alpha = 0.08f),
                            1.00f to Color.Transparent,
                        )
                        val scrimBase = Color.Black
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
                        val bottomScrim = Brush.verticalGradient(
                            0.00f to Color.Transparent,
                            0.35f to Color.Transparent,
                            0.65f to Color.Transparent,
                            0.85f to Color(0xFF0F0F0F).copy(alpha = 0.88f),
                            1.00f to Color(0xFF0F0F0F),
                        )
                        onDrawWithContent {
                            drawContent()
                            drawRect(bottomScrim)
                            drawRect(logoVignette)
                            drawRect(verticalFade, blendMode = BlendMode.DstIn)
                        }
                    },
            ) { targetBgUrl ->
                val enhancedBgUrl = remember(targetBgUrl) { ImageUtils.enhanceBackdropUrl(targetBgUrl) }
                AsyncImage(
                    model = ImageRequest.Builder(LocalPlatformContext.current)
                        .data(enhancedBgUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
                    modifier = Modifier.fillMaxSize(),
                    alignment = Alignment.TopCenter,
                )
            }
        }
    }
}
