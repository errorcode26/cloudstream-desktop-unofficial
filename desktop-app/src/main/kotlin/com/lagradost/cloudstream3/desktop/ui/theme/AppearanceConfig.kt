package com.lagradost.cloudstream3.desktop.ui.theme

import com.lagradost.cloudstream3.desktop.ui.DockPosition
import com.lagradost.cloudstream3.desktop.ui.screens.details.contract.DetailsSectionKey
import com.lagradost.common.storage.DesktopDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object AppearanceConfig {
    private const val PREF_GLOBAL_UI_SCALE = "pref_global_ui_scale"
    private const val PREF_CLEAN_MODE_ENABLED = "pref_clean_mode_master_enabled"
    private const val PREF_HIDE_PROVIDER_NAMES = "pref_clean_mode_hide_provider_names"
    private const val PREF_HIDE_DETAILS_SOURCE = "pref_clean_mode_hide_details_source"
    private const val PREF_HIDE_STREAM_PROVIDERS = "pref_clean_mode_hide_stream_providers"
    private const val PREF_NAVIGATION_STYLE = "pref_navigation_style"
    private const val PREF_HERO_BANNER_STYLE = "pref_hero_banner_style"
    private const val PREF_TOP_BAR_PROVIDER_STYLE = "pref_top_bar_provider_style"
    private const val PREF_PROVIDER_BADGE_DISPLAY_MODE = "pref_provider_badge_display_mode"
    private const val PREF_THEME_ACCENT = "pref_theme_accent"
    private const val PREF_AMOLED_MODE = "pref_amoled_mode"
    private const val PREF_LIGHT_MODE = "pref_light_mode"
    private const val PREF_GRID_SCALE = "pref_grid_scale"
    private const val PREF_AMBIENT_GLOW = "pref_ambient_glow"
    private const val PREF_AMBIENT_GLOW_INTENSITY = "pref_ambient_glow_intensity"
    private const val PREF_AMBIENT_GLOW_POSITION = "pref_ambient_glow_position"
    private const val PREF_HERO_BACKGROUND_BLUR = "pref_hero_background_blur"
    private const val PREF_HERO_BACKDROP_BLUR_RADIUS = "pref_hero_backdrop_blur_radius"
    private const val PREF_HERO_BACKDROP_DARKENING = "pref_hero_backdrop_darkening"
    private const val PREF_PERFORMANCE_MODE = "pref_performance_mode"
    private const val PREF_DOCK_POSITION = "pref_dock_position"
    private const val PREF_FONT = "pref_font"
    private const val PREF_SCREENSAVER_ENABLED = "pref_screensaver_enabled"
    private const val PREF_HERO_AUTO_SLIDE_DELAY = "pref_hero_auto_slide_delay"
    private const val PREF_CONTINUE_WATCHING_STYLE = "pref_continue_watching_style"
    private const val PREF_POSTER_HOVER_GLOW_ENABLED = "pref_poster_hover_glow_enabled"
    private const val PREF_POSTER_TITLE_POSITION = "pref_poster_title_position"
    private const val PREF_HOME_SPACING_DP = "pref_home_spacing_dp"
    private const val PREF_HOME_VERTICAL_SPACING_DP = "pref_home_vertical_spacing_dp"
    private const val PREF_POSTER_WIDTH = "pref_poster_width"
    private const val PREF_POSTER_ROUNDING = "pref_poster_rounding"
    private const val PREF_CUSTOM_THEME_ACCENT = "pref_custom_theme_accent"
    private const val PREF_APP_THEME_BACKGROUND = "pref_app_theme_background"
    private const val PREF_CUSTOM_APP_THEME_BACKGROUND = "pref_custom_app_theme_background"
    private const val PREF_HERO_ENABLED = "pref_hero_enabled"
    private const val PREF_SHOW_POSTER_RATING = "pref_show_poster_rating"
    private const val PREF_SHOW_POSTER_QUALITY = "pref_show_poster_quality"
    private const val PREF_SHOW_POSTER_LANGUAGE = "pref_show_poster_language"
    private const val PREF_POSTER_METADATA_POSITION = "pref_poster_metadata_position"
    private const val PREF_POSTER_TEXT_ALIGNMENT = "pref_poster_text_alignment"
    private const val PREF_POSTER_TITLE_MAX_LINES = "pref_poster_title_max_lines"
    private const val PREF_TEXT_DROP_SHADOW_ENABLED = "pref_text_drop_shadow_enabled"
    private const val PREF_TEXT_DROP_SHADOW_BLUR = "pref_text_drop_shadow_blur"
    private const val PREF_ELEMENT_SHADOWS_ENABLED = "pref_element_shadows_enabled"
    private const val PREF_ELEMENT_SHADOW_MULTIPLIER = "pref_element_shadow_multiplier"
    private const val PREF_POSTER_DEPTH_EFFECT_ENABLED = "pref_poster_depth_effect_enabled"
    private const val PREF_APP_PRESET_THEME = "pref_app_preset_theme"
    private const val PREF_BACKGROUND_GRADIENT_ENABLED = "pref_background_gradient_enabled"
    private const val PREF_BACKGROUND_GRADIENT_TYPE = "pref_background_gradient_type"
    private const val PREF_BACKGROUND_GRADIENT_INTENSITY = "pref_background_gradient_intensity"
    private const val PREF_CLOCK_MODE = "pref_clock_mode"
    private const val PREF_CLOCK_TIME_FORMAT = "pref_clock_time_format"
    private const val PREF_CLOCK_DATE_FORMAT = "pref_clock_date_format"
    private const val PREF_ANTI_SPOILER_ENABLED = "pref_anti_spoiler_enabled"
    private const val PREF_LOCK_UNRELEASED_EPISODES = "pref_lock_unreleased_episodes"
    private const val PREF_DETAILS_SECTION_ORDER = "pref_details_section_order"
    private const val PREF_DETAILS_DISABLED_SECTIONS = "pref_details_disabled_sections"

    val dockItemOrder: StateFlow<List<DockItemKey>> get() = DockLayoutEngine.dockItemOrder
    val dockDisabledItems: StateFlow<Set<DockItemKey>> get() = DockLayoutEngine.dockDisabledItems
    val topBarShowProfile: StateFlow<Boolean> get() = DockLayoutEngine.topBarShowProfile
    val topBarShowProfileName: StateFlow<Boolean> get() = DockLayoutEngine.topBarShowProfileName
    val showContinueWatching: StateFlow<Boolean> get() = DockLayoutEngine.showContinueWatching

    val backgroundImagePath: StateFlow<String> get() = WallpaperConfig.backgroundImagePath
    val backgroundImageBlur: StateFlow<Float> get() = WallpaperConfig.backgroundImageBlur
    val backgroundImageBrightness: StateFlow<Float> get() = WallpaperConfig.backgroundImageBrightness
    val backgroundImageOpacity: StateFlow<Float> get() = WallpaperConfig.backgroundImageOpacity
    val backgroundImageSaturation: StateFlow<Float> get() = WallpaperConfig.backgroundImageSaturation
    val backgroundImageVignetteEnabled: StateFlow<Boolean> get() = WallpaperConfig.backgroundImageVignetteEnabled
    val backgroundImageVignetteIntensity: StateFlow<Float> get() = WallpaperConfig.backgroundImageVignetteIntensity
    val backgroundImageTintEnabled: StateFlow<Boolean> get() = WallpaperConfig.backgroundImageTintEnabled
    val backgroundImageTintColor: StateFlow<String> get() = WallpaperConfig.backgroundImageTintColor
    val backgroundImageTintAlpha: StateFlow<Float> get() = WallpaperConfig.backgroundImageTintAlpha
    val uiCardOpacity: StateFlow<Float> get() = WallpaperConfig.uiCardOpacity

    val customPresets: StateFlow<List<ThemePreset>> get() = ThemePresetsEngine.customPresets

    private val _themeAccent = MutableStateFlow(DesktopDataStore.getKey<String>(PREF_THEME_ACCENT) ?: "Blue")
    val themeAccent: StateFlow<String> = _themeAccent.asStateFlow()

    private val _antiSpoilerEnabled = MutableStateFlow(DesktopDataStore.getKey<Boolean>(PREF_ANTI_SPOILER_ENABLED) ?: true)
    val antiSpoilerEnabled: StateFlow<Boolean> = _antiSpoilerEnabled.asStateFlow()

    private val _lockUnreleasedEpisodes = MutableStateFlow(DesktopDataStore.getKey<Boolean>(PREF_LOCK_UNRELEASED_EPISODES) ?: true)
    val lockUnreleasedEpisodes: StateFlow<Boolean> = _lockUnreleasedEpisodes.asStateFlow()

    private val _detailsSectionOrder = MutableStateFlow(
        DetailsSectionKey.parseOrder(DesktopDataStore.getKey<String>(PREF_DETAILS_SECTION_ORDER)),
    )
    val detailsSectionOrder: StateFlow<List<DetailsSectionKey>> = _detailsSectionOrder.asStateFlow()

    private val _detailsDisabledSections = MutableStateFlow(
        DetailsSectionKey.parseDisabled(DesktopDataStore.getKey<String>(PREF_DETAILS_DISABLED_SECTIONS)),
    )
    val detailsDisabledSections: StateFlow<Set<DetailsSectionKey>> = _detailsDisabledSections.asStateFlow()

    private val _amoledMode = MutableStateFlow(DesktopDataStore.getKey<Boolean>(PREF_AMOLED_MODE) ?: false)
    val amoledMode: StateFlow<Boolean> = _amoledMode.asStateFlow()

    private val _isLightMode = MutableStateFlow(DesktopDataStore.getKey<Boolean>(PREF_LIGHT_MODE) ?: false)
    val isLightMode: StateFlow<Boolean> = _isLightMode.asStateFlow()

    private val _gridScale = MutableStateFlow(DesktopDataStore.getKey<String>(PREF_GRID_SCALE) ?: "Normal")
    val gridScale: StateFlow<String> = _gridScale.asStateFlow()

    private val _ambientGlowEnabled = MutableStateFlow(DesktopDataStore.getKey<Boolean>(PREF_AMBIENT_GLOW) ?: false)
    val ambientGlowEnabled: StateFlow<Boolean> = _ambientGlowEnabled.asStateFlow()

    private val _ambientGlowIntensity = MutableStateFlow(DesktopDataStore.getKey<Float>(PREF_AMBIENT_GLOW_INTENSITY) ?: 0.15f)
    val ambientGlowIntensity: StateFlow<Float> = _ambientGlowIntensity.asStateFlow()

    private val _ambientGlowPositions = MutableStateFlow(
        (DesktopDataStore.getKey<String>(PREF_AMBIENT_GLOW_POSITION) ?: "Center")
            .split(",")
            .filter { it.isNotBlank() }
            .toSet(),
    )
    val ambientGlowPositions: StateFlow<Set<String>> = _ambientGlowPositions.asStateFlow()

    private val _heroBackgroundBlurEnabled = MutableStateFlow(DesktopDataStore.getKey<Boolean>(PREF_HERO_BACKGROUND_BLUR) ?: true)
    val heroBackgroundBlurEnabled: StateFlow<Boolean> = _heroBackgroundBlurEnabled.asStateFlow()

    private val _heroBackdropBlurRadius = MutableStateFlow(DesktopDataStore.getKey<Float>(PREF_HERO_BACKDROP_BLUR_RADIUS) ?: 80f)
    val heroBackdropBlurRadius: StateFlow<Float> = _heroBackdropBlurRadius.asStateFlow()

    private val _heroBackdropDarkening = MutableStateFlow(DesktopDataStore.getKey<Float>(PREF_HERO_BACKDROP_DARKENING) ?: 0.65f)
    val heroBackdropDarkening: StateFlow<Float> = _heroBackdropDarkening.asStateFlow()

    private val _performanceModeEnabled = MutableStateFlow(DesktopDataStore.getKey<Boolean>(PREF_PERFORMANCE_MODE) ?: false)
    val performanceModeEnabled: StateFlow<Boolean> = _performanceModeEnabled.asStateFlow()

    private val _globalUiScale = MutableStateFlow(DesktopDataStore.getKey<Float>(PREF_GLOBAL_UI_SCALE) ?: 1.0f)
    val globalUiScale: StateFlow<Float> = _globalUiScale.asStateFlow()

    private val _navigationStyle = MutableStateFlow(NavigationStyle.fromString(DesktopDataStore.getKey<String>(PREF_NAVIGATION_STYLE)))
    val navigationStyle: StateFlow<NavigationStyle> = _navigationStyle.asStateFlow()

    private val _dockPosition = MutableStateFlow(DockPosition.fromString(DesktopDataStore.getKey<String>(PREF_DOCK_POSITION) ?: "Left"))
    val dockPosition: StateFlow<DockPosition> = _dockPosition.asStateFlow()

    private val _selectedFont = MutableStateFlow(DesktopDataStore.getKey<String>(PREF_FONT) ?: "Plus Jakarta Sans")
    val selectedFont: StateFlow<String> = _selectedFont.asStateFlow()

    private val _screensaverEnabled = MutableStateFlow(DesktopDataStore.getKey<Boolean>(PREF_SCREENSAVER_ENABLED) ?: true)
    val screensaverEnabled: StateFlow<Boolean> = _screensaverEnabled.asStateFlow()

    private val _heroAutoSlideDelaySeconds = MutableStateFlow(DesktopDataStore.getKey<Int>(PREF_HERO_AUTO_SLIDE_DELAY) ?: 10)
    val heroAutoSlideDelaySeconds: StateFlow<Int> = _heroAutoSlideDelaySeconds.asStateFlow()

    private val _heroBannerStyle = MutableStateFlow(HeroBannerStyle.fromString(DesktopDataStore.getKey<String>(PREF_HERO_BANNER_STYLE)))
    val heroBannerStyle: StateFlow<HeroBannerStyle> = _heroBannerStyle.asStateFlow()

    private val _continueWatchingStyle = MutableStateFlow(ContinueWatchingStyle.fromString(DesktopDataStore.getKey<String>(PREF_CONTINUE_WATCHING_STYLE)))
    val continueWatchingStyle: StateFlow<ContinueWatchingStyle> = _continueWatchingStyle.asStateFlow()

    private val _topBarProviderStyle = MutableStateFlow(TopBarProviderStyle.fromString(DesktopDataStore.getKey<String>(PREF_TOP_BAR_PROVIDER_STYLE)))
    val topBarProviderStyle: StateFlow<TopBarProviderStyle> = _topBarProviderStyle.asStateFlow()

    private val _cleanModeEnabled = MutableStateFlow(DesktopDataStore.getKey<Boolean>(PREF_CLEAN_MODE_ENABLED) ?: false)
    val cleanModeEnabled: StateFlow<Boolean> = _cleanModeEnabled.asStateFlow()

    private val _hideProviderNames = MutableStateFlow(DesktopDataStore.getKey<Boolean>(PREF_HIDE_PROVIDER_NAMES) ?: true)
    val hideProviderNames: StateFlow<Boolean> = _hideProviderNames.asStateFlow()

    private val _hideDetailsSource = MutableStateFlow(DesktopDataStore.getKey<Boolean>(PREF_HIDE_DETAILS_SOURCE) ?: false)
    val hideDetailsSource: StateFlow<Boolean> = _hideDetailsSource.asStateFlow()

    private val _hideStreamProviders = MutableStateFlow(DesktopDataStore.getKey<Boolean>(PREF_HIDE_STREAM_PROVIDERS) ?: false)
    val hideStreamProviders: StateFlow<Boolean> = _hideStreamProviders.asStateFlow()

    private val _providerBadgeDisplayMode = MutableStateFlow(ProviderBadgeDisplayMode.fromString(DesktopDataStore.getKey<String>(PREF_PROVIDER_BADGE_DISPLAY_MODE)))
    val providerBadgeDisplayMode: StateFlow<ProviderBadgeDisplayMode> = _providerBadgeDisplayMode.asStateFlow()

    private val _posterHoverGlowEnabled = MutableStateFlow(DesktopDataStore.getKey<Boolean>(PREF_POSTER_HOVER_GLOW_ENABLED) ?: true)
    val posterHoverGlowEnabled: StateFlow<Boolean> = _posterHoverGlowEnabled.asStateFlow()

    private val _posterTitlePosition = MutableStateFlow(PosterTitlePosition.fromString(DesktopDataStore.getKey<String>(PREF_POSTER_TITLE_POSITION)))
    val posterTitlePosition: StateFlow<PosterTitlePosition> = _posterTitlePosition.asStateFlow()

    private val _homeSpacingDp = MutableStateFlow(DesktopDataStore.getKey<Int>(PREF_HOME_SPACING_DP) ?: 12)
    val homeSpacingDp: StateFlow<Int> = _homeSpacingDp.asStateFlow()

    private val _homeVerticalSpacingDp = MutableStateFlow(DesktopDataStore.getKey<Int>(PREF_HOME_VERTICAL_SPACING_DP) ?: 0)
    val homeVerticalSpacingDp: StateFlow<Int> = _homeVerticalSpacingDp.asStateFlow()

    private val _posterWidthDp = MutableStateFlow(DesktopDataStore.getKey<Int>(PREF_POSTER_WIDTH) ?: 190)
    val posterWidthDp: StateFlow<Int> = _posterWidthDp.asStateFlow()

    private val _posterRoundingDp = MutableStateFlow(DesktopDataStore.getKey<Int>(PREF_POSTER_ROUNDING) ?: 12)
    val posterRoundingDp: StateFlow<Int> = _posterRoundingDp.asStateFlow()

    private val _customThemeAccent = MutableStateFlow(DesktopDataStore.getKey<String>(PREF_CUSTOM_THEME_ACCENT) ?: "#3B82F6")
    val customThemeAccent: StateFlow<String> = _customThemeAccent.asStateFlow()

    private val _appThemeBackground = MutableStateFlow(DesktopDataStore.getKey<String>(PREF_APP_THEME_BACKGROUND) ?: "Navy")
    val appThemeBackground: StateFlow<String> = _appThemeBackground.asStateFlow()

    private val _customAppThemeBackground = MutableStateFlow(DesktopDataStore.getKey<String>(PREF_CUSTOM_APP_THEME_BACKGROUND) ?: "#0C0C16")
    val customAppThemeBackground: StateFlow<String> = _customAppThemeBackground.asStateFlow()

    private val _heroEnabled = MutableStateFlow(DesktopDataStore.getKey<Boolean>(PREF_HERO_ENABLED) ?: true)
    val heroEnabled: StateFlow<Boolean> = _heroEnabled.asStateFlow()

    private val _showPosterRating = MutableStateFlow(DesktopDataStore.getKey<Boolean>(PREF_SHOW_POSTER_RATING) ?: true)
    val showPosterRating: StateFlow<Boolean> = _showPosterRating.asStateFlow()

    private val _showPosterQuality = MutableStateFlow(DesktopDataStore.getKey<Boolean>(PREF_SHOW_POSTER_QUALITY) ?: true)
    val showPosterQuality: StateFlow<Boolean> = _showPosterQuality.asStateFlow()

    private val _showPosterLanguage = MutableStateFlow(DesktopDataStore.getKey<Boolean>(PREF_SHOW_POSTER_LANGUAGE) ?: true)
    val showPosterLanguage: StateFlow<Boolean> = _showPosterLanguage.asStateFlow()

    private val _posterMetadataPosition = MutableStateFlow(
        PosterMetadataPosition.fromString(DesktopDataStore.getKey<String>(PREF_POSTER_METADATA_POSITION)),
    )
    val posterMetadataPosition: StateFlow<PosterMetadataPosition> = _posterMetadataPosition.asStateFlow()

    private val _posterTextAlignment = MutableStateFlow(
        PosterTextAlignment.fromString(DesktopDataStore.getKey<String>(PREF_POSTER_TEXT_ALIGNMENT)),
    )
    val posterTextAlignment: StateFlow<PosterTextAlignment> = _posterTextAlignment.asStateFlow()

    private val _posterTitleMaxLines = MutableStateFlow(
        DesktopDataStore.getKey<Int>(PREF_POSTER_TITLE_MAX_LINES) ?: 2,
    )
    val posterTitleMaxLines: StateFlow<Int> = _posterTitleMaxLines.asStateFlow()

    private val _textDropShadowEnabled = MutableStateFlow(DesktopDataStore.getKey<Boolean>(PREF_TEXT_DROP_SHADOW_ENABLED) ?: true)
    val textDropShadowEnabled: StateFlow<Boolean> = _textDropShadowEnabled.asStateFlow()

    private val _textDropShadowBlur = MutableStateFlow(DesktopDataStore.getKey<Float>(PREF_TEXT_DROP_SHADOW_BLUR) ?: 8f)
    val textDropShadowBlur: StateFlow<Float> = _textDropShadowBlur.asStateFlow()

    private val _elementShadowsEnabled = MutableStateFlow(DesktopDataStore.getKey<Boolean>(PREF_ELEMENT_SHADOWS_ENABLED) ?: true)
    val elementShadowsEnabled: StateFlow<Boolean> = _elementShadowsEnabled.asStateFlow()

    private val _elementShadowMultiplier = MutableStateFlow(DesktopDataStore.getKey<Float>(PREF_ELEMENT_SHADOW_MULTIPLIER) ?: 1.0f)
    val elementShadowMultiplier: StateFlow<Float> = _elementShadowMultiplier.asStateFlow()

    private val _posterDepthEffectEnabled = MutableStateFlow(DesktopDataStore.getKey<Boolean>(PREF_POSTER_DEPTH_EFFECT_ENABLED) ?: true)
    val posterDepthEffectEnabled: StateFlow<Boolean> = _posterDepthEffectEnabled.asStateFlow()

    private val _appPresetTheme = MutableStateFlow(DesktopDataStore.getKey<String>(PREF_APP_PRESET_THEME) ?: "preset_classic_dark")
    val appPresetTheme: StateFlow<String> = _appPresetTheme.asStateFlow()

    private val _backgroundGradientEnabled = MutableStateFlow(false)
    val backgroundGradientEnabled: StateFlow<Boolean> = _backgroundGradientEnabled.asStateFlow()

    private val _backgroundGradientType = MutableStateFlow(DesktopDataStore.getKey<String>(PREF_BACKGROUND_GRADIENT_TYPE) ?: "Radial")
    val backgroundGradientType: StateFlow<String> = _backgroundGradientType.asStateFlow()

    private val _backgroundGradientIntensity = MutableStateFlow(DesktopDataStore.getKey<Float>(PREF_BACKGROUND_GRADIENT_INTENSITY) ?: 0.5f)
    val backgroundGradientIntensity: StateFlow<Float> = _backgroundGradientIntensity.asStateFlow()

    private val _clockMode = MutableStateFlow(ClockDisplayMode.fromString(DesktopDataStore.getKey<String>(PREF_CLOCK_MODE)))
    val clockMode: StateFlow<ClockDisplayMode> = _clockMode.asStateFlow()

    private val _clockTimeFormat = MutableStateFlow(DesktopDataStore.getKey<String>(PREF_CLOCK_TIME_FORMAT) ?: "HH:mm")
    val clockTimeFormat: StateFlow<String> = _clockTimeFormat.asStateFlow()

    private val _clockDateFormat = MutableStateFlow(DesktopDataStore.getKey<String>(PREF_CLOCK_DATE_FORMAT) ?: "EEE, dd MMM")
    val clockDateFormat: StateFlow<String> = _clockDateFormat.asStateFlow()

    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        checkAndMigrateDefaults()
    }

    private fun checkAndMigrateDefaults() {
        val migrated = DesktopDataStore.getKey<Boolean>("pref_defaults_migrated_v4") ?: false
        if (!migrated) {
            val existingNav = DesktopDataStore.getKey<String>(PREF_NAVIGATION_STYLE)
            if (existingNav == null || existingNav.equals("FLOATING_DOCK", ignoreCase = true) || existingNav.equals("Floating Dock", ignoreCase = true)) {
                _navigationStyle.value = NavigationStyle.SEAMLESS_BAR
                persist(PREF_NAVIGATION_STYLE, NavigationStyle.SEAMLESS_BAR.name)
            }
            val existingScale = DesktopDataStore.getKey<Float>(PREF_GLOBAL_UI_SCALE)
            if (existingScale == null || kotlin.math.abs(existingScale - 1.0f) < 0.01f || kotlin.math.abs(existingScale - 1.20f) < 0.01f) {
                _globalUiScale.value = 1.10f
                persist(PREF_GLOBAL_UI_SCALE, 1.10f)
            }
            val existingTopBarProfile = DesktopDataStore.getKey<Boolean>("pref_topbar_show_profile")
            if (existingTopBarProfile == null || existingTopBarProfile == true) {
                DockLayoutEngine.setTopBarShowProfile(false)
            }
            persist("pref_defaults_migrated_v4", true)
        }
    }

    private fun persist(key: String, value: Any?) {
        persistenceScope.launch {
            DesktopDataStore.setKey(key, value)
        }
    }

    private fun removeKey(key: String) {
        persistenceScope.launch {
            DesktopDataStore.removeKey(key)
        }
    }

    fun setThemeAccent(colorName: String) {
        _themeAccent.value = colorName
        persist(PREF_THEME_ACCENT, colorName)
    }

    fun setAntiSpoilerEnabled(enabled: Boolean) {
        _antiSpoilerEnabled.value = enabled
        persist(PREF_ANTI_SPOILER_ENABLED, enabled)
    }

    fun setThemeMode(mode: ThemeMode) {
        when (mode) {
            ThemeMode.LIGHT -> {
                _isLightMode.value = true
                _amoledMode.value = false
                persist(PREF_LIGHT_MODE, true)
                persist(PREF_AMOLED_MODE, false)
            }
            ThemeMode.DARK -> {
                _isLightMode.value = false
                _amoledMode.value = false
                persist(PREF_LIGHT_MODE, false)
                persist(PREF_AMOLED_MODE, false)
            }
            ThemeMode.AMOLED -> {
                _isLightMode.value = false
                _amoledMode.value = true
                _appThemeBackground.value = "Pure Black"
                persist(PREF_LIGHT_MODE, false)
                persist(PREF_AMOLED_MODE, true)
                persist(PREF_APP_THEME_BACKGROUND, "Pure Black")
            }
        }
    }

    fun setAmoledMode(enabled: Boolean) {
        _amoledMode.value = enabled
        if (enabled) {
            _isLightMode.value = false
            _appThemeBackground.value = "Pure Black"
            persist(PREF_LIGHT_MODE, false)
            persist(PREF_APP_THEME_BACKGROUND, "Pure Black")
        }
        persist(PREF_AMOLED_MODE, enabled)
    }

    fun setLightMode(enabled: Boolean) {
        _isLightMode.value = enabled
        if (enabled) {
            _amoledMode.value = false
            persist(PREF_AMOLED_MODE, false)
        }
        persist(PREF_LIGHT_MODE, enabled)
    }

    fun setGridScale(scale: String) {
        _gridScale.value = scale
        persist(PREF_GRID_SCALE, scale)
    }

    fun setAmbientGlowEnabled(enabled: Boolean) {
        _ambientGlowEnabled.value = enabled
        persist(PREF_AMBIENT_GLOW, enabled)
    }

    fun setAmbientGlowIntensity(intensity: Float) {
        _ambientGlowIntensity.value = intensity
        persist(PREF_AMBIENT_GLOW_INTENSITY, intensity)
    }

    fun toggleAmbientGlowPosition(position: String) {
        val current = _ambientGlowPositions.value.toMutableSet()
        if (current.contains(position)) {
            current.remove(position)
        } else {
            current.add(position)
        }
        if (current.isEmpty()) current.add("Center")
        _ambientGlowPositions.value = current
        persist(PREF_AMBIENT_GLOW_POSITION, current.joinToString(","))
    }

    fun setHeroBackgroundBlurEnabled(enabled: Boolean) {
        _heroBackgroundBlurEnabled.value = enabled
        persist(PREF_HERO_BACKGROUND_BLUR, enabled)
        if (enabled && _performanceModeEnabled.value) {
            _performanceModeEnabled.value = false
            persist(PREF_PERFORMANCE_MODE, false)
        }
    }

    fun setHeroBackdropBlurRadius(radius: Float) {
        _heroBackdropBlurRadius.value = radius
        persist(PREF_HERO_BACKDROP_BLUR_RADIUS, radius)
    }

    fun setHeroBackdropDarkening(darkening: Float) {
        _heroBackdropDarkening.value = darkening
        persist(PREF_HERO_BACKDROP_DARKENING, darkening)
    }

    fun setPerformanceModeEnabled(enabled: Boolean) {
        _performanceModeEnabled.value = enabled
        persist(PREF_PERFORMANCE_MODE, enabled)
        if (enabled && _heroBackgroundBlurEnabled.value) {
            _heroBackgroundBlurEnabled.value = false
            persist(PREF_HERO_BACKGROUND_BLUR, false)
        }
    }

    fun setGlobalUiScale(scale: Float, notify: Boolean = true) {
        val clamped = (scale.coerceIn(0.70f, 1.80f) * 100).toInt() / 100f
        if (kotlin.math.abs(_globalUiScale.value - clamped) < 0.001f) return
        _globalUiScale.value = clamped
        persist(PREF_GLOBAL_UI_SCALE, clamped)
        if (notify) {
            val percent = (clamped * 100).toInt()
            com.lagradost.cloudstream3.desktop.ui.components.AppToastManager.showToast(
                text = if (percent == 100) "UI Scale: 100% (Default)" else "UI Scale: $percent%",
                durationMs = 1500L,
            )
        }
    }

    fun zoomIn() {
        setGlobalUiScale(_globalUiScale.value + 0.10f, notify = true)
    }

    fun zoomOut() {
        setGlobalUiScale(_globalUiScale.value - 0.10f, notify = true)
    }

    fun resetZoom() {
        setGlobalUiScale(1.0f, notify = true)
    }

    fun setNavigationStyle(style: NavigationStyle) {
        _navigationStyle.value = style
        persist(PREF_NAVIGATION_STYLE, style.name)
    }

    fun setDockPosition(position: DockPosition) {
        _dockPosition.value = position
        persist(PREF_DOCK_POSITION, position.label)
    }

    fun setSelectedFont(font: String) {
        _selectedFont.value = font
        persist(PREF_FONT, font)
    }

    fun setScreensaverEnabled(enabled: Boolean) {
        _screensaverEnabled.value = enabled
        persist(PREF_SCREENSAVER_ENABLED, enabled)
    }

    fun setHeroAutoSlideDelaySeconds(seconds: Int) {
        _heroAutoSlideDelaySeconds.value = seconds
        persist(PREF_HERO_AUTO_SLIDE_DELAY, seconds)
    }

    fun setHeroBannerStyle(style: HeroBannerStyle) {
        _heroBannerStyle.value = style
        persist(PREF_HERO_BANNER_STYLE, style.name)
    }

    fun setHomeSpacingDp(dp: Int) {
        _homeSpacingDp.value = dp
        persist(PREF_HOME_SPACING_DP, dp)
    }

    fun setHomeVerticalSpacingDp(dp: Int) {
        _homeVerticalSpacingDp.value = dp
        persist(PREF_HOME_VERTICAL_SPACING_DP, dp)
    }

    fun setPosterWidthDp(width: Int) {
        _posterWidthDp.value = width
        persist(PREF_POSTER_WIDTH, width)
    }

    fun setPosterRoundingDp(dp: Int) {
        _posterRoundingDp.value = dp
        persist(PREF_POSTER_ROUNDING, dp)
    }

    fun setCustomThemeAccent(hex: String) {
        _customThemeAccent.value = hex
        persist(PREF_CUSTOM_THEME_ACCENT, hex)
    }

    fun setAppThemeBackground(themeName: String) {
        _appThemeBackground.value = themeName
        persist(PREF_APP_THEME_BACKGROUND, themeName)
    }

    fun setCustomAppThemeBackground(hex: String) {
        _customAppThemeBackground.value = hex
        persist(PREF_CUSTOM_APP_THEME_BACKGROUND, hex)
    }

    fun setHeroEnabled(enabled: Boolean) {
        _heroEnabled.value = enabled
        persist(PREF_HERO_ENABLED, enabled)
    }

    fun setContinueWatchingStyle(style: ContinueWatchingStyle) {
        _continueWatchingStyle.value = style
        persist(PREF_CONTINUE_WATCHING_STYLE, style.name)
    }

    fun setTopBarProviderStyle(style: TopBarProviderStyle) {
        _topBarProviderStyle.value = style
        persist(PREF_TOP_BAR_PROVIDER_STYLE, style.name)
    }

    fun setCleanModeEnabled(enabled: Boolean) {
        _cleanModeEnabled.value = enabled
        _hideProviderNames.value = enabled
        _hideStreamProviders.value = enabled
        if (enabled) {
            _providerBadgeDisplayMode.value = ProviderBadgeDisplayMode.HIDDEN
            com.lagradost.cloudstream3.desktop.ui.badges.CardMetadataConfig.setAutoCleanTitles(true)
        }
        persist(PREF_CLEAN_MODE_ENABLED, enabled)
        persist(PREF_HIDE_PROVIDER_NAMES, enabled)
        persist(PREF_HIDE_STREAM_PROVIDERS, enabled)
        if (enabled) {
            persist(PREF_PROVIDER_BADGE_DISPLAY_MODE, ProviderBadgeDisplayMode.HIDDEN.name)
        }
    }

    fun toggleCleanMode(): Boolean {
        val next = !_cleanModeEnabled.value
        setCleanModeEnabled(next)
        return next
    }

    fun setHideProviderNames(enabled: Boolean) {
        _hideProviderNames.value = enabled
        persist(PREF_HIDE_PROVIDER_NAMES, enabled)
    }

    fun setHideDetailsSource(enabled: Boolean) {
        _hideDetailsSource.value = enabled
        persist(PREF_HIDE_DETAILS_SOURCE, enabled)
    }

    fun setHideStreamProviders(enabled: Boolean) {
        _hideStreamProviders.value = enabled
        persist(PREF_HIDE_STREAM_PROVIDERS, enabled)
    }

    fun setProviderBadgeDisplayMode(mode: ProviderBadgeDisplayMode) {
        _providerBadgeDisplayMode.value = mode
        persist(PREF_PROVIDER_BADGE_DISPLAY_MODE, mode.name)
    }

    fun setPosterHoverGlowEnabled(enabled: Boolean) {
        _posterHoverGlowEnabled.value = enabled
        persist(PREF_POSTER_HOVER_GLOW_ENABLED, enabled)
    }

    fun setPosterTitlePosition(position: PosterTitlePosition) {
        _posterTitlePosition.value = position
        persist(PREF_POSTER_TITLE_POSITION, position.name)
    }

    fun setShowPosterRating(enabled: Boolean) {
        _showPosterRating.value = enabled
        persist(PREF_SHOW_POSTER_RATING, enabled)
    }

    fun setShowPosterQuality(enabled: Boolean) {
        _showPosterQuality.value = enabled
        persist(PREF_SHOW_POSTER_QUALITY, enabled)
    }

    fun setShowPosterLanguage(show: Boolean) {
        _showPosterLanguage.value = show
        persist(PREF_SHOW_POSTER_LANGUAGE, show)
    }

    fun setPosterMetadataPosition(position: PosterMetadataPosition) {
        _posterMetadataPosition.value = position
        persist(PREF_POSTER_METADATA_POSITION, position.name)
    }

    fun setPosterTextAlignment(alignment: PosterTextAlignment) {
        _posterTextAlignment.value = alignment
        persist(PREF_POSTER_TEXT_ALIGNMENT, alignment.name)
    }

    fun setPosterTitleMaxLines(maxLines: Int) {
        _posterTitleMaxLines.value = maxLines
        persist(PREF_POSTER_TITLE_MAX_LINES, maxLines)
    }

    fun setTextDropShadowEnabled(enabled: Boolean) {
        _textDropShadowEnabled.value = enabled
        persist(PREF_TEXT_DROP_SHADOW_ENABLED, enabled)
    }

    fun setTextDropShadowBlur(blur: Float) {
        _textDropShadowBlur.value = blur
        persist(PREF_TEXT_DROP_SHADOW_BLUR, blur)
    }

    fun setElementShadowsEnabled(enabled: Boolean) {
        _elementShadowsEnabled.value = enabled
        persist(PREF_ELEMENT_SHADOWS_ENABLED, enabled)
    }

    fun setElementShadowMultiplier(multiplier: Float) {
        _elementShadowMultiplier.value = multiplier
        persist(PREF_ELEMENT_SHADOW_MULTIPLIER, multiplier)
    }

    fun setPosterDepthEffectEnabled(enabled: Boolean) {
        _posterDepthEffectEnabled.value = enabled
        persist(PREF_POSTER_DEPTH_EFFECT_ENABLED, enabled)
    }

    fun setAppPresetTheme(presetId: String) {
        _appPresetTheme.value = presetId
        persist(PREF_APP_PRESET_THEME, presetId)
    }

    fun setBackgroundGradientEnabled(enabled: Boolean) {
        _backgroundGradientEnabled.value = enabled
        persist(PREF_BACKGROUND_GRADIENT_ENABLED, enabled)
    }

    fun setBackgroundGradientType(type: String) {
        _backgroundGradientType.value = type
        persist(PREF_BACKGROUND_GRADIENT_TYPE, type)
    }

    fun setBackgroundGradientIntensity(intensity: Float) {
        _backgroundGradientIntensity.value = intensity
        persist(PREF_BACKGROUND_GRADIENT_INTENSITY, intensity)
    }

    fun setClockMode(mode: ClockDisplayMode) {
        _clockMode.value = mode
        persist(PREF_CLOCK_MODE, mode.name)
    }

    fun setClockTimeFormat(format: String) {
        _clockTimeFormat.value = format
        persist(PREF_CLOCK_TIME_FORMAT, format)
    }

    fun setClockDateFormat(format: String) {
        _clockDateFormat.value = format
        persist(PREF_CLOCK_DATE_FORMAT, format)
    }

    fun setLockUnreleasedEpisodes(enabled: Boolean) {
        _lockUnreleasedEpisodes.value = enabled
        persist(PREF_LOCK_UNRELEASED_EPISODES, enabled)
    }

    fun setBackgroundImagePath(path: String) = WallpaperConfig.setBackgroundImagePath(path)
    fun setBackgroundImageBlur(blur: Float) = WallpaperConfig.setBackgroundImageBlur(blur)
    fun setBackgroundImageBrightness(brightness: Float) = WallpaperConfig.setBackgroundImageBrightness(brightness)
    fun clearBackgroundImage() = WallpaperConfig.clearBackgroundImage()
    fun setBackgroundImageOpacity(opacity: Float) = WallpaperConfig.setBackgroundImageOpacity(opacity)
    fun setBackgroundImageSaturation(saturation: Float) = WallpaperConfig.setBackgroundImageSaturation(saturation)
    fun setBackgroundImageVignetteEnabled(enabled: Boolean) = WallpaperConfig.setBackgroundImageVignetteEnabled(enabled)
    fun setBackgroundImageVignetteIntensity(intensity: Float) = WallpaperConfig.setBackgroundImageVignetteIntensity(intensity)
    fun setBackgroundImageTintEnabled(enabled: Boolean) = WallpaperConfig.setBackgroundImageTintEnabled(enabled)
    fun setBackgroundImageTintColor(hex: String) = WallpaperConfig.setBackgroundImageTintColor(hex)
    fun setBackgroundImageTintAlpha(alpha: Float) = WallpaperConfig.setBackgroundImageTintAlpha(alpha)
    fun setUiCardOpacity(opacity: Float) = WallpaperConfig.setUiCardOpacity(opacity)

    fun saveCustomPreset(preset: ThemePreset) {
        ThemePresetsEngine.saveCustomPreset(preset) { id ->
            setAppPresetTheme(id)
        }
    }

    fun deleteCustomPreset(id: String) {
        val wasActive = _appPresetTheme.value == id
        ThemePresetsEngine.deleteCustomPreset(id) {
            if (wasActive) {
                setAppPresetTheme("preset_classic_dark")
            }
        }
    }

    fun applyPreset(preset: ThemePreset) {
        setAppPresetTheme(preset.id)
        setLightMode(preset.isLightMode)
        setThemeAccent(preset.themeAccent)
        if (preset.themeAccent == "Custom") {
            setCustomThemeAccent(preset.customThemeAccent)
        }
        setAppThemeBackground(preset.appThemeBackground)
        if (preset.appThemeBackground == "Custom") {
            setCustomAppThemeBackground(preset.customAppThemeBackground)
        }
        setBackgroundGradientEnabled(preset.backgroundGradientEnabled)
        setBackgroundGradientType(preset.backgroundGradientType)
        setBackgroundGradientIntensity(preset.backgroundGradientIntensity)
    }

    fun setDetailsSectionOrder(order: List<DetailsSectionKey>) {
        _detailsSectionOrder.value = order
        persist(
            PREF_DETAILS_SECTION_ORDER,
            DetailsSectionKey.serialize(order),
        )
    }

    fun toggleDetailsSection(key: DetailsSectionKey, enabled: Boolean) {
        val current = _detailsDisabledSections.value.toMutableSet()
        if (enabled) {
            current.remove(key)
        } else {
            current.add(key)
        }
        _detailsDisabledSections.value = current
        persist(
            PREF_DETAILS_DISABLED_SECTIONS,
            DetailsSectionKey.serialize(current),
        )
    }

    fun moveDetailsSection(fromIndex: Int, toIndex: Int) {
        val current = _detailsSectionOrder.value.toMutableList()
        if (fromIndex in current.indices && toIndex in current.indices && fromIndex != toIndex) {
            val item = current.removeAt(fromIndex)
            current.add(toIndex, item)
            setDetailsSectionOrder(current)
        }
    }

    fun resetDetailsSectionOrder() {
        setDetailsSectionOrder(DetailsSectionKey.defaultOrder)
        _detailsDisabledSections.value = emptySet()
        removeKey(PREF_DETAILS_DISABLED_SECTIONS)
    }

    fun setDockItemOrder(order: List<DockItemKey>) = DockLayoutEngine.setDockItemOrder(order)
    fun toggleDockItem(key: DockItemKey, enabled: Boolean) = DockLayoutEngine.toggleDockItem(key, enabled)
    fun moveDockItem(fromIndex: Int, toIndex: Int) = DockLayoutEngine.moveDockItem(fromIndex, toIndex)
    fun resetDockItemOrder() = DockLayoutEngine.resetDockItemOrder()
    fun setTopBarShowProfile(enabled: Boolean) = DockLayoutEngine.setTopBarShowProfile(enabled)
    fun setTopBarShowProfileName(enabled: Boolean) = DockLayoutEngine.setTopBarShowProfileName(enabled)
    fun setShowContinueWatching(enabled: Boolean) = DockLayoutEngine.setShowContinueWatching(enabled)

    fun reloadFromDataStore() {
        DockLayoutEngine.reloadFromDataStore()
        WallpaperConfig.reloadFromDataStore()
        ThemePresetsEngine.reloadFromDataStore()

        _themeAccent.value = DesktopDataStore.getKey<String>(PREF_THEME_ACCENT) ?: "Blue"
        _antiSpoilerEnabled.value = DesktopDataStore.getKey<Boolean>(PREF_ANTI_SPOILER_ENABLED) ?: true
        _lockUnreleasedEpisodes.value = DesktopDataStore.getKey<Boolean>(PREF_LOCK_UNRELEASED_EPISODES) ?: true
        _detailsSectionOrder.value = DetailsSectionKey.parseOrder(DesktopDataStore.getKey<String>(PREF_DETAILS_SECTION_ORDER))
        _detailsDisabledSections.value = DetailsSectionKey.parseDisabled(DesktopDataStore.getKey<String>(PREF_DETAILS_DISABLED_SECTIONS))
        _amoledMode.value = DesktopDataStore.getKey<Boolean>(PREF_AMOLED_MODE) ?: false
        _isLightMode.value = DesktopDataStore.getKey<Boolean>(PREF_LIGHT_MODE) ?: false
        _gridScale.value = DesktopDataStore.getKey<String>(PREF_GRID_SCALE) ?: "Normal"
        _ambientGlowEnabled.value = DesktopDataStore.getKey<Boolean>(PREF_AMBIENT_GLOW) ?: false
        _ambientGlowIntensity.value = DesktopDataStore.getKey<Float>(PREF_AMBIENT_GLOW_INTENSITY) ?: 0.15f
        _ambientGlowPositions.value = (DesktopDataStore.getKey<String>(PREF_AMBIENT_GLOW_POSITION) ?: "Center").split(",").filter { it.isNotBlank() }.toSet()
        _heroBackgroundBlurEnabled.value = DesktopDataStore.getKey<Boolean>(PREF_HERO_BACKGROUND_BLUR) ?: true
        _heroBackdropBlurRadius.value = DesktopDataStore.getKey<Float>(PREF_HERO_BACKDROP_BLUR_RADIUS) ?: 80f
        _heroBackdropDarkening.value = DesktopDataStore.getKey<Float>(PREF_HERO_BACKDROP_DARKENING) ?: 0.65f
        _globalUiScale.value = DesktopDataStore.getKey<Float>(PREF_GLOBAL_UI_SCALE) ?: 1.0f
        _navigationStyle.value = NavigationStyle.fromString(DesktopDataStore.getKey<String>(PREF_NAVIGATION_STYLE))
        _dockPosition.value = DockPosition.fromString(DesktopDataStore.getKey<String>(PREF_DOCK_POSITION) ?: "Left")
        _selectedFont.value = DesktopDataStore.getKey<String>(PREF_FONT) ?: "Plus Jakarta Sans"
        _screensaverEnabled.value = DesktopDataStore.getKey<Boolean>(PREF_SCREENSAVER_ENABLED) ?: true
        _heroAutoSlideDelaySeconds.value = DesktopDataStore.getKey<Int>(PREF_HERO_AUTO_SLIDE_DELAY) ?: 10
        _heroBannerStyle.value = HeroBannerStyle.fromString(DesktopDataStore.getKey<String>(PREF_HERO_BANNER_STYLE))
        _continueWatchingStyle.value = ContinueWatchingStyle.fromString(DesktopDataStore.getKey<String>(PREF_CONTINUE_WATCHING_STYLE))
        _topBarProviderStyle.value = TopBarProviderStyle.fromString(DesktopDataStore.getKey<String>(PREF_TOP_BAR_PROVIDER_STYLE))
        _cleanModeEnabled.value = DesktopDataStore.getKey<Boolean>(PREF_CLEAN_MODE_ENABLED) ?: false
        _hideProviderNames.value = DesktopDataStore.getKey<Boolean>(PREF_HIDE_PROVIDER_NAMES) ?: true
        _hideDetailsSource.value = DesktopDataStore.getKey<Boolean>(PREF_HIDE_DETAILS_SOURCE) ?: false
        _hideStreamProviders.value = DesktopDataStore.getKey<Boolean>(PREF_HIDE_STREAM_PROVIDERS) ?: false
        _providerBadgeDisplayMode.value = ProviderBadgeDisplayMode.fromString(DesktopDataStore.getKey<String>(PREF_PROVIDER_BADGE_DISPLAY_MODE))
        _posterHoverGlowEnabled.value = DesktopDataStore.getKey<Boolean>(PREF_POSTER_HOVER_GLOW_ENABLED) ?: true
        _posterTitlePosition.value = PosterTitlePosition.fromString(DesktopDataStore.getKey<String>(PREF_POSTER_TITLE_POSITION))
        _homeSpacingDp.value = DesktopDataStore.getKey<Int>(PREF_HOME_SPACING_DP) ?: 12
        _homeVerticalSpacingDp.value = DesktopDataStore.getKey<Int>(PREF_HOME_VERTICAL_SPACING_DP) ?: 0
        _posterWidthDp.value = DesktopDataStore.getKey<Int>(PREF_POSTER_WIDTH) ?: 190
        _posterRoundingDp.value = DesktopDataStore.getKey<Int>(PREF_POSTER_ROUNDING) ?: 12
        _customThemeAccent.value = DesktopDataStore.getKey<String>(PREF_CUSTOM_THEME_ACCENT) ?: "#3B82F6"
        _appThemeBackground.value = DesktopDataStore.getKey<String>(PREF_APP_THEME_BACKGROUND) ?: "Navy"
        _customAppThemeBackground.value = DesktopDataStore.getKey<String>(PREF_CUSTOM_APP_THEME_BACKGROUND) ?: "#0C0C16"
        _heroEnabled.value = DesktopDataStore.getKey<Boolean>(PREF_HERO_ENABLED) ?: true
        _showPosterRating.value = DesktopDataStore.getKey<Boolean>(PREF_SHOW_POSTER_RATING) ?: true
        _showPosterQuality.value = DesktopDataStore.getKey<Boolean>(PREF_SHOW_POSTER_QUALITY) ?: true
        _showPosterLanguage.value = DesktopDataStore.getKey<Boolean>(PREF_SHOW_POSTER_LANGUAGE) ?: true
        _posterMetadataPosition.value = PosterMetadataPosition.fromString(DesktopDataStore.getKey<String>(PREF_POSTER_METADATA_POSITION))
        _posterTextAlignment.value = PosterTextAlignment.fromString(DesktopDataStore.getKey<String>(PREF_POSTER_TEXT_ALIGNMENT))
        _posterTitleMaxLines.value = DesktopDataStore.getKey<Int>(PREF_POSTER_TITLE_MAX_LINES) ?: 2
        _textDropShadowEnabled.value = DesktopDataStore.getKey<Boolean>(PREF_TEXT_DROP_SHADOW_ENABLED) ?: true
        _textDropShadowBlur.value = DesktopDataStore.getKey<Float>(PREF_TEXT_DROP_SHADOW_BLUR) ?: 8f
        _elementShadowsEnabled.value = DesktopDataStore.getKey<Boolean>(PREF_ELEMENT_SHADOWS_ENABLED) ?: true
        _elementShadowMultiplier.value = DesktopDataStore.getKey<Float>(PREF_ELEMENT_SHADOW_MULTIPLIER) ?: 1.0f
        _appPresetTheme.value = DesktopDataStore.getKey<String>(PREF_APP_PRESET_THEME) ?: "preset_classic_dark"
        _backgroundGradientEnabled.value = false
        _backgroundGradientType.value = DesktopDataStore.getKey<String>(PREF_BACKGROUND_GRADIENT_TYPE) ?: "Radial"
        _backgroundGradientIntensity.value = DesktopDataStore.getKey<Float>(PREF_BACKGROUND_GRADIENT_INTENSITY) ?: 0.5f
        _clockMode.value = ClockDisplayMode.fromString(DesktopDataStore.getKey<String>(PREF_CLOCK_MODE))
        _clockTimeFormat.value = DesktopDataStore.getKey<String>(PREF_CLOCK_TIME_FORMAT) ?: "HH:mm"
        _clockDateFormat.value = DesktopDataStore.getKey<String>(PREF_CLOCK_DATE_FORMAT) ?: "EEE, dd MMM"
    }
}
