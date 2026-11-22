package com.lagradost.cloudstream3.desktop.explore.viewmodel

import com.lagradost.cloudstream3.desktop.explore.models.ExploreItem
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExploreFilterEngineTest {

    @Test
    fun testTitleRelevance() {
        assertTrue(
            ExploreFilterEngine.isTitleRelevant("Breaking Bad", "Breaking Bad (2008)"),
            "Matching title with year suffix must be relevant",
        )
        assertTrue(
            ExploreFilterEngine.isTitleRelevant("Spider-Man: Across the Spider-Verse", "Spider Man Across the Spider Verse 1080p"),
            "Title with punctuation differences must be relevant",
        )
        assertFalse(
            ExploreFilterEngine.isTitleRelevant("Avatar", "The Matrix"),
            "Completely different titles must not be relevant",
        )
    }

    @Test
    fun testApplyFiltersByYearAndGenre() {
        val items = listOf(
            ExploreItem(id = "1", type = "movie", name = "Movie 2024", posterUrl = null, releaseYear = "2024", genres = listOf("Action", "Sci-Fi")),
            ExploreItem(id = "2", type = "movie", name = "Movie 2015", posterUrl = null, releaseYear = "2015", genres = listOf("Comedy")),
            ExploreItem(id = "3", type = "movie", name = "Old Movie", posterUrl = null, releaseYear = "1995", genres = listOf("Family")),
        )

        // Filter by 2010s decade
        val decadeItems = ExploreFilterEngine.applyFilters(items, query = "", year = "2010s", genre = "All")
        assertEquals(1, decadeItems.size)
        assertEquals("Movie 2015", decadeItems[0].name)

        // Filter by genre mapping (Kids -> Family)
        val kidsItems = ExploreFilterEngine.applyFilters(items, query = "", year = "All Years", genre = "Kids")
        assertEquals(1, kidsItems.size)
        assertEquals("Old Movie", kidsItems[0].name)

        // Filter by 1990s & Older
        val oldItems = ExploreFilterEngine.applyFilters(items, query = "", year = "1990s & Older", genre = "All")
        assertEquals(1, oldItems.size)
        assertEquals("Old Movie", oldItems[0].name)
    }

    @Test
    fun testFormatTypeTitle() {
        assertEquals("Add-ons", ExploreFilterEngine.formatTypeTitle("addons"))
        assertEquals("Discover", ExploreFilterEngine.formatTypeTitle("platforms"))
        assertEquals("Movies", ExploreFilterEngine.formatTypeTitle("movie"))
        assertEquals("TV Shows", ExploreFilterEngine.formatTypeTitle("series"))
        assertEquals("Anime", ExploreFilterEngine.formatTypeTitle("anime"))
        assertEquals("Collections", ExploreFilterEngine.formatTypeTitle("collections"))
    }
}
