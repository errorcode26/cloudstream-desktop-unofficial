package com.lagradost.cloudstream3.desktop.explore.viewmodel

import com.lagradost.cloudstream3.desktop.explore.models.ExploreItem
import com.lagradost.cloudstream3.desktop.explore.models.ManifestCatalogDescriptor
import com.lagradost.cloudstream3.desktop.ui.badges.CardTitleSanitizer
import java.util.Locale

/**
 * Pure filtering and string matching algorithms for explore queries,
 * release year filters, genre mappings, and title relevance checks.
 */
internal object ExploreFilterEngine {
    private val NON_ALPHANUMERIC_REGEX = Regex("[^a-z0-9]")
    private val WHITESPACE_REGEX = Regex("\\s+")
    private val STOP_WORDS = setOf("the", "a", "an", "and", "of", "in", "to", "for", "with", "on", "at", "by", "from", "season", "episode")

    fun applyFilters(
        items: List<ExploreItem>,
        query: String,
        year: String,
        genre: String = "All",
    ): List<ExploreItem> {
        val q = query.trim().lowercase(Locale.US)
        val targetGenre = genre.trim().lowercase(Locale.US)
        return items.filter { item ->
            val matchesQuery = if (q.isBlank()) {
                true
            } else {
                item.name.lowercase(Locale.US).contains(q) ||
                    item.genres.any { it.lowercase(Locale.US).contains(q) } ||
                    item.releaseYear?.contains(q) == true
            }

            val itemYearInt = item.releaseYear?.toIntOrNull() ?: 0
            val matchesYear = when (year) {
                "All Years" -> true
                "2026" -> item.releaseYear == "2026"
                "2025" -> item.releaseYear == "2025"
                "2024" -> item.releaseYear == "2024"
                "2023" -> item.releaseYear == "2023"
                "2022" -> item.releaseYear == "2022"
                "2021" -> item.releaseYear == "2021"
                "2020" -> item.releaseYear == "2020"
                "2010s" -> itemYearInt in 2010..2019
                "2000s" -> itemYearInt in 2000..2009
                "1990s & Older" -> itemYearInt in 1..1999
                else -> item.releaseYear == year
            }

            val matchesGenre = if (targetGenre.isBlank() || targetGenre == "all") {
                true
            } else {
                val hasMatch = item.genres.any { itemGenre ->
                    val g = itemGenre.trim().lowercase(Locale.US)
                    g == targetGenre ||
                        (targetGenre == "kids" && (g == "family" || g == "animation" || g == "children")) ||
                        (targetGenre == "family" && g == "kids") ||
                        (targetGenre == "action" && g == "action & adventure") ||
                        (targetGenre == "adventure" && g == "action & adventure") ||
                        (targetGenre == "sci-fi" && (g == "sci-fi & fantasy" || g == "science fiction")) ||
                        (targetGenre == "fantasy" && g == "sci-fi & fantasy") ||
                        (targetGenre == "war" && (g == "war & politics" || g == "war"))
                }
                val isMature = item.genres.any {
                    val g = it.trim().lowercase(Locale.US)
                    g == "horror" || g == "crime" || g == "erotica" || g == "ecchi" || g == "hentai" || g.contains("mature")
                }
                if (targetGenre == "kids" && isMature) {
                    false
                } else {
                    hasMatch
                }
            }

            matchesQuery && matchesYear && matchesGenre
        }
    }

    fun isTitleRelevant(targetTitle: String, candidateTitle: String): Boolean {
        val targetNorm = NON_ALPHANUMERIC_REGEX.replace(
            CardTitleSanitizer.sanitize(targetTitle).displayTitle.lowercase(Locale.US),
            " ",
        ).trim()
        val candidateNorm = NON_ALPHANUMERIC_REGEX.replace(
            CardTitleSanitizer.sanitize(candidateTitle).displayTitle.lowercase(Locale.US),
            " ",
        ).trim()

        if (targetNorm.isBlank() || candidateNorm.isBlank()) return false
        if (targetNorm == candidateNorm) return true
        if (candidateNorm.contains(targetNorm) || targetNorm.contains(candidateNorm)) return true

        val targetWords = targetNorm.split(WHITESPACE_REGEX).filter { it.length > 1 && it !in STOP_WORDS }

        if (targetWords.isNotEmpty() && targetWords.all { candidateNorm.contains(it) }) {
            return true
        }

        return false
    }

    fun matchesType(catalog: ManifestCatalogDescriptor, targetType: String): Boolean {
        return when (targetType.lowercase(Locale.US)) {
            "addons" -> !catalog.isBuiltIn
            "platforms" -> catalog.addonName.equals("Networks", ignoreCase = true)
            "collections" -> catalog.type.equals("collections", ignoreCase = true)
            "anime" -> catalog.type.equals("anime", ignoreCase = true)
            "movie" -> catalog.type.equals("movie", ignoreCase = true)
            "series" -> catalog.type.equals("series", ignoreCase = true) || catalog.type.equals("tv", ignoreCase = true)
            "all" -> true
            else -> {
                val normCat = if (catalog.type.equals("tv", ignoreCase = true)) "series" else catalog.type.lowercase(Locale.US)
                val normTarget = if (targetType.equals("tv", ignoreCase = true)) "series" else targetType.lowercase(Locale.US)
                normCat == normTarget
            }
        }
    }

    fun matchesType(catalogType: String, targetType: String): Boolean {
        if (targetType.equals("all", ignoreCase = true)) return true
        val normCat = if (catalogType.equals("tv", ignoreCase = true)) "series" else catalogType.lowercase(Locale.US)
        val normTarget = if (targetType.equals("tv", ignoreCase = true)) "series" else targetType.lowercase(Locale.US)
        return normCat == normTarget
    }

    fun formatTypeTitle(type: String): String {
        return when (type.lowercase(Locale.US)) {
            "addons" -> "Add-ons"
            "platforms" -> "Discover"
            "all" -> "All"
            "movie" -> "Movies"
            "series", "tv" -> "TV Shows"
            "anime" -> "Anime"
            "collections", "collection" -> "Collections"
            else -> type.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
        }
    }
}
