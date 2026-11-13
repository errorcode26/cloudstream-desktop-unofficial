package com.lagradost.cloudstream3.desktop.ui.screens.settings

import androidx.compose.runtime.Composable
import com.lagradost.cloudstream3.desktop.ui.screens.settings.appearance.*

@Composable
fun SettingsAppearanceScreen(onNavigateToSubScreen: (SettingsSubScreen) -> Unit = {}) =
    com.lagradost.cloudstream3.desktop.ui.screens.settings.appearance.SettingsAppearanceScreen(onNavigateToSubScreen)

@Composable
fun SettingsNavDockScreen() =
    com.lagradost.cloudstream3.desktop.ui.screens.settings.appearance.SettingsNavDockScreen()

@Composable
fun SettingsThemeWallpaperScreen() =
    com.lagradost.cloudstream3.desktop.ui.screens.settings.appearance.SettingsThemeWallpaperScreen()

@Composable
fun SettingsTypographyScreen() =
    com.lagradost.cloudstream3.desktop.ui.screens.settings.appearance.SettingsTypographyScreen()

@Composable
fun SettingsWallpaperAmbienceScreen() =
    com.lagradost.cloudstream3.desktop.ui.screens.settings.appearance.SettingsWallpaperAmbienceScreen()

@Composable
fun SettingsPostersBadgesScreen(onNavigateToSubScreen: (SettingsSubScreen) -> Unit = {}) =
    com.lagradost.cloudstream3.desktop.ui.screens.settings.appearance.SettingsPostersBadgesScreen(onNavigateToSubScreen)

@Composable
fun SettingsHomeFeedScreen(onNavigateToSubScreen: (SettingsSubScreen) -> Unit = {}) =
    com.lagradost.cloudstream3.desktop.ui.screens.settings.appearance.SettingsHomeFeedScreen(onNavigateToSubScreen)

@Composable
fun SettingsDetailsSectionsScreen() =
    com.lagradost.cloudstream3.desktop.ui.screens.settings.appearance.SettingsDetailsSectionsScreen()

@Composable
fun SettingsPosterEditorScreen(onBack: () -> Unit = {}) =
    com.lagradost.cloudstream3.desktop.ui.screens.settings.appearance.SettingsPosterEditorScreen(onBack)

@Composable
fun SettingsLayoutScreen(onNavigateToSubScreen: (SettingsSubScreen) -> Unit = {}) =
    com.lagradost.cloudstream3.desktop.ui.screens.settings.appearance.SettingsLayoutScreen(onNavigateToSubScreen)

// Backward compatibility aliases
@Composable
fun SettingsAppearanceThemeScreen() = SettingsThemeWallpaperScreen()

@Composable
fun SettingsAppearanceLayoutScreen(onNavigateToSubScreen: (SettingsSubScreen) -> Unit) = SettingsNavDockScreen()

@Composable
fun SettingsAppearanceEffectsScreen() = SettingsHomeFeedScreen()
