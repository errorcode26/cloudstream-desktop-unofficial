package com.lagradost.common.storage

import com.lagradost.common.db.DatabaseFactory
import com.lagradost.common.logging.AppLogger

object DesktopBookmarkStore {
    fun normalizeUrl(url: String): String {
        return url.trim().removeSuffix("/")
    }

    fun bookmarkId(apiName: String, url: String): String {
        return "${apiName}_${normalizeUrl(url).hashCode()}"
    }

    fun getBookmarks(profileId: Int = DesktopDataStore.activeProfileId): List<DesktopBookmark> {
        val prefix = "p${profileId}_"
        return DesktopDataStore.withDbLock {
            try {
                DatabaseFactory.database.cloudstreamDBQueries.selectBookmarksByProfile(profileId.toLong()).executeAsList()
                    .map {
                        val cleanId = if (it.id.startsWith(prefix)) {
                            it.id.removePrefix(prefix)
                        } else if (it.id.startsWith("p") && it.id.contains("_")) {
                            it.id.substringAfter("_")
                        } else {
                            it.id
                        }
                        DesktopBookmark(cleanId, it.name, it.url, it.apiName, it.posterUrl, it.watchType?.toInt() ?: 0, it.dateAdded ?: 0L)
                    }.distinctBy { it.id }
                    .filter { it.name.isNotBlank() }
            } catch (e: Exception) {
                AppLogger.e("Failed to fetch bookmarks for profile $profileId", e)
                emptyList()
            }
        }
    }

    fun addBookmark(bookmark: DesktopBookmark, profileId: Int = DesktopDataStore.activeProfileId) {
        val cleanId = if (bookmark.id.startsWith("p") && bookmark.id.contains("_")) bookmark.id.substringAfter("_") else bookmark.id
        val resolvedId = "p${profileId}_$cleanId"
        DesktopDataStore.withDbLock {
            try {
                DatabaseFactory.database.cloudstreamDBQueries.insertBookmark(
                    resolvedId,
                    bookmark.name,
                    bookmark.url,
                    bookmark.apiName,
                    bookmark.posterUrl,
                    bookmark.watchType.toLong(),
                    bookmark.dateAdded.also {
                        if (profileId == 0 && cleanId != resolvedId) {
                            DatabaseFactory.database.cloudstreamDBQueries.deleteBookmark(cleanId)
                        }
                    },
                    profileId.toLong(),
                )
            } catch (e: Exception) {
                AppLogger.e("Failed to insert bookmark $resolvedId", e)
            }
        }
        DesktopDataStore.notifyBookmarksChanged()
    }

    fun removeBookmark(id: String, profileId: Int = DesktopDataStore.activeProfileId) {
        val cleanId = if (id.startsWith("p") && id.contains("_")) id.substringAfter("_") else id
        val resolvedId = "p${profileId}_$cleanId"
        DesktopDataStore.withDbLock {
            try {
                DatabaseFactory.database.cloudstreamDBQueries.deleteBookmark(resolvedId)
                if (profileId == 0) {
                    DatabaseFactory.database.cloudstreamDBQueries.deleteBookmark(cleanId)
                }
            } catch (e: Exception) {
                AppLogger.e("Failed to delete bookmark $resolvedId", e)
            }
        }
        DesktopDataStore.notifyBookmarksChanged()
    }

    fun isBookmarked(id: String, profileId: Int = DesktopDataStore.activeProfileId): Boolean {
        val cleanId = if (id.startsWith("p") && id.contains("_")) id.substringAfter("_") else id
        val resolvedId = "p${profileId}_$cleanId"
        return DesktopDataStore.withDbLock {
            try {
                val exists = DatabaseFactory.database.cloudstreamDBQueries.selectBookmarkById(resolvedId).executeAsOneOrNull() != null
                if (exists) return@withDbLock true
                profileId == 0 && DatabaseFactory.database.cloudstreamDBQueries.selectBookmarkById(cleanId).executeAsOneOrNull() != null
            } catch (e: Exception) {
                AppLogger.e("Failed to check if bookmarked: $id", e)
                false
            }
        }
    }

    fun clearAllBookmarks(profileId: Int = DesktopDataStore.activeProfileId) {
        DesktopDataStore.withDbLock {
            try {
                DatabaseFactory.database.cloudstreamDBQueries.deleteBookmarksByProfile(profileId.toLong())
            } catch (e: Exception) {
                AppLogger.e("Failed to clear all bookmarks for profile $profileId", e)
            }
        }
        DesktopDataStore.notifyBookmarksChanged()
    }
}
