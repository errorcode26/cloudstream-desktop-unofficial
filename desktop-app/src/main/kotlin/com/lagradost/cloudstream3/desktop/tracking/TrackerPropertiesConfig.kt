package com.lagradost.cloudstream3.desktop.tracking

import com.lagradost.common.storage.DesktopDataStore
import java.util.Properties

/**
 * Shared loader for baked build-time properties (`/tracker.properties`) and optional
 * runtime Client ID overrides stored via [DesktopDataStore].
 */
object TrackerPropertiesConfig {
    const val PROP_ANILIST_CLIENT_ID = "anilist.client_id"
    const val PROP_ANILIST_CLIENT_SECRET = "anilist.client_secret"
    const val PROP_TMDB_API_KEY = "tmdb.api_key"
    const val PROP_MAL_CLIENT_ID = "mal.client_id"
    const val PROP_MAL_CLIENT_SECRET = "mal.client_secret"
    const val PROP_SIMKL_CLIENT_ID = "simkl.client_id"

    const val KEY_TRACKER_MAL_CUSTOM_CLIENT_ID = "tracker_mal_custom_client_id"
    const val KEY_TRACKER_MAL_CUSTOM_CLIENT_SECRET = "tracker_mal_custom_client_secret"
    const val KEY_TRACKER_ANILIST_CUSTOM_CLIENT_SECRET = "tracker_anilist_custom_client_secret"
    const val KEY_TRACKER_SIMKL_CUSTOM_CLIENT_ID = "tracker_simkl_custom_client_id"

    private val properties: Properties by lazy {
        Properties().apply {
            runCatching {
                TrackerPropertiesConfig::class.java.getResourceAsStream("/tracker.properties")?.use { stream ->
                    load(stream)
                }
            }
        }
    }

    fun bakedProperty(key: String): String = properties.getProperty(key)?.trim().orEmpty()

    fun customClientId(storeKey: String): String =
        runCatching { DesktopDataStore.getKey<String>(storeKey)?.trim().orEmpty() }.getOrDefault("")

    fun setCustomClientId(storeKey: String, value: String) {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) {
            DesktopDataStore.removeKey(storeKey)
        } else {
            DesktopDataStore.setKey(storeKey, trimmed)
        }
    }

    fun resolveClientId(customStoreKey: String, propertyKey: String): String =
        customClientId(customStoreKey).ifBlank { bakedProperty(propertyKey) }
}
