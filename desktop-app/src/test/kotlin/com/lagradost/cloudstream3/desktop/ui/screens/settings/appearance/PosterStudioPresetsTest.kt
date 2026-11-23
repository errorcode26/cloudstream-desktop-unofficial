package com.lagradost.cloudstream3.desktop.ui.screens.settings.appearance

import com.lagradost.cloudstream3.desktop.ui.badges.CardMetadataConfig
import com.lagradost.cloudstream3.desktop.ui.theme.AppearanceConfig
import com.lagradost.cloudstream3.desktop.ui.theme.ContinueWatchingStyle
import com.lagradost.cloudstream3.desktop.ui.theme.PosterMetadataPosition
import com.lagradost.cloudstream3.desktop.ui.theme.PosterTextAlignment
import com.lagradost.cloudstream3.desktop.ui.theme.PosterTitlePosition
import com.lagradost.cloudstream3.desktop.ui.theme.ProviderBadgeDisplayMode
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PosterStudioPresetsTest {

    @Test
    fun testCinematicPresetRecognition() {
        assertTrue(
            PosterStudioPresets.isCinematicSelected(
                textAlignment = PosterTextAlignment.LEFT,
                metadataPosition = PosterMetadataPosition.BELOW_POSTER,
                titlePosition = PosterTitlePosition.BELOW,
                titleMaxLines = 2,
                roundingDp = 10,
                autoCleanTitles = true,
            )
        )

        assertFalse(
            PosterStudioPresets.isCinematicSelected(
                textAlignment = PosterTextAlignment.CENTER,
                metadataPosition = PosterMetadataPosition.BELOW_POSTER,
                titlePosition = PosterTitlePosition.BELOW,
                titleMaxLines = 2,
                roundingDp = 10,
                autoCleanTitles = true,
            )
        )

        assertFalse(
            PosterStudioPresets.isCinematicSelected(
                textAlignment = PosterTextAlignment.LEFT,
                metadataPosition = PosterMetadataPosition.BELOW_POSTER,
                titlePosition = PosterTitlePosition.BELOW,
                titleMaxLines = 1,
                roundingDp = 10,
                autoCleanTitles = true,
            )
        )
    }

    @Test
    fun testCenteredPresetRecognition() {
        assertTrue(
            PosterStudioPresets.isCenteredSelected(
                textAlignment = PosterTextAlignment.CENTER,
                metadataPosition = PosterMetadataPosition.BELOW_POSTER,
                titlePosition = PosterTitlePosition.BELOW,
            )
        )

        assertFalse(
            PosterStudioPresets.isCenteredSelected(
                textAlignment = PosterTextAlignment.LEFT,
                metadataPosition = PosterMetadataPosition.BELOW_POSTER,
                titlePosition = PosterTitlePosition.BELOW,
            )
        )
    }

    @Test
    fun testOnPosterPresetRecognition() {
        assertTrue(
            PosterStudioPresets.isOnPosterSelected(
                metadataPosition = PosterMetadataPosition.ON_POSTER
            )
        )

        assertFalse(
            PosterStudioPresets.isOnPosterSelected(
                metadataPosition = PosterMetadataPosition.BELOW_POSTER
            )
        )
    }

    @Test
    fun testCompactPresetRecognition() {
        assertTrue(
            PosterStudioPresets.isCompactSelected(
                textAlignment = PosterTextAlignment.LEFT,
                metadataPosition = PosterMetadataPosition.BELOW_POSTER,
                titlePosition = PosterTitlePosition.BELOW,
                titleMaxLines = 1,
                posterWidthDp = 135,
            )
        )

        assertFalse(
            PosterStudioPresets.isCompactSelected(
                textAlignment = PosterTextAlignment.LEFT,
                metadataPosition = PosterMetadataPosition.BELOW_POSTER,
                titlePosition = PosterTitlePosition.BELOW,
                titleMaxLines = 2,
                posterWidthDp = 135,
            )
        )
    }

    @Test
    fun testPresetApplication() {
        PosterStudioPresets.applyDefaults()
        assertEquals(160, AppearanceConfig.posterWidthDp.value)
        assertEquals(12, AppearanceConfig.homeSpacingDp.value)
        assertEquals(16, AppearanceConfig.homeVerticalSpacingDp.value)
        assertEquals(12, AppearanceConfig.posterRoundingDp.value)
        assertEquals(PosterTitlePosition.BELOW, AppearanceConfig.posterTitlePosition.value)
        assertEquals(PosterMetadataPosition.BELOW_POSTER, AppearanceConfig.posterMetadataPosition.value)
        assertEquals(PosterTextAlignment.LEFT, AppearanceConfig.posterTextAlignment.value)
        assertEquals(2, AppearanceConfig.posterTitleMaxLines.value)
        assertEquals(ContinueWatchingStyle.PREMIUM, AppearanceConfig.continueWatchingStyle.value)
        assertEquals(ProviderBadgeDisplayMode.HIDDEN, AppearanceConfig.providerBadgeDisplayMode.value)
        assertTrue(AppearanceConfig.posterHoverGlowEnabled.value)
        assertTrue(CardMetadataConfig.autoCleanTitles.value)

        PosterStudioPresets.applyCinematic()
        assertEquals(PosterTextAlignment.LEFT, AppearanceConfig.posterTextAlignment.value)
        assertEquals(PosterMetadataPosition.BELOW_POSTER, AppearanceConfig.posterMetadataPosition.value)
        assertEquals(PosterTitlePosition.BELOW, AppearanceConfig.posterTitlePosition.value)
        assertEquals(2, AppearanceConfig.posterTitleMaxLines.value)
        assertEquals(10, AppearanceConfig.posterRoundingDp.value)
        assertTrue(CardMetadataConfig.autoCleanTitles.value)

        PosterStudioPresets.applyCompact()
        assertEquals(135, AppearanceConfig.posterWidthDp.value)
        assertEquals(1, AppearanceConfig.posterTitleMaxLines.value)

        PosterStudioPresets.applyOnPoster()
        assertEquals(PosterMetadataPosition.ON_POSTER, AppearanceConfig.posterMetadataPosition.value)

        PosterStudioPresets.applyCentered()
        assertEquals(PosterTextAlignment.CENTER, AppearanceConfig.posterTextAlignment.value)
    }
}
