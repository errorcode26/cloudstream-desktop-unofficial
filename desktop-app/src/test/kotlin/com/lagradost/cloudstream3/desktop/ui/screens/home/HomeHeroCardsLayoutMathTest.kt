package com.lagradost.cloudstream3.desktop.ui.screens.home

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HomeHeroCardsLayoutMathTest {

    @Test
    fun `effectivePeeksPerSide caps small catalogs symmetrically and allows 4 peeks for 9+ items`() {
        assertEquals(0, HomeHeroCardsLayoutMath.effectivePeeksPerSide(0))
        assertEquals(0, HomeHeroCardsLayoutMath.effectivePeeksPerSide(1))
        assertEquals(1, HomeHeroCardsLayoutMath.effectivePeeksPerSide(2))
        assertEquals(1, HomeHeroCardsLayoutMath.effectivePeeksPerSide(3))
        assertEquals(1, HomeHeroCardsLayoutMath.effectivePeeksPerSide(4))
        assertEquals(2, HomeHeroCardsLayoutMath.effectivePeeksPerSide(5))
        assertEquals(3, HomeHeroCardsLayoutMath.effectivePeeksPerSide(8))
        assertEquals(4, HomeHeroCardsLayoutMath.effectivePeeksPerSide(9))
        assertEquals(4, HomeHeroCardsLayoutMath.effectivePeeksPerSide(12))
    }

    @Test
    fun `shortestCircularDelta picks minimal wrapped path forward and backward`() {
        assertEquals(0f, HomeHeroCardsLayoutMath.shortestCircularDelta(0, 0f, 10), 0.001f)
        assertEquals(4f, HomeHeroCardsLayoutMath.shortestCircularDelta(4, 0f, 10), 0.001f)
        assertEquals(-4f, HomeHeroCardsLayoutMath.shortestCircularDelta(6, 0f, 10), 0.001f)
        assertEquals(-1f, HomeHeroCardsLayoutMath.shortestCircularDelta(9, 0f, 10), 0.001f)
        assertEquals(1f, HomeHeroCardsLayoutMath.shortestCircularDelta(0, 9f, 10), 0.001f)
        assertEquals(-1.25f, HomeHeroCardsLayoutMath.shortestCircularDelta(8, 9.25f, 10), 0.001f)
    }

    @Test
    fun `stack translation produces 4 strictly receding outer edges inside available side width`() {
        val cardWidthPx = 640f
        val availableSidePx = 220f
        val peeks = 4

        var prevOuterEdge = cardWidthPx * 0.5f
        for (layer in 1..peeks) {
            val rightX = HomeHeroCardsLayoutMath.calculateStackTranslationXPx(
                signedOffset = layer.toFloat(),
                cardWidthPx = cardWidthPx,
                availableSidePx = availableSidePx,
                effectivePeeks = peeks,
            )
            val leftX = HomeHeroCardsLayoutMath.calculateStackTranslationXPx(
                signedOffset = -layer.toFloat(),
                cardWidthPx = cardWidthPx,
                availableSidePx = availableSidePx,
                effectivePeeks = peeks,
            )
            assertEquals(-rightX, leftX, 0.01f)

            val scale = HomeHeroCardsLayoutMath.calculateScale(layer.toFloat())
            val outerEdge = rightX + (cardWidthPx * 0.5f * scale)
            assertTrue(outerEdge > prevOuterEdge + 5f, "Layer $layer outer edge ($outerEdge) must peek outside layer ${layer - 1} ($prevOuterEdge)")
            assertTrue(outerEdge <= cardWidthPx * 0.5f + availableSidePx + 0.5f, "Layer $layer outer edge must remain inside available stage margin")
            prevOuterEdge = outerEdge
        }

        // Wide HiDPI stage: 4th layer should expand proportionally into availableSidePx instead of hitting a low raw-px ceiling
        val wideCardPx = 1200f
        val wideSidePx = 620f
        val wideLayer4X = HomeHeroCardsLayoutMath.calculateStackTranslationXPx(
            signedOffset = 4f,
            cardWidthPx = wideCardPx,
            availableSidePx = wideSidePx,
            effectivePeeks = 4,
        )
        val wideLayer4Outer = wideLayer4X + wideCardPx * 0.5f * HomeHeroCardsLayoutMath.calculateScale(4f)
        assertTrue(
            wideLayer4Outer - (wideCardPx * 0.5f) > 550f,
            "Wide stage flank should expand proportionally without dead space",
        )
    }

    @Test
    fun `scale zIndex and dim alpha vary monotonically with stack depth and wrap fades out before seam`() {
        val scales = (0..4).map { HomeHeroCardsLayoutMath.calculateScale(it.toFloat()) }
        val zIndices = (0..4).map { HomeHeroCardsLayoutMath.calculateZIndex(it.toFloat()) }
        val dims = (0..4).map { HomeHeroCardsLayoutMath.calculateDimAlpha(it.toFloat()) }

        assertEquals(0f, dims[0], 0.001f)
        for (i in 0 until 4) {
            assertTrue(scales[i] > scales[i + 1])
            assertTrue(zIndices[i] > zIndices[i + 1])
            assertTrue(dims[i] < dims[i + 1])
        }

        // Across list sizes 9..12, cards at layer 4 are solid and fade to 0 strictly before half-ring seam
        for (count in listOf(9, 10, 11, 12)) {
            assertEquals(1f, HomeHeroCardsLayoutMath.calculateAlpha(4f, effectivePeeks = 4, totalItems = count), 0.001f)
            assertEquals(0f, HomeHeroCardsLayoutMath.calculateAlpha(count * 0.5f, effectivePeeks = 4, totalItems = count), 0.001f)
        }
    }
}
