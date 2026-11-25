package com.lagradost.cloudstream3.desktop.ui.screens.home

data class HeroEdgeFadeStops(
    val leftFadeEnd: Float,
    val leftMidLow: Float,
    val leftMidHigh: Float,
    val leftFadeStart: Float,
    val rightFadeStart: Float,
    val rightMidHigh: Float,
    val rightMidLow: Float,
    val rightFadeEnd: Float,
)

object HomeHeroCardsLayoutMath {

    const val BACKDROP_PARALLAX_SCALE = 1.08f
    const val MAX_BACKDROP_PARALLAX_RATIO = 0.032f
    const val MAX_FOREGROUND_PARALLAX_RATIO = 0.06f

    /** Uniform full height across center and spaced flanking cinema cards. */
    fun calculateScale(absOffset: Float): Float {
        return 1.0f
    }

    /** Keep center and immediate ±1..±2 cards solid while fading far offscreen slides. */
    fun calculateAlpha(absOffset: Float): Float {
        return when {
            absOffset <= 2f -> 1.0f
            absOffset <= 3f -> (3f - absOffset).coerceIn(0f, 1f)
            else -> 0.0f
        }
    }

    /** Soft dark surface dim on flanking cards so active center hero maintains visual focus. */
    fun calculateDimAlpha(absOffset: Float): Float {
        return when {
            absOffset <= 1f -> 0.28f * absOffset
            absOffset <= 2f -> 0.28f + (0.45f - 0.28f) * (absOffset - 1f)
            else -> 0.45f
        }
    }

    fun calculateZIndex(absOffset: Float): Float {
        return (10f - absOffset).coerceAtLeast(0f)
    }

    fun calculateDetailsAlpha(absOffset: Float): Float {
        return (1f - absOffset * 2.2f).coerceIn(0f, 1f)
    }

    /**
     * Counter-translates the 1.08x overscaled backdrop inside card bounds so the artwork lags behind
     * horizontal scrolling while staying strictly within the 4% per-side image overhang.
     */
    fun calculateBackdropParallaxPx(pageOffset: Float, cardWidthPx: Float): Float {
        if (cardWidthPx <= 0f) return 0f
        val clamped = pageOffset.coerceIn(-1f, 1f)
        return -clamped * (cardWidthPx * MAX_BACKDROP_PARALLAX_RATIO)
    }

    /** Subtle forward horizontal separation for foreground text/CTA layers during page switches. */
    fun calculateForegroundParallaxPx(
        pageOffset: Float,
        cardWidthPx: Float,
        depthMultiplier: Float = 1f,
    ): Float {
        if (cardWidthPx <= 0f) return 0f
        val clamped = pageOffset.coerceIn(-1f, 1f)
        return clamped * (cardWidthPx * MAX_FOREGROUND_PARALLAX_RATIO) * depthMultiplier
    }

    /**
     * Normalized horizontal gradient stops for BlendMode.DstIn masking outer flanking cinema peeks
     * smoothly toward the edges while keeping the center card and inner ~42% of ±1 peeks crisp.
     */
    fun calculateEdgeFadeStops(
        canvasWidthPx: Float,
        cardWidthPx: Float,
        gapPx: Float,
        sideClearancePx: Float,
    ): HeroEdgeFadeStops {
        if (canvasWidthPx <= 0f) {
            return HeroEdgeFadeStops(0.03f, 0.08f, 0.14f, 0.19f, 0.81f, 0.86f, 0.92f, 0.97f)
        }
        val halfWidthPx = canvasWidthPx * 0.5f
        val peekInnerEdgePx = (cardWidthPx * 0.5f) + gapPx
        val peekInnerEdgeFrac = ((halfWidthPx - peekInnerEdgePx) / canvasWidthPx).coerceIn(0.08f, 0.44f)

        val leftFadeEnd = (sideClearancePx / canvasWidthPx)
            .coerceIn(0.01f, (peekInnerEdgeFrac - 0.05f).coerceAtLeast(0.01f))
        val leftFadeStart = (leftFadeEnd + (peekInnerEdgeFrac - leftFadeEnd) * 0.58f)
            .coerceIn(leftFadeEnd + 0.04f, 0.46f)
        val span = leftFadeStart - leftFadeEnd
        val leftMidLow = leftFadeEnd + span * 0.35f
        val leftMidHigh = leftFadeEnd + span * 0.72f

        return HeroEdgeFadeStops(
            leftFadeEnd = leftFadeEnd,
            leftMidLow = leftMidLow,
            leftMidHigh = leftMidHigh,
            leftFadeStart = leftFadeStart,
            rightFadeStart = 1f - leftFadeStart,
            rightMidHigh = 1f - leftMidHigh,
            rightMidLow = 1f - leftMidLow,
            rightFadeEnd = 1f - leftFadeEnd,
        )
    }

    fun safeWrapIndex(page: Int, totalItems: Int): Int {
        if (totalItems <= 0) return 0
        return ((page % totalItems) + totalItems) % totalItems
    }
}
