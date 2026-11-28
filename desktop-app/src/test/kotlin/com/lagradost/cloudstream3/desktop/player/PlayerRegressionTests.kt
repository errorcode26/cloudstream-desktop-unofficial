package com.lagradost.cloudstream3.desktop.player

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.desktop.domain.history.interactor.UpsertWatchHistory
import com.lagradost.cloudstream3.desktop.domain.history.repository.WatchHistoryRepository
import com.lagradost.cloudstream3.desktop.domain.player.interactor.SavePlaybackProgress
import com.lagradost.cloudstream3.desktop.metadata.RatingSource
import com.lagradost.cloudstream3.desktop.ui.VideoLaunchData
import com.lagradost.cloudstream3.desktop.ui.badges.BadgeManager
import com.lagradost.cloudstream3.desktop.ui.badges.StreamTechTagExtractor
import com.lagradost.cloudstream3.desktop.ui.badges.TechTagType
import com.lagradost.cloudstream3.desktop.ui.screens.details.EpisodeHistoryLookup
import com.lagradost.cloudstream3.desktop.ui.screens.player.EmbeddedPlayerViewModel
import com.lagradost.cloudstream3.desktop.ui.screens.player.LinkCache
import com.lagradost.cloudstream3.desktop.ui.screens.player.PlayerScrapeCoordinator
import com.lagradost.cloudstream3.desktop.ui.screens.player.PlayerState
import com.lagradost.cloudstream3.desktop.ui.screens.player.PlayerStreamScraper
import com.lagradost.cloudstream3.desktop.ui.screens.player.WatchHistoryCoordinator
import com.lagradost.cloudstream3.desktop.ui.screens.player.contract.PlayerPhase
import com.lagradost.cloudstream3.desktop.ui.screens.player.contract.PlayerUiEvent
import com.lagradost.cloudstream3.desktop.ui.screens.player.contract.PlayerUiState
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.common.storage.DesktopDataStore
import com.lagradost.common.storage.WatchHistory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class PlayerRegressionTests {

    private lateinit var tempDir: File
    private var prevDataDir: String? = null
    private lateinit var testScope: CoroutineScope

    private class FakeWatchHistoryRepository(
        private val upsertDelayMs: Long = 0L,
    ) : WatchHistoryRepository {
        private val store = ConcurrentHashMap<String, WatchHistory>()
        private val updates = MutableStateFlow(0)
        val recordedUpserts = CopyOnWriteArrayList<WatchHistory>()

        override fun subscribeAll(): Flow<List<WatchHistory>> =
            updates.map { store.values.toList() }

        override fun subscribeByParent(parentId: String): Flow<List<WatchHistory>> =
            updates.map { store.values.filter { it.parentId == parentId } }

        override suspend fun getByParent(parentId: String): List<WatchHistory> =
            store.values.filter { it.parentId == parentId }

        override suspend fun getByEpisode(parentId: String, episodeId: String): WatchHistory? =
            store[episodeId]

        override suspend fun upsert(history: WatchHistory, forceNotify: Boolean) {
            if (upsertDelayMs > 0L) delay(upsertDelayMs)
            val key = history.episodeId ?: history.parentId
            store[key] = history
            recordedUpserts.add(history)
            updates.value += 1
        }

        override suspend fun deleteByParent(parentId: String) {
            store.entries.removeIf { it.value.parentId == parentId }
        }

        override suspend fun deleteByEpisode(
            parentId: String,
            episodeId: String,
            season: Int?,
            episode: Int?,
            extraEpisodeIds: List<String>,
        ) {
            store.remove(episodeId)
            extraEpisodeIds.forEach { store.remove(it) }
        }

        override suspend fun deleteAll() {
            store.clear()
        }
    }

    @BeforeEach
    fun setup() {
        prevDataDir = System.getProperty("cloudstream.data.dir")
        tempDir = Files.createTempDirectory("cs_player_reg_test_").toFile()
        System.setProperty("cloudstream.data.dir", tempDir.absolutePath)
        DesktopDataStore.resetForTesting()
        DesktopDataStore.init()
        val testBadgesDir = File(tempDir, "badges").apply { mkdirs() }
        val defaultStyleDir = File(testBadgesDir, BadgeManager.DEFAULT_STYLE).apply { mkdirs() }
        listOf("hevc.png", "torbox.png").forEach { name ->
            File(defaultStyleDir, name).writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
        }
        listOf("rating_imdb.png", "rating_tmdb.png").forEach { name ->
            File(testBadgesDir, name).writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
        }
        File(testBadgesDir, "tracker_anilist.svg").writeText("<svg xmlns=\"http://www.w3.org/2000/svg\"/>")
        BadgeManager.badgesRootOverrideForTesting = testBadgesDir
        testScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @AfterEach
    fun teardown() {
        BadgeManager.badgesRootOverrideForTesting = null
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
    @Suppress("DEPRECATION_ERROR")
    fun `clicking Next Episode at 80 percent marks episode completed despite outgoing stale ticks`() = runBlocking {
        val fakeRepo = FakeWatchHistoryRepository(upsertDelayMs = 40L)
        val saveInteractor = SavePlaybackProgress(fakeRepo, UpsertWatchHistory(fakeRepo))
        var fakeClockMs = 1_000_000L

        val ep1File = File(tempDir, "ep1.mkv").apply { writeText("stub1") }
        val ep2File = File(tempDir, "ep2.mkv").apply { writeText("stub2") }

        val ep1 = Episode(data = ep1File.absolutePath, name = "Episode 1", season = 1, episode = 1)
        val ep2 = Episode(data = ep2File.absolutePath, name = "Episode 2", season = 1, episode = 2)
        val ep1Link = newExtractorLink("Offline", "Ep1", ep1File.absolutePath, ExtractorLinkType.VIDEO) {
            this.quality = Qualities.P1080.value
        }

        val initialHistory = WatchHistory(
            parentId = "offline_show_1",
            showName = "Sample Show",
            showUrl = ep1File.absolutePath,
            apiName = "Offline",
            posterUrl = null,
            episodeThumbnailUrl = null,
            screenshotUrl = null,
            episode = 1,
            season = 1,
            episodeId = ep1File.absolutePath,
            position = 0L,
            duration = 1440L,
        )

        val launchData = VideoLaunchData(
            links = listOf(ep1Link),
            initialIndex = 0,
            title = "Sample Show - S1E1",
            subtitles = emptyList(),
            startPositionMs = 0L,
            history = initialHistory,
            episodes = listOf(ep1, ep2),
        )

        val vm = EmbeddedPlayerViewModel(
            savePlaybackProgress = saveInteractor,
            trackerSyncEngine = null,
            trackingRepository = null,
            clockMs = { fakeClockMs },
        )

        try {
            vm.onEvent(PlayerUiEvent.OnInit(launchData))
            vm.onEvent(PlayerUiEvent.OnPlaybackReady)

            // Wait until phase becomes Playing
            repeat(40) {
                if (vm.uiState.value.phase is PlayerPhase.Playing) return@repeat
                delay(15)
            }
            assertTrue(vm.uiState.value.phase is PlayerPhase.Playing)

            vm.playerState._durationMs.value = 1_440_000L
            vm.playerState._isBuffering.value = false
            vm.playerState._isPaused.value = false

            // Simulate continuous genuine playback up to 1158s (~80.4%)
            for (sec in 0L..1158L step 3L) {
                fakeClockMs += 3_000L
                vm.playerState.updatePositionFromPlayer(sec * 1000L)
                delay(1)
            }
            // Wait for any periodic save around 80% before switching episode
            delay(120)

            // Click Next Episode right at 80.4% (below 85% auto-threshold, >= 80% Next-Episode threshold)
            vm.onEvent(PlayerUiEvent.OnLoadEpisode(ep2))

            // Simulate outgoing MPV ticks arriving right after OnLoadEpisode before engine stops
            vm.playerState.updatePositionFromPlayer(1163_000L)
            vm.playerState.updatePositionFromPlayer(1168_000L)

            repeat(50) {
                val saved = fakeRepo.getByEpisode("offline_show_1", ep1File.absolutePath)
                if (saved != null && saved.position == 1440L) return@repeat
                delay(25)
            }

            // Wait longer to confirm late async periodic jobs do NOT downgrade completed ep1 back below 1440s
            delay(200)
            val finalEp1History = fakeRepo.getByEpisode("offline_show_1", ep1File.absolutePath)
            assertNotNull(finalEp1History, "Episode 1 history must be persisted")
            assertEquals(1440L, finalEp1History!!.position, "Episode 1 at 80% Next Episode must remain completed (1440s)")
            assertTrue(UpsertWatchHistory.isWatched(finalEp1History.position, finalEp1History.duration))
        } finally {
            vm.dispose()
        }
    }

    @Test
    @Suppress("DEPRECATION_ERROR")
    fun `PlayerScrapeCoordinator invokes completion guard reset hook on online episode cache hit`() = runBlocking {
        val guardResetCount = AtomicInteger(0)
        var committedLaunch: VideoLaunchData? = null
        val ep1 = Episode(data = "https://cdn.example.invalid/ep1", name = "Ep 1", season = 1, episode = 1)
        val ep2 = Episode(data = "https://cdn.example.invalid/ep2", name = "Ep 2", season = 1, episode = 2)
        val ep2Link = newExtractorLink("SrcA", "1080p HEVC", "https://cdn.example.invalid/ep2.mkv", ExtractorLinkType.VIDEO) {
            this.quality = Qualities.P1080.value
        }

        val currentHistory = WatchHistory(
            parentId = "p0_SrcA_1",
            showName = "Sample Series",
            showUrl = "https://cdn.example.invalid/show",
            apiName = "SrcA",
            posterUrl = null,
            episodeThumbnailUrl = null,
            screenshotUrl = null,
            episode = 1,
            season = 1,
            episodeId = ep1.data,
            position = 900L,
            duration = 1440L,
        )
        val baseLaunch = VideoLaunchData(
            links = listOf(ep2Link),
            initialIndex = 0,
            title = "Sample Series - S1E1",
            subtitles = emptyList(),
            startPositionMs = 0L,
            history = currentHistory,
            episodes = listOf(ep1, ep2),
        )

        var state = PlayerUiState(
            launchData = baseLaunch,
            targetEpisodeData = ep2,
            phase = PlayerPhase.Scraping,
        )

        val testProvider = object : MainAPI() {
            override var name = "SrcA"
            override var mainUrl = "https://cdn.example.invalid"
        }
        LinkCache.set(ep2.data, listOf(ep2Link), emptyList())

        val coordinator = PlayerScrapeCoordinator(
            scope = testScope,
            scraper = PlayerStreamScraper(testScope),
            getState = { state },
            updateState = { updater -> state = state.updater() },
            updatePhase = { phase, failed ->
                state = state.copy(phase = phase, failedLinks = failed ?: state.failedLinks)
            },
            onPlaybackError = { _, _ -> },
            getScrapeFlowControl = { null },
            onNewEpisodeLaunchCommitted = { launch ->
                guardResetCount.incrementAndGet()
                committedLaunch = launch
            },
        )

        coordinator.startScrape(testProvider, ep2.data, baseLaunch, ep2).join()

        assertEquals(1, guardResetCount.get(), "Online episode switch must trigger completion guard reset")
        assertNotNull(committedLaunch)
        assertEquals(ep2.data, committedLaunch!!.history.episodeId)
    }

    @Test
    @Suppress("DEPRECATION_ERROR")
    fun `WatchHistoryCoordinator queues next episode at 85 percent auto-watched threshold`() = runBlocking {
        val fakeRepo = FakeWatchHistoryRepository()
        val saveInteractor = SavePlaybackProgress(fakeRepo, UpsertWatchHistory(fakeRepo))

        val ep1History = WatchHistory(
            parentId = "p0_test_99",
            showName = "Sample Show",
            showUrl = "https://cdn.example.invalid/show",
            apiName = "Test",
            posterUrl = null,
            episodeThumbnailUrl = null,
            screenshotUrl = null,
            episode = 1,
            season = 1,
            episodeId = "ep1",
            position = 850L,
            duration = 1000L,
        )
        val ep2 = Episode(data = "ep2", name = "Episode 2", season = 1, episode = 2)

        WatchHistoryCoordinator.saveWithNextEpisodeQueue(
            history = ep1History,
            hasNextEpisode = true,
            nextEpisode = ep2,
            saveProgress = saveInteractor,
            forceNotify = true,
            allowCompletion = true,
        )

        val savedEp1 = fakeRepo.getByEpisode("p0_test_99", "ep1")
        val queuedEp2 = fakeRepo.getByEpisode("p0_test_99", "ep2")
        assertNotNull(savedEp1)
        assertEquals(1000L, savedEp1!!.position, "85% automatic watch must normalize to 100% completed")
        assertNotNull(queuedEp2, "Completed episode at 85% must enqueue next episode")
    }

    @Test
    fun `MpvEventLoop transition guard suppresses outgoing stale samples until START_FILE`() {
        assertTrue(MpvEventLoop.shouldIgnoreSampleDuringTransition(waitingForReset = true, hasStartedCurrentFile = false))
        assertFalse(MpvEventLoop.shouldIgnoreSampleDuringTransition(waitingForReset = true, hasStartedCurrentFile = true))
        assertFalse(MpvEventLoop.shouldIgnoreSampleDuringTransition(waitingForReset = false, hasStartedCurrentFile = false))
    }

    @Test
    fun `PlayerState reset preserves persisted mute preference across episodes`() {
        DesktopDataStore.setKey(PlayerConfig.PREF_AUDIO_MUTED, true)
        val playerState = PlayerState()
        assertTrue(playerState.isMuted.value)

        playerState._positionMs.value = 123_000L
        playerState.reset()

        assertEquals(0L, playerState.positionMs.value)
        assertTrue(playerState.isMuted.value, "Reset must preserve persisted mute state")
    }

    @Test
    fun `StreamTechTagExtractor and BadgeManager resolve badges and fallback styles cleanly`() {
        val tags = StreamTechTagExtractor.extract(
            "[TB+] Movie.2025.2160p.REMUX.DV.HEVC.10bit.TrueHD.Atmos.7.1.Dual Audio.HIN.ENG",
        )
        val texts = tags.map { it.text }
        assertTrue("TorBox" in texts)
        assertTrue("REMUX" in texts)
        assertTrue("Dolby Vision" in texts)
        assertTrue("HEVC" in texts)
        assertTrue("10-bit" in texts)
        assertTrue("Atmos" in texts)
        assertTrue("TrueHD" in texts)
        assertTrue("7.1" in texts)
        assertTrue("Dual Audio" in texts)
        assertTrue("HIN" in texts)
        assertTrue("ENG" in texts)
        assertEquals(TechTagType.CODEC, tags.first { it.text == "HEVC" }.type)

        // Verify fallback from unknown style -> Badges-Black-White and real asset existence
        val fallbackUri = BadgeManager.getBadgeUri("hevc.png", style = "NonExistent-Custom-Style")
        assertNotNull(fallbackUri, "Missing style must fall back to default style badge URI")
        assertTrue(fallbackUri!!.startsWith("file:///"))

        assertNull(BadgeManager.getBadgeUri("definitely_missing_badge_asset_xyz.png"))

        val imdbUri = BadgeManager.getRootAssetUri("rating_imdb.png")
        val tmdbUri = BadgeManager.getRootAssetUri("rating_tmdb.png")
        val aniUri = BadgeManager.getRootAssetUri("tracker_anilist.svg")
        assertNotNull(imdbUri)
        assertNotNull(tmdbUri)
        assertNotNull(aniUri)
    }

    @Test
    @Suppress("DEPRECATION_ERROR")
    fun `PlayerSyncStateBuilder populates resolved techTags, pause ratingBadges and 85 percent seen threshold`() {
        val link = runBlocking {
            newExtractorLink(
            source = "SrcA",
            name = "[TB+] Sample.2160p.REMUX.HEVC.Atmos",
            url = "https://cdn.example.invalid/stream.mkv",
            type = ExtractorLinkType.VIDEO,
        ) {
            quality = Qualities.P2160.value
        }
        }
        val ep85 = Episode(data = "ep85", name = "Episode 85", season = 1, episode = 1)
        val ep80 = Episode(data = "ep80", name = "Episode 80", season = 1, episode = 2)

        val history = mapOf(
            "ep85" to WatchHistory(
                parentId = "p0_SrcA_1",
                showName = "Sample Show",
                showUrl = "https://cdn.example.invalid/show",
                apiName = "SrcA",
                posterUrl = null,
                episodeThumbnailUrl = null,
                screenshotUrl = null,
                episode = 1,
                season = 1,
                episodeId = "ep85",
                position = 850L,
                duration = 1000L,
            ),
            "ep80" to WatchHistory(
                parentId = "p0_SrcA_1",
                showName = "Sample Show",
                showUrl = "https://cdn.example.invalid/show",
                apiName = "SrcA",
                posterUrl = null,
                episodeThumbnailUrl = null,
                screenshotUrl = null,
                episode = 2,
                season = 1,
                episodeId = "ep80",
                position = 800L,
                duration = 1000L,
            ),
        )

        val syncState = PlayerSyncStateBuilder.buildPlayerUiSyncState(
            plot = "Plot",
            year = 2025,
            tags = listOf("Drama"),
            contentRating = "TV-14",
            rating = 8.4,
            isProbing = true,
            isScraping = false,
            backdropUrl = null,
            logoUrl = null,
            currentLinkIndex = 0,
            failedLinks = emptyMap(),
            links = listOf(link),
            liveFileSize = 0L,
            providerName = "SrcA",
            episodes = listOf(ep85, ep80),
            currentEpisodeId = "ep85",
            currentEpisodeNumber = 1,
            currentSeasonNumber = 1,
            seriesPosterUrl = null,
            episodeHistoryLookup = EpisodeHistoryLookup(history),
            audioTracks = emptyList(),
            subtitleTracks = emptyList(),
            videoTracks = emptyList(),
            proxyAudioTracks = emptyList(),
            proxySubtitleTracks = emptyList(),
            proxyVideoTracks = emptyList(),
            autoFetchedSubtitleTracks = emptyList(),
            startPositionMs = 0L,
            title = "Sample Show",
            activeShader = "None",
            activeLazyVideoTrackUrl = null,
            activeLazyAudioTrackUrl = null,
            resolution = null,
            activeSubtitleOverrideEnabled = true,
            isLive = false,
            isAudioOnlyStream = false,
            isAudioMode = false,
            chapters = emptyList(),
            currentChapterIndex = -1,
            activeSkipInterval = null,
            skipIntervals = emptyList(),
            actors = emptyList(),
            isAnime = false,
            isExhausted = false,
            exhaustionReason = null,
            exhaustionDiagnostics = null,
            resolvedImdbId = "tt1234567",
            imdbScore = 8.6,
            tmdbScore = 8.2,
            anilistScore = 8.1,
            episodeScore = null,
            episodeRatingSource = null,
        )

        assertNotNull(syncState.badgesBaseUrl)
        val linkPayload = syncState.links.single()
        assertTrue(linkPayload.techTags.any { it.text == "TorBox" && it.badgeUrl?.startsWith("file:///") == true })
        assertTrue(linkPayload.techTags.any { it.text == "HEVC" && it.badgeUrl?.startsWith("file:///") == true })

        assertEquals(listOf("imdb", "tmdb", "anilist"), syncState.ratingBadges.map { it.source })
        assertEquals("81%", syncState.ratingBadges.last().scoreText)
        assertTrue(syncState.ratingBadges.all { it.iconUrl?.startsWith("file:///") == true })

        // Verify episode watched threshold alignment at 85% vs 80%
        assertTrue(syncState.episodes[0].isSeen, "Episode at 85% should be marked seen")
        assertFalse(syncState.episodes[1].isSeen, "Episode at 80% before explicit Next Episode flush should remain in-progress")
    }
}
