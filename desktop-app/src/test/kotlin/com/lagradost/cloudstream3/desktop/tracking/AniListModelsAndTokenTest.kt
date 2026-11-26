package com.lagradost.cloudstream3.desktop.tracking

import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.syncproviders.providers.AniListApi
import com.lagradost.cloudstream3.syncproviders.providers.anilist.AniListQueries
import com.lagradost.cloudstream3.syncproviders.providers.anilist.AniListStatusType
import com.lagradost.cloudstream3.syncproviders.providers.anilist.FullAnilistList
import com.lagradost.cloudstream3.syncproviders.providers.anilist.GetDataRoot
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AniListModelsAndTokenTest {

    @Test
    fun `ongoing anime library payload with nulls parses without throwing and maps cleanly`() {
        val json = """
            {
              "data": {
                "MediaListCollection": {
                  "lists": [
                    {
                      "status": "CURRENT",
                      "entries": [
                        {
                          "status": "CURRENT",
                          "completedAt": { "year": null, "month": null, "day": null },
                          "startedAt": null,
                          "updatedAt": 1700000123,
                          "progress": 6,
                          "score": 80,
                          "private": false,
                          "media": {
                            "id": 9991,
                            "idMal": 5511,
                            "season": "FALL",
                            "seasonYear": null,
                            "format": "TV",
                            "status": "RELEASING",
                            "isFavourite": true,
                            "averageScore": 84,
                            "episodes": null,
                            "chapters": null,
                            "title": {
                              "english": "Airing Title",
                              "romaji": "Airing Title JP",
                              "userPreferred": "Airing Title"
                            },
                            "coverImage": {
                              "extraLarge": "https://example.invalid/xl.jpg",
                              "large": "https://example.invalid/l.jpg",
                              "medium": null
                            },
                            "synonyms": null,
                            "nextAiringEpisode": {
                              "timeUntilAiring": 43200,
                              "episode": 7
                            }
                          }
                        }
                      ]
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        val parsed = tryParseJson<FullAnilistList>(json)
        assertNotNull(parsed)
        val entry = parsed.data?.mediaListCollection?.lists?.single()?.entries?.single()
        assertNotNull(entry)
        assertEquals(6, entry.progress)
        assertEquals(80, entry.score)
        assertEquals(7, entry.media?.nextAiringEpisode?.episode)

        val libItem = entry.toLibraryItem()
        assertNotNull(libItem)
        assertEquals("Airing Title", libItem.name)
        assertEquals(6, libItem.episodesCompleted)
        assertNull(libItem.episodesTotal)
        assertEquals(80, libItem.personalRating?.toInt(100))
    }

    @Test
    fun `getDataAboutId payload parses nextAiringEpisode and seasonYear`() {
        val json = """
            {
              "data": {
                "Media": {
                  "id": 42,
                  "episodes": 12,
                  "format": "TV",
                  "status": "RELEASING",
                  "seasonYear": 2026,
                  "isFavourite": false,
                  "coverImage": { "extraLarge": "https://example.invalid/cover.jpg", "large": null, "medium": null },
                  "nextAiringEpisode": { "timeUntilAiring": 12000, "episode": 5 },
                  "mediaListEntry": { "progress": 3, "status": "CURRENT", "score": 70 },
                  "title": { "english": "Test", "romaji": "Test", "userPreferred": "Test" }
                }
              }
            }
        """.trimIndent()

        val root = tryParseJson<GetDataRoot>(json)
        val media = root?.data?.media
        assertNotNull(media)
        assertEquals(2026, media.seasonYear)
        assertEquals(5, media.nextAiringEpisode?.episode)
        assertEquals(12000, media.nextAiringEpisode?.timeUntilAiring)
        assertEquals("RELEASING", media.status)
    }

    @Test
    fun `oauth token parser accepts loopback fragment callbacks pin urls and rejects invalid urls`() {
        val loopbackUrl = "http://127.0.0.1:41932/anilistlogin/callback?access_token=abc.def.ghi&expires_in=3600"
        val parsedLoopback = AniListApi.parseTokenInput(loopbackUrl)
        assertNotNull(parsedLoopback)
        assertEquals("abc.def.ghi", parsedLoopback.accessToken)

        val pinUrl = "https://anilist.co/api/v2/oauth/pin#access_token=token.part.two&token_type=Bearer"
        val parsedPin = AniListApi.parseTokenInput(pinUrl)
        assertNotNull(parsedPin)
        assertEquals("token.part.two", parsedPin.accessToken)

        val bearer = AniListApi.parseTokenInput("Bearer my.jwt.token")
        assertNotNull(bearer)
        assertEquals("my.jwt.token", bearer.accessToken)

        assertNull(AniListApi.parseTokenInput("https://anilist.co/api/v2/oauth/authorize?client_id=1"))
        assertNull(AniListApi.parseTokenInput("<html>error</html>"))
    }

    @Test
    fun `save mutation preserves omitted score clears score at zero or writes score`() {
        val preserveMutation = AniListQueries.buildSaveMediaListEntryMutation(
            id = 10,
            status = "CURRENT",
            score = null,
            progress = 4,
            clearScore = false,
        )
        assertFalse(preserveMutation.contains("scoreRaw"))
        assertTrue(preserveMutation.contains("progress"))

        val clearMutation = AniListQueries.buildSaveMediaListEntryMutation(
            id = 10,
            status = "CURRENT",
            score = null,
            progress = 4,
            clearScore = true,
        )
        assertTrue(clearMutation.contains("scoreRaw: Int = 0"))

        val ratedMutation = AniListQueries.buildSaveMediaListEntryMutation(
            id = 10,
            status = "CURRENT",
            score = Score.from100(90),
            progress = 4,
            clearScore = false,
        )
        assertTrue(ratedMutation.contains("scoreRaw: Int = 90"))
    }

    @Test
    fun `bind policy parses strict explore and manual modes cleanly`() {
        assertEquals(TrackerBindPolicy.STRICT_AUTO_AND_PINNED, TrackerBindPolicy.fromString("STRICT_AUTO_AND_PINNED"))
        assertEquals(TrackerBindPolicy.EXPLORE_OR_PINNED_ONLY, TrackerBindPolicy.fromString("EXPLORE_OR_PINNED_ONLY"))
        assertEquals(TrackerBindPolicy.MANUAL_PIN_ONLY, TrackerBindPolicy.fromString("MANUAL_PIN_ONLY"))
        assertEquals(TrackerBindPolicy.STRICT_AUTO_AND_PINNED, TrackerBindPolicy.fromString("LEGACY_UNKNOWN"))
        assertEquals(AniListStatusType.Watching, AniListApi.fromIntToAnimeStatus(0))
    }
}
