package com.lagradost.cloudstream3.desktop.metadata

import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.desktop.explore.models.ExploreItem
import com.lagradost.cloudstream3.desktop.metadata.providers.AniListMetadataProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CanonicalSeedAndMatcherTest {

    @Test
    fun `explore item preserves both anilist and mal ids in canonical seed`() {
        val item = ExploreItem(
            id = "mal:52991",
            type = "series",
            name = "Sample Fantasy Series",
            posterUrl = null,
            releaseYear = "2023",
            anilistId = 154587,
            malId = 52991,
        )

        val seed = item.toCanonicalSeed()
        assertNotNull(seed)
        assertEquals(MediaSeedOrigin.EXPLORE, seed.origin)
        assertEquals(154587, seed.anilistId)
        assertEquals(52991, seed.malId)
        assertEquals(2023, seed.expectedYear)
    }

    @Test
    fun `canonical seed parses tmdb imdb and anilist strings`() {
        val tmdbSeed = CanonicalMediaSeed.fromCanonicalId("tmdb:1399")
        assertNotNull(tmdbSeed)
        assertEquals(1399, tmdbSeed.tmdbId)
        assertNull(tmdbSeed.anilistId)

        val imdbSeed = CanonicalMediaSeed.fromCanonicalId("tt0944947:1:1")
        assertNotNull(imdbSeed)
        assertEquals("tt0944947", imdbSeed.imdbId)

        val anilistSeed = CanonicalMediaSeed.fromCanonicalId("anilist:21")
        assertNotNull(anilistSeed)
        assertEquals(21, anilistSeed.anilistId)
    }

    @Test
    fun `strict anilist matcher rejects sequel number and movie format mismatches`() {
        val wrongSequel = AniListMetadataProvider.scoreStrictCandidate(
            cleanQuery = "Jujutsu Kaisen",
            candidateTitles = listOf("Jujutsu Kaisen 2nd Season", "Jujutsu Kaisen 2"),
            canonicalYear = 2020,
            candidateYear = 2023,
            candidateFormat = "TV",
            requestedType = TvType.Anime,
        )
        assertNull(wrongSequel)

        val movieMismatch = AniListMetadataProvider.scoreStrictCandidate(
            cleanQuery = "Attack on Titan",
            candidateTitles = listOf("Attack on Titan"),
            canonicalYear = 2013,
            candidateYear = 2013,
            candidateFormat = "MOVIE",
            requestedType = TvType.Anime,
        )
        assertNull(movieMismatch)
    }

    @Test
    fun `strict anilist matcher rejects unrelated suffix collision`() {
        val collision = AniListMetadataProvider.scoreStrictCandidate(
            cleanQuery = "Monster",
            candidateTitles = listOf("Monster Musume no Iru Nichijou"),
            canonicalYear = 2004,
            candidateYear = 2015,
            candidateFormat = "TV",
            requestedType = TvType.Anime,
        )
        assertNull(collision)
    }

    @Test
    fun `strict anilist matcher accepts exact english or romaji match with compatible format`() {
        val valid = AniListMetadataProvider.scoreStrictCandidate(
            cleanQuery = "Attack on Titan",
            candidateTitles = listOf("Shingeki no Kyojin", "Attack on Titan"),
            canonicalYear = 2013,
            candidateYear = 2013,
            candidateFormat = "TV",
            requestedType = TvType.Anime,
        )
        assertNotNull(valid)
        assertTrue(valid >= 0.99)
    }
}
