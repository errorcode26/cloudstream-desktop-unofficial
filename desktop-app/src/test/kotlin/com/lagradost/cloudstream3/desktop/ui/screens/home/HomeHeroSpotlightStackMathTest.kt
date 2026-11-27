package com.lagradost.cloudstream3.desktop.ui.screens.home

import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HomeHeroSpotlightStackMathTest {

    @Test
    fun testQueueIndices_ExcludesActiveItemAndWrapsCleanly() {
        assertEquals(emptyList(), HomeHeroSpotlightStackMath.queueIndices(0, 0))
        assertEquals(emptyList(), HomeHeroSpotlightStackMath.queueIndices(0, 1))
        assertEquals(listOf(1), HomeHeroSpotlightStackMath.queueIndices(0, 2))
        assertEquals(listOf(1, 2, 3, 4, 5), HomeHeroSpotlightStackMath.queueIndices(0, 10))
        assertEquals(listOf(9, 0, 1, 2, 3), HomeHeroSpotlightStackMath.queueIndices(8, 10))
        assertEquals(listOf(0, 1, 2, 3, 4), HomeHeroSpotlightStackMath.queueIndices(9, 10))
    }

    @Test
    fun testQueueDepthCurves_UniformScaleMonotonicFadeShadowAndZOrder() {
        val scales = (0..4).map { HomeHeroSpotlightStackMath.queueScaleForPosition(it) }
        val dims = (0..4).map { HomeHeroSpotlightStackMath.queueDimAlphaForPosition(it) }
        val shadowAlphas = (0..4).map { HomeHeroSpotlightStackMath.queueShadowAlphaForPosition(it) }
        val zIndices = (0..4).map { HomeHeroSpotlightStackMath.queueZIndexForPosition(it) }

        assertEquals(1.0f, scales[0], 0.001f)
        assertEquals(0.0f, dims[0], 0.001f)
        assertEquals(1.0f, shadowAlphas[0], 0.001f)
        for (i in 0 until 4) {
            assertEquals(1.0f, scales[i + 1], 0.001f, "Stack cards keep uniform size at pos ${i + 1}")
            assertTrue(dims[i] < dims[i + 1], "Dimming must strictly increase at pos $i")
            assertTrue(shadowAlphas[i] > shadowAlphas[i + 1], "Cast shadow alpha must decrease at pos $i")
            assertTrue(zIndices[i] > zIndices[i + 1], "zIndex must strictly decrease at pos $i")
        }
        assertTrue(HomeHeroSpotlightStackMath.queueOpacityForPosition(3) in 0.8f..0.95f)
        assertTrue(HomeHeroSpotlightStackMath.queueOpacityForPosition(4) in 0.65f..0.8f)
    }

    @Test
    fun testCalculateQueueStep_RespectsAvailableStackWidthBounds() {
        val posterWidth = HomeHeroSpotlightStackMath.calculatePosterWidth(270.dp)
        assertEquals(180.0f, posterWidth.value, 0.01f)

        val tightBudget = 420.dp
        val tightStep = HomeHeroSpotlightStackMath.calculateQueueStep(posterWidth, 5, tightBudget)
        val totalTightWidth = HomeHeroSpotlightStackMath.calculateTotalStackWidth(posterWidth, 5, tightStep)
        assertTrue(totalTightWidth.value <= tightBudget.value + 0.01f)

        val generousBudget = 800.dp
        val generousStep = HomeHeroSpotlightStackMath.calculateQueueStep(posterWidth, 5, generousBudget)
        assertTrue(generousStep > tightStep)
        assertTrue(generousStep.value <= posterWidth.value * 0.76f + 0.01f)
    }

    @Test
    fun testHeroAndRowHeights_FillsDesktopViewportAndStaysBounded() {
        val compactHero = HomeHeroSpotlightStackMath.calculateHeroHeight(600.dp, 800.dp)
        val compactRow = HomeHeroSpotlightStackMath.calculateRowHeight(compactHero, isCompact = true)
        assertEquals(440f, compactHero.value, 0.01f)
        assertEquals(192f, compactRow.value, 0.01f)

        val desktopHero = HomeHeroSpotlightStackMath.calculateHeroHeight(1440.dp, 900.dp)
        val desktopRow = HomeHeroSpotlightStackMath.calculateRowHeight(desktopHero, isCompact = false)
        assertEquals(844f, desktopHero.value, 0.01f)
        assertTrue(desktopRow.value in 220f..290f)

        val largeHero = HomeHeroSpotlightStackMath.calculateHeroHeight(1920.dp, 1080.dp)
        val largeRow = HomeHeroSpotlightStackMath.calculateRowHeight(largeHero, isCompact = false)
        assertEquals(1024f, largeHero.value, 0.01f)
        assertEquals(290f, largeRow.value, 0.01f)
    }
}
