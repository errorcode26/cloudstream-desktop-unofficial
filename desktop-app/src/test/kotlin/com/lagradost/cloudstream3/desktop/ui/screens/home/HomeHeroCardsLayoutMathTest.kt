package com.lagradost.cloudstream3.desktop.ui.screens.home

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HomeHeroCardsLayoutMathTest {

    @Test
    fun testCalculateScale_MonotonicallyDecreasesWithOffset() {
        val scale0 = HomeHeroCardsLayoutMath.calculateScale(0.0f)
        val scale1 = HomeHeroCardsLayoutMath.calculateScale(1.0f)
        val scale2 = HomeHeroCardsLayoutMath.calculateScale(2.0f)
        val scale3 = HomeHeroCardsLayoutMath.calculateScale(3.0f)
        val scale4 = HomeHeroCardsLayoutMath.calculateScale(4.0f)

        assertEquals(1.0f, scale0, 0.001f)
        assertEquals(0.88f, scale1, 0.001f)
        assertEquals(0.74f, scale2, 0.001f)
        assertEquals(0.60f, scale3, 0.001f)
        assertEquals(0.60f, scale4, 0.001f)

        assertTrue(scale0 > scale1)
        assertTrue(scale1 > scale2)
        assertTrue(scale2 > scale3)
    }

    @Test
    fun testCalculateAlpha_ContinuousWithoutStepCutoff() {
        val alpha0 = HomeHeroCardsLayoutMath.calculateAlpha(0.0f)
        val alpha1 = HomeHeroCardsLayoutMath.calculateAlpha(1.0f)
        val alpha2 = HomeHeroCardsLayoutMath.calculateAlpha(2.0f)
        val alpha25 = HomeHeroCardsLayoutMath.calculateAlpha(2.5f)
        val alpha3 = HomeHeroCardsLayoutMath.calculateAlpha(3.0f)
        val alpha4 = HomeHeroCardsLayoutMath.calculateAlpha(4.0f)

        assertEquals(1.0f, alpha0, 0.001f)
        assertEquals(0.75f, alpha1, 0.001f)
        assertEquals(0.45f, alpha2, 0.001f)
        assertTrue(alpha25 in 0.0f..0.45f)
        assertEquals(0.0f, alpha3, 0.001f)
        assertEquals(0.0f, alpha4, 0.001f)

        // Verify smooth continuity around offset 2.0
        val alpha1_99 = HomeHeroCardsLayoutMath.calculateAlpha(1.99f)
        val alpha2_01 = HomeHeroCardsLayoutMath.calculateAlpha(2.01f)
        assertTrue(kotlin.math.abs(alpha1_99 - alpha2_01) < 0.01f)
    }

    @Test
    fun testCalculateZIndex_StrictHierarchy() {
        val z0 = HomeHeroCardsLayoutMath.calculateZIndex(0.0f)
        val z1 = HomeHeroCardsLayoutMath.calculateZIndex(1.0f)
        val z2 = HomeHeroCardsLayoutMath.calculateZIndex(2.0f)
        val z3 = HomeHeroCardsLayoutMath.calculateZIndex(3.0f)

        assertTrue(z0 > z1, "Center card must have higher zIndex than offset 1")
        assertTrue(z1 > z2, "Offset 1 card must have higher zIndex than offset 2")
        assertTrue(z2 >= z3, "Offset 2 card must have higher or equal zIndex than offset 3")
    }

    @Test
    fun testCalculateTranslationX_PullsFlankingCardsInward() {
        val stepPx = 200f
        val widthPx = 700f

        val transCenter = HomeHeroCardsLayoutMath.calculateTranslationX(0f, stepPx, widthPx, false)
        assertEquals(0f, transCenter)

        val transRight = HomeHeroCardsLayoutMath.calculateTranslationX(1f, stepPx, widthPx, false)
        assertEquals(-500f, transRight, "Right card must translate left to pull inward")

        val transLeft = HomeHeroCardsLayoutMath.calculateTranslationX(-1f, stepPx, widthPx, false)
        assertEquals(500f, transLeft, "Left card must translate right to pull inward")

        val transCompact = HomeHeroCardsLayoutMath.calculateTranslationX(1f, stepPx, widthPx, true)
        assertEquals(0f, transCompact, "Compact mode must disable stack offset translation")
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
