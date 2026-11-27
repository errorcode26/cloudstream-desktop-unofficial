package com.lagradost.cloudstream3.desktop.backup

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

enum class BackupKind {
    MANUAL,
    AUTO_STARTUP,
    PRE_UPDATE,
    PRE_RESTORE,
}

data class BackupOptions(
    val includeDatabase: Boolean = true,
    val includeCredentials: Boolean = true,
    val includeRepositories: Boolean = true,
    val includeExtensionBinaries: Boolean = true,
    val includeAssetsAndCookies: Boolean = true,
)

data class RestoreOptions(
    val restoreDatabase: Boolean = true,
    val restoreCredentials: Boolean = true,
    val restoreRepositories: Boolean = true,
    val restoreExtensionBinaries: Boolean = true,
    val restoreAssetsAndCookies: Boolean = true,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class BackupCounts(
    val profiles: Int = 0,
    val bookmarks: Int = 0,
    val watchHistoryEntries: Int = 0,
    val watchHistoryShows: Int = 0,
    val trackerBindings: Int = 0,
    val trackerSyncAudits: Int = 0,
    val downloadTasks: Int = 0,
    val pluginUpdates: Int = 0,
    val settingsKeys: Int = 0,
    val clonedSites: Int = 0,
    val repositories: Int = 0,
    val extensions: Int = 0,
    val avatars: Int = 0,
    val customFonts: Int = 0,
    val cookieDomains: Int = 0,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class BackedUpExtension(
    val relativePath: String,
    val fileName: String,
    val sha256: String,
    val sizeBytes: Long,
    val repoName: String? = null,
    val repoUrl: String? = null,
    val internalName: String? = null,
    val name: String? = null,
    val version: Int? = null,
    val binaryBundled: Boolean = true,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class BackupManifest(
    val formatVersion: Int = 1,
    val appVersion: String = "0.0.0-dev",
    val schemaVersion: Long = 2L,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val kind: BackupKind = BackupKind.MANUAL,
    val options: BackupOptions = BackupOptions(),
    val counts: BackupCounts = BackupCounts(),
    val checksums: Map<String, String> = emptyMap(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class KeyValueExport(val key: String, val value: String)

@JsonIgnoreProperties(ignoreUnknown = true)
data class BookmarkExport(
    val id: String,
    val name: String,
    val url: String,
    val apiName: String,
    val posterUrl: String?,
    val watchType: Long,
    val dateAdded: Long,
    val profileId: Long,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class WatchHistoryExport(
    val parentId: String,
    val episodeId: String,
    val showName: String,
    val showUrl: String,
    val apiName: String,
    val posterUrl: String?,
    val episodeThumbnailUrl: String?,
    val screenshotUrl: String?,
    val episode: Long?,
    val season: Long?,
    val position: Long,
    val duration: Long,
    val updateTime: Long,
    val episodeName: String?,
    val episodeDescription: String?,
    val profileId: Long,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PluginUpdateExport(
    val pluginName: String,
    val version: Long,
    val iconUrl: String?,
    val timestamp: Long,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class DownloadTaskExport(
    val id: String,
    val canonicalKey: String,
    val showName: String,
    val showUrl: String,
    val episodeTitle: String?,
    val posterUrl: String?,
    val backdropUrl: String?,
    val season: Long?,
    val episode: Long?,
    val filePath: String,
    val streamUrl: String,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val status: String,
    val quality: Long,
    val apiName: String,
    val headersJson: String?,
    val dateAdded: Long,
    val dateCompleted: Long?,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TrackerBindingExport(
    val profileId: Long,
    val trackerId: String,
    val showKey: String,
    val mappedSeason: Long,
    val remoteMediaId: String,
    val remoteTitle: String,
    val bindMode: String,
    val episodeOffset: Long,
    val expectedTotalEpisodes: Long?,
    val updatedAt: Long,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TrackerSyncAuditExport(
    val profileId: Long,
    val trackerId: String,
    val showName: String,
    val remoteMediaId: String,
    val episodeNum: Long,
    val effectiveRemoteEpisode: Long,
    val oldProgress: Long,
    val newProgress: Long,
    val statusApplied: String?,
    val state: String,
    val reason: String?,
    val timestamp: Long,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PortableDatabaseExport(
    val schemaVersion: Long = 2L,
    val keyValues: List<KeyValueExport> = emptyList(),
    val bookmarks: List<BookmarkExport> = emptyList(),
    val watchHistory: List<WatchHistoryExport> = emptyList(),
    val pluginUpdates: List<PluginUpdateExport> = emptyList(),
    val downloadTasks: List<DownloadTaskExport> = emptyList(),
    val trackerBindings: List<TrackerBindingExport> = emptyList(),
    val trackerSyncAudits: List<TrackerSyncAuditExport> = emptyList(),
)

data class BackupInspection(
    val filePath: String,
    val fileSizeBytes: Long,
    val manifest: BackupManifest,
    val hasSqliteDatabase: Boolean,
    val hasPortableJson: Boolean,
    val hasRepositories: Boolean,
    val hasCredentialsInBackup: Boolean,
    val extensions: List<BackedUpExtension> = emptyList(),
    val repositoryUrls: List<String> = emptyList(),
)

data class BackupArchiveEntry(
    val filePath: String,
    val fileName: String,
    val kind: BackupKind,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
    val counts: BackupCounts? = null,
)

data class RestoreResult(
    val success: Boolean,
    val message: String,
    val rollbackBackupPath: String? = null,
    val warnings: List<String> = emptyList(),
    val countsRestored: BackupCounts = BackupCounts(),
)
