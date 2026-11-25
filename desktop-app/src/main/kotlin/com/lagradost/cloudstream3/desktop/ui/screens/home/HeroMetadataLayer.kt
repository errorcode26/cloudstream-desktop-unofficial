package com.lagradost.cloudstream3.desktop.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.lagradost.cloudstream3.desktop.ui.components.HeroPrimaryCtaButton
import com.lagradost.cloudstream3.desktop.ui.theme.LocalDesktopButtonShapes
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.desktop.repo.HeroMeta
import com.lagradost.cloudstream3.desktop.repo.HeroRepository
import com.lagradost.cloudstream3.desktop.ui.DesktopDimens
import com.lagradost.cloudstream3.desktop.ui.badges.DesktopBadgeComponents
import com.lagradost.cloudstream3.desktop.ui.components.CinematicTitle
import com.lagradost.cloudstream3.desktop.ui.components.getTextShadow
import com.lagradost.cloudstream3.desktop.ui.theme.AppearanceConfig
import com.lagradost.cloudstream3.desktop.ui.theme.HeroBannerStyle
import com.lagradost.cloudstream3.desktop.utils.ImageUtils
import com.lagradost.cloudstream3.desktop.utils.TitleUtils

@Composable
internal fun HeroMetadataLayer(
    item: SearchResponse,
    meta: HeroMeta?,
    isCompact: Boolean,
    heroBannerStyle: HeroBannerStyle,
) {
    val performanceModeEnabled by AppearanceConfig.performanceModeEnabled.collectAsState()
    Column(
        modifier = if (isCompact || heroBannerStyle == HeroBannerStyle.CINEMA_PEEKING) Modifier.wrapContentHeight() else Modifier.height(350.dp),
        verticalArrangement = Arrangement.Bottom,
    ) {
        if (!meta?.logoUrl.isNullOrBlank()) {
            val displayTitle = meta.title ?: HeroRepository.cleanHeroTitle(item.name)
            var isValidLogo by remember(meta.logoUrl) {
                mutableStateOf(ImageUtils.isClearLogoCached(meta.logoUrl) ?: true)
            }
            if (isValidLogo) {
                Box(
                    modifier = Modifier
                        .widthIn(
                            min = if (isCompact) 120.dp else DesktopDimens.HeroLogoMinWidth,
                            max = if (isCompact) 220.dp else DesktopDimens.HeroLogoMaxWidth,
                        )
                        .heightIn(max = if (isCompact) 85.dp else DesktopDimens.HeroLogoMaxHeight),
                    contentAlignment = Alignment.BottomStart,
                ) {
                    var isDarkLogo by remember(meta.logoUrl) {
                        mutableStateOf(ImageUtils.isDarkLogoCached(meta.logoUrl) ?: false)
                    }
                    val platformContext = LocalPlatformContext.current

                    val logoRequest = remember(meta.logoUrl, platformContext) {
                        ImageRequest.Builder(platformContext)
                            .data(meta.logoUrl)
                            .size(1600, 800)
                            .crossfade(true)
                            .listener(
                                onSuccess = { _, result ->
                                    val isClear = ImageUtils.isClearLogo(result.image)
                                    ImageUtils.cacheClearLogo(meta.logoUrl, isClear)
                                    isValidLogo = isClear

                                    if (isClear) {
                                        val dark = ImageUtils.isDarkImage(result.image)
                                        ImageUtils.cacheDarkLogo(meta.logoUrl, dark)
                                        isDarkLogo = dark
                                    }
                                },
                            )
                            .build()
                    }
                    if (!performanceModeEnabled) {
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
                                    edgeTreatment = BlurredEdgeTreatment.Unbounded,
                                ),
                            contentScale = ContentScale.Fit,
                            alignment = Alignment.BottomStart,
                            colorFilter = DesktopDimens.LogoShadowFilter,
                        )
                    }
                    SubcomposeAsyncImage(
                        model = logoRequest,
                        contentDescription = "Logo",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.BottomStart,
                        colorFilter = if (isDarkLogo) ColorFilter.colorMatrix(ImageUtils.InvertColorMatrix) else null,
                        error = {
                            if (displayTitle.isNotBlank()) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
                                    CinematicTitle(
                                        text = displayTitle,
                                        isCompact = isCompact,
                                        fontSize = if (isCompact) 26.sp else 46.sp,
                                    )
                                }
                            }
                        },
                    )
                }
            } else {
                if (displayTitle.isNotBlank()) {
                    CinematicTitle(
                        text = displayTitle,
                        isCompact = isCompact,
                        fontSize = if (isCompact) 26.sp else 46.sp,
                    )
                }
            }
        } else {
            val displayTitle = meta?.title ?: HeroRepository.cleanHeroTitle(item.name)
            if (displayTitle.isNotBlank()) {
                CinematicTitle(
                    text = displayTitle,
                    isCompact = isCompact,
                    fontSize = if (isCompact) 26.sp else 46.sp,
                )
            }
        }

        Spacer(Modifier.height(if (isCompact) 8.dp else 20.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            if (meta?.score != null && meta.score.toDoubleOrNull()?.let { it > 0.0 } == true) {
                Icon(
                    Icons.Default.Star,
                    contentDescription = "Rating",
                    tint = Color(0xFFFFD700),
                    modifier = Modifier.size(if (isCompact) 16.dp else 20.dp),
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    text = meta.score,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = if (isCompact) 12.5.sp else 15.sp,
                    fontWeight = FontWeight.Bold,
                    style = LocalTextStyle.current.copy(
                        shadow = getTextShadow(),
                    ),
                )
                Spacer(Modifier.width(if (isCompact) 8.dp else 14.dp))
            }
            if (meta?.year != null) {
                Text(
                    text = meta.year.toString(),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = if (isCompact) 12.5.sp else 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    style = LocalTextStyle.current.copy(
                        shadow = getTextShadow(),
                    ),
                )
                Spacer(Modifier.width(if (isCompact) 8.dp else 14.dp))
            }
            if (!meta?.contentRating.isNullOrBlank()) {
                DesktopBadgeComponents.ContentRatingBadge(
                    rating = meta.contentRating,
                    isLarge = !isCompact,
                )
                Spacer(Modifier.width(if (isCompact) 8.dp else 14.dp))
            }
            if (meta?.duration != null && meta.duration > 0) {
                Text(
                    text = "${meta.duration}m",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = if (isCompact) 12.5.sp else 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    style = LocalTextStyle.current.copy(
                        shadow = getTextShadow(),
                    ),
                )
                Spacer(Modifier.width(if (isCompact) 8.dp else 14.dp))
            }
            if (!meta?.tags.isNullOrEmpty()) {
                val tagsText = meta.tags.distinct().take(2).joinToString(" • ")
                Text(
                    text = tagsText,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = if (isCompact) 12.5.sp else 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = LocalTextStyle.current.copy(
                        shadow = getTextShadow(),
                    ),
                )
            }
        }

        val displayPlot = remember(meta?.plot) {
            TitleUtils.cleanPlot(meta?.plot)
                ?.replace(Regex("""\s*\n+\s*"""), " ")
        }
        if (!displayPlot.isNullOrBlank()) {
            Spacer(Modifier.height(if (isCompact) 6.dp else 16.dp))
            Text(
                text = displayPlot,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                fontSize = if (isCompact) 12.5.sp else 15.sp,
                maxLines = if (isCompact) 2 else 3,
                overflow = TextOverflow.Ellipsis,
                lineHeight = if (isCompact) 17.sp else 22.sp,
                style = LocalTextStyle.current.copy(
                    shadow = getTextShadow(),
                ),
            )
        }
    }
}

@Composable
internal fun HeroActionButtons(
    item: SearchResponse,
    meta: HeroMeta?,
    isCompact: Boolean,
    displayItemsCount: Int,
    currentIndex: Int,
    onSelect: (SearchResponse, String?) -> Unit,
) {
    if (isCompact && displayItemsCount > 1) {
        Spacer(Modifier.height(14.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(displayItemsCount) { dotIndex ->
                val isSelected = dotIndex == currentIndex
                Box(
                    modifier = Modifier
                        .height(4.dp)
                        .width(if (isSelected) 18.dp else 5.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.35f)),
                )
            }
        }
    }

    Spacer(Modifier.height(if (isCompact) 14.dp else 24.dp))

    HeroPrimaryCtaButton(
        text = "Watch Now",
        icon = Icons.Default.PlayArrow,
        onClick = { onSelect(item, meta?.backdropUrl) },
        shape = LocalDesktopButtonShapes.current.primary,
        contentPadding = PaddingValues(horizontal = if (isCompact) 16.dp else 24.dp),
        iconSize = if (isCompact) 18.dp else 22.dp,
        fontSize = if (isCompact) 13.sp else 15.sp,
        iconSpacing = if (isCompact) 6.dp else 8.dp,
        modifier = if (isCompact) Modifier.height(40.dp).fillMaxWidth() else Modifier.height(48.dp).widthIn(min = 160.dp),
    )
}
