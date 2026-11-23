package com.lagradost.cloudstream3.desktop.ui.screens.settings.appearance

import com.lagradost.cloudstream3.desktop.ui.badges.CardMetadataConfig
import com.lagradost.cloudstream3.desktop.ui.theme.AppearanceConfig
import com.lagradost.cloudstream3.desktop.ui.theme.ContinueWatchingStyle
import com.lagradost.cloudstream3.desktop.ui.theme.PosterMetadataPosition
import com.lagradost.cloudstream3.desktop.ui.theme.PosterTextAlignment
import com.lagradost.cloudstream3.desktop.ui.theme.PosterTitlePosition
import com.lagradost.cloudstream3.desktop.ui.theme.ProviderBadgeDisplayMode

object PosterStudioPresets {

    fun isCinematicSelected(
        textAlignment: PosterTextAlignment,
        metadataPosition: PosterMetadataPosition,
        titlePosition: PosterTitlePosition,
        titleMaxLines: Int,
        roundingDp: Int,
        autoCleanTitles: Boolean,
    ): Boolean = textAlignment == PosterTextAlignment.LEFT &&
        metadataPosition == PosterMetadataPosition.BELOW_POSTER &&
        titlePosition == PosterTitlePosition.BELOW &&
        titleMaxLines == 2 &&
        roundingDp == 10 &&
        autoCleanTitles

    fun isCenteredSelected(
        textAlignment: PosterTextAlignment,
        metadataPosition: PosterMetadataPosition,
        titlePosition: PosterTitlePosition,
    ): Boolean = textAlignment == PosterTextAlignment.CENTER &&
        metadataPosition == PosterMetadataPosition.BELOW_POSTER &&
        titlePosition == PosterTitlePosition.BELOW

    fun isOnPosterSelected(
        metadataPosition: PosterMetadataPosition,
    ): Boolean = metadataPosition == PosterMetadataPosition.ON_POSTER

    fun isCompactSelected(
        textAlignment: PosterTextAlignment,
        metadataPosition: PosterMetadataPosition,
        titlePosition: PosterTitlePosition,
        titleMaxLines: Int,
        posterWidthDp: Int,
    ): Boolean = textAlignment == PosterTextAlignment.LEFT &&
        metadataPosition == PosterMetadataPosition.BELOW_POSTER &&
        titlePosition == PosterTitlePosition.BELOW &&
        titleMaxLines == 1 &&
        posterWidthDp == 135

    fun applyCinematic() {
        AppearanceConfig.setPosterTextAlignment(PosterTextAlignment.LEFT)
        AppearanceConfig.setPosterMetadataPosition(PosterMetadataPosition.BELOW_POSTER)
        AppearanceConfig.setPosterTitlePosition(PosterTitlePosition.BELOW)
        AppearanceConfig.setPosterTitleMaxLines(2)
        AppearanceConfig.setPosterRoundingDp(10)
        CardMetadataConfig.setAutoCleanTitles(true)
    }

    fun applyCentered() {
        AppearanceConfig.setPosterTextAlignment(PosterTextAlignment.CENTER)
        AppearanceConfig.setPosterMetadataPosition(PosterMetadataPosition.BELOW_POSTER)
        AppearanceConfig.setPosterTitlePosition(PosterTitlePosition.BELOW)
    }

    fun applyOnPoster() {
        AppearanceConfig.setPosterMetadataPosition(PosterMetadataPosition.ON_POSTER)
        AppearanceConfig.setPosterTitlePosition(PosterTitlePosition.BELOW)
    }

    fun applyCompact() {
        AppearanceConfig.setPosterTextAlignment(PosterTextAlignment.LEFT)
        AppearanceConfig.setPosterTitleMaxLines(1)
        AppearanceConfig.setPosterMetadataPosition(PosterMetadataPosition.BELOW_POSTER)
        AppearanceConfig.setPosterTitlePosition(PosterTitlePosition.BELOW)
        AppearanceConfig.setPosterWidthDp(135)
    }

    fun applyDefaults() {
        AppearanceConfig.setPosterWidthDp(160)
        AppearanceConfig.setHomeSpacingDp(12)
        AppearanceConfig.setHomeVerticalSpacingDp(16)
        AppearanceConfig.setPosterRoundingDp(12)
        AppearanceConfig.setPosterTitlePosition(PosterTitlePosition.BELOW)
        AppearanceConfig.setPosterMetadataPosition(PosterMetadataPosition.BELOW_POSTER)
        AppearanceConfig.setPosterTextAlignment(PosterTextAlignment.LEFT)
        AppearanceConfig.setPosterTitleMaxLines(2)
        AppearanceConfig.setContinueWatchingStyle(ContinueWatchingStyle.PREMIUM)
        AppearanceConfig.setProviderBadgeDisplayMode(ProviderBadgeDisplayMode.HIDDEN)
        AppearanceConfig.setPosterHoverGlowEnabled(true)
        CardMetadataConfig.setAutoCleanTitles(true)
        CardMetadataConfig.setAutoDetectSubDub(true)
        CardMetadataConfig.setAutoDetectQuality(true)
        CardMetadataConfig.setShowRatingBadges(true)
    }
}
