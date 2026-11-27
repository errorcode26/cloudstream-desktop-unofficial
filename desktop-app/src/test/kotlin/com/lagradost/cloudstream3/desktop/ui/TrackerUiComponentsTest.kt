package com.lagradost.cloudstream3.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.desktop.data.tracking.TrackingRepositoryImpl
import com.lagradost.cloudstream3.desktop.tracking.BindMode
import com.lagradost.cloudstream3.desktop.tracking.ReleaseBounds
import com.lagradost.cloudstream3.desktop.tracking.TrackerBinding
import com.lagradost.cloudstream3.desktop.tracking.TrackerDescriptor
import com.lagradost.cloudstream3.desktop.tracking.TrackerIds
import com.lagradost.cloudstream3.desktop.tracking.UnifiedLibraryEntry
import com.lagradost.cloudstream3.desktop.tracking.UnifiedTrackerStatus
import com.lagradost.cloudstream3.desktop.tracking.UnifiedWatchStatus
import com.lagradost.cloudstream3.desktop.ui.screens.details.DetailsHeroLibraryButton
import com.lagradost.cloudstream3.desktop.ui.screens.details.DetailsTrackerDrawerHost
import com.lagradost.cloudstream3.desktop.ui.screens.details.DetailsTrackersHeroCluster
import com.lagradost.cloudstream3.desktop.ui.screens.details.contract.DetailsTrackerEntryState
import com.lagradost.cloudstream3.desktop.ui.screens.details.contract.DetailsTrackersUiState
import com.lagradost.cloudstream3.desktop.ui.screens.details.contract.DetailsUiEvent
import com.lagradost.cloudstream3.desktop.ui.screens.library.components.TrackerLibraryCard
import com.lagradost.cloudstream3.desktop.ui.screens.settings.SettingsPlayerAutoPlayScreen
import com.lagradost.cloudstream3.desktop.ui.screens.settings.SettingsViewModel
import com.lagradost.common.storage.DesktopBookmark
import com.lagradost.common.storage.DesktopDataStore
import com.lagradost.common.storage.DesktopWatchType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class TrackerUiComponentsTest {

    private lateinit var tempDir: File
    private var prevDataDir: String? = null

    @BeforeEach
    fun setUp() {
        prevDataDir = System.getProperty("cloudstream.data.dir")
        DesktopDataStore.resetForTesting()
        tempDir = Files.createTempDirectory("cs_ui_test_").toFile()
        System.setProperty("cloudstream.data.dir", tempDir.absolutePath)
        DesktopDataStore.resetForTesting()
        DesktopDataStore.init()
    }

    @AfterEach
    fun tearDown() {
        DesktopDataStore.resetForTesting()
        if (prevDataDir != null) {
            System.setProperty("cloudstream.data.dir", prevDataDir)
        } else {
            System.clearProperty("cloudstream.data.dir")
        }
        tempDir.deleteRecursively()
    }

    private val aniListDescriptor = TrackerDescriptor(
        id = TrackerIds.ANILIST,
        displayName = "AniList",
        shortTag = "AL",
        accentColorHex = 0xFF02A9FFL,
        syncedColorHex = 0xFF38BDF8L,
        supportsFavorites = true,
    )

    private val malDescriptor = TrackerDescriptor(
        id = TrackerIds.MAL,
        displayName = "MyAnimeList",
        shortTag = "MAL",
        accentColorHex = 0xFF2E51A2L,
        syncedColorHex = 0xFF60A5FAL,
    )

    @Test
    fun `tracker library card keeps resting surface clean and reveals actions when selected`() = runComposeUiTest {
        var selectedForEdit by mutableStateOf(false)
        var cardClicks = 0
        var editClicks = 0
        var incrementClicks = 0

        val sampleEntry = UnifiedLibraryEntry(
            providerId = TrackerIds.SIMKL,
            remoteMediaId = "anime:4478",
            malId = 31964,
            title = "My Hero Academia",
            format = "TV",
            mediaStatus = "FINISHED",
            seasonYear = 2016,
            posterUrl = null,
            progress = 4,
            totalEpisodes = 13,
            score100 = 80,
            averageScore = 84,
            isFavourite = false,
            updatedAt = 1_700_000_000_000L,
            status = UnifiedWatchStatus.WATCHING,
            releaseBounds = ReleaseBounds(canMarkCompleted = true),
        )
        val descriptor = TrackerDescriptor(
            id = TrackerIds.SIMKL,
            displayName = "SIMKL",
            shortTag = "SK",
            accentColorHex = 0xFF00B7FFL,
            syncedColorHex = 0xFF22D3EEL,
        )

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(modifier = Modifier.width(220.dp)) {
                    TrackerLibraryCard(
                        entry = sampleEntry,
                        descriptor = descriptor,
                        linkedBookmark = null,
                        isSelectedForEdit = selectedForEdit,
                        isIncrementing = false,
                        onClick = { cardClicks++ },
                        onQuickEdit = { editClicks++ },
                        onIncrementEpisode = { incrementClicks++ },
                    )
                }
            }
        }

        waitForIdle()
        onNodeWithText("My Hero Academia").assertIsDisplayed()
        onNodeWithText("Ep 4 / 13").assertIsDisplayed()
        onNodeWithText("8.0").assertIsDisplayed()
        onNodeWithText("SK").assertDoesNotExist()
        onNodeWithText("Cloud").assertDoesNotExist()
        onNodeWithText("Linked").assertDoesNotExist()
        onNodeWithContentDescription("Quick Edit My Hero Academia").assertDoesNotExist()
        onNodeWithText("1").assertDoesNotExist()

        selectedForEdit = true
        waitForIdle()

        onNodeWithContentDescription("Quick Edit My Hero Academia").assertIsDisplayed().performClick()
        onNodeWithText("1").assertIsDisplayed().performClick()
        assertEquals(1, editClicks)
        assertEquals(1, incrementClicks)
    }

    @Test
    fun `details hero library button and trackers cluster display active states cleanly`() = runComposeUiTest {
        var bookmark by mutableStateOf<DesktopBookmark?>(null)
        var openedTrackerId: String? = null
        var libraryEdits = 0

        val bindingAl = TrackerBinding(
            profileId = 1L,
            trackerId = TrackerIds.ANILIST,
            showKey = "prov:frieren",
            mappedSeason = 1L,
            remoteMediaId = "154587",
            remoteTitle = "Frieren",
            bindMode = BindMode.AUTO_VERIFIED,
            episodeOffset = 0L,
            expectedTotalEpisodes = 28L,
            updatedAt = 1L,
        )
        val bindingMal = bindingAl.copy(trackerId = TrackerIds.MAL, remoteMediaId = "52991")

        val clusterState = DetailsTrackersUiState(
            entries = linkedMapOf(
                TrackerIds.ANILIST to DetailsTrackerEntryState(
                    descriptor = aniListDescriptor,
                    isConnected = true,
                    isEligible = true,
                    boundEntry = bindingAl,
                    remoteStatus = UnifiedTrackerStatus(
                        providerId = TrackerIds.ANILIST,
                        remoteMediaId = "154587",
                        title = "Frieren",
                        status = UnifiedWatchStatus.WATCHING,
                        progress = 4,
                        totalEpisodes = 28,
                        score100 = 90,
                        isFavourite = false,
                        posterUrl = null,
                        releaseBounds = ReleaseBounds(totalEpisodes = 28),
                    ),
                    isAutoSyncEnabled = true,
                ),
                TrackerIds.MAL to DetailsTrackerEntryState(
                    descriptor = malDescriptor,
                    isConnected = true,
                    isEligible = true,
                    boundEntry = bindingMal,
                    isAutoSyncEnabled = false,
                ),
            ),
        )

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                androidx.compose.foundation.layout.Row {
                    DetailsHeroLibraryButton(
                        currentBookmark = bookmark,
                        activeWatchType = DesktopWatchType.WATCHING,
                        isButtonsNarrow = false,
                        onEditStatus = { libraryEdits++ },
                    )
                    DetailsTrackersHeroCluster(
                        trackersState = clusterState,
                        isButtonsNarrow = false,
                        onOpenTracker = { openedTrackerId = it },
                    )
                }
            }
        }

        waitForIdle()
        onNodeWithContentDescription("Add to Library").assertIsDisplayed()
        onNodeWithText("2 Trackers · 4/28").assertIsDisplayed().performClick()
        assertEquals(TrackerIds.ANILIST, openedTrackerId)

        bookmark = DesktopBookmark(
            id = "b1",
            name = "Frieren",
            url = "https://example.org/frieren",
            apiName = "Demo",
            posterUrl = null,
            watchType = DesktopWatchType.WATCHING.id,
        )
        waitForIdle()
        onNodeWithText(DesktopWatchType.WATCHING.stringRes).assertIsDisplayed().performClick()
        assertEquals(1, libraryEdits)
    }

    @Test
    fun `details tracker drawer switches provider tabs and dispatches progress and status events`() = runComposeUiTest {
        val dispatched = mutableListOf<DetailsUiEvent>()
        val bindingAl = TrackerBinding(
            profileId = 1L,
            trackerId = TrackerIds.ANILIST,
            showKey = "prov:frieren",
            mappedSeason = 1L,
            remoteMediaId = "154587",
            remoteTitle = "Frieren: Beyond Journey's End",
            bindMode = BindMode.USER_PINNED,
            episodeOffset = 0L,
            expectedTotalEpisodes = 28L,
            updatedAt = 1L,
        )
        val drawerState = DetailsTrackersUiState(
            isDrawerOpen = true,
            activeProviderId = TrackerIds.ANILIST,
            entries = linkedMapOf(
                TrackerIds.ANILIST to DetailsTrackerEntryState(
                    descriptor = aniListDescriptor,
                    isConnected = true,
                    isEligible = true,
                    showKey = "prov:frieren",
                    mappedSeason = 1,
                    boundEntry = bindingAl,
                    releaseBounds = ReleaseBounds(totalEpisodes = 28, ceilingEpisode = 28, canMarkCompleted = true),
                    editStatus = UnifiedWatchStatus.WATCHING,
                    editProgress = 4,
                    editScore = 80,
                ),
                TrackerIds.MAL to DetailsTrackerEntryState(
                    descriptor = malDescriptor,
                    isConnected = true,
                    isEligible = true,
                    showKey = "prov:frieren",
                    mappedSeason = 1,
                ),
            ),
        )

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                DetailsTrackerDrawerHost(
                    isOpen = true,
                    showName = "Frieren",
                    trackersState = drawerState,
                    currentSeasonEpisodes = emptyList(),
                    onEvent = { dispatched += it },
                )
            }
        }

        waitForIdle()
        onNodeWithText("Media Tracking").assertIsDisplayed()
        onNodeWithText("Ep 4 / 28").assertIsDisplayed()

        onNodeWithContentDescription("Increase episode").performClick()
        onNodeWithText(UnifiedWatchStatus.COMPLETED.label).performClick()
        onNodeWithText("MAL").performClick()

        assertTrue(dispatched.contains(DetailsUiEvent.OnSetTrackerRemoteProgress(TrackerIds.ANILIST, 5)))
        assertTrue(dispatched.contains(DetailsUiEvent.OnSetTrackerRemoteStatus(TrackerIds.ANILIST, UnifiedWatchStatus.COMPLETED)))
        assertTrue(dispatched.contains(DetailsUiEvent.OnSelectTrackerTab(TrackerIds.MAL)))
    }

    @Test
    fun `settings autoplay per-tracker auto-track toggles remain independent`() = runComposeUiTest {
        val repo = TrackingRepositoryImpl()
        val settingsVm = SettingsViewModel()

        try {
            setContent {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    SettingsPlayerAutoPlayScreen(
                        viewModel = settingsVm,
                        trackingRepo = repo,
                    )
                }
            }

            waitForIdle()
            onNodeWithTag("settings_autoplay_auto_track_${TrackerIds.ANILIST}").performScrollTo().assertIsOff()
            onNodeWithTag("settings_autoplay_auto_track_${TrackerIds.MAL}").performScrollTo().assertIsOff()
            onNodeWithTag("settings_autoplay_auto_track_${TrackerIds.SIMKL}").performScrollTo().assertIsOff()

            onNodeWithTag("settings_autoplay_auto_track_${TrackerIds.MAL}").performScrollTo().performClick()

            waitUntil(timeoutMillis = 3_000L) {
                repo.isAutoSyncEnabled(TrackerIds.MAL)
            }
            waitForIdle()

            assertTrue(repo.isAutoSyncEnabled(TrackerIds.MAL))
            assertFalse(repo.isAutoSyncEnabled(TrackerIds.ANILIST))
            assertFalse(repo.isAutoSyncEnabled(TrackerIds.SIMKL))

            onNodeWithTag("settings_autoplay_auto_track_${TrackerIds.MAL}").assertIsOn()
            onNodeWithTag("settings_autoplay_auto_track_${TrackerIds.ANILIST}").assertIsOff()
            onNodeWithTag("settings_autoplay_auto_track_${TrackerIds.SIMKL}").assertIsOff()
        } finally {
            settingsVm.dispose()
        }
    }
}
