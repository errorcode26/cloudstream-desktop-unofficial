package com.lagradost.cloudstream3.desktop.ui

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import com.lagradost.cloudstream3.desktop.ui.components.desktopFocusZone
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.lagradost.cloudstream3.desktop.ui.components.LocalPosterCardStyle
import com.lagradost.cloudstream3.desktop.ui.components.ProfileWelcomeToast
import com.lagradost.cloudstream3.desktop.ui.components.TopBar
import com.lagradost.cloudstream3.desktop.ui.components.rememberPosterCardStyle
import com.lagradost.cloudstream3.desktop.ui.navigation.Config
import com.lagradost.cloudstream3.desktop.ui.screens.home.contract.HomeUiEvent
import com.lagradost.cloudstream3.desktop.ui.screens.home.contract.HomeUiState
import com.lagradost.cloudstream3.desktop.ui.theme.LocalDesktopAppearance
import com.lagradost.cloudstream3.desktop.ui.theme.rememberDesktopAppearance
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

val LocalSafeArea = compositionLocalOf<PaddingValues> { PaddingValues(0.dp) }
val LocalHazeState = compositionLocalOf<HazeState?> { null }

@Composable
fun DesktopAppShell(
    onNavigate: (Config) -> Unit,
    onBack: () -> Unit = {},
    title: String? = null,
    homeUiState: HomeUiState? = null,
    homeActionDispatcher: ((HomeUiEvent) -> Unit)? = null,
    showDock: Boolean = true,
    showTopBar: Boolean = true,
    applySafePadding: Boolean = false,
    onOpenProfileManager: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val appearance = rememberDesktopAppearance()
    val dockPosition = appearance.dockPosition
    val posterCardStyle = rememberPosterCardStyle()
    val hazeState = remember { HazeState() }

    CompositionLocalProvider(
        LocalDesktopAppearance provides appearance,
        LocalPosterCardStyle provides posterCardStyle,
        LocalHazeState provides hazeState,
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val isCompact = maxWidth < 600.dp
                val effectiveDockPosition = if (isCompact) DockPosition.BOTTOM else dockPosition
                val topBarRequester = remember { FocusRequester() }
                val dockRequester = remember { FocusRequester() }
                val contentRequester = remember { FocusRequester() }
                val hasSeparateDock = showDock && (isCompact || effectiveDockPosition != DockPosition.TOP)

                // Main Viewport Container
                Box(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    DesktopShellBackground(
                        modifier = Modifier
                            .fillMaxSize()
                            .hazeSource(state = hazeState),
                    )

                    val contentPadding = calculateShellContentPadding(
                        showDock = showDock,
                        effectiveDockPosition = effectiveDockPosition,
                        isCompact = isCompact,
                        showTopBar = showTopBar,
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(if (applySafePadding) Modifier.padding(contentPadding) else Modifier)
                            .desktopFocusZone(
                                zoneRequester = contentRequester,
                                restoreFocus = true,
                                up = if (showTopBar) {
                                    topBarRequester
                                } else if (hasSeparateDock && !isCompact && effectiveDockPosition == DockPosition.TOP) {
                                    dockRequester
                                } else {
                                    null
                                },
                                down = if (hasSeparateDock && (isCompact || effectiveDockPosition == DockPosition.BOTTOM)) {
                                    dockRequester
                                } else {
                                    null
                                },
                                left = if (hasSeparateDock && !isCompact && effectiveDockPosition == DockPosition.LEFT) {
                                    dockRequester
                                } else {
                                    null
                                },
                                right = if (hasSeparateDock && !isCompact && effectiveDockPosition == DockPosition.RIGHT) {
                                    dockRequester
                                } else {
                                    null
                                },
                            ),
                    ) {
                        CompositionLocalProvider(LocalSafeArea provides contentPadding) {
                            content()
                        }
                    }
                }

                if (showTopBar) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter)
                            .desktopFocusZone(
                                zoneRequester = topBarRequester,
                                restoreFocus = true,
                                down = if (hasSeparateDock && !isCompact && effectiveDockPosition == DockPosition.TOP) {
                                    dockRequester
                                } else {
                                    contentRequester
                                },
                                left = if (hasSeparateDock && !isCompact && effectiveDockPosition == DockPosition.LEFT) {
                                    dockRequester
                                } else {
                                    null
                                },
                                right = if (hasSeparateDock && !isCompact && effectiveDockPosition == DockPosition.RIGHT) {
                                    dockRequester
                                } else {
                                    null
                                },
                            ),
                    ) {
                        TopBar(
                            isHome = title == "Home",
                            homeUiState = homeUiState,
                            homeActionDispatcher = homeActionDispatcher,
                            onBack = onBack,
                            onOpenProfileManager = onOpenProfileManager,
                            showDock = showDock,
                            currentTitle = title ?: "",
                            onNavigate = onNavigate,
                            onSearchClick = { onNavigate(Config.Search) },
                        )
                    }
                }

                DesktopShellOfflineBanner(
                    modifier = Modifier.align(Alignment.TopCenter),
                )

                ProfileWelcomeToast()

                if (showDock) {
                    if (isCompact) {
                        MobileBottomNavBar(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .zIndex(90f)
                                .desktopFocusZone(
                                    zoneRequester = dockRequester,
                                    restoreFocus = true,
                                    up = contentRequester,
                                ),
                            currentTitle = title ?: "",
                            onNavigate = onNavigate,
                            onSearchClick = { onNavigate(Config.Search) },
                        )
                    } else if (effectiveDockPosition != DockPosition.TOP) {
                        val dockAlignment = when (effectiveDockPosition) {
                            DockPosition.RIGHT -> Alignment.CenterEnd
                            DockPosition.BOTTOM -> Alignment.BottomCenter
                            else -> Alignment.CenterStart
                        }
                        NavigationDock(
                            modifier = Modifier.align(dockAlignment),
                            dockFocusModifier = Modifier.desktopFocusZone(
                                zoneRequester = dockRequester,
                                restoreFocus = true,
                                up = if (showTopBar && effectiveDockPosition != DockPosition.BOTTOM) topBarRequester else if (effectiveDockPosition == DockPosition.BOTTOM) contentRequester else null,
                                down = if (effectiveDockPosition == DockPosition.TOP) contentRequester else null,
                                left = if (effectiveDockPosition == DockPosition.RIGHT) contentRequester else null,
                                right = if (effectiveDockPosition == DockPosition.LEFT) contentRequester else null,
                            ),
                            currentTitle = title ?: "",
                            dockPosition = effectiveDockPosition,
                            onNavigate = onNavigate,
                            onSearchClick = { onNavigate(Config.Search) },
                            onOpenProfileManager = onOpenProfileManager,
                        )
                    }
                }
            }
        }
    }
}
