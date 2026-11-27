package com.lagradost.cloudstream3.desktop.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.desktop.ui.DesktopDimens

/**
 * Corner-anchored floating header actions shared across full-bleed drilldown screens.
 */
@Composable
fun DesktopFloatingTopActions(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    showWindowControls: Boolean = true,
    isHome: Boolean = false,
    isCompact: Boolean = false,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        DesktopBackButton(onClick = onBack)
        if (showWindowControls) {
            WindowControlsPill(isHome = isHome, isCompact = isCompact)
        }
    }
}

/**
 * Standard full-bleed drilldown container with transparent root (preserving [DesktopShellBackground]),
 * consistent safe margins below the floating header, and standardized top corner navigation chrome.
 */
@Composable
fun DesktopDrilldownScaffold(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    enableEscapeBack: Boolean = true,
    showWindowControls: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(
        start = DesktopDimens.DrilldownHorizontalPadding,
        end = DesktopDimens.DrilldownHorizontalPadding,
        top = DesktopDimens.DrilldownTopPadding,
        bottom = DesktopDimens.DrilldownBottomPadding,
    ),
    backgroundOverlay: @Composable BoxScope.() -> Unit = {},
    dialogs: @Composable BoxScope.() -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    val rootModifier = modifier
        .fillMaxSize()
        .handleEscapeKey(enabled = enableEscapeBack, onEscape = onBack)

    Box(modifier = rootModifier) {
        backgroundOverlay()

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            content = content,
        )

        DesktopFloatingTopActions(
            onBack = onBack,
            showWindowControls = showWindowControls,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        dialogs()
    }
}
