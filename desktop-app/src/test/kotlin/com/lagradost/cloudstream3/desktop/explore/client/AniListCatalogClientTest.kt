package com.lagradost.cloudstream3.desktop.explore.client

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AniListCatalogClientTest {

    private val mapper = jacksonObjectMapper()

    @Test
    fun `resolvePreferredTitle picks english over romaji userPreferred when english selected`() {
        val titleNode = mapper.readTree(
            """
            {
              "english": "Frieren: Beyond Journey's End",
              "romaji": "Sousou no Frieren",
              "native": "葬送のフリーレン",
              "userPreferred": "Sousou no Frieren"
            }
            """.trimIndent(),
        )

        assertEquals(
            "Frieren: Beyond Journey's End",
            AniListCatalogClient.resolvePreferredTitle(titleNode, "english"),
        )
        assertEquals(
            "Sousou no Frieren",
            AniListCatalogClient.resolvePreferredTitle(titleNode, "romaji"),
        )
        assertEquals(
            "葬送のフリーレン",
            AniListCatalogClient.resolvePreferredTitle(titleNode, "native"),
        )
    }

    @Test
    fun `resolvePreferredTitle handles Jackson NullNode without returning literal null`() {
        val titleNode = mapper.readTree(
            """
            {
              "english": null,
              "romaji": "Bocchi the Rock!",
              "native": "ぼっち・ざ・ろっく！",
              "userPreferred": "Bocchi the Rock!"
            }
            """.trimIndent(),
        )

        assertEquals(
            "Bocchi the Rock!",
            AniListCatalogClient.resolvePreferredTitle(titleNode, "english"),
        )
    }

    @Test
    fun `parseMediaItems maps titles and guards null MAL IDs and scores`() {
        val mediaArray = mapper.readTree(
            """
            [
              {
                "id": 154587,
                "idMal": 52991,
                "title": {
                  "english": "Frieren: Beyond Journey's End",
                  "romaji": "Sousou no Frieren",
                  "native": "葬送のフリーレン",
                  "userPreferred": "Sousou no Frieren"
                },
                "coverImage": {
                  "extraLarge": "https://img.example.com/frieren_xl.jpg",
                  "large": "https://img.example.com/frieren_l.jpg"
                },
                "bannerImage": null,
                "averageScore": 91,
                "seasonYear": 2023,
                "genres": ["Adventure", "Drama", "Fantasy"],
                "description": "An elf mage reflects after the hero's party completes its quest.",
                "format": "TV"
              },
              {
                "id": 999999,
                "idMal": null,
                "title": {
                  "english": "null",
                  "romaji": "Original Work Title",
                  "native": null,
                  "userPreferred": "Original Work Title"
                },
                "coverImage": {
                  "extraLarge": null,
                  "large": "https://img.example.com/orig_l.jpg"
                },
                "bannerImage": "https://img.example.com/orig_banner.jpg",
                "averageScore": null,
                "seasonYear": null,
                "genres": [],
                "description": null,
                "format": "MOVIE"
              }
            ]
            """.trimIndent(),
        )

        val withMal = AniListCatalogClient.parseMediaItems(mediaArray, useMalPrefix = true, preferredLanguage = "english")
        assertEquals(2, withMal.size)

        val first = withMal[0]
        assertEquals("mal:52991", first.id)
        assertEquals("series", first.type)
        assertEquals("Frieren: Beyond Journey's End", first.name)
        assertEquals("https://img.example.com/frieren_xl.jpg", first.posterUrl)
        assertNull(first.backgroundUrl)
        assertEquals(9.1, first.rating)
        assertEquals("2023", first.releaseYear)
        assertEquals(listOf("Adventure", "Drama", "Fantasy"), first.genres)

        val second = withMal[1]
        assertEquals("anilist:999999", second.id)
        assertEquals("movie", second.type)
        assertEquals("Original Work Title", second.name)
        assertEquals("https://img.example.com/orig_l.jpg", second.posterUrl)
        assertNull(second.rating)
        assertNull(second.releaseYear)
        assertNull(second.description)

        val withoutMal = AniListCatalogClient.parseMediaItems(mediaArray, useMalPrefix = false, preferredLanguage = "english")
        assertEquals("anilist:154587", withoutMal[0].id)
    }
}
