package com.lagradost.cloudstream3.desktop.ui.theme

import com.lagradost.common.storage.DesktopDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object WallpaperConfig {
    private const val PREF_BG_IMAGE_PATH = "pref_bg_image_path"
    private const val PREF_BG_IMAGE_BLUR = "pref_bg_image_blur"
    private const val PREF_BG_IMAGE_BRIGHTNESS = "pref_bg_image_brightness"
    private const val PREF_BG_IMAGE_OPACITY = "pref_bg_image_opacity"
    private const val PREF_BG_IMAGE_SATURATION = "pref_bg_image_saturation"
    private const val PREF_BG_IMAGE_VIGNETTE = "pref_bg_image_vignette"
    private const val PREF_BG_IMAGE_VIGNETTE_INTENSITY = "pref_bg_image_vignette_intensity"
    private const val PREF_BG_IMAGE_TINT_ENABLED = "pref_bg_image_tint_enabled"
    private const val PREF_BG_IMAGE_TINT_COLOR = "pref_bg_image_tint_color"
    private const val PREF_BG_IMAGE_TINT_ALPHA = "pref_bg_image_tint_alpha"
    private const val PREF_UI_CARD_OPACITY = "pref_ui_card_opacity"

    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _backgroundImagePath = MutableStateFlow(DesktopDataStore.getKey<String>(PREF_BG_IMAGE_PATH) ?: "")
    val backgroundImagePath: StateFlow<String> = _backgroundImagePath.asStateFlow()

    private val _backgroundImageBlur = MutableStateFlow(DesktopDataStore.getKey<Float>(PREF_BG_IMAGE_BLUR) ?: 20f)
    val backgroundImageBlur: StateFlow<Float> = _backgroundImageBlur.asStateFlow()

    private val _backgroundImageBrightness = MutableStateFlow(DesktopDataStore.getKey<Float>(PREF_BG_IMAGE_BRIGHTNESS) ?: 0.35f)
    val backgroundImageBrightness: StateFlow<Float> = _backgroundImageBrightness.asStateFlow()

    private val _backgroundImageOpacity = MutableStateFlow(DesktopDataStore.getKey<Float>(PREF_BG_IMAGE_OPACITY) ?: 1.0f)
    val backgroundImageOpacity: StateFlow<Float> = _backgroundImageOpacity.asStateFlow()

    private val _backgroundImageSaturation = MutableStateFlow(DesktopDataStore.getKey<Float>(PREF_BG_IMAGE_SATURATION) ?: 1.0f)
    val backgroundImageSaturation: StateFlow<Float> = _backgroundImageSaturation.asStateFlow()

    private val _backgroundImageVignetteEnabled = MutableStateFlow(DesktopDataStore.getKey<Boolean>(PREF_BG_IMAGE_VIGNETTE) ?: false)
    val backgroundImageVignetteEnabled: StateFlow<Boolean> = _backgroundImageVignetteEnabled.asStateFlow()

    private val _backgroundImageVignetteIntensity = MutableStateFlow(DesktopDataStore.getKey<Float>(PREF_BG_IMAGE_VIGNETTE_INTENSITY) ?: 0.7f)
    val backgroundImageVignetteIntensity: StateFlow<Float> = _backgroundImageVignetteIntensity.asStateFlow()

    private val _backgroundImageTintEnabled = MutableStateFlow(DesktopDataStore.getKey<Boolean>(PREF_BG_IMAGE_TINT_ENABLED) ?: false)
    val backgroundImageTintEnabled: StateFlow<Boolean> = _backgroundImageTintEnabled.asStateFlow()

    private val _backgroundImageTintColor = MutableStateFlow(DesktopDataStore.getKey<String>(PREF_BG_IMAGE_TINT_COLOR) ?: "#7C6BFF")
    val backgroundImageTintColor: StateFlow<String> = _backgroundImageTintColor.asStateFlow()

    private val _backgroundImageTintAlpha = MutableStateFlow(DesktopDataStore.getKey<Float>(PREF_BG_IMAGE_TINT_ALPHA) ?: 0.3f)
    val backgroundImageTintAlpha: StateFlow<Float> = _backgroundImageTintAlpha.asStateFlow()

    private val _uiCardOpacity = MutableStateFlow(DesktopDataStore.getKey<Float>(PREF_UI_CARD_OPACITY) ?: 0.4f)
    val uiCardOpacity: StateFlow<Float> = _uiCardOpacity.asStateFlow()

    private fun persist(key: String, value: Any?) {
        persistenceScope.launch {
            DesktopDataStore.setKey(key, value)
        }
    }

    fun setBackgroundImagePath(path: String) {
        _backgroundImagePath.value = path
        persist(PREF_BG_IMAGE_PATH, path)
    }

    fun setBackgroundImageBlur(blur: Float) {
        _backgroundImageBlur.value = blur
        persist(PREF_BG_IMAGE_BLUR, blur)
    }

    fun setBackgroundImageBrightness(brightness: Float) {
        _backgroundImageBrightness.value = brightness
        persist(PREF_BG_IMAGE_BRIGHTNESS, brightness)
    }

    fun clearBackgroundImage() {
        _backgroundImagePath.value = ""
        persist(PREF_BG_IMAGE_PATH, "")
    }

    fun setBackgroundImageOpacity(opacity: Float) {
        _backgroundImageOpacity.value = opacity
        persist(PREF_BG_IMAGE_OPACITY, opacity)
    }

    fun setBackgroundImageSaturation(saturation: Float) {
        _backgroundImageSaturation.value = saturation
        persist(PREF_BG_IMAGE_SATURATION, saturation)
    }

    fun setBackgroundImageVignetteEnabled(enabled: Boolean) {
        _backgroundImageVignetteEnabled.value = enabled
        persist(PREF_BG_IMAGE_VIGNETTE, enabled)
    }

    fun setBackgroundImageVignetteIntensity(intensity: Float) {
        _backgroundImageVignetteIntensity.value = intensity
        persist(PREF_BG_IMAGE_VIGNETTE_INTENSITY, intensity)
    }

    fun setBackgroundImageTintEnabled(enabled: Boolean) {
        _backgroundImageTintEnabled.value = enabled
        persist(PREF_BG_IMAGE_TINT_ENABLED, enabled)
    }

    fun setBackgroundImageTintColor(hex: String) {
        _backgroundImageTintColor.value = hex
        persist(PREF_BG_IMAGE_TINT_COLOR, hex)
    }

    fun setBackgroundImageTintAlpha(alpha: Float) {
        _backgroundImageTintAlpha.value = alpha
        persist(PREF_BG_IMAGE_TINT_ALPHA, alpha)
    }

    fun setUiCardOpacity(opacity: Float) {
        _uiCardOpacity.value = opacity
        persist(PREF_UI_CARD_OPACITY, opacity)
    }

    fun reloadFromDataStore() {
        _backgroundImagePath.value = DesktopDataStore.getKey<String>(PREF_BG_IMAGE_PATH) ?: ""
        _backgroundImageBlur.value = DesktopDataStore.getKey<Float>(PREF_BG_IMAGE_BLUR) ?: 20f
        _backgroundImageBrightness.value = DesktopDataStore.getKey<Float>(PREF_BG_IMAGE_BRIGHTNESS) ?: 0.35f
        _backgroundImageOpacity.value = DesktopDataStore.getKey<Float>(PREF_BG_IMAGE_OPACITY) ?: 1.0f
        _backgroundImageSaturation.value = DesktopDataStore.getKey<Float>(PREF_BG_IMAGE_SATURATION) ?: 1.0f
        _backgroundImageVignetteEnabled.value = DesktopDataStore.getKey<Boolean>(PREF_BG_IMAGE_VIGNETTE) ?: false
        _backgroundImageVignetteIntensity.value = DesktopDataStore.getKey<Float>(PREF_BG_IMAGE_VIGNETTE_INTENSITY) ?: 0.7f
        _backgroundImageTintEnabled.value = DesktopDataStore.getKey<Boolean>(PREF_BG_IMAGE_TINT_ENABLED) ?: false
        _backgroundImageTintColor.value = DesktopDataStore.getKey<String>(PREF_BG_IMAGE_TINT_COLOR) ?: "#7C6BFF"
        _backgroundImageTintAlpha.value = DesktopDataStore.getKey<Float>(PREF_BG_IMAGE_TINT_ALPHA) ?: 0.3f
        _uiCardOpacity.value = DesktopDataStore.getKey<Float>(PREF_UI_CARD_OPACITY) ?: 0.4f
    }
}
