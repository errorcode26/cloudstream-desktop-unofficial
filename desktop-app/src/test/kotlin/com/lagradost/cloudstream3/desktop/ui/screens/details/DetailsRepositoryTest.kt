package com.lagradost.cloudstream3.desktop.ui.screens.details

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.ProviderType
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newMovieLoadResponse
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Suppress("DEPRECATION", "DEPRECATION_ERROR")
class DetailsRepositoryTest {

    private var savedProviders: List<MainAPI> = emptyList()

    @BeforeEach
    fun setUp() {
        DetailsCache.clear()
        synchronized(APIHolder.allProviders) {
            savedProviders = APIHolder.allProviders.toList()
        }
    }

    @AfterEach
    fun tearDown() {
        DetailsCache.clear()
        synchronized(APIHolder.allProviders) {
            APIHolder.allProviders.clear()
            APIHolder.allProviders.addAll(savedProviders)
        }
    }

    @Test
    fun `fetchRaw loads natively when provider owns themoviedb domain without triggering meta searches`() = runBlocking {
        val searchCalls = AtomicInteger(0)
        val metaSearchCalls = AtomicInteger(0)
        val loadCalls = AtomicInteger(0)

        val tmdbOwnedProvider = object : MainAPI() {
            override var name = "MockOwnedProvider"
            override var mainUrl = "https://www.themoviedb.org"
            override val supportedTypes = setOf(TvType.Movie)

            override suspend fun search(query: String, page: Int): SearchResponseList? {
                searchCalls.incrementAndGet()
                return null
            }

            override suspend fun load(url: String): LoadResponse? {
                loadCalls.incrementAndGet()
                return newMovieLoadResponse("Owned Title", url, TvType.Movie, url)
            }
        }

        val mockMetaProvider = object : MainAPI() {
            override var name = "MockMetaProvider"
            override var mainUrl = "meta://internal"
            override val providerType = ProviderType.MetaProvider

            override suspend fun search(query: String): List<com.lagradost.cloudstream3.SearchResponse>? {
                metaSearchCalls.incrementAndGet()
                throw NotImplementedError()
            }
        }

        synchronized(APIHolder.allProviders) {
            APIHolder.allProviders.clear()
            APIHolder.allProviders.add(tmdbOwnedProvider)
            APIHolder.allProviders.add(mockMetaProvider)
        }

        val loaded = DetailsRepository.fetchRaw(
            provider = tmdbOwnedProvider,
            url = "https://www.themoviedb.org/imdb/movie/tt9990001",
            fallbackName = "Owned Title",
        )

        assertNotNull(loaded)
        assertEquals("Owned Title", loaded.name)
        assertEquals(1, loadCalls.get())
        assertEquals(0, searchCalls.get(), "Provider owning the URL host must not trigger pre-load searches")
        assertEquals(0, metaSearchCalls.get(), "MetaProvider must never be searched as fallback")
    }

    @Test
    fun `fetchRaw skips MetaProviders when searching candidates for foreign themoviedb link`() = runBlocking {
        val metaSearchCalls = AtomicInteger(0)
        val foreignUrl = "https://www.themoviedb.org/movie/123456"

        val foreignProvider = object : MainAPI() {
            override var name = "MockScraperProvider"
            override var mainUrl = "https://scraper.example.com"
            override val supportedTypes = setOf(TvType.Movie)

            override suspend fun search(query: String, page: Int): SearchResponseList? = null

            override suspend fun load(url: String): LoadResponse? {
                return newMovieLoadResponse("Fallback Title", url, TvType.Movie, url)
            }
        }

        val mockMetaProvider = object : MainAPI() {
            override var name = "MockMetaCatalog"
            override var mainUrl = "meta://catalog"
            override val providerType = ProviderType.MetaProvider

            override suspend fun search(query: String): List<com.lagradost.cloudstream3.SearchResponse>? {
                metaSearchCalls.incrementAndGet()
                throw NotImplementedError()
            }
        }

        synchronized(APIHolder.allProviders) {
            APIHolder.allProviders.clear()
            APIHolder.allProviders.add(foreignProvider)
            APIHolder.allProviders.add(mockMetaProvider)
        }

        val loaded = DetailsRepository.fetchRaw(
            provider = foreignProvider,
            url = foreignUrl,
            fallbackName = "Fallback Title",
        )

        assertNotNull(loaded)
        assertEquals(0, metaSearchCalls.get(), "MetaProvider candidates must be excluded from search bridge")
    }

    @Test
    fun `fetchRaw retries transient IOException before failing`() = runBlocking {
        val loadAttempts = AtomicInteger(0)
        val targetUrl = "https://www.themoviedb.org/imdb/movie/tt9990002"

        val flakyProvider = object : MainAPI() {
            override var name = "MockFlakyRetryProvider"
            override var mainUrl = "https://www.themoviedb.org"
            override val supportedTypes = setOf(TvType.Movie)

            override suspend fun load(url: String): LoadResponse? {
                val attempt = loadAttempts.incrementAndGet()
                if (attempt == 1) {
                    throw IOException("Simulated worker process restart")
                }
                return newMovieLoadResponse("Recovered Title", url, TvType.Movie, url)
            }
        }

        synchronized(APIHolder.allProviders) {
            APIHolder.allProviders.clear()
            APIHolder.allProviders.add(flakyProvider)
        }

        val loaded = DetailsRepository.fetchRaw(
            provider = flakyProvider,
            url = targetUrl,
            fallbackName = "Recovered Title",
        )

        assertNotNull(loaded)
        assertEquals("Recovered Title", loaded.name)
        assertEquals(2, loadAttempts.get(), "DetailsRepository must retry on transient IOException")
    }

    @Test
    fun `fetchRaw reports no media details when load legitimately returns null`() = runBlocking {
        val emptyProvider = object : MainAPI() {
            override var name = "MockEmptyNullProvider"
            override var mainUrl = "https://www.themoviedb.org"
            override val supportedTypes = setOf(TvType.Movie)

            override suspend fun load(url: String): LoadResponse? = null
        }

        synchronized(APIHolder.allProviders) {
            APIHolder.allProviders.clear()
            APIHolder.allProviders.add(emptyProvider)
        }

        val ex = assertFailsWith<IOException> {
            DetailsRepository.fetchRaw(
                provider = emptyProvider,
                url = "https://www.themoviedb.org/imdb/movie/tt9990003",
                fallbackName = "Missing Title",
            )
        }
        assertTrue(ex.message?.contains("returned no media details") == true)
    }
}
