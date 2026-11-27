package com.lagradost.cloudstream3.desktop.ui

import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object DesktopDimens {
    val HeroLogoMinWidth: Dp = 0.dp // Allow natural wrapping
    val HeroLogoMaxWidth: Dp = 800.dp // Increased significantly for wide text logos
    val HeroLogoMaxHeight: Dp = 220.dp // Increased significantly for square/stacked logos

    val LogoShadowBlur: Dp = 12.dp
    val LogoShadowAlpha: Float = 0.75f
    val LogoShadowOffsetX: Dp = 1.dp
    val LogoShadowOffsetY: Dp = 3.dp
    val LogoShadowFilter: ColorFilter = ColorFilter.tint(
        Color.Black.copy(alpha = LogoShadowAlpha),
        blendMode = BlendMode.SrcIn,
    )

    val MaxContentWidth: Dp = 1600.dp
    val MaxHeroWidth: Dp = 1200.dp

    val PosterAspectRatio: Float = 2f / 3f
    val PosterCornerRadius: Dp = 8.dp

    val ScreenMaxContentWidth: Dp = 1280.dp
    val TabInnerHorizontalPadding: Dp = 8.dp
    val TabInnerVerticalPadding: Dp = 8.dp
    val DrilldownHorizontalPadding: Dp = 28.dp
    val DrilldownTopPadding: Dp = 68.dp
    val DrilldownBottomPadding: Dp = 24.dp
    val WatermarkBottomInset: Dp = 38.dp
}
