package com.lagradost.cloudstream3.desktop.ui.screens.home

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HomeHeroCardsLayoutMathTest {

    @Test
    fun testCalculateScale_UniformFullHeightAcrossDeck() {
        assertEquals(1.00f, HomeHeroCardsLayoutMath.calculateScale(0.0f), 0.001f)
        assertEquals(1.00f, HomeHeroCardsLayoutMath.calculateScale(1.0f), 0.001f)
        assertEquals(1.00f, HomeHeroCardsLayoutMath.calculateScale(2.0f), 0.001f)
        assertEquals(1.00f, HomeHeroCardsLayoutMath.calculateScale(3.0f), 0.001f)
        assertEquals(1.00f, HomeHeroCardsLayoutMath.calculateScale(4.0f), 0.001f)
        assertEquals(1.00f, HomeHeroCardsLayoutMath.calculateScale(5.0f), 0.001f)
    }

    @Test
    fun testCalculateAlpha_OpaqueThroughFlankingSlotsThenFades() {
        assertEquals(1.0f, HomeHeroCardsLayoutMath.calculateAlpha(0.0f), 0.001f)
        assertEquals(1.0f, HomeHeroCardsLayoutMath.calculateAlpha(1.0f), 0.001f)
        assertEquals(1.0f, HomeHeroCardsLayoutMath.calculateAlpha(2.0f), 0.001f)
        assertEquals(0.5f, HomeHeroCardsLayoutMath.calculateAlpha(2.5f), 0.001f)
        assertEquals(0.0f, HomeHeroCardsLayoutMath.calculateAlpha(3.0f), 0.001f)
        assertEquals(0.0f, HomeHeroCardsLayoutMath.calculateAlpha(4.0f), 0.001f)
    }

    @Test
    fun testCalculateDimAlpha_SoftVignetteOnFlankingCards() {
        val dim0 = HomeHeroCardsLayoutMath.calculateDimAlpha(0.0f)
        val dim1 = HomeHeroCardsLayoutMath.calculateDimAlpha(1.0f)
        val dim2 = HomeHeroCardsLayoutMath.calculateDimAlpha(2.0f)

        assertEquals(0.00f, dim0, 0.001f)
        assertEquals(0.28f, dim1, 0.001f)
        assertEquals(0.45f, dim2, 0.001f)
        assertTrue(dim0 < dim1 && dim1 < dim2)
    }

    @Test
    fun testCalculateZIndex_StrictHierarchy() {
        val z0 = HomeHeroCardsLayoutMath.calculateZIndex(0.0f)
        val z1 = HomeHeroCardsLayoutMath.calculateZIndex(1.0f)
        val z2 = HomeHeroCardsLayoutMath.calculateZIndex(2.0f)

        assertTrue(z0 > z1 && z1 > z2)
    }

    @Test
    fun testCalculateEdgeFadeStops_StrictMonotonicOrderAndSymmetry() {
        data class ViewportCase(val rootW: Float, val cardW: Float, val clearance: Float)
        val cases = listOf(
            ViewportCase(1920f, 820f, 84f),
            ViewportCase(1536f, 768f, 84f),
            ViewportCase(1280f, 640f, 56f),
            ViewportCase(960f, 580f, 16f),
            ViewportCase(680f, 580f, 16f),
            ViewportCase(0f, 0f, 0f),
        )
        for (case in cases) {
            val stops = HomeHeroCardsLayoutMath.calculateEdgeFadeStops(case.rootW, case.cardW, 20f, case.clearance)

            assertTrue(0f < stops.leftFadeEnd, "leftFadeEnd must exceed 0 at rootWidth=${case.rootW}")
            assertTrue(stops.leftFadeEnd < stops.leftMidLow)
            assertTrue(stops.leftMidLow < stops.leftMidHigh)
            assertTrue(stops.leftMidHigh < stops.leftFadeStart)
            assertTrue(stops.leftFadeStart < 0.5f)
            assertTrue(0.5f < stops.rightFadeStart)
            assertTrue(stops.rightFadeStart < stops.rightMidHigh)
            assertTrue(stops.rightMidHigh < stops.rightMidLow)
            assertTrue(stops.rightMidLow < stops.rightFadeEnd)
            assertTrue(stops.rightFadeEnd < 1f)

            assertEquals(1f - stops.leftFadeEnd, stops.rightFadeEnd, 0.0001f)
            assertEquals(1f - stops.leftFadeStart, stops.rightFadeStart, 0.0001f)

            // Center card must stay 100% outside the left/right fade masks
            if (case.rootW > 0f) {
                val centerLeftFrac = ((case.rootW - case.cardW) * 0.5f) / case.rootW
                assertTrue(stops.leftFadeStart <= centerLeftFrac + 0.001f, "Fade encroached on center card at ${case.rootW}")
            }
        }
    }

    @Test
    fun testCalculateBackdropAndForegroundParallax_CenteredSymmetricalAndWithinOverhang() {
        val cardWidth = 820f
        val overhangPerSide = cardWidth * ((HomeHeroCardsLayoutMath.BACKDROP_PARALLAX_SCALE - 1f) * 0.5f)

        assertEquals(0f, HomeHeroCardsLayoutMath.calculateBackdropParallaxPx(0f, cardWidth), 0.001f)
        assertEquals(0f, HomeHeroCardsLayoutMath.calculateForegroundParallaxPx(0f, cardWidth), 0.001f)

        val bgRight = HomeHeroCardsLayoutMath.calculateBackdropParallaxPx(1f, cardWidth)
        val bgLeft = HomeHeroCardsLayoutMath.calculateBackdropParallaxPx(-1f, cardWidth)
        assertEquals(-bgRight, bgLeft, 0.001f)
        assertTrue(bgRight < 0f, "Incoming right card backdrop must counter-lag left")

        for (offset in listOf(-2f, -1f, -0.5f, 0.5f, 1f, 2f)) {
            val bgShift = HomeHeroCardsLayoutMath.calculateBackdropParallaxPx(offset, cardWidth)
            assertTrue(kotlin.math.abs(bgShift) < overhangPerSide, "Backdrop shift $bgShift exceeded overhang $overhangPerSide at offset=$offset")
        }

        val fgRight = HomeHeroCardsLayoutMath.calculateForegroundParallaxPx(0.4f, cardWidth, depthMultiplier = 1f)
        val fgLeft = HomeHeroCardsLayoutMath.calculateForegroundParallaxPx(-0.4f, cardWidth, depthMultiplier = 1f)
        assertEquals(-fgRight, fgLeft, 0.001f)
        assertTrue(fgRight > 0f, "Foreground overlay must move ahead of card container")
    }

    @Test
    fun testCalculateDetailsAlpha_FadesSmoothly() {
        assertEquals(1.0f, HomeHeroCardsLayoutMath.calculateDetailsAlpha(0.0f), 0.001f)
        assertEquals(0.0f, HomeHeroCardsLayoutMath.calculateDetailsAlpha(0.5f), 0.001f)
        assertEquals(0.0f, HomeHeroCardsLayoutMath.calculateDetailsAlpha(1.0f), 0.001f)

        val mid = HomeHeroCardsLayoutMath.calculateDetailsAlpha(0.2f)
        assertTrue(mid in 0.4f..0.6f)
    }

    @Test
    fun testSafeWrapIndex_HandlesPositiveAndNegativeVirtualPages() {
        val total = 10
        assertEquals(0, HomeHeroCardsLayoutMath.safeWrapIndex(0, total))
        assertEquals(9, HomeHeroCardsLayoutMath.safeWrapIndex(9, total))
        assertEquals(0, HomeHeroCardsLayoutMath.safeWrapIndex(10, total))
        assertEquals(5, HomeHeroCardsLayoutMath.safeWrapIndex(5005, total))

        // Negative virtual pages
        assertEquals(9, HomeHeroCardsLayoutMath.safeWrapIndex(-1, total))
        assertEquals(8, HomeHeroCardsLayoutMath.safeWrapIndex(-2, total))
        assertEquals(0, HomeHeroCardsLayoutMath.safeWrapIndex(-10, total))

        // Empty safety
        assertEquals(0, HomeHeroCardsLayoutMath.safeWrapIndex(5, 0))
    }
}
