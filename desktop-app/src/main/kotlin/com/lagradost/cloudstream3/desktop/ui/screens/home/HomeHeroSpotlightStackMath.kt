package com.lagradost.cloudstream3.desktop.ui.screens.home

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Pure layout geometry and queue calculation helpers for the Glass Spotlight Stack hero.
 */
object HomeHeroSpotlightStackMath {

    private const val MIN_DESKTOP_HERO_HEIGHT_DP = 520f
    private const val COMPACT_HERO_HEIGHT_DP = 440f

    private const val MIN_ROW_HEIGHT_DP = 220f
    private const val MAX_ROW_HEIGHT_DP = 290f
    private const val COMPACT_ROW_HEIGHT_DP = 192f

    private const val POSTER_ASPECT_RATIO = 2f / 3f

    /**
     * Occupies the full desktop first fold so catalog rows do not compete directly underneath
     * the right-hand poster stack until scrolling down.
     */
    fun calculateHeroHeight(rawWidth: Dp, rawHeight: Dp): Dp {
        if (rawWidth < 760.dp) return COMPACT_HERO_HEIGHT_DP.dp
        return (rawHeight.value - 56f).coerceAtLeast(MIN_DESKTOP_HERO_HEIGHT_DP).dp
    }

    fun calculateRowHeight(heroHeight: Dp, isCompact: Boolean): Dp {
        if (isCompact) return COMPACT_ROW_HEIGHT_DP.dp
        return (heroHeight.value * 0.34f)
            .coerceIn(MIN_ROW_HEIGHT_DP, MAX_ROW_HEIGHT_DP)
            .dp
    }

    fun calculatePosterWidth(rowHeight: Dp): Dp {
        return (rowHeight.value * POSTER_ASPECT_RATIO).dp
    }

    /**
     * Returns the ordered display indices for the upcoming poster stack on the right,
     * excluding the active spotlight slide at [currentIndex].
     */
    fun queueIndices(
        currentIndex: Int,
        totalItems: Int,
        maxQueueCount: Int = 5,
    ): List<Int> {
        if (totalItems <= 1 || maxQueueCount <= 0) return emptyList()
        val safeCurrent = HomeHeroCardsLayoutMath.safeWrapIndex(currentIndex, totalItems)
        val count = minOf(maxQueueCount, totalItems - 1)
        return (1..count).map { offset -> (safeCurrent + offset) % totalItems }
    }

    fun queueScaleForPosition(queuePos: Int): Float {
        return 1.00f
    }

    fun queueDimAlphaForPosition(queuePos: Int): Float {
        return when (queuePos) {
            0 -> 0.00f
            1 -> 0.12f
            2 -> 0.24f
            3 -> 0.36f
            else -> 0.48f
        }
    }

    fun queueOpacityForPosition(queuePos: Int): Float {
        return when (queuePos) {
            0 -> 1.00f
            1 -> 1.00f
            2 -> 0.96f
            3 -> 0.86f
            else -> 0.72f
        }
    }

    fun queueZIndexForPosition(queuePos: Int): Float {
        return (50f - queuePos * 8f).coerceAtLeast(1f)
    }

    fun queueShadowAlphaForPosition(queuePos: Int): Float {
        return when (queuePos) {
            0 -> 1.00f
            1 -> 0.78f
            2 -> 0.56f
            3 -> 0.36f
            else -> 0.20f
        }
    }

    fun queueElevationForPosition(queuePos: Int): Float {
        return when (queuePos) {
            0 -> 22f
            1 -> 16f
            2 -> 12f
            3 -> 8f
            else -> 6f
        }
    }

    /**
     * Computes horizontal offset step per queued poster card scaled from left edge
     * so stacked shoulders stay well exposed while fitting within [availableStackWidth].
     */
    fun calculateQueueStep(
        posterWidth: Dp,
        visibleQueueCount: Int,
        availableStackWidth: Dp,
    ): Dp {
        if (visibleQueueCount <= 1 || posterWidth <= 0.dp) return 0.dp
        val nominalStep = posterWidth.value * 0.76f
        val tailScale = queueScaleForPosition(visibleQueueCount - 1)
        val remainingSpace = (availableStackWidth.value - posterWidth.value * tailScale).coerceAtLeast(0f)
        val maxStep = remainingSpace / (visibleQueueCount - 1).toFloat()
        return minOf(nominalStep, maxStep).coerceAtLeast(0f).dp
    }

    fun calculateTotalStackWidth(
        posterWidth: Dp,
        visibleQueueCount: Int,
        step: Dp,
    ): Dp {
        if (visibleQueueCount <= 0 || posterWidth <= 0.dp) return 0.dp
        val tailScale = queueScaleForPosition(visibleQueueCount - 1)
        return (step.value * (visibleQueueCount - 1) + posterWidth.value * tailScale).dp
    }
}
