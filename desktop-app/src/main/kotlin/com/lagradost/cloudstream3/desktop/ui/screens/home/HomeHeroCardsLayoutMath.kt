package com.lagradost.cloudstream3.desktop.ui.screens.home

object HomeHeroCardsLayoutMath {

    fun calculateScale(absOffset: Float): Float {
        return when {
            absOffset <= 1f -> {
                val progress = 1f - absOffset
                0.88f + (1.0f - 0.88f) * progress
            }
            absOffset <= 2f -> {
                val progress = 2f - absOffset
                0.74f + (0.88f - 0.74f) * progress
            }
            absOffset <= 3f -> {
                val progress = 3f - absOffset
                0.60f + (0.74f - 0.60f) * progress
            }
            else -> 0.60f
        }
    }

    fun calculateAlpha(absOffset: Float): Float {
        return when {
            absOffset <= 1f -> {
                val progress = 1f - absOffset
                0.75f + (1.0f - 0.75f) * progress
            }
            absOffset <= 2f -> {
                val progress = 2f - absOffset
                0.45f + (0.75f - 0.45f) * progress
            }
            absOffset <= 3f -> {
                val progress = 3f - absOffset
                0.00f + (0.45f - 0.00f) * progress
            }
            else -> 0.00f
        }
    }

    fun calculateZIndex(absOffset: Float): Float {
        return when {
            absOffset <= 1f -> 10f - absOffset
            absOffset <= 2f -> 5f - absOffset
            absOffset <= 3f -> (2f - absOffset).coerceAtLeast(0f)
            else -> 0f
        }
    }

    fun calculateTranslationX(
        pageOffset: Float,
        stackStepPx: Float,
        cardWidthPx: Float,
        isCompact: Boolean,
    ): Float {
        if (isCompact) return 0f
        val desiredOffsetPx = pageOffset * stackStepPx
        val layoutOffsetPx = pageOffset * cardWidthPx
        return desiredOffsetPx - layoutOffsetPx
    }

    fun calculateDetailsAlpha(absOffset: Float): Float {
        return (1f - absOffset * 2.5f).coerceIn(0f, 1f)
    }

    fun safeWrapIndex(page: Int, totalItems: Int): Int {
        if (totalItems <= 0) return 0
        return ((page % totalItems) + totalItems) % totalItems
    }
}
