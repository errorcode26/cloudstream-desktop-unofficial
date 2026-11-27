package com.lagradost.cloudstream3.desktop.player

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.desktop.player.ipc.PlayerInboundEvent
import com.lagradost.cloudstream3.desktop.ui.screens.details.EpisodeHistoryLookup
import com.lagradost.cloudstream3.desktop.ui.screens.player.PlayerState
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.common.storage.DesktopDataStore
import com.lagradost.common.storage.WatchHistory
import com.lagradost.player.impl.proxy.ProxyTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class PlayerCoreStateTest {

    private lateinit var tempDir: File
    private var prevDataDir: String? = null
    private lateinit var testScope: CoroutineScope

    @BeforeEach
    fun setup() {
        prevDataDir = System.getProperty("cloudstream.data.dir")
        tempDir = Files.createTempDirectory("cs_player_core_test_").toFile()
        System.setProperty("cloudstream.data.dir", tempDir.absolutePath)
        DesktopDataStore.resetForTesting()
        DesktopDataStore.init()
        testScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @AfterEach
    fun teardown() {
        testScope.cancel()
        DesktopDataStore.resetForTesting()
        if (prevDataDir != null) {
            System.setProperty("cloudstream.data.dir", prevDataDir)
        } else {
            System.clearProperty("cloudstream.data.dir")
        }
        tempDir.deleteRecursively()
    }

    @Test
    fun `PlayerInboundDispatcher routes playback audio subtitle preference and lifecycle events`() = runBlocking {
        val playerState = PlayerState()
        val uiReady = AtomicBoolean(false)
        val fullscreen = AtomicBoolean(false)
        val closed = AtomicBoolean(false)
        val retried = AtomicBoolean(false)
        val nextEp = AtomicBoolean(false)
        val cancelCountdown = AtomicBoolean(false)
        val replayed = AtomicBoolean(false)
        val interacted = AtomicInteger(0)
        val continued = AtomicBoolean(false)
        val skipped = AtomicBoolean(false)
        var changedLink: String? = null
        var loadedEpisode: String? = null
        var sleepTimer: String? = null
        val syncPushCount = AtomicInteger(0)

        val dispatcher = PlayerInboundDispatcher(
            scope = testScope,
            window = null,
            playerState = playerState,
            persistentSubtitles = CopyOnWriteArrayList(),
            resolvedImdbId = "tt1234567",
            getGlobalUiScale = { 1.0f },
            getAutoFetchedSubtitleTracks = { emptyList() },
            getTrickplayEngine = { null },
            pushMetadataToWebView = {},
            pushSyncStateToWebView = { syncPushCount.incrementAndGet() },
            onUiReady = { uiReady.set(true) },
            onFullscreenToggle = { fullscreen.set(true) },
            onCloseRequest = { closed.set(true) },
            onRetryPlayback = { retried.set(true) },
            onLinkChange = { changedLink = it },
            onEpisodeChange = { loadedEpisode = it },
            onNextEpisode = { nextEp.set(true) },
            onCancelCountdown = { cancelCountdown.set(true) },
            onReplayEpisode = { replayed.set(true) },
            onUserInteraction = { interacted.incrementAndGet() },
            onSetSleepTimer = { sleepTimer = it },
            onContinueFromInactivity = { continued.set(true) },
            onSkipScraping = { skipped.set(true) },
        )

        dispatcher.dispatch(PlayerInboundEvent.UiReady)
        dispatcher.dispatch(PlayerInboundEvent.ToggleFullscreen)
        dispatcher.dispatch(PlayerInboundEvent.ExitPlayer)
        dispatcher.dispatch(PlayerInboundEvent.RetryPlayback)
        dispatcher.dispatch(PlayerInboundEvent.ChangeLink("https://cdn.example.invalid/alt.m3u8"))
        dispatcher.dispatch(PlayerInboundEvent.LoadEpisode("ep-2"))
        dispatcher.dispatch(PlayerInboundEvent.LoadNextEpisode)
        dispatcher.dispatch(PlayerInboundEvent.CancelCountdown)
        dispatcher.dispatch(PlayerInboundEvent.ReplayEpisode)
        dispatcher.dispatch(PlayerInboundEvent.UserInteraction)
        dispatcher.dispatch(PlayerInboundEvent.SetSleepTimer("30m"))
        dispatcher.dispatch(PlayerInboundEvent.ContinueFromInactivity)
        dispatcher.dispatch(PlayerInboundEvent.SkipScraping)

        dispatcher.dispatch(PlayerInboundEvent.SelectShader("Anime4K"))
        dispatcher.dispatch(PlayerInboundEvent.SetSpeed(1.5))
        dispatcher.dispatch(PlayerInboundEvent.SetVolume(65.0))
        dispatcher.dispatch(PlayerInboundEvent.ToggleMute(true))
        dispatcher.dispatch(PlayerInboundEvent.SetSubDelay(1.25))
        dispatcher.dispatch(PlayerInboundEvent.SetSubtitleBackground("box"))
        dispatcher.dispatch(PlayerInboundEvent.SetSubsourceApiKey("  demo-sub-key  "))
        dispatcher.dispatch(PlayerInboundEvent.ToggleAutoPlay(false))

        // Wait briefly for IO coroutines spawned by DataStore preference updates
        repeat(40) {
            if (DesktopDataStore.getKey<String>(PlayerConfig.PREF_SUBSOURCE_API_KEY) == "demo-sub-key") return@repeat
            delay(25)
        }

        assertTrue(uiReady.get())
        assertTrue(fullscreen.get())
        assertTrue(closed.get())
        assertTrue(retried.get())
        assertEquals("https://cdn.example.invalid/alt.m3u8", changedLink)
        assertEquals("ep-2", loadedEpisode)
        assertTrue(nextEp.get())
        assertTrue(cancelCountdown.get())
        assertTrue(replayed.get())
        assertTrue(interacted.get() >= 1)
        assertEquals("30m", sleepTimer)
        assertTrue(continued.get())
        assertTrue(skipped.get())

        assertEquals("Anime4K", playerState.activeShader.value)
        assertEquals(1.5f, playerState.playbackSpeed.value)
        assertEquals(65f, playerState.volume.value)
        assertTrue(playerState.isMuted.value)
        assertEquals("box", DesktopDataStore.getKey<String>(PlayerConfig.PREF_SUB_BG))
        assertEquals("demo-sub-key", DesktopDataStore.getKey<String>(PlayerConfig.PREF_SUBSOURCE_API_KEY))
        assertFalse(DesktopDataStore.getKey<Boolean>(PlayerConfig.PREF_AUTO_PLAY) ?: true)
    }

    @Test
    @Suppress("DEPRECATION_ERROR")
    fun `buildPlayerUiSyncState maps links episodes history progress tracks and protocol urls`() = runBlocking {
        val link0 = newExtractorLink("SrcA", "1080p", "https://cdn.example.invalid/1080.mp4", ExtractorLinkType.VIDEO)
        val link1 = newExtractorLink("SrcB", "720p", "https://cdn.example.invalid/720.mpd", ExtractorLinkType.DASH)
        val ep1 = Episode(data = "ep1", name = "Pilot", season = 1, episode = 1, posterUrl = "//cdn.example.invalid/ep1.jpg")
        val ep2 = Episode(data = "ep2", name = "Second", season = 1, episode = 2)

        val history = mapOf(
            "ep1" to WatchHistory(
                parentId = "p0_SrcA_1",
                showName = "Sample Show",
                showUrl = "https://example.invalid/show",
                apiName = "SrcA",
                posterUrl = null,
                episodeThumbnailUrl = null,
                screenshotUrl = null,
                episode = 1,
                season = 1,
                episodeId = "ep1",
                position = 920L,
                duration = 1000L,
                updateTime = 1000L,
                episodeName = "Pilot",
                episodeDescription = null,
            ),
        )

        val state = PlayerSyncStateBuilder.buildPlayerUiSyncState(
            plot = "Test plot",
            year = 2025,
            tags = listOf("Action", "Animation"),
            contentRating = "TV-14",
            rating = 8.4,
            isProbing = false,
            isScraping = true,
            backdropUrl = "//cdn.example.invalid/backdrop.jpg",
            logoUrl = "//cdn.example.invalid/logo.png",
            currentLinkIndex = 0,
            failedLinks = mapOf(1 to "HTTP 502"),
            links = listOf(link0, link1),
            liveFileSize = 1024L * 1024L * 150L,
            providerName = "SrcA",
            episodes = listOf(ep1, ep2),
            currentEpisodeId = "ep1",
            currentEpisodeNumber = 1,
            currentSeasonNumber = 1,
            seriesPosterUrl = "//cdn.example.invalid/series.jpg",
            episodeHistoryLookup = EpisodeHistoryLookup(history),
            audioTracks = listOf(PlayerState.VideoTrack(1, "English", true)),
            subtitleTracks = listOf(PlayerState.VideoTrack(2, "English CC", false)),
            videoTracks = listOf(PlayerState.VideoTrack(3, "1080p", true)),
            proxyAudioTracks = listOf(ProxyTrack("http://127.0.0.1/a.m3u8", "Japanese", "ja")),
            proxySubtitleTracks = listOf(ProxyTrack("http://127.0.0.1/s.vtt", "English", "en")),
            proxyVideoTracks = listOf(ProxyTrack("http://127.0.0.1/v.m3u8", "1080p", "und", 5000)),
            autoFetchedSubtitleTracks = listOf(LazyTrackPayload("http://127.0.0.1/auto.srt", "Auto EN", "en")),
            startPositionMs = 15_000L,
            title = "Sample Show",
            activeShader = "FSR",
            activeLazyVideoTrackUrl = "http://127.0.0.1/v.m3u8",
            activeLazyAudioTrackUrl = null,
            resolution = "1920x1080",
            activeSubtitleOverrideEnabled = true,
            isLive = false,
            isAudioOnlyStream = false,
            isAudioMode = false,
            chapters = listOf(PlayerState.Chapter(0, "Opening", 0L)),
            currentChapterIndex = 0,
            activeSkipInterval = null,
            skipIntervals = emptyList(),
            actors = emptyList(),
            isAnime = true,
            isExhausted = false,
            exhaustionReason = null,
            exhaustionDiagnostics = null,
            resolvedImdbId = "tt1234567",
        )

        assertEquals("Sample Show", state.title)
        assertEquals("https://cdn.example.invalid/backdrop.jpg", state.backdropUrl)
        assertEquals("https://cdn.example.invalid/logo.png", state.logoUrl)
        assertEquals(2, state.links.size)
        assertTrue(state.links[0].isActive)
        assertFalse(state.links[0].isM3u8)
        assertTrue(state.links[1].isDash)
        assertEquals("150 MB", state.links[0].size)
        assertEquals(1, state.failedLinks.size)
        assertEquals("HTTP 502", state.failedLinks.first().reason)
        assertEquals(2, state.episodes.size)
        assertTrue(state.episodes[0].isActive)
        assertTrue(state.episodes[0].isSeen)
        assertEquals(1, state.chapters.size)
        assertEquals("tt1234567", state.imdbId)
    }
}
