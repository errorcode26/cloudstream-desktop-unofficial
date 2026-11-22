package com.lagradost.cloudstream3.desktop.ui.theme

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.common.storage.DesktopDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object ThemePresetsEngine {
    private const val PREF_CUSTOM_PRESETS = "pref_custom_presets"
    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mapper = jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    private val _customPresets = MutableStateFlow(loadPresetsFromStorage())
    val customPresets: StateFlow<List<ThemePreset>> = _customPresets.asStateFlow()

    private fun loadPresetsFromStorage(): List<ThemePreset> {
        val json = DesktopDataStore.getKey<String>(PREF_CUSTOM_PRESETS) ?: "[]"
        return try {
            mapper.readValue(json, object : TypeReference<List<ThemePreset>>() {})
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveCustomPreset(preset: ThemePreset, onPresetSaved: ((String) -> Unit)? = null) {
        val currentList = _customPresets.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == preset.id }
        if (index != -1) {
            currentList[index] = preset
        } else {
            currentList.add(preset)
        }
        _customPresets.value = currentList
        saveCustomPresetsToDisk(currentList)
        onPresetSaved?.invoke(preset.id)
    }

    fun deleteCustomPreset(id: String, onPresetDeleted: ((id: String) -> Unit)? = null) {
        val currentList = _customPresets.value.filter { it.id != id }
        _customPresets.value = currentList
        saveCustomPresetsToDisk(currentList)
        onPresetDeleted?.invoke(id)
    }

    private fun saveCustomPresetsToDisk(list: List<ThemePreset>) {
        persistenceScope.launch {
            try {
                val json = mapper.writeValueAsString(list)
                DesktopDataStore.setKey(PREF_CUSTOM_PRESETS, json)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun reloadFromDataStore() {
        _customPresets.value = loadPresetsFromStorage()
    }
}
