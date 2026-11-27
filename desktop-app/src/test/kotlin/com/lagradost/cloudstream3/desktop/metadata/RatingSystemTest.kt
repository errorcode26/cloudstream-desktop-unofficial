package com.lagradost.cloudstream3.desktop.metadata

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.desktop.metadata.providers.CinemetaMetadataProvider
import com.lagradost.cloudstream3.desktop.metadata.providers.TvMazeMetadataProvider
import com.lagradost.cloudstream3.desktop.metadata.stremio.StremioAddonClient
import com.lagradost.cloudstream3.desktop.ui.screens.details.TmdbEpisodeEnricher
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RatingSystemTest {

    private val testMainDispatcher = UnconfinedTestDispatcher()

    private val testApi = object : MainAPI() {
        override var name = "TestProvider"
        override var mainUrl = "https://example.invalid"
    }

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testMainDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `tmdb episode enricher assigns TMDB source for positive scores and ignores zero ratings`() = runBlocking {
        val loaded = testApi.newTvSeriesLoadResponse(
            name = "Sample Series",
            url = "https://example.invalid/series",
            type = TvType.TvSeries,
            episodes = listOf(
                testApi.newEpisode("ep1") {
                    season = 13
                    episode = 1
                },
                testApi.newEpisode("ep2") {
                    season = 13
                    episode = 2
                },
            ),
        )

        val seasonJson = mapper.readTree(
            """
            {
              "episodes": [
                {
                  "episode_number": 1,
                  "name": "Rated Episode",
                  "overview": "First episode",
                  "air_date": "2024-01-10",
                  "still_path": "/still1.jpg",
                  "vote_average": 8.4
                },
                {
                  "episode_number": 2,
                  "name": "Zero Rated Episode",
                  "overview": "Second episode",
                  "air_date": "2024-01-17",
                  "still_path": "/still2.jpg",
                  "vote_average": 0.0
                }
              ]
            }
            """.trimIndent(),
        )

        val sources = TmdbEpisodeEnricher.enrichSeasonNode(
            loaded = loaded,
            seasonNum = 13,
            seasonNode = seasonJson,
            overwrite = false,
        )

        assertEquals(mapOf((13 to 1) to RatingSource.TMDB), sources)
        assertEquals(8.4, loaded.episodes[0].score!!.toDouble(10), 0.05)
        assertNull(loaded.episodes[1].score)
    }

    @Test
    fun `tvmaze enrichment populates episode scores with TVMAZE source and never emits into anilist slot`() = runBlocking {
        val ep1 = testApi.newEpisode("ep1") {
            season = 1
            episode = 1
        }
        val ep2 = testApi.newEpisode("ep2") {
            season = 1
            episode = 2
        }
        val loaded = testApi.newTvSeriesLoadResponse(
            name = "Sample Western Drama",
            url = "https://example.invalid/drama",
            type = TvType.TvSeries,
            episodes = listOf(ep1, ep2),
        ) {
            backgroundPosterUrl = "https://example.invalid/bg.jpg"
            logoUrl = "https://example.invalid/logo.png"
        }

        val show = TvMazeMetadataProvider.TvMazeShow(
            id = 42,
            name = "Sample Western Drama",
            type = "Scripted",
            language = "English",
            genres = listOf("Drama"),
            status = "Running",
            runtime = 45,
            averageRuntime = 45,
            premiered = "2022-05-01",
            ended = null,
            officialSite = null,
            rating = TvMazeMetadataProvider.TvMazeRating(8.1),
            network = null,
            webChannel = null,
            image = null,
            summary = "Sample plot",
            externals = TvMazeMetadataProvider.TvMazeExternals(imdb = null, thetvdb = null),
            embedded = TvMazeMetadataProvider.TvMazeEmbedded(
                cast = emptyList(),
                episodes = listOf(
                    TvMazeMetadataProvider.TvMazeEpisode(
                        id = 101,
                        name = "Pilot",
                        season = 1,
                        number = 1,
                        airdate = "2022-05-01",
                        airtime = null,
                        airstamp = null,
                        runtime = 45,
                        rating = TvMazeMetadataProvider.TvMazeRating(8.7),
                        image = null,
                        summary = "Pilot summary",
                    ),
                    TvMazeMetadataProvider.TvMazeEpisode(
                        id = 102,
                        name = "Unrated Followup",
                        season = 1,
                        number = 2,
                        airdate = "2022-05-08",
                        airtime = null,
                        airstamp = null,
                        runtime = 45,
                        rating = TvMazeMetadataProvider.TvMazeRating(0.0),
                        image = null,
                        summary = "Second summary",
                    ),
                ),
                nextepisode = null,
            ),
        )

        var emittedAniList: Double? = -1.0
        var emittedImdb: Double? = -1.0
        var emittedTmdb: Double? = -1.0
        val episodeSources = mutableMapOf<Pair<Int, Int>, RatingSource>()

        val applied = TvMazeMetadataProvider.enrich(
            loaded = loaded,
            match = MetadataMatch(
                providerId = TvMazeMetadataProvider.id,
                matchedTitle = "Sample Western Drama",
                rawData = show,
            ),
            context = MetadataEnrichmentContext(rawUrl = "https://example.invalid/drama"),
            callbacks = MetadataEnrichmentCallbacks(
                onRatingsLoaded = { imdb, tmdb, anilist ->
                    emittedImdb = imdb
                    emittedTmdb = tmdb
                    emittedAniList = anilist
                },
                onEpisodeRatingSourcesLoaded = { episodeSources.putAll(it) },
            ),
        )

        assertTrue(applied)
        assertEquals(8.1, loaded.score!!.toDouble(10), 0.05)
        assertEquals(-1.0, emittedAniList)
        assertEquals(-1.0, emittedImdb)
        assertEquals(-1.0, emittedTmdb)
        assertEquals(mapOf((1 to 1) to RatingSource.TVMAZE), episodeSources)
        assertEquals(8.7, loaded.episodes[0].score!!.toDouble(10), 0.05)
        assertNull(loaded.episodes[1].score)
    }

    @Test
    fun `cinemeta enrichment attributes IMDB vs PROVIDER episode rating sources and ignores zero scores`() = runBlocking {
        val loaded = testApi.newTvSeriesLoadResponse(
            name = "Sample Mystery Show",
            url = "https://example.invalid/mystery",
            type = TvType.TvSeries,
            episodes = listOf(
                testApi.newEpisode("e1") { season = 1; episode = 1 },
                testApi.newEpisode("e2") { season = 1; episode = 2 },
                testApi.newEpisode("e3") { season = 1; episode = 3 },
            ),
        )

        val metaItem = StremioAddonClient.StremioMetaItem(
            id = "tt9999999",
            type = "series",
            name = "Sample Mystery Show",
            imdbRating = "0.0",
            videos = listOf(
                StremioAddonClient.StremioVideo(
                    season = 1,
                    episode = 1,
                    title = "Ep One",
                    imdbRating = "9.1",
                ),
                StremioAddonClient.StremioVideo(
                    season = 1,
                    episode = 2,
                    title = "Ep Two",
                    imdbRating = null,
                    rating = "7.8",
                ),
                StremioAddonClient.StremioVideo(
                    season = 1,
                    episode = 3,
                    title = "Ep Three",
                    imdbRating = "0.0",
                    rating = "0",
                ),
            ),
        )

        val titleRatings = mutableListOf<Triple<Double?, Double?, Double?>>()
        val episodeSources = mutableMapOf<Pair<Int, Int>, RatingSource>()

        val applied = CinemetaMetadataProvider.enrich(
            loaded = loaded,
            match = MetadataMatch(
                providerId = CinemetaMetadataProvider.id,
                matchedTitle = "Sample Mystery Show",
                imdbId = "tt9999999",
                rawData = metaItem,
            ),
            context = MetadataEnrichmentContext(rawUrl = "https://example.invalid/mystery"),
            callbacks = MetadataEnrichmentCallbacks(
                onRatingsLoaded = { imdb, tmdb, anilist -> titleRatings += Triple(imdb, tmdb, anilist) },
                onEpisodeRatingSourcesLoaded = { episodeSources.putAll(it) },
            ),
        )

        assertTrue(applied)
        assertTrue(titleRatings.isEmpty())
        assertNull(loaded.score)
        assertEquals(
            mapOf(
                (1 to 1) to RatingSource.IMDB,
                (1 to 2) to RatingSource.PROVIDER,
            ),
            episodeSources,
        )
        assertEquals(9.1, loaded.episodes[0].score!!.toDouble(10), 0.05)
        assertEquals(7.8, loaded.episodes[1].score!!.toDouble(10), 0.05)
        assertNull(loaded.episodes[2].score)
    }
}
