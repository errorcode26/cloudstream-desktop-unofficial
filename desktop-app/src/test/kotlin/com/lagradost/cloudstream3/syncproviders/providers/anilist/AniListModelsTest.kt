package com.lagradost.cloudstream3.syncproviders.providers.anilist

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AniListModelsTest {

    @Test
    fun testAniListStatusTypeMapping() {
        assertEquals(AniListStatusType.None, AniListStatusType.fromInt(-1))
        assertEquals(AniListStatusType.Watching, AniListStatusType.fromInt(0))
        assertEquals(AniListStatusType.Completed, AniListStatusType.fromInt(1))
        assertEquals(AniListStatusType.Paused, AniListStatusType.fromInt(2))
        assertEquals(AniListStatusType.Dropped, AniListStatusType.fromInt(3))
        assertEquals(AniListStatusType.Planning, AniListStatusType.fromInt(4))
        assertEquals(AniListStatusType.ReWatching, AniListStatusType.fromInt(5))
        assertEquals(AniListStatusType.None, AniListStatusType.fromInt(99))
    }

    @Test
    fun testEntriesToLibraryItemMapping() {
        val entry = Entries(
            status = "CURRENT",
            completedAt = CompletedAt(year = 0, month = 0, day = 0),
            startedAt = StartedAt(year = "2024", month = "1", day = "1"),
            updatedAt = 1700000000,
            progress = 12,
            score = 85,
            private = false,
            media = Media(
                id = 12345,
                idMal = 54321,
                season = "WINTER",
                seasonYear = 2024,
                format = "TV",
                episodes = 24,
                title = Title(english = "Test Anime English", romaji = "Test Anime Romaji"),
                description = "A test anime plot summary.",
                coverImage = CoverImage(medium = "med.jpg", large = "large.jpg", extraLarge = "xlarge.jpg"),
                synonyms = listOf("Synonym A"),
                nextAiringEpisode = null,
            ),
        )

        val libraryItem = assertNotNull(entry.toLibraryItem())
        assertEquals("Test Anime English", libraryItem.name)
        assertEquals("https://anilist.co/anime/12345/", libraryItem.url)
        assertEquals("12345", libraryItem.syncId)
        assertEquals(12, libraryItem.episodesCompleted)
        assertEquals(24, libraryItem.episodesTotal)
        assertEquals("xlarge.jpg", libraryItem.posterUrl)
        assertEquals("A test anime plot summary.", libraryItem.plot)
    }
}
