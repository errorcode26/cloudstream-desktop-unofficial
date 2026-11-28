package com.lagradost.cloudstream3.desktop.ui.badges

import com.lagradost.common.platform.PlatformPaths
import com.lagradost.common.storage.DesktopDataStore
import java.io.File

object BadgeManager {
    const val PREF_BADGE_STYLE = "pref_badge_style"
    const val DEFAULT_STYLE = "Badges-Black-White"

    val currentStyle: String
        get() = DesktopDataStore.getKey<String>(PREF_BADGE_STYLE) ?: DEFAULT_STYLE

    /**
     * Resolves the root directory containing all badge styles.
     */
    fun getBadgesRootDir(): File {
        val devDir = File("desktop-app/src/main/resources/badges").takeIf { it.exists() && it.isDirectory }
            ?: File("src/main/resources/badges").takeIf { it.exists() && it.isDirectory }
        if (devDir != null) return devDir

        val resourcesDir = System.getProperty("compose.application.resources.dir")
        if (resourcesDir != null) {
            val resBadges = File(resourcesDir, "badges")
            if (resBadges.exists() && resBadges.isDirectory) return resBadges
        }

        val appDataBadges = File(PlatformPaths.appDataDir, "badges")
        if (!appDataBadges.exists()) {
            appDataBadges.mkdirs()
        }
        return appDataBadges
    }

    /**
     * Resolves the active style folder (e.g. Badges-Black-White).
     */
    fun getBadgesDir(style: String = currentStyle): File {
        val root = getBadgesRootDir()
        val target = File(root, style)
        if (!target.exists()) {
            val defaultTarget = File(root, DEFAULT_STYLE)
            if (defaultTarget.exists()) return defaultTarget
        }
        return target
    }

    private fun toNormalizedFileUri(file: File): String {
        var uri = file.toURI().toString()
        if (uri.startsWith("file:/") && !uri.startsWith("file:///")) {
            uri = uri.replaceFirst("file:/", "file:///")
        }
        return uri
    }

    private fun resolveStyleFile(fileName: String, style: String): File? {
        val dir = getBadgesDir(style)
        val diskFile = File(dir, fileName)
        if (diskFile.exists() && diskFile.length() > 0L) return diskFile

        // Packaged installers ship src/main/resources inside the JAR classpath rather than appResources.
        val cacheDest = File(File(PlatformPaths.appDataDir, "badges/$style"), fileName)
        return StreamTechTagExtractor.extractClasspathResourceIfNeeded("/badges/$style/$fileName", cacheDest)
    }

    /**
     * Resolves a local File handle for a badge image across the active style,
     * falling back to DEFAULT_STYLE and extracting from JAR resources if needed.
     */
    fun getBadgeFile(fileName: String, style: String = currentStyle): File? {
        val cleanName = fileName.trim().trimStart('/', '\\')
        if (cleanName.isBlank() || cleanName.contains("..")) return null
        resolveStyleFile(cleanName, style)?.let { return it }
        if (style != DEFAULT_STYLE) {
            resolveStyleFile(cleanName, DEFAULT_STYLE)?.let { return it }
        }
        return null
    }

    /**
     * Resolves a top-level badge asset (e.g. rating_imdb.png, tracker_anilist.svg)
     * from disk or JAR classpath resources.
     */
    fun getRootAssetFile(fileName: String): File? {
        val cleanName = fileName.trim().trimStart('/', '\\')
        if (cleanName.isBlank() || cleanName.contains("..")) return null
        val diskFile = File(getBadgesRootDir(), cleanName)
        if (diskFile.exists() && diskFile.length() > 0L) return diskFile
        val cacheDest = File(File(PlatformPaths.appDataDir, "badges"), cleanName)
        return StreamTechTagExtractor.extractClasspathResourceIfNeeded("/badges/$cleanName", cacheDest)
    }

    fun getRootAssetUri(fileName: String): String? {
        val file = getRootAssetFile(fileName) ?: return null
        return toNormalizedFileUri(file)
    }

    /**
     * Returns a file:/// URI for the badge image.
     */
    fun getBadgeUri(fileName: String, style: String = currentStyle): String? {
        val file = getBadgeFile(fileName, style) ?: return null
        return toNormalizedFileUri(file)
    }

    /**
     * Returns the base file:/// URI for the active badge style directory (with trailing slash)
     * suitable for HTML / WebView2 img src concatenation.
     */
    fun getBadgesBaseUri(style: String = currentStyle): String {
        val dir = getBadgesDir(style)
        var uri = toNormalizedFileUri(dir)
        if (!uri.endsWith("/")) {
            uri += "/"
        }
        return uri
    }
}
