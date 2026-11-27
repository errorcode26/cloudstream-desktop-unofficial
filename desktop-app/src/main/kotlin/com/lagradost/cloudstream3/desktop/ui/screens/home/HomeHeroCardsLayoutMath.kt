package com.lagradost.cloudstream3.desktop.ui.screens.home

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

object HomeHeroCardsLayoutMath {

    const val MAX_PEEK_CARDS_PER_SIDE = 4
    const val DEFAULT_SCALE_STEP = 0.065f
    const val BACKDROP_PARALLAX_SCALE = 1.08f
    const val MAX_BACKDROP_PARALLAX_RATIO = 0.032f
    const val MAX_FOREGROUND_PARALLAX_RATIO = 0.06f

    /** Caps symmetrical flanking card count so short catalogs never mirror an item on both wings. */
    fun effectivePeeksPerSide(
        itemCount: Int,
        maxPeeks: Int = MAX_PEEK_CARDS_PER_SIDE,
    ): Int {
        if (itemCount <= 1 || maxPeeks <= 0) return 0
        if (itemCount == 2) return 1
        return min(maxPeeks, (itemCount - 1) / 2)
    }

    /** Signed shortest angular distance on the circular hero ring. */
    fun shortestCircularDelta(
        index: Int,
        currentPosition: Float,
        totalItems: Int,
    ): Float {
        if (totalItems <= 0) return 0f
        val wrappedPos = ((currentPosition % totalItems) + totalItems) % totalItems
        var delta = ((index.toFloat() - wrappedPos) % totalItems + totalItems) % totalItems
        if (delta > totalItems * 0.5f) {
            delta -= totalItems
        }
        return delta
    }

    /** Symmetrical scale step receding into stack depth (`1.00 -> 0.935 -> 0.87 -> 0.805 -> 0.74`). */
    fun calculateScale(
        absOffset: Float,
        scaleStep: Float = DEFAULT_SCALE_STEP,
    ): Float {
        return (1f - absOffset.coerceAtLeast(0f) * scaleStep).coerceAtLeast(0.55f)
    }

    /** Solid across all active + peeking wings, fading before crossing the opposite ring seam. */
    fun calculateAlpha(
        absOffset: Float,
        effectivePeeks: Int = MAX_PEEK_CARDS_PER_SIDE,
        totalItems: Int = 12,
    ): Float {
        if (totalItems <= 2) return 1f
        val fadeStart = effectivePeeks.coerceAtLeast(0).toFloat()
        val fadeEnd = min(fadeStart + 0.85f, totalItems * 0.5f - 0.05f)
        if (absOffset <= fadeStart) return 1f
        if (fadeEnd <= fadeStart || absOffset >= fadeEnd) return 0f
        return ((fadeEnd - absOffset) / (fadeEnd - fadeStart)).coerceIn(0f, 1f)
    }

    /** Progressive surface dimming keeps outer stacked cards readable while separating edges. */
    fun calculateDimAlpha(absOffset: Float): Float {
        val safe = absOffset.coerceAtLeast(0f)
        return if (safe <= 0.001f) {
            0f
        } else {
            (0.12f + safe * 0.11f).coerceIn(0f, 0.58f)
        }
    }

    fun calculateZIndex(absOffset: Float): Float {
        return (60f - absOffset.coerceAtLeast(0f) * 10f).coerceAtLeast(1f)
    }

    fun calculateDetailsAlpha(absOffset: Float): Float {
        return (1f - absOffset * 2.4f).coerceIn(0f, 1f)
    }

    /**
     * Computes horizontal translation relative to stage center so each deeper stacked layer
     * fills [availableSidePx] proportionally up to half a card width per flank without DPI-dependent clipping.
     */
    fun calculateStackTranslationXPx(
        signedOffset: Float,
        cardWidthPx: Float,
        availableSidePx: Float,
        effectivePeeks: Int = MAX_PEEK_CARDS_PER_SIDE,
        scaleStep: Float = DEFAULT_SCALE_STEP,
    ): Float {
        val absOffset = abs(signedOffset)
        if (absOffset <= 0.0001f || cardWidthPx <= 0f || effectivePeeks <= 0) return 0f

        val halfCardPx = cardWidthPx * 0.5f
        val safeAvailablePx = availableSidePx.coerceAtLeast(0f)
        val maxHealthyReachPx = cardWidthPx * 0.54f
        val minIdealReachPx = cardWidthPx * 0.28f
        val targetSideReachPx = min(safeAvailablePx, max(minIdealReachPx, maxHealthyReachPx))
            .coerceAtMost(safeAvailablePx)

        fun stepWeight(layer: Int): Float = max(0.55f, 1.18f - (layer - 1) * 0.14f)

        var weightSum = 0f
        for (layer in 1..effectivePeeks) {
            weightSum += stepWeight(layer)
        }
        val effectivePeekPx = if (weightSum > 0f) targetSideReachPx / weightSum else 0f

        fun ringCenterOffset(layer: Int): Float {
            if (layer <= 0) return 0f
            var outerEdge = halfCardPx
            for (k in 1..layer) {
                outerEdge += effectivePeekPx * stepWeight(k)
            }
            val layerScale = calculateScale(layer.toFloat(), scaleStep)
            return (outerEdge - halfCardPx * layerScale).coerceAtLeast(0f)
        }

        val maxLayer = effectivePeeks + 2
        val lowLayer = floor(absOffset).toInt().coerceIn(0, maxLayer)
        val highLayer = (lowLayer + 1).coerceAtMost(maxLayer)
        val frac = (absOffset - lowLayer).coerceIn(0f, 1f)
        val lowX = ringCenterOffset(lowLayer)
        val highX = ringCenterOffset(highLayer)
        val magnitude = lowX + (highX - lowX) * frac
        return if (signedOffset < 0f) -magnitude else magnitude
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

    fun safeWrapIndex(page: Int, totalItems: Int): Int {
        if (totalItems <= 0) return 0
        return ((page % totalItems) + totalItems) % totalItems
    }
}
