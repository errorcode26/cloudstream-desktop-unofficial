package com.lagradost.common.storage

enum class DesktopWatchType(val id: Int, val stringRes: String) {
    WATCHING(0, "Watching"),
    COMPLETED(1, "Completed"),
    ONHOLD(2, "On Hold"),
    DROPPED(3, "Dropped"),
    PLANTOWATCH(4, "Plan to Watch"),
    REWATCHING(5, "Re-watching"),
}

data class DesktopBookmark(
    val id: String,
    val name: String,
    val url: String,
    val apiName: String,
    val posterUrl: String?,
    val watchType: Int = 0,
    val dateAdded: Long = System.currentTimeMillis(),
)

data class WatchHistory(
    val parentId: String,
    val showName: String,
    val showUrl: String,
    val apiName: String,
    val posterUrl: String?,
    val episodeThumbnailUrl: String?,
    val screenshotUrl: String?,
    val episode: Int?,
    val season: Int?,
    val episodeId: String?,
    val position: Long,
    val duration: Long,
    val updateTime: Long = System.currentTimeMillis(),
    val episodeName: String? = null,
    val episodeDescription: String? = null,
)

data class PluginUpdateRecord(
    val pluginName: String,
    val version: Int,
    val iconUrl: String?,
    val timestamp: Long = System.currentTimeMillis(),
    val isSuccess: Boolean = true,
    val errorMessage: String? = null,
)
