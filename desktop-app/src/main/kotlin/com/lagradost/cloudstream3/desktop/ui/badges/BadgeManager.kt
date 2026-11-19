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

    /**
     * Resolves a local File handle for a badge image.
     */
    fun getBadgeFile(fileName: String, style: String = currentStyle): File? {
        if (fileName.isBlank()) return null
        val file = File(getBadgesDir(style), fileName)
        if (file.exists() && file.length() > 0L) return file
        // Fallback to default style if active style doesn't have it
        if (style != DEFAULT_STYLE) {
            val fallbackFile = File(getBadgesDir(DEFAULT_STYLE), fileName)
            if (fallbackFile.exists() && fallbackFile.length() > 0L) return fallbackFile
        }
        return null
    }

    /**
     * Returns a file:/// URI for the badge image.
     */
    fun getBadgeUri(fileName: String, style: String = currentStyle): String? {
        val file = getBadgeFile(fileName, style) ?: return null
        val uri = file.toURI().toString()
        return if (uri.startsWith("file:/") && !uri.startsWith("file:///")) {
            uri.replaceFirst("file:/", "file:///")
        } else {
            uri
        }
    }

    /**
     * Returns the base file:/// URI for the active badge style directory (with trailing slash)
     * suitable for HTML / WebView2 img src concatenation.
     */
    fun getBadgesBaseUri(style: String = currentStyle): String {
        val dir = getBadgesDir(style)
        var uri = dir.toURI().toString()
        if (uri.startsWith("file:/") && !uri.startsWith("file:///")) {
            uri = uri.replaceFirst("file:/", "file:///")
        }
        if (!uri.endsWith("/")) {
            uri += "/"
        }
        return uri
    }
}
