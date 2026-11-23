package com.lagradost.cloudstream3.desktop.ui.screens.home

import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.desktop.ui.DockPosition
import com.lagradost.cloudstream3.desktop.ui.theme.HeroBannerStyle
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeHeroCarouselTest {

    @Test
    fun testDimensionsCompact() {
        val dims = calculateHeroDimensions(
            rawWidth = 500.dp,
            rawHeight = 800.dp,
            heroBannerStyle = HeroBannerStyle.CINEMA_PEEKING,
        )

        assertTrue(dims.isCompact)
        assertEquals(460.dp, dims.dynamicHeight)
        assertEquals(840.dp, dims.backdropHeight) // 460 + 380 coerced >= 800
    }

    @Test
    fun testDimensionsDesktopCinemaPeeking() {
        val dims = calculateHeroDimensions(
            rawWidth = 1200.dp,
            rawHeight = 1000.dp,
            heroBannerStyle = HeroBannerStyle.CINEMA_PEEKING,
        )

        assertFalse(dims.isCompact)
        // 1000 * 0.72 = 720, clamped to 680
        assertEquals(680.dp, dims.dynamicHeight)
        // 680 + 380 = 1060, coerced >= 1000
        assertEquals(1060.dp, dims.backdropHeight)
    }

    @Test
    fun testDimensionsDesktopThumbnailStrip() {
        val dims = calculateHeroDimensions(
            rawWidth = 1200.dp,
            rawHeight = 900.dp,
            heroBannerStyle = HeroBannerStyle.THUMBNAIL_STRIP,
        )

        assertFalse(dims.isCompact)
        assertEquals(900.dp, dims.dynamicHeight)
        assertEquals(900.dp, dims.backdropHeight)
    }

    @Test
    fun testLayoutPaddingsCompact() {
        val paddings = calculateHeroLayoutPaddings(
            isCompact = true,
            dockPosition = DockPosition.BOTTOM,
            safeBottom = 0.dp,
            heroBannerStyle = HeroBannerStyle.CINEMA_PEEKING,
            maxWidth = 400.dp,
            maxHeight = 700.dp,
        )

        assertEquals(12.dp, paddings.paddingStart)
        assertEquals(12.dp, paddings.paddingEnd)
        assertEquals(16.dp, paddings.infoBlockBottomPadding)
        assertEquals(376.dp, paddings.infoBlockMaxWidth) // 400 - 12 - 12
    }

    @Test
    fun testLayoutPaddingsDockLeft() {
        val paddings = calculateHeroLayoutPaddings(
            isCompact = false,
            dockPosition = DockPosition.LEFT,
            safeBottom = 16.dp,
            heroBannerStyle = HeroBannerStyle.CINEMA_PEEKING,
            maxWidth = 1200.dp,
            maxHeight = 900.dp,
        )

        assertEquals(98.dp, paddings.paddingStart)
        assertEquals(32.dp, paddings.paddingEnd)
        assertEquals(52.dp, paddings.infoBlockBottomPadding) // 16 + 36
        assertEquals(540.dp, paddings.infoBlockMaxWidth) // 1200 * 0.45
    }

    @Test
    fun testLayoutPaddingsDockRight() {
        val paddings = calculateHeroLayoutPaddings(
            isCompact = false,
            dockPosition = DockPosition.RIGHT,
            safeBottom = 16.dp,
            heroBannerStyle = HeroBannerStyle.CINEMA_PEEKING,
            maxWidth = 1200.dp,
            maxHeight = 900.dp,
        )

        assertEquals(32.dp, paddings.paddingStart)
        assertEquals(98.dp, paddings.paddingEnd)
    }
}
