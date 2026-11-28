package com.lagradost.cloudstream3.desktop.ui.screens.dev

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Global coordinator for Dev Studio & Live LogCat visibility across windows.
 */
object DevStudioState {
    private val _isOpen = MutableStateFlow(false)
    val isOpen = _isOpen.asStateFlow()

    private val _isDetachedWindow = MutableStateFlow(true)
    val isDetachedWindow = _isDetachedWindow.asStateFlow()

    private val _isVisualFocusHudEnabled = MutableStateFlow(false)
    val isVisualFocusHudEnabled = _isVisualFocusHudEnabled.asStateFlow()

    private val _visualFocusHudMode = MutableStateFlow(VisualFocusHudMode.FULL)
    val visualFocusHudMode = _visualFocusHudMode.asStateFlow()

    fun toggle() {
        if (_isOpen.value) {
            close()
        } else {
            open(detached = _isDetachedWindow.value)
        }
    }

    fun toggleVisualFocusHud(): Boolean {
        val next = !_isVisualFocusHudEnabled.value
        _isVisualFocusHudEnabled.value = next
        if (!next) {
            VisualFocusInspectorRegistry.clearAll()
        }
        return next
    }

    fun setVisualFocusHudEnabled(enabled: Boolean) {
        _isVisualFocusHudEnabled.value = enabled
        if (!enabled) {
            VisualFocusInspectorRegistry.clearAll()
        }
    }

    fun cycleVisualFocusHudMode(): VisualFocusHudMode {
        val entries = VisualFocusHudMode.entries
        val next = if (!_isVisualFocusHudEnabled.value) {
            _isVisualFocusHudEnabled.value = true
            _visualFocusHudMode.value
        } else {
            entries[(_visualFocusHudMode.value.ordinal + 1) % entries.size]
        }
        _visualFocusHudMode.value = next
        return next
    }

    fun open(detached: Boolean = true) {
        _isDetachedWindow.value = detached
        _isOpen.value = true
    }

    fun close() {
        _isOpen.value = false
    }

    fun detachToWindow() {
        _isDetachedWindow.value = true
        _isOpen.value = true
    }

    fun dockToMain() {
        _isDetachedWindow.value = false
        _isOpen.value = true
    }
}
