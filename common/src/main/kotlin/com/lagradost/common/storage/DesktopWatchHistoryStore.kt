package com.lagradost.common.storage

import com.lagradost.common.db.DatabaseFactory
import com.lagradost.common.logging.AppLogger

object DesktopWatchHistoryStore {
    private var lastHistoryNotifyMs = 0L

    fun notifyHistoryChanged(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (force || now - lastHistoryNotifyMs >= 1000L) {
            lastHistoryNotifyMs = now
            DesktopDataStore.historyUpdates.value++
        }
    }

    fun watchHistoryId(
        apiName: String,
        showUrl: String,
        season: Int? = null,
        episode: Int? = null,
        episodeData: String? = null,
        profileId: Int = DesktopDataStore.activeProfileId,
    ): String {
        val base = "p${profileId}_${apiName}_${DesktopBookmarkStore.normalizeUrl(showUrl).hashCode()}"
        return if (season != null || episode != null || !episodeData.isNullOrBlank()) {
            "${base}_s${season ?: 0}_e${episode ?: 0}_${episodeData?.hashCode() ?: 0}"
        } else {
            base
        }
    }

    fun getAllWatchHistory(profileId: Int = DesktopDataStore.activeProfileId): List<WatchHistory> {
        return DesktopDataStore.withDbLock {
            try {
                DatabaseFactory.database.cloudstreamDBQueries.selectWatchHistoryByProfile(profileId.toLong()).executeAsList()
                    .filter {
                        it.showName.isNotBlank() &&
                            !it.showName.equals("Unknown Channel", ignoreCase = true) &&
                            !it.showName.equals("Unknown", ignoreCase = true) &&
                            (it.duration > 0 || it.position > 0)
                    }
                    .map {
                        WatchHistory(
                            parentId = it.parentId,
                            showName = it.showName,
                            showUrl = it.showUrl,
                            apiName = it.apiName,
                            posterUrl = it.posterUrl,
                            episodeThumbnailUrl = it.episodeThumbnailUrl,
                            screenshotUrl = it.screenshotUrl,
                            episode = it.episode?.toInt(),
                            season = it.season?.toInt(),
                            episodeId = it.episodeId.takeIf { id -> id.isNotEmpty() },
                            position = it.position,
                            duration = it.duration,
                            updateTime = it.updateTime,
                            episodeName = it.episodeName,
                            episodeDescription = it.episodeDescription,
                        )
                    }
            } catch (e: Exception) {
                AppLogger.e("Failed to get watch history for profile $profileId", e)
                emptyList()
            }
        }
    }

    fun getLastWatched(parentId: String): WatchHistory? {
        return DesktopDataStore.withDbLock {
            try {
                DatabaseFactory.database.cloudstreamDBQueries
                    .selectWatchHistoryByParent(parentId)
                    .executeAsList()
                    .firstOrNull()
                    ?.let {
                        WatchHistory(
                            parentId = it.parentId,
                            showName = it.showName,
                            showUrl = it.showUrl,
                            apiName = it.apiName,
                            posterUrl = it.posterUrl,
                            episodeThumbnailUrl = it.episodeThumbnailUrl,
                            screenshotUrl = it.screenshotUrl,
                            episode = it.episode?.toInt(),
                            season = it.season?.toInt(),
                            episodeId = it.episodeId.takeIf { id -> id.isNotEmpty() },
                            position = it.position,
                            duration = it.duration,
                            updateTime = it.updateTime,
                            episodeName = it.episodeName,
                            episodeDescription = it.episodeDescription,
                        )
                    }
            } catch (e: Exception) {
                AppLogger.e("Failed to get last watched for $parentId", e)
                null
            }
        }
    }

    fun getWatchHistoryByParent(parentId: String): List<WatchHistory> {
        return DesktopDataStore.withDbLock {
            try {
                DatabaseFactory.database.cloudstreamDBQueries
                    .selectWatchHistoryByParent(parentId)
                    .executeAsList()
                    .map {
                        WatchHistory(
                            parentId = it.parentId,
                            showName = it.showName,
                            showUrl = it.showUrl,
                            apiName = it.apiName,
                            posterUrl = it.posterUrl,
                            episodeThumbnailUrl = it.episodeThumbnailUrl,
                            screenshotUrl = it.screenshotUrl,
                            episode = it.episode?.toInt(),
                            season = it.season?.toInt(),
                            episodeId = it.episodeId.takeIf { id -> id.isNotEmpty() },
                            position = it.position,
                            duration = it.duration,
                            updateTime = it.updateTime,
                            episodeName = it.episodeName,
                            episodeDescription = it.episodeDescription,
                        )
                    }
            } catch (e: Exception) {
                AppLogger.e("Failed to get watch history by parent for $parentId", e)
                emptyList()
            }
        }
    }

    fun getLatestWatchHistoryForShow(showUrl: String): WatchHistory? {
        return DesktopDataStore.withDbLock {
            try {
                DatabaseFactory.database.cloudstreamDBQueries
                    .selectLatestWatchHistoryForShow(showUrl)
                    .executeAsOneOrNull()
                    ?.let {
                        WatchHistory(
                            parentId = it.parentId,
                            showName = it.showName,
                            showUrl = it.showUrl,
                            apiName = it.apiName,
                            posterUrl = it.posterUrl,
                            episodeThumbnailUrl = it.episodeThumbnailUrl,
                            screenshotUrl = it.screenshotUrl,
                            episode = it.episode?.toInt(),
                            season = it.season?.toInt(),
                            episodeId = it.episodeId.takeIf { id -> id.isNotEmpty() },
                            position = it.position,
                            duration = it.duration,
                            updateTime = it.updateTime,
                            episodeName = it.episodeName,
                            episodeDescription = it.episodeDescription,
                        )
                    }
            } catch (e: Exception) {
                AppLogger.e("Failed to get latest watch history for show $showUrl", e)
                null
            }
        }
    }

    fun getEpisodeWatched(
        parentId: String,
        episodeId: String?,
    ): WatchHistory? {
        val searchId = episodeId ?: ""
        return DesktopDataStore.withDbLock {
            try {
                DatabaseFactory.database.cloudstreamDBQueries
                    .selectWatchHistoryByEpisode(parentId, searchId)
                    .executeAsOneOrNull()
                    ?.let {
                        WatchHistory(
                            parentId = it.parentId,
                            showName = it.showName,
                            showUrl = it.showUrl,
                            apiName = it.apiName,
                            posterUrl = it.posterUrl,
                            episodeThumbnailUrl = it.episodeThumbnailUrl,
                            screenshotUrl = it.screenshotUrl,
                            episode = it.episode?.toInt(),
                            season = it.season?.toInt(),
                            episodeId = it.episodeId.takeIf { id -> id.isNotEmpty() },
                            position = it.position,
                            duration = it.duration,
                            updateTime = it.updateTime,
                            episodeName = it.episodeName,
                            episodeDescription = it.episodeDescription,
                        )
                    }
            } catch (e: Exception) {
                AppLogger.e("Failed to get episode watched for $parentId / $searchId", e)
                null
            }
        }
    }

    fun setLastWatched(history: WatchHistory, forceNotify: Boolean = false) {
        val name = history.showName.trim()
        if (name.isBlank() || name.equals("Unknown Channel", ignoreCase = true) || name.equals("Unknown", ignoreCase = true)) {
            return
        }
        val normalizedDuration = history.duration.coerceAtLeast(0)
        val normalizedPosition = if (normalizedDuration > 0) {
            history.position.coerceIn(0, normalizedDuration)
        } else {
            history.position.coerceAtLeast(0)
        }
        if (normalizedDuration <= 0 && normalizedPosition <= 0) {
            return
        }

        val resolvedProfileId = if (history.parentId.startsWith("p") && history.parentId.contains("_")) {
            history.parentId.substringBefore("_").removePrefix("p").toLongOrNull() ?: DesktopDataStore.activeProfileId.toLong()
        } else {
            DesktopDataStore.activeProfileId.toLong()
        }

        DesktopDataStore.withDbLock {
            try {
                DatabaseFactory.database.cloudstreamDBQueries.insertWatchHistory(
                    parentId = history.parentId,
                    episodeId = history.episodeId ?: "",
                    showName = history.showName,
                    showUrl = history.showUrl,
                    apiName = history.apiName,
                    posterUrl = history.posterUrl,
                    episodeThumbnailUrl = history.episodeThumbnailUrl,
                    screenshotUrl = history.screenshotUrl,
                    episode = history.episode?.toLong(),
                    season = history.season?.toLong(),
                    position = normalizedPosition,
                    duration = normalizedDuration,
                    updateTime = history.updateTime.takeIf { it > 0 } ?: System.currentTimeMillis(),
                    episodeName = history.episodeName,
                    episodeDescription = history.episodeDescription,
                    profileId = resolvedProfileId,
                )
            } catch (e: Exception) {
                AppLogger.e("Failed to insert watch history for ${history.parentId}", e)
            }
        }
        notifyHistoryChanged(force = forceNotify)
    }

    fun setMultipleLastWatched(histories: List<WatchHistory>) {
        val validHistories = histories.filter {
            val name = it.showName.trim()
            name.isNotBlank() &&
                !name.equals("Unknown Channel", ignoreCase = true) &&
                !name.equals("Unknown", ignoreCase = true) &&
                (it.duration > 0 || it.position > 0)
        }
        if (validHistories.isEmpty()) return
        DesktopDataStore.withDbLock {
            try {
                DatabaseFactory.database.cloudstreamDBQueries.transaction {
                    validHistories.forEach { history ->
                        val normalizedDuration = history.duration.coerceAtLeast(0)
                        val normalizedPosition = if (normalizedDuration > 0) {
                            history.position.coerceIn(0, normalizedDuration)
                        } else {
                            history.position.coerceAtLeast(0)
                        }

                        val resolvedProfileId = if (history.parentId.startsWith("p") && history.parentId.contains("_")) {
                            history.parentId.substringBefore("_").removePrefix("p").toLongOrNull() ?: DesktopDataStore.activeProfileId.toLong()
                        } else {
                            DesktopDataStore.activeProfileId.toLong()
                        }

                        DatabaseFactory.database.cloudstreamDBQueries.insertWatchHistory(
                            parentId = history.parentId,
                            episodeId = history.episodeId ?: "",
                            showName = history.showName,
                            showUrl = history.showUrl,
                            apiName = history.apiName,
                            posterUrl = history.posterUrl,
                            episodeThumbnailUrl = history.episodeThumbnailUrl,
                            screenshotUrl = history.screenshotUrl,
                            episode = history.episode?.toLong(),
                            season = history.season?.toLong(),
                            position = normalizedPosition,
                            duration = normalizedDuration,
                            updateTime = history.updateTime.takeIf { it > 0 } ?: System.currentTimeMillis(),
                            episodeName = history.episodeName,
                            episodeDescription = history.episodeDescription,
                            profileId = resolvedProfileId,
                        )
                    }
                }
            } catch (e: Exception) {
                AppLogger.e("Failed to insert multiple watch history items", e)
            }
        }
        notifyHistoryChanged(force = true)
    }

    fun clearAllWatchHistory(profileId: Int = DesktopDataStore.activeProfileId) {
        DesktopDataStore.withDbLock {
            try {
                DatabaseFactory.database.cloudstreamDBQueries.deleteWatchHistoryByProfile(profileId.toLong())
            } catch (e: Exception) {
                AppLogger.e("Failed to clear all watch history for profile $profileId", e)
            }
        }
        notifyHistoryChanged(force = true)
    }

    fun removeWatchHistory(parentId: String) {
        DesktopDataStore.withDbLock {
            try {
                DatabaseFactory.database.cloudstreamDBQueries.deleteWatchHistoryByParent(parentId)
                val legacyId = if (parentId.startsWith("p") && parentId.contains("_")) {
                    parentId.substringAfter("_")
                } else {
                    null
                }
                if (legacyId != null && legacyId != parentId) {
                    DatabaseFactory.database.cloudstreamDBQueries.deleteWatchHistoryByParent(legacyId)
                }
            } catch (e: Exception) {
                AppLogger.e("Failed to remove watch history for $parentId", e)
            }
        }
        notifyHistoryChanged(force = true)
    }

    fun removeEpisodeWatched(
        parentId: String,
        episodeId: String,
        season: Int? = null,
        episode: Int? = null,
        extraEpisodeIds: List<String> = emptyList(),
    ) {
        val allParentIds = mutableListOf(parentId)
        val legacyId = if (parentId.startsWith("p") && parentId.contains("_")) {
            parentId.substringAfter("_")
        } else {
            null
        }
        if (legacyId != null && legacyId != parentId) {
            allParentIds.add(legacyId)
        }

        val allEpisodeIds = (listOf(episodeId) + extraEpisodeIds).filter { it.isNotBlank() }.distinct()

        DesktopDataStore.withDbLock {
            try {
                DatabaseFactory.database.cloudstreamDBQueries.transaction {
                    allParentIds.forEach { pid ->
                        allEpisodeIds.forEach { eid ->
                            DatabaseFactory.database.cloudstreamDBQueries.deleteWatchHistoryByEpisode(pid, eid)
                        }
                        if (episode != null) {
                            val rows = DatabaseFactory.database.cloudstreamDBQueries.selectWatchHistoryByParent(pid).executeAsList()
                            rows.forEach { row ->
                                val rowSeason = row.season?.toInt() ?: 1
                                val targetSeason = season ?: 1
                                if (row.episode?.toInt() == episode && rowSeason == targetSeason) {
                                    DatabaseFactory.database.cloudstreamDBQueries.deleteWatchHistoryByEpisode(pid, row.episodeId)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                AppLogger.e("Failed to remove episode watched for $parentId", e)
            }
        }
        notifyHistoryChanged(force = true)
    }

    fun removeMultipleEpisodesWatched(
        parentId: String,
        episodeIds: List<String>,
        extraParentIds: List<String> = emptyList(),
    ) {
        if (episodeIds.isEmpty()) return
        val allParentIds = (listOf(parentId) + extraParentIds).toMutableList()
        val legacyId = if (parentId.startsWith("p") && parentId.contains("_")) {
            parentId.substringAfter("_")
        } else {
            null
        }
        if (legacyId != null && !allParentIds.contains(legacyId)) {
            allParentIds.add(legacyId)
        }

        val targetEpisodeIds = episodeIds.filter { it.isNotBlank() }.distinct()
        DesktopDataStore.withDbLock {
            try {
                DatabaseFactory.database.cloudstreamDBQueries.transaction {
                    allParentIds.forEach { pid ->
                        targetEpisodeIds.forEach { episodeId ->
                            DatabaseFactory.database.cloudstreamDBQueries.deleteWatchHistoryByEpisode(pid, episodeId)
                        }
                    }
                }
            } catch (e: Exception) {
                AppLogger.e("Failed to remove multiple episodes watched", e)
            }
        }
        notifyHistoryChanged(force = true)
    }

    fun migrateWatchHistoryAndBookmark(
        oldApiName: String,
        oldUrl: String,
        newApiName: String,
        newUrl: String,
        newName: String,
        newPosterUrl: String?,
        profileId: Int = DesktopDataStore.activeProfileId,
    ): Boolean {
        val oldParentId = watchHistoryId(oldApiName, oldUrl, profileId = profileId)
        val newParentId = watchHistoryId(newApiName, newUrl, profileId = profileId)
        val legacyOldParentId = if (oldParentId.startsWith("p") && oldParentId.contains("_")) oldParentId.substringAfter("_") else null

        val oldCleanBookmarkId = DesktopBookmarkStore.bookmarkId(oldApiName, oldUrl)
        val newCleanBookmarkId = DesktopBookmarkStore.bookmarkId(newApiName, newUrl)
        val oldResolvedBookmarkId = "p${profileId}_$oldCleanBookmarkId"
        val newResolvedBookmarkId = "p${profileId}_$newCleanBookmarkId"

        return DesktopDataStore.withDbLock {
            try {
                DatabaseFactory.database.cloudstreamDBQueries.transaction {
                    val existingRows = DatabaseFactory.database.cloudstreamDBQueries.selectWatchHistoryByParent(oldParentId).executeAsList().toMutableList()
                    if (legacyOldParentId != null && legacyOldParentId != oldParentId) {
                        existingRows.addAll(DatabaseFactory.database.cloudstreamDBQueries.selectWatchHistoryByParent(legacyOldParentId).executeAsList())
                    }

                    existingRows.distinctBy { "${it.season}_${it.episode}_${it.episodeId}" }.forEach { row ->
                        DatabaseFactory.database.cloudstreamDBQueries.insertWatchHistory(
                            parentId = newParentId,
                            episodeId = row.episodeId,
                            showName = newName.takeIf { it.isNotBlank() } ?: row.showName,
                            showUrl = newUrl,
                            apiName = newApiName,
                            posterUrl = newPosterUrl ?: row.posterUrl,
                            episodeThumbnailUrl = row.episodeThumbnailUrl,
                            screenshotUrl = row.screenshotUrl,
                            episode = row.episode,
                            season = row.season,
                            position = row.position,
                            duration = row.duration,
                            updateTime = row.updateTime,
                            episodeName = row.episodeName,
                            episodeDescription = row.episodeDescription,
                            profileId = profileId.toLong(),
                        )
                    }

                    DatabaseFactory.database.cloudstreamDBQueries.deleteWatchHistoryByParent(oldParentId)
                    if (legacyOldParentId != null) {
                        DatabaseFactory.database.cloudstreamDBQueries.deleteWatchHistoryByParent(legacyOldParentId)
                    }

                    val existingBookmark = DatabaseFactory.database.cloudstreamDBQueries.selectBookmarkById(oldResolvedBookmarkId).executeAsOneOrNull()
                        ?: DatabaseFactory.database.cloudstreamDBQueries.selectBookmarkById(oldCleanBookmarkId).executeAsOneOrNull()
                    if (existingBookmark != null) {
                        DatabaseFactory.database.cloudstreamDBQueries.insertBookmark(
                            newResolvedBookmarkId,
                            newName.takeIf { it.isNotBlank() } ?: existingBookmark.name,
                            newUrl,
                            newApiName,
                            newPosterUrl ?: existingBookmark.posterUrl,
                            existingBookmark.watchType ?: 0L,
                            existingBookmark.dateAdded ?: System.currentTimeMillis(),
                            profileId.toLong(),
                        )
                        DatabaseFactory.database.cloudstreamDBQueries.deleteBookmark(oldResolvedBookmarkId)
                        DatabaseFactory.database.cloudstreamDBQueries.deleteBookmark(oldCleanBookmarkId)
                    }
                }
                notifyHistoryChanged(force = true)
                DesktopDataStore.notifyBookmarksChanged()
                true
            } catch (e: Exception) {
                AppLogger.e("Failed to migrate watch history and bookmark from $oldParentId to $newParentId", e)
                false
            }
        }
    }
}
