package com.lagradost.cloudstream3.desktop.ui

import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class DesktopShellDockTest {

    @Test
    fun testContentPaddingDesktopDockBottom() {
        val padding = calculateShellContentPadding(
            showDock = true,
            effectiveDockPosition = DockPosition.BOTTOM,
            isCompact = false,
            showTopBar = true,
        )

        assertEquals(16.dp, padding.calculateStartPadding(LayoutDirection.Ltr))
        assertEquals(80.dp, padding.calculateTopPadding()) // 64 + 16
        assertEquals(16.dp, padding.calculateEndPadding(LayoutDirection.Ltr))
        assertEquals(98.dp, padding.calculateBottomPadding()) // 82 + 16
    }

    @Test
    fun testContentPaddingDesktopDockLeft() {
        val padding = calculateShellContentPadding(
            showDock = true,
            effectiveDockPosition = DockPosition.LEFT,
            isCompact = false,
            showTopBar = true,
        )

        assertEquals(98.dp, padding.calculateStartPadding(LayoutDirection.Ltr)) // 82 + 16
        assertEquals(80.dp, padding.calculateTopPadding()) // 64 + 16
        assertEquals(16.dp, padding.calculateEndPadding(LayoutDirection.Ltr))
        assertEquals(16.dp, padding.calculateBottomPadding())
    }

    @Test
    fun testContentPaddingDesktopDockRight() {
        val padding = calculateShellContentPadding(
            showDock = true,
            effectiveDockPosition = DockPosition.RIGHT,
            isCompact = false,
            showTopBar = true,
        )

        assertEquals(16.dp, padding.calculateStartPadding(LayoutDirection.Ltr))
        assertEquals(80.dp, padding.calculateTopPadding())
        assertEquals(98.dp, padding.calculateEndPadding(LayoutDirection.Ltr)) // 82 + 16
        assertEquals(16.dp, padding.calculateBottomPadding())
    }

    @Test
    fun testContentPaddingDesktopDockTop() {
        val padding = calculateShellContentPadding(
            showDock = true,
            effectiveDockPosition = DockPosition.TOP,
            isCompact = false,
            showTopBar = true,
        )

        assertEquals(16.dp, padding.calculateStartPadding(LayoutDirection.Ltr))
        assertEquals(84.dp, padding.calculateTopPadding()) // 68 + 16
        assertEquals(16.dp, padding.calculateEndPadding(LayoutDirection.Ltr))
        assertEquals(16.dp, padding.calculateBottomPadding())
    }

    @Test
    fun testContentPaddingCompactMobile() {
        val padding = calculateShellContentPadding(
            showDock = true,
            effectiveDockPosition = DockPosition.BOTTOM,
            isCompact = true,
            showTopBar = true,
        )

        assertEquals(8.dp, padding.calculateStartPadding(LayoutDirection.Ltr))
        assertEquals(62.dp, padding.calculateTopPadding()) // 54 + 8
        assertEquals(8.dp, padding.calculateEndPadding(LayoutDirection.Ltr))
        assertEquals(72.dp, padding.calculateBottomPadding()) // 64 + 8
    }

    @Test
    fun testContentPaddingNoDockNoTopBar() {
        val padding = calculateShellContentPadding(
            showDock = false,
            effectiveDockPosition = DockPosition.BOTTOM,
            isCompact = false,
            showTopBar = false,
        )

        assertEquals(16.dp, padding.calculateStartPadding(LayoutDirection.Ltr))
        assertEquals(16.dp, padding.calculateTopPadding()) // 0 + 16
        assertEquals(16.dp, padding.calculateEndPadding(LayoutDirection.Ltr))
        assertEquals(16.dp, padding.calculateBottomPadding())
    }
}
