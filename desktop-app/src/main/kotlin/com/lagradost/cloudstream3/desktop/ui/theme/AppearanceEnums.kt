package com.lagradost.cloudstream3.desktop.ui.theme

enum class ThemeMode(val label: String) {
    LIGHT("Light"),
    DARK("Dark"),
    AMOLED("Pure AMOLED"),
}

enum class PosterTitlePosition {
    INSIDE,
    BELOW,
    HIDDEN,
    ;

    companion object {
        fun fromString(value: String?): PosterTitlePosition {
            return entries.find { it.name.equals(value, ignoreCase = true) } ?: BELOW
        }
    }
}

enum class PosterMetadataPosition(val label: String, val description: String) {
    BELOW_POSTER("Below Poster", "Apple TV+ / Infuse style: 100% clean artwork; specs sit under the title."),
    ON_POSTER("On Poster", "Dual micro-pills floating in poster top corners."),
    HYBRID("Hybrid", "Star rating on poster top-left; technical specs under title."),
    STEALTH_HOVER("Stealth Hover", "Artwork is clean when resting; badges fade in on hover."),
    ;

    companion object {
        fun fromString(value: String?): PosterMetadataPosition {
            return entries.find { it.name.equals(value, ignoreCase = true) } ?: BELOW_POSTER
        }
    }
}

enum class PosterTextAlignment(val label: String) {
    LEFT("Left"),
    CENTER("Center"),
    ;

    companion object {
        fun fromString(value: String?): PosterTextAlignment {
            return entries.find { it.name.equals(value, ignoreCase = true) } ?: LEFT
        }
    }
}

enum class ContinueWatchingStyle {
    THUMBNAIL,
    PREMIUM,
    DETAILED,
    ;

    companion object {
        fun fromString(value: String?): ContinueWatchingStyle {
            return entries.find { it.name.equals(value, ignoreCase = true) } ?: THUMBNAIL
        }
    }
}

enum class ClockDisplayMode {
    HIDDEN,
    TIME_ONLY,
    DATE_ONLY,
    BOTH,
    ;

    companion object {
        fun fromString(v: String?) = entries.find { it.name.equals(v, ignoreCase = true) } ?: HIDDEN
    }
}

enum class DockItemKey(
    val id: String,
    val displayName: String,
    val description: String,
    val isRequired: Boolean = false,
) {
    HOME("home", "Home", "Main landing page with hero banner & catalogs", isRequired = true),
    EXPLORE("explore", "Explore", "Browse movies, series & anime catalogs by genre and year"),
    SEARCH("search", "Search", "Global search & provider explorer"),
    LIBRARY("library", "Library", "Bookmarked shows, movies, and custom lists"),
    DOWNLOADS("downloads", "Downloads", "Multi-threaded offline downloads & active queue"),
    SETTINGS("settings", "Settings", "Preferences, appearance, player, and plugins", isRequired = true),
    HISTORY("history", "Watch History", "Recently watched episodes & resume points"),
    EXTENSIONS("extensions", "Extensions", "Installed plugins, repos, and updates"),
    ;

    companion object {
        val DEFAULT_ORDER = listOf(HOME, EXPLORE, SEARCH, LIBRARY, DOWNLOADS, SETTINGS, HISTORY, EXTENSIONS)
        val DEFAULT_DISABLED = setOf(HISTORY, EXTENSIONS)

        fun parseOrder(raw: String?): List<DockItemKey> {
            if (raw.isNullOrBlank()) return DEFAULT_ORDER
            val parsed = raw.split(",").mapNotNull { id -> entries.find { it.id.equals(id.trim(), ignoreCase = true) } }
            val missing = entries.filter { it !in parsed }
            return parsed + missing
        }

        fun parseDisabled(raw: String?): Set<DockItemKey> {
            if (raw == null) return DEFAULT_DISABLED
            if (raw.isBlank() || raw.equals("NONE", ignoreCase = true)) return emptySet()
            return raw.split(",")
                .mapNotNull { id -> entries.find { it.id.equals(id.trim(), ignoreCase = true) } }
                .filter { !it.isRequired }
                .toSet()
        }

        fun serialize(items: Iterable<DockItemKey>): String {
            return serializeOrder(items)
        }

        fun serializeOrder(items: Iterable<DockItemKey>): String {
            return items.joinToString(",") { it.id }
        }

        fun serializeDisabled(items: Iterable<DockItemKey>): String {
            val list = items.filter { !it.isRequired }
            return if (list.isEmpty()) "NONE" else list.joinToString(",") { it.id }
        }
    }
}

enum class NavigationStyle(val label: String) {
    FLOATING_DOCK("Floating Dock"),
    SEAMLESS_BAR("Navigation Bar"),
    ;

    companion object {
        fun fromString(v: String?) = entries.find { it.name.equals(v, ignoreCase = true) || it.label.equals(v, ignoreCase = true) } ?: SEAMLESS_BAR
    }
}

enum class HeroBannerStyle(val label: String) {
    CINEMA_PEEKING("Cinema (Peeking Rails)"),
    FULLSCREEN_IMMERSIVE("Fullscreen Immersive"),
    THUMBNAIL_STRIP("Thumbnail Filmstrip"),
    CARDS_CAROUSEL("Cards Carousel (Peeking)"),
    ;

    companion object {
        fun fromString(v: String?) = entries.find { it.name.equals(v, ignoreCase = true) || it.label.equals(v, ignoreCase = true) } ?: CINEMA_PEEKING
    }
}

enum class TopBarProviderStyle(val label: String) {
    ICON_ONLY("Icon Only (Clean)"),
    ICON_AND_NAME("Icon & Name"),
    ;

    companion object {
        fun fromString(value: String?): TopBarProviderStyle {
            return entries.find { it.name.equals(value, ignoreCase = true) || it.label.equals(value, ignoreCase = true) } ?: ICON_AND_NAME
        }
    }
}

enum class ProviderBadgeDisplayMode(val label: String) {
    HIDDEN("Hidden (Clean)"),
    ICON_ONLY("Icon Only"),
    FULL_BADGE("Full Badge"),
    ;

    companion object {
        fun fromString(value: String?): ProviderBadgeDisplayMode {
            return entries.find { it.name.equals(value, ignoreCase = true) || it.label.equals(value, ignoreCase = true) } ?: HIDDEN
        }
    }
}
