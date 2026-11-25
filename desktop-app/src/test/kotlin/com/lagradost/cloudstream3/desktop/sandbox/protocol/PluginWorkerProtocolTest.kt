@file:Suppress("DEPRECATION_ERROR")

package com.lagradost.cloudstream3.desktop.sandbox.protocol

import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class PluginWorkerProtocolTest {

    @Test
    fun testTvSeriesLoadResponseSerializationAndDeserialization() {
        val episodes = listOf(
            Episode(
                data = "https://example.com/ep1",
                name = "Episode 1",
                season = 1,
                episode = 1
            ),
            Episode(
                data = "https://example.com/ep2",
                name = "Episode 2",
                season = 1,
                episode = 2
            )
        )

        @Suppress("DEPRECATION_ERROR")
        val tvSeries = TvSeriesLoadResponse(
            name = "Test Series",
            url = "https://example.com/series/test",
            apiName = "TestProvider",
            type = TvType.TvSeries,
            episodes = episodes
        )

        val serializedJson = WorkerProtocolSerializer.mapper.writeValueAsString(tvSeries)
        assertNotNull(serializedJson)

        val deserialized = WorkerProtocolSerializer.mapper.readValue(
            serializedJson,
            TvSeriesLoadResponse::class.java
        )

        assertEquals("Test Series", deserialized.name)
        assertEquals("TestProvider", deserialized.apiName)
        assertEquals(2, deserialized.episodes.size)
        assertEquals(2, deserialized.getLatestEpisodes()[DubStatus.None])
    }

    @Test
    fun testAnimeLoadResponseSerializationAndDeserialization() {
        @Suppress("DEPRECATION_ERROR")
        val anime = AnimeLoadResponse(
            name = "Test Anime",
            url = "https://example.com/anime/test",
            apiName = "TestAnimeProvider",
            type = TvType.Anime
        )
        anime.episodes[DubStatus.Subbed] = listOf(
            Episode(
                data = "https://example.com/ep1",
                name = "Episode 1",
                season = 1,
                episode = 1
            )
        )

        val serializedJson = WorkerProtocolSerializer.mapper.writeValueAsString(anime)
        assertNotNull(serializedJson)

        val deserialized = WorkerProtocolSerializer.mapper.readValue(
            serializedJson,
            AnimeLoadResponse::class.java
        )

        assertEquals("Test Anime", deserialized.name)
        assertEquals("TestAnimeProvider", deserialized.apiName)
        assertEquals(1, deserialized.episodes[DubStatus.Subbed]?.size)
        assertEquals(1, deserialized.getLatestEpisodes()[DubStatus.Subbed])
    }

    @Test
    fun testWorkerRequestResponseSerialization() {
        val req = WorkerRequest(
            id = 42L,
            action = WorkerRequest.ACTION_LOAD,
            providerName = "TestProvider",
            url = "https://example.com/item"
        )
        val serializedReq = WorkerProtocolSerializer.serializeRequest(req)
        val deserializedReq = WorkerProtocolSerializer.deserializeRequest(serializedReq)

        assertEquals(42L, deserializedReq.id)
        assertEquals(WorkerRequest.ACTION_LOAD, deserializedReq.action)
        assertEquals("TestProvider", deserializedReq.providerName)
        assertEquals("https://example.com/item", deserializedReq.url)

        val resp = WorkerResponse(
            id = 42L,
            success = false,
            error = "Operation timed out after 12000ms inside plugin runtime"
        )
        val serializedResp = WorkerProtocolSerializer.serializeResponse(resp)
        val deserializedResp = WorkerProtocolSerializer.deserializeResponse(serializedResp)

        assertEquals(42L, deserializedResp.id)
        assertEquals(false, deserializedResp.success)
        assertEquals("Operation timed out after 12000ms inside plugin runtime", deserializedResp.error)
    }
}
