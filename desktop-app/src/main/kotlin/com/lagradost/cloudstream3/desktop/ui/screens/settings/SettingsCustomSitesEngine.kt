package com.lagradost.cloudstream3.desktop.ui.screens.settings

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.desktop.core.preference.PreferenceKeys
import com.lagradost.cloudstream3.desktop.init.loadClonedSites
import com.lagradost.cloudstream3.desktop.models.CustomSite
import com.lagradost.cloudstream3.desktop.repo.ActiveProviderRepository
import com.lagradost.cloudstream3.desktop.sandbox.SandboxedPluginManager
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.storage.DesktopDataStore

object SettingsCustomSitesEngine {

    fun loadClonedSitesList(): List<CustomSite> {
        return try {
            val json = DesktopDataStore.getKey<String>(PreferenceKeys.USER_PROVIDER_API)
            if (json != null) {
                jacksonObjectMapper().readValue(json, object : TypeReference<List<CustomSite>>() {})
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun addClonedSite(site: CustomSite): Pair<List<CustomSite>, String> {
        val mapper = jacksonObjectMapper()
        val existing = try {
            val json = DesktopDataStore.getKey<String>(PreferenceKeys.USER_PROVIDER_API)
            if (json != null) mapper.readValue(json, object : TypeReference<List<CustomSite>>() {}) else emptyList()
        } catch (e: Exception) {
            AppLogger.w("Settings: Failed parsing cloned sites JSON", e)
            emptyList()
        }
        val newList = existing.filter { it.name != site.name } + site
        val jsonString = mapper.writeValueAsString(newList)
        DesktopDataStore.setKey(PreferenceKeys.USER_PROVIDER_API, jsonString)

        if (SandboxedPluginManager.isEnabled()) {
            try {
                SandboxedPluginManager.syncClonedSites(jsonString)
            } catch (e: Throwable) {
                AppLogger.d("SettingsCustomSitesEngine", "Sandboxed cloned site sync note: ${e.message}")
            }
        }
        try {
            loadClonedSites()
            ActiveProviderRepository.refreshProviders()
        } catch (e: Throwable) {
            AppLogger.w("SettingsCustomSitesEngine", "Failed to refresh cloned providers: ${e.message}")
        }
        return newList to jsonString
    }

    suspend fun removeClonedSite(site: CustomSite): Pair<List<CustomSite>, String> {
        val mapper = jacksonObjectMapper()
        val existing = try {
            val json = DesktopDataStore.getKey<String>(PreferenceKeys.USER_PROVIDER_API)
            if (json != null) mapper.readValue(json, object : TypeReference<List<CustomSite>>() {}) else emptyList()
        } catch (e: Exception) {
            AppLogger.w("Settings: Failed parsing cloned sites JSON", e)
            emptyList()
        }
        val newList = existing.filter { it.name != site.name && it.url != site.url }
        val jsonString = mapper.writeValueAsString(newList)
        DesktopDataStore.setKey(PreferenceKeys.USER_PROVIDER_API, jsonString)

        if (SandboxedPluginManager.isEnabled()) {
            try {
                SandboxedPluginManager.syncClonedSites(jsonString)
            } catch (e: Throwable) {
                AppLogger.d("SettingsCustomSitesEngine", "Sandboxed cloned site sync note: ${e.message}")
            }
        }
        synchronized(APIHolder.allProviders) {
            val toRemove = APIHolder.allProviders.filter { it.name == site.name && !it.canBeOverridden }
            toRemove.forEach {
                APIHolder.allProviders.remove(it)
                APIHolder.removePluginMapping(it)
            }
        }
        try {
            ActiveProviderRepository.refreshProviders()
        } catch (e: Throwable) {
            AppLogger.w("SettingsCustomSitesEngine", "Failed to refresh providers on removal: ${e.message}")
        }
        return newList to jsonString
    }
}
