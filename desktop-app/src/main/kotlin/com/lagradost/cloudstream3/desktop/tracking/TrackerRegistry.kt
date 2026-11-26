package com.lagradost.cloudstream3.desktop.tracking

import com.lagradost.cloudstream3.desktop.domain.tracking.repository.TrackingRepository
import com.lagradost.cloudstream3.syncproviders.AuthData
import java.util.concurrent.ConcurrentHashMap

data class PlannedTrackerDescriptor(
    val id: String,
    val displayName: String,
    val shortTag: String,
    val subtitle: String,
    val accentColorHex: Long,
)

/**
 * Central registry for all remote list trackers.
 * New trackers register a [TrackerProvider] implementation here without duplicating ViewModel or UI files.
 */
object TrackerRegistry {
    private val providers = ConcurrentHashMap<String, TrackerProvider>()

    init {
        register(AniListTrackerProvider())
        register(MalTrackerProvider())
        register(SimklTrackerProvider())
    }

    val plannedTrackers: List<PlannedTrackerDescriptor> = listOf(
        PlannedTrackerDescriptor(
            id = TrackerIds.TRAKT,
            displayName = "Trakt",
            shortTag = "TRK",
            subtitle = "TV Shows & Movies scrobbling — integration queued",
            accentColorHex = 0xFFED1C24L,
        ),
    )

    fun register(provider: TrackerProvider) {
        providers[provider.id] = provider
    }

    fun get(id: String): TrackerProvider? = providers[id]

    fun all(): List<TrackerProvider> = providers.values.sortedBy { providerSortOrder(it.id) }

    fun allConnected(accounts: Map<String, Array<AuthData>>): List<TrackerProvider> =
        all().filter { it.isConnected(accounts) }

    fun allConnected(repository: TrackingRepository): List<TrackerProvider> =
        all().filter { it.isConnected(repository) }

    private fun providerSortOrder(id: String): Int = when (id) {
        TrackerIds.ANILIST -> 0
        TrackerIds.MAL -> 1
        TrackerIds.SIMKL -> 2
        TrackerIds.TRAKT -> 3
        else -> 99
    }
}
