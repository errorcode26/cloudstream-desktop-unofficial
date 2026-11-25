package com.lagradost.cloudstream3.desktop.ui.screens.settings.appearance

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.desktop.ui.screens.settings.SettingsHubCard
import com.lagradost.cloudstream3.desktop.ui.screens.settings.SettingsSubScreen
import com.lagradost.cloudstream3.desktop.ui.theme.AppearanceConfig

@Composable
fun SettingsAppearanceScreen(onNavigateToSubScreen: (SettingsSubScreen) -> Unit = {}) {
    val isLightMode by AppearanceConfig.isLightMode.collectAsState()
    val amoledMode by AppearanceConfig.amoledMode.collectAsState()
    val themeAccent by AppearanceConfig.themeAccent.collectAsState()
    val selectedFont by AppearanceConfig.selectedFont.collectAsState()
    val ambientGlowEnabled by AppearanceConfig.ambientGlowEnabled.collectAsState()
    val bgImagePath by AppearanceConfig.backgroundImagePath.collectAsState()
    val performanceModeEnabled by AppearanceConfig.performanceModeEnabled.collectAsState()
    val providerBadgeDisplayMode by AppearanceConfig.providerBadgeDisplayMode.collectAsState()
    val heroEnabled by AppearanceConfig.heroEnabled.collectAsState()
    val dockPosition by AppearanceConfig.dockPosition.collectAsState()
    val scrollState = rememberScrollState()

    val themeBadge = when {
        isLightMode -> "Light Theme"
        amoledMode -> "AMOLED Black"
        else -> "$themeAccent Accent"
    }

    val ambienceBadge = when {
        performanceModeEnabled -> "Performance Mode"
        bgImagePath.isNotBlank() -> "Wallpaper Active"
        ambientGlowEnabled -> "Ambient Glow"
        else -> "No Effects"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(top = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // 1 — Theme & Colors
        SettingsHubCard(
            icon = Icons.Default.Palette,
            title = "Theme & Colors",
            subtitle = "Light/Dark/AMOLED mode, button & profile shapes, accent color, canvas background, and curated presets.",
            badge = themeBadge,
            onClick = { onNavigateToSubScreen(SettingsSubScreen.APPEARANCE_THEME_WALLPAPER) },
        )

        // 2 — Typography
        SettingsHubCard(
            icon = Icons.Default.TextFields,
            title = "Typography",
            subtitle = "App-wide font family selection and custom font file installer (.ttf / .otf).",
            badge = selectedFont,
            onClick = { onNavigateToSubScreen(SettingsSubScreen.APPEARANCE_TYPOGRAPHY) },
        )

        // 3 — Wallpaper & Ambience
        SettingsHubCard(
            icon = Icons.Default.Wallpaper,
            title = "Wallpaper & Ambience",
            subtitle = "Custom background image, ambient glow, blur and vignette controls, and performance mode for lower-end hardware.",
            badge = ambienceBadge,
            onClick = { onNavigateToSubScreen(SettingsSubScreen.APPEARANCE_WALLPAPER_AMBIENCE) },
        )

        // 4 — Posters & Cards
        SettingsHubCard(
            icon = Icons.Default.Dashboard,
            title = "Posters & Cards",
            subtitle = "Clean mode, provider badges, title auto-cleaning, quality tags, card shadows, and Poster Studio.",
            badge = providerBadgeDisplayMode.label,
            onClick = { onNavigateToSubScreen(SettingsSubScreen.APPEARANCE_POSTERS_BADGES) },
        )

        // 5 — Home Screen
        SettingsHubCard(
            icon = Icons.Default.PlayArrow,
            title = "Home Screen",
            subtitle = "Hero spotlight carousel, Continue Watching row, banner layout style, auto-slide delay, and backdrop blur.",
            badge = if (heroEnabled) "Hero Active" else "Hero Off",
            onClick = { onNavigateToSubScreen(SettingsSubScreen.APPEARANCE_HOME_FEED) },
        )

        // 6 — Details Page Layout
        SettingsHubCard(
            icon = Icons.Default.Layers,
            title = "Details Page Layout",
            subtitle = "Modular drag-and-drop section ordering. Show or hide trailers, cast, watch history, and recommendations.",
            badge = "Modular Sections",
            onClick = { onNavigateToSubScreen(SettingsSubScreen.DETAILS_LAYOUT) },
        )

        // 7 — Navigation & Dock
        SettingsHubCard(
            icon = Icons.Default.GridView,
            title = "Navigation & Dock",
            subtitle = "Dock position, floating vs edge style, button reorder, global UI zoom scale, and clock format.",
            badge = "${dockPosition.label} Dock",
            onClick = { onNavigateToSubScreen(SettingsSubScreen.APPEARANCE_NAV_DOCK) },
        )
    }
}

