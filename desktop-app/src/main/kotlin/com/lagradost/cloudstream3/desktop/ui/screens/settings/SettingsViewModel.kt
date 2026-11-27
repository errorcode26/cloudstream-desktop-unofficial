package com.lagradost.cloudstream3.desktop.ui.screens.settings

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.desktop.backup.BackupOptions
import com.lagradost.cloudstream3.desktop.backup.DesktopBackupEngine
import com.lagradost.cloudstream3.desktop.backup.RestoreOptions
import com.lagradost.cloudstream3.desktop.core.preference.PreferenceKeys
import com.lagradost.cloudstream3.desktop.debrid.torbox.TorboxApiClient
import com.lagradost.cloudstream3.desktop.discord.DiscordRpcManager
import com.lagradost.cloudstream3.desktop.download.AppDownloadManager
import com.lagradost.cloudstream3.desktop.download.TaskStatus
import com.lagradost.cloudstream3.desktop.downloader.DesktopDownloadManager
import com.lagradost.cloudstream3.desktop.models.CustomSite
import com.lagradost.cloudstream3.desktop.network.DohProvider
import com.lagradost.cloudstream3.desktop.network.NetworkConfig
import com.lagradost.cloudstream3.desktop.network.webview.WindowsWebView2Runtime
import com.lagradost.cloudstream3.desktop.player.LanguagePriorityHelper
import com.lagradost.cloudstream3.desktop.player.PlayerConfig
import com.lagradost.cloudstream3.desktop.player.QualityDataHelper
import com.lagradost.cloudstream3.desktop.torrent.DesktopTorrentEngine
import com.lagradost.cloudstream3.desktop.ui.base.BaseMviViewModel
import com.lagradost.cloudstream3.desktop.ui.screens.settings.contract.DnsTestState
import com.lagradost.cloudstream3.desktop.ui.screens.settings.contract.ProviderTestReportState
import com.lagradost.cloudstream3.desktop.ui.screens.settings.contract.SettingsUiEffect
import com.lagradost.cloudstream3.desktop.ui.screens.settings.contract.SettingsUiEvent
import com.lagradost.cloudstream3.desktop.ui.screens.settings.contract.SettingsUiState
import com.lagradost.cloudstream3.desktop.ui.screens.settings.contract.StorageCacheType
import com.lagradost.cloudstream3.desktop.ui.theme.AppearanceConfig
import com.lagradost.cloudstream3.desktop.updates.UnifiedUpdateManager
import com.lagradost.cloudstream3.desktop.utils.DeveloperModeManager
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.platform.PlatformPaths
import com.lagradost.common.storage.DesktopDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.net.URI

private val SUB_BOOLEAN_KEYS = setOf(
    PlayerConfig.PREF_SUB_ENABLED,
    PlayerConfig.PREF_ENABLE_SUB_OVERRIDE,
    PlayerConfig.PREF_SUB_REMOVE_CAPTIONS,
    PlayerConfig.PREF_SUB_REMOVE_BLOAT,
    PlayerConfig.PREF_SUB_UPPERCASE,
    PlayerConfig.PREF_SUBSOURCE_ENABLED,
)

class SettingsViewModel : BaseMviViewModel<SettingsUiState, SettingsUiEvent, SettingsUiEffect>(
    initialState = SettingsUiState(),
) {
    private var storageMetricsJob: Job? = null
    private val diagnosticEngine = SettingsDiagnosticEngine(viewModelScope)
    private val debridCoordinator = SettingsDebridCoordinator(viewModelScope)

    init {
        updateState {
            copy(
                isDevModeEnabled = DeveloperModeManager.isEnabled,
                downloadPath = DesktopDownloadManager.downloadsDir.absolutePath,
                screenshotPath = PlatformPaths.screenshotsDir.absolutePath,
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            loadPersistedSettingsIntoState()
        }
        handleEvent(SettingsUiEvent.RefreshStorageMetrics)
        handleEvent(SettingsUiEvent.RefreshTorrServerStatus)
        handleEvent(SettingsUiEvent.RefreshClearanceCookies)
        handleEvent(SettingsUiEvent.LoadBackupsList)
        observeTorrServerDownloadTask()
        viewModelScope.launch(Dispatchers.IO) {
            DesktopDataStore.keyUpdates.collect { key ->
                if (key.startsWith("player_sub_") || key == PlayerConfig.PREF_ENABLE_SUB_OVERRIDE) {
                    if (key in SUB_BOOLEAN_KEYS) {
                        val boolVal = DesktopDataStore.getKey<Boolean>(key)
                        updateState {
                            copy(
                                booleanSettings = if (boolVal != null) booleanSettings + (key to boolVal) else booleanSettings - key,
                            )
                        }
                    } else {
                        val strVal = DesktopDataStore.getKey<String>(key)
                        updateState {
                            copy(
                                stringSettings = if (strVal != null) stringSettings + (key to strVal) else stringSettings - key,
                            )
                        }
                    }
                }
            }
        }
    }

    override fun handleEvent(event: SettingsUiEvent) {
        when (event) {
            is SettingsUiEvent.OnUpdateString -> {
                updateState {
                    copy(
                        stringSettings = stringSettings + (event.key to event.value),
                        dnsTestState = if (event.key == NetworkConfig.PREF_CUSTOM_DOH_URL) DnsTestState() else dnsTestState,
                    )
                }
                viewModelScope.launch(Dispatchers.IO) {
                    DesktopDataStore.setKey(event.key, event.value)
                    if (event.key == NetworkConfig.PREF_CUSTOM_DOH_URL) {
                        try {
                            NetworkConfig.updateGlobalNetworkClients()
                        } catch (e: Exception) {
                            AppLogger.e("Network Reload Error", e)
                        }
                    }
                }
            }
            is SettingsUiEvent.OnUpdateBoolean -> {
                updateState { copy(booleanSettings = booleanSettings + (event.key to event.value)) }
                viewModelScope.launch(Dispatchers.IO) {
                    DesktopDataStore.setKey(event.key, event.value)

                    if (event.key == DesktopDataStore.PREF_ALLOW_CF_BYPASS && event.value) {
                        CloudflareKiller.unmarkAllFailed()
                        val lastUrl = CloudflareKiller.lastChallengedUrl
                        val lastTime = CloudflareKiller.lastChallengeTimestamp
                        if (!lastUrl.isNullOrBlank() && (System.currentTimeMillis() - lastTime) < 300_000L) {
                            launchManualClearance(lastUrl)
                        }
                    }

                    if (event.key == NetworkConfig.PREF_DOH_AUTO_FALLBACK) {
                        try {
                            NetworkConfig.updateGlobalNetworkClients()
                        } catch (e: Exception) {
                            AppLogger.e("Network Reload Error", e)
                        }
                    }

                    if (event.key.startsWith("DISCORD_RPC")) {
                        try {
                            DiscordRpcManager.onSettingsChanged()
                        } catch (e: Exception) {
                            AppLogger.e("Discord RPC Error", e)
                        }
                    }
                }
            }
            is SettingsUiEvent.OnUpdateInt -> {
                updateState {
                    copy(
                        intSettings = intSettings + (event.key to event.value),
                        dnsTestState = if (event.key == NetworkConfig.PREF_DOH_PROVIDER) DnsTestState() else dnsTestState,
                    )
                }
                viewModelScope.launch(Dispatchers.IO) {
                    DesktopDataStore.setKey(event.key, event.value)

                    if (event.key == NetworkConfig.PREF_DOH_PROVIDER) {
                        try {
                            NetworkConfig.updateGlobalNetworkClients()
                        } catch (e: Exception) {
                            AppLogger.e("Network Reload Error", e)
                        }
                    }
                }
            }
            is SettingsUiEvent.OnUpdateFloat -> {
                updateState { copy(floatSettings = floatSettings + (event.key to event.value)) }
                viewModelScope.launch(Dispatchers.IO) {
                    DesktopDataStore.setKey(event.key, event.value)
                }
            }
            is SettingsUiEvent.RefreshStorageMetrics -> refreshStorageMetrics()
            is SettingsUiEvent.ClearCache -> clearCache(event.cacheType)
            is SettingsUiEvent.VacuumDatabase -> vacuumDatabase()
            is SettingsUiEvent.ResetSettingsToDefault -> resetSettingsToDefault()
            is SettingsUiEvent.FactoryReset -> SettingsStorageEngine.executeFactoryReset()
            is SettingsUiEvent.CheckUpdates -> checkUpdates(event.force)
            is SettingsUiEvent.RefreshTorrServerStatus -> refreshTorrServerStatus()
            is SettingsUiEvent.CheckTorrServerUpdates -> checkTorrServerUpdates()
            is SettingsUiEvent.DeleteTorrServerBinary -> deleteTorrServerBinary()
            is SettingsUiEvent.RefreshClearanceCookies -> refreshClearanceCookies()
            is SettingsUiEvent.ClearDomainCookies -> clearDomainCookies(event.domain)
            is SettingsUiEvent.ClearAllClearanceCookies -> clearAllClearanceCookies()
            is SettingsUiEvent.LaunchManualClearance -> launchManualClearance(event.url)
            is SettingsUiEvent.UnlockDeveloperMode -> unlockDeveloperMode(event.password)
            is SettingsUiEvent.SetDeveloperMode -> setDeveloperMode(event.enabled)
            is SettingsUiEvent.StartProviderTests -> startProviderTests()
            is SettingsUiEvent.StartSingleProviderTest -> testSingleProvider(event.apiName)
            is SettingsUiEvent.SetProviderTestFilter -> {
                updateState { copy(providerTestState = providerTestState.copy(filter = event.filter)) }
                startProviderTests()
            }
            is SettingsUiEvent.CancelProviderTests -> cancelProviderTests()
            is SettingsUiEvent.RunNetworkDiagnostics -> runNetworkDiagnostics()
            is SettingsUiEvent.RunMetaDiagnostics -> runMetaDiagnostics()
            is SettingsUiEvent.TestDns -> testDns(event.provider, event.customEndpoint)
            is SettingsUiEvent.UpdateDownloadPath -> updateDownloadPath(event.path)
            is SettingsUiEvent.UpdateScreenshotPath -> updateScreenshotPath(event.path)
            is SettingsUiEvent.SetQualityPriority -> {
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        QualityDataHelper.setQualityPriority(event.quality, event.priority)
                    } catch (e: Exception) {
                        AppLogger.e("SettingsViewModel", "Failed to set quality priority", e)
                    }
                }
            }
            is SettingsUiEvent.ResetQualityDefaults -> {
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        QualityDataHelper.resetToDefaults()
                    } catch (e: Exception) {
                        AppLogger.e("SettingsViewModel", "Failed to reset quality defaults", e)
                    }
                }
            }
            is SettingsUiEvent.SetQualityPreset4K -> {
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        QualityDataHelper.setQualityPriority(Qualities.P2160.value, 10)
                        QualityDataHelper.setQualityPriority(Qualities.P1080.value, 8)
                        QualityDataHelper.setQualityPriority(Qualities.P720.value, 5)
                    } catch (e: Exception) {
                        AppLogger.e("SettingsViewModel", "Failed to set 4K preset", e)
                    }
                }
            }
            is SettingsUiEvent.SetQualityPreset1080p -> {
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        QualityDataHelper.setQualityPriority(Qualities.P1080.value, 10)
                        QualityDataHelper.setQualityPriority(Qualities.P720.value, 8)
                        QualityDataHelper.setQualityPriority(Qualities.P2160.value, 4)
                    } catch (e: Exception) {
                        AppLogger.e("SettingsViewModel", "Failed to set 1080p preset", e)
                    }
                }
            }
            is SettingsUiEvent.SetAudioLanguagePreset -> {
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        LanguagePriorityHelper.setAudioPreset(event.preset)
                    } catch (e: Exception) {
                        AppLogger.e("SettingsViewModel", "Failed to set audio preset", e)
                    }
                }
            }
            is SettingsUiEvent.ResetAudioDefaults -> {
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        LanguagePriorityHelper.resetAudioDefaults()
                    } catch (e: Exception) {
                        AppLogger.e("SettingsViewModel", "Failed to reset audio defaults", e)
                    }
                }
            }
            is SettingsUiEvent.MoveAudioLanguage -> {
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        LanguagePriorityHelper.moveAudio(event.fromIndex, event.toIndex)
                    } catch (e: Exception) {
                        AppLogger.e("SettingsViewModel", "Failed to move audio language", e)
                    }
                }
            }
            is SettingsUiEvent.AddAudioLanguage -> {
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        LanguagePriorityHelper.addAudioToStack(event.code)
                    } catch (e: Exception) {
                        AppLogger.e("SettingsViewModel", "Failed to add audio language", e)
                    }
                }
            }
            is SettingsUiEvent.RemoveAudioLanguage -> {
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        LanguagePriorityHelper.removeAudioFromStack(event.code)
                    } catch (e: Exception) {
                        AppLogger.e("SettingsViewModel", "Failed to remove audio language", e)
                    }
                }
            }
            is SettingsUiEvent.SetSubtitleLanguagePreset -> {
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        LanguagePriorityHelper.setSubtitlePreset(event.preset)
                    } catch (e: Exception) {
                        AppLogger.e("SettingsViewModel", "Failed to set subtitle preset", e)
                    }
                }
            }
            is SettingsUiEvent.ResetSubtitleDefaults -> {
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        LanguagePriorityHelper.resetSubtitleDefaults()
                    } catch (e: Exception) {
                        AppLogger.e("SettingsViewModel", "Failed to reset subtitle defaults", e)
                    }
                }
            }
            is SettingsUiEvent.MoveSubtitleLanguage -> {
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        LanguagePriorityHelper.moveSubtitle(event.fromIndex, event.toIndex)
                    } catch (e: Exception) {
                        AppLogger.e("SettingsViewModel", "Failed to move subtitle language", e)
                    }
                }
            }
            is SettingsUiEvent.AddSubtitleLanguage -> {
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        LanguagePriorityHelper.addSubtitleToStack(event.code)
                    } catch (e: Exception) {
                        AppLogger.e("SettingsViewModel", "Failed to add subtitle language", e)
                    }
                }
            }
            is SettingsUiEvent.RemoveSubtitleLanguage -> {
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        LanguagePriorityHelper.removeSubtitleFromStack(event.code)
                    } catch (e: Exception) {
                        AppLogger.e("SettingsViewModel", "Failed to remove subtitle language", e)
                    }
                }
            }
            is SettingsUiEvent.AddClonedSite -> addClonedSite(event.site)
            is SettingsUiEvent.RemoveClonedSite -> removeClonedSite(event.site)
            is SettingsUiEvent.ValidateTorboxKey -> validateTorboxKey(event.apiKey)
            is SettingsUiEvent.StartTorboxAuth -> startTorboxAuth()
            is SettingsUiEvent.CompleteTorboxAuth -> completeTorboxAuth(event.token)
            is SettingsUiEvent.SetTorboxAuthError -> setTorboxAuthError(event.error)
            is SettingsUiEvent.CancelTorboxAuth -> cancelTorboxAuth()
            is SettingsUiEvent.DisconnectTorbox -> disconnectTorbox()
            is SettingsUiEvent.LoadIgnoredCfDomains -> loadIgnoredCfDomains()
            is SettingsUiEvent.RemoveIgnoredCfDomain -> removeIgnoredCfDomain(event.domain)
            is SettingsUiEvent.ClearAllIgnoredCfDomains -> clearAllIgnoredCfDomains()
            is SettingsUiEvent.TestAccountApiKey -> testAccountApiKey(event.idPrefix, event.apiKey)
            is SettingsUiEvent.ResetAccountApiTestStatus -> updateState { copy(testAccountApiStatus = null) }
            is SettingsUiEvent.LoadBackupsList -> refreshBackupsList()
            is SettingsUiEvent.OpenExportBackupDialog -> updateState { copy(backupState = backupState.copy(showExportDialog = true)) }
            is SettingsUiEvent.DismissExportBackupDialog -> updateState { copy(backupState = backupState.copy(showExportDialog = false)) }
            is SettingsUiEvent.UpdateExportOptions -> updateState { copy(backupState = backupState.copy(exportOptions = event.options)) }
            is SettingsUiEvent.CreateBackup -> createBackup(event.targetPath, event.options)
            is SettingsUiEvent.InspectBackupFile -> inspectBackupFile(event.filePath)
            is SettingsUiEvent.DismissRestoreDialog -> updateState {
                copy(backupState = backupState.copy(pendingRestoreInspection = null))
            }
            is SettingsUiEvent.UpdateRestoreOptions -> updateState { copy(backupState = backupState.copy(restoreOptions = event.options)) }
            is SettingsUiEvent.ConfirmRestoreBackup -> confirmRestoreBackup()
            is SettingsUiEvent.RestoreBackupArchive -> restoreBackupArchive(event.filePath, event.options)
        }
    }

    private suspend fun loadPersistedSettingsIntoState() {
        val initialDownloadPath = DesktopDataStore.getKey<String>(DesktopDataStore.PREF_DOWNLOAD_PATH)
            ?: DesktopDownloadManager.downloadsDir.absolutePath
        val initialScreenshotPath = DesktopDataStore.getKey<String>(PlayerConfig.PREF_SCREENSHOT_DIR)
            ?: PlatformPaths.screenshotsDir.absolutePath
        val cfEnabled = DesktopDataStore.getKey<Boolean>(DesktopDataStore.PREF_ALLOW_CF_BYPASS) ?: false
        val p2pEnabled = DesktopDataStore.getKey<Boolean>(DesktopDataStore.PREF_P2P_ENABLED) ?: false
        val audioNorm = DesktopDataStore.getKey<Boolean>(PlayerConfig.PREF_AUDIO_NORMALIZATION) ?: false
        val audioDelay = DesktopDataStore.getKey<Float>(PlayerConfig.PREF_AUDIO_DELAY) ?: 0f
        val downloadThreads = DesktopDataStore.getKey<Float>(DesktopDataStore.PREF_DOWNLOAD_THREADS) ?: 8f
        val maxConcurrent = DesktopDataStore.getKey<Float>(DesktopDataStore.PREF_DOWNLOAD_MAX_CONCURRENT) ?: 2f
        val subBold = DesktopDataStore.getKey<String>(PlayerConfig.PREF_SUB_BOLD) ?: "no"
        val subItalic = DesktopDataStore.getKey<String>(PlayerConfig.PREF_SUB_ITALIC) ?: "no"
        val subColor = DesktopDataStore.getKey<String>(PlayerConfig.PREF_SUB_COLOR) ?: "#FFFFFF"
        val subSize = DesktopDataStore.getKey<String>(PlayerConfig.PREF_SUB_SIZE) ?: "45"
        val subBg = DesktopDataStore.getKey<String>(PlayerConfig.PREF_SUB_BG) ?: "#00000000"
        val subFont = DesktopDataStore.getKey<String>(PlayerConfig.PREF_SUB_FONT) ?: "Inter"
        val subBorderColor = DesktopDataStore.getKey<String>(PlayerConfig.PREF_SUB_BORDER_COLOR) ?: "#000000"
        val subBorderSize = DesktopDataStore.getKey<String>(PlayerConfig.PREF_SUB_BORDER_SIZE) ?: "3"
        val subShadowColor = DesktopDataStore.getKey<String>(PlayerConfig.PREF_SUB_SHADOW_COLOR) ?: "#00000000"
        val subShadowOffset = DesktopDataStore.getKey<String>(PlayerConfig.PREF_SUB_SHADOW_OFFSET) ?: "0"
        val subBlur = DesktopDataStore.getKey<String>(PlayerConfig.PREF_SUB_BLUR) ?: "0"
        val subPos = DesktopDataStore.getKey<String>(PlayerConfig.PREF_SUB_POS) ?: "100"

        val subOverride = DesktopDataStore.getKey<Boolean>(PlayerConfig.PREF_ENABLE_SUB_OVERRIDE) ?: true
        val subCaptions = DesktopDataStore.getKey<Boolean>(PlayerConfig.PREF_SUB_REMOVE_CAPTIONS) ?: false
        val subBloat = DesktopDataStore.getKey<Boolean>(PlayerConfig.PREF_SUB_REMOVE_BLOAT) ?: true
        val subUppercase = DesktopDataStore.getKey<Boolean>(PlayerConfig.PREF_SUB_UPPERCASE) ?: false
        val subEnabled = DesktopDataStore.getKey<Boolean>(PlayerConfig.PREF_SUB_ENABLED) ?: true

        val dohProvider = DesktopDataStore.getKey<Int>(NetworkConfig.PREF_DOH_PROVIDER) ?: 0
        val torboxApiKey = DesktopDataStore.getKey<String>(DesktopDataStore.PREF_TORBOX_API_KEY) ?: ""
        val torboxUser = if (torboxApiKey.isNotBlank()) {
            try {
                TorboxApiClient.getUser(torboxApiKey)
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }

        val ignoredDomains = DesktopDataStore.getIgnoredCfDomains().toList().sorted()
        val clonedSitesList = SettingsCustomSitesEngine.loadClonedSitesList()

        updateState {
            copy(
                downloadPath = initialDownloadPath,
                screenshotPath = initialScreenshotPath,
                torboxAccountState = torboxAccountState.copy(user = torboxUser),
                ignoredCfDomains = ignoredDomains,
                clonedSites = clonedSitesList,
                booleanSettings = booleanSettings + mapOf(
                    DesktopDataStore.PREF_ALLOW_CF_BYPASS to cfEnabled,
                    DesktopDataStore.PREF_P2P_ENABLED to p2pEnabled,
                    PlayerConfig.PREF_AUDIO_NORMALIZATION to audioNorm,
                    PlayerConfig.PREF_ENABLE_SUB_OVERRIDE to subOverride,
                    PlayerConfig.PREF_SUB_REMOVE_CAPTIONS to subCaptions,
                    PlayerConfig.PREF_SUB_REMOVE_BLOAT to subBloat,
                    PlayerConfig.PREF_SUB_UPPERCASE to subUppercase,
                    PlayerConfig.PREF_SUB_ENABLED to subEnabled,
                ),
                intSettings = intSettings + mapOf(
                    NetworkConfig.PREF_DOH_PROVIDER to dohProvider,
                ),
                floatSettings = floatSettings + mapOf(
                    PlayerConfig.PREF_AUDIO_DELAY to audioDelay,
                    DesktopDataStore.PREF_DOWNLOAD_THREADS to downloadThreads,
                    DesktopDataStore.PREF_DOWNLOAD_MAX_CONCURRENT to maxConcurrent,
                ),
                stringSettings = stringSettings + mapOf(
                    PlayerConfig.PREF_SUB_BOLD to subBold,
                    PlayerConfig.PREF_SUB_ITALIC to subItalic,
                    PlayerConfig.PREF_SUB_COLOR to subColor,
                    PlayerConfig.PREF_SUB_SIZE to subSize,
                    PlayerConfig.PREF_SUB_BG to subBg,
                    PlayerConfig.PREF_SUB_FONT to subFont,
                    PlayerConfig.PREF_SUB_BORDER_COLOR to subBorderColor,
                    PlayerConfig.PREF_SUB_BORDER_SIZE to subBorderSize,
                    PlayerConfig.PREF_SUB_SHADOW_COLOR to subShadowColor,
                    PlayerConfig.PREF_SUB_SHADOW_OFFSET to subShadowOffset,
                    PlayerConfig.PREF_SUB_BLUR to subBlur,
                    PlayerConfig.PREF_SUB_POS to subPos,
                    NetworkConfig.PREF_CUSTOM_DOH_URL to (DesktopDataStore.getKey<String>(NetworkConfig.PREF_CUSTOM_DOH_URL) ?: ""),
                ),
            )
        }
    }

    private fun refreshBackupsList() {
        viewModelScope.launch(Dispatchers.IO) {
            val backups = runCatching { DesktopBackupEngine.listBackups() }.getOrDefault(emptyList())
            updateState {
                copy(backupState = backupState.copy(availableBackups = backups))
            }
        }
    }

    private fun createBackup(targetPath: String?, options: BackupOptions) {
        if (uiState.value.backupState.isCreatingBackup) return
        viewModelScope.launch(Dispatchers.IO) {
            updateState {
                copy(
                    backupState = backupState.copy(
                        isCreatingBackup = true,
                        showExportDialog = false,
                        lastStatusMessage = null,
                        lastStatusIsError = false,
                    ),
                )
            }
            try {
                val targetFile = if (!targetPath.isNullOrBlank()) {
                    val normalized = if (targetPath.endsWith(".${DesktopBackupEngine.BACKUP_EXTENSION}", ignoreCase = true)) {
                        targetPath
                    } else {
                        "$targetPath.${DesktopBackupEngine.BACKUP_EXTENSION}"
                    }
                    File(normalized)
                } else {
                    File(DesktopBackupEngine.getBackupsRootDir(), DesktopBackupEngine.defaultBackupFileName())
                }
                val inspection = DesktopBackupEngine.createBackup(targetFile, options)
                val msg = "Backup saved: ${File(inspection.filePath).name} (${inspection.manifest.counts.profiles} profiles, ${inspection.manifest.counts.bookmarks} bookmarks, ${inspection.manifest.counts.extensions} extensions)"
                updateState {
                    copy(
                        backupState = backupState.copy(
                            isCreatingBackup = false,
                            availableBackups = DesktopBackupEngine.listBackups(),
                            lastStatusMessage = msg,
                            lastStatusIsError = false,
                            lastRestoreWarnings = emptyList(),
                        ),
                    )
                }
                sendEffect(SettingsUiEffect.ShowToast(msg))
            } catch (e: Exception) {
                val msg = "Backup failed: ${e.message}"
                updateState {
                    copy(
                        backupState = backupState.copy(
                            isCreatingBackup = false,
                            lastStatusMessage = msg,
                            lastStatusIsError = true,
                        ),
                    )
                }
                sendEffect(SettingsUiEffect.ShowToast(msg, isError = true))
            }
        }
    }

    private fun inspectBackupFile(filePath: String) {
        if (uiState.value.backupState.isInspectingBackup || uiState.value.backupState.isRestoringBackup) return
        viewModelScope.launch(Dispatchers.IO) {
            updateState {
                copy(
                    backupState = backupState.copy(
                        isInspectingBackup = true,
                        lastStatusMessage = null,
                        lastStatusIsError = false,
                    ),
                )
            }
            try {
                val inspection = DesktopBackupEngine.inspectBackup(File(filePath))
                val initialRestoreOptions = RestoreOptions(
                    restoreDatabase = inspection.hasSqliteDatabase || inspection.hasPortableJson,
                    restoreCredentials = inspection.hasCredentialsInBackup,
                    restoreRepositories = inspection.hasRepositories,
                    restoreExtensionBinaries = inspection.extensions.any { it.binaryBundled },
                    restoreAssetsAndCookies = true,
                )
                updateState {
                    copy(
                        backupState = backupState.copy(
                            isInspectingBackup = false,
                            pendingRestoreInspection = inspection,
                            restoreOptions = initialRestoreOptions,
                        ),
                    )
                }
            } catch (e: Exception) {
                val msg = "Could not open backup: ${e.message}"
                updateState {
                    copy(
                        backupState = backupState.copy(
                            isInspectingBackup = false,
                            lastStatusMessage = msg,
                            lastStatusIsError = true,
                        ),
                    )
                }
                sendEffect(SettingsUiEffect.ShowToast(msg, isError = true))
            }
        }
    }

    private fun confirmRestoreBackup() {
        val inspection = uiState.value.backupState.pendingRestoreInspection ?: return
        val options = uiState.value.backupState.restoreOptions
        restoreBackupArchive(inspection.filePath, options)
    }

    private fun restoreBackupArchive(filePath: String, options: RestoreOptions) {
        if (uiState.value.backupState.isRestoringBackup) return
        viewModelScope.launch(Dispatchers.IO) {
            updateState {
                copy(
                    backupState = backupState.copy(
                        isRestoringBackup = true,
                        pendingRestoreInspection = null,
                        lastStatusMessage = null,
                        lastStatusIsError = false,
                    ),
                )
            }
            val result = DesktopBackupEngine.restoreBackup(File(filePath), options)
            if (result.success) {
                loadPersistedSettingsIntoState()
            }
            updateState {
                copy(
                    backupState = backupState.copy(
                        isRestoringBackup = false,
                        availableBackups = runCatching { DesktopBackupEngine.listBackups() }.getOrDefault(backupState.availableBackups),
                        lastStatusMessage = result.message,
                        lastStatusIsError = !result.success,
                        lastRestoreWarnings = result.warnings,
                    ),
                )
            }
            sendEffect(SettingsUiEffect.ShowToast(result.message, isError = !result.success))
            refreshStorageMetrics()
            refreshClearanceCookies()
        }
    }

    private fun refreshStorageMetrics() {
        storageMetricsJob?.cancel()
        storageMetricsJob = viewModelScope.launch(Dispatchers.IO) {
            updateState { copy(isRefreshingStorage = true) }
            val metrics = SettingsStorageEngine.calculateStorageMetrics()
            updateState {
                copy(
                    storageMetrics = metrics,
                    isRefreshingStorage = false,
                )
            }
        }
    }

    private fun clearCache(type: StorageCacheType) {
        viewModelScope.launch(Dispatchers.IO) {
            val (success, message) = SettingsStorageEngine.clearCache(type)
            sendEffect(SettingsUiEffect.ShowToast(message, isError = !success))
            refreshStorageMetrics()
        }
    }

    private fun vacuumDatabase() {
        if (uiState.value.isOptimizingDb) return
        viewModelScope.launch(Dispatchers.IO) {
            updateState { copy(isOptimizingDb = true) }
            val (success, message) = SettingsStorageEngine.vacuumDatabase()
            updateState { copy(isOptimizingDb = false) }
            sendEffect(SettingsUiEffect.ShowToast(message, isError = !success))
            refreshStorageMetrics()
        }
    }

    private fun resetSettingsToDefault() {
        viewModelScope.launch(Dispatchers.IO) {
            val (success, message) = SettingsStorageEngine.resetSettingsToDefault()
            if (success) {
                updateState {
                    copy(
                        stringSettings = emptyMap(),
                        booleanSettings = emptyMap(),
                        intSettings = emptyMap(),
                        floatSettings = emptyMap(),
                    )
                }
            }
            sendEffect(SettingsUiEffect.ShowToast(message, isError = !success))
            refreshStorageMetrics()
        }
    }

    private fun checkUpdates(force: Boolean) {
        if (uiState.value.updateCheckState.isChecking) return
        viewModelScope.launch(Dispatchers.IO) {
            updateState { copy(updateCheckState = updateCheckState.copy(isChecking = true, showCheckedFeedback = false)) }
            try {
                UnifiedUpdateManager.checkAllUpdates(force = force)
                val hasNoUpdates = UnifiedUpdateManager.availableUpdates.value.isEmpty()
                updateState {
                    copy(
                        updateCheckState = updateCheckState.copy(
                            isChecking = false,
                            showCheckedFeedback = hasNoUpdates,
                        ),
                    )
                }
            } catch (e: Exception) {
                updateState { copy(updateCheckState = updateCheckState.copy(isChecking = false)) }
                sendEffect(SettingsUiEffect.ShowToast("Update check failed: ${e.message}", isError = true))
            }
        }
    }

    private fun refreshTorrServerStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            val installed = DesktopTorrentEngine.binary.isInstalled()
            val size = DesktopTorrentEngine.binary.getFileSizeMB()
            updateState {
                copy(
                    engineState = engineState.copy(
                        isInstalled = installed,
                        fileSizeMB = size,
                    ),
                )
            }
        }
    }

    private fun checkTorrServerUpdates() {
        if (uiState.value.engineState.isCheckingUpdates) return
        viewModelScope.launch(Dispatchers.IO) {
            updateState { copy(engineState = engineState.copy(isCheckingUpdates = true, updateFeedback = null)) }
            try {
                val update = UnifiedUpdateManager.checkTorrServerUpdate(force = true)
                if (update != null) {
                    UnifiedUpdateManager.showDialogForUpdate(update)
                    updateState { copy(engineState = engineState.copy(isCheckingUpdates = false)) }
                } else {
                    val version = UnifiedUpdateManager.getTorrServerInstalledVersion()
                    updateState {
                        copy(
                            engineState = engineState.copy(
                                isCheckingUpdates = false,
                                updateFeedback = "✓ TorrServer is up to date ($version)",
                            ),
                        )
                    }
                }
            } catch (e: Exception) {
                updateState {
                    copy(
                        engineState = engineState.copy(
                            isCheckingUpdates = false,
                            updateFeedback = "Failed to check update: ${e.message}",
                        ),
                    )
                }
            }
        }
    }

    private fun deleteTorrServerBinary() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                DesktopTorrentEngine.binary.deleteBinary()
                refreshTorrServerStatus()
                sendEffect(SettingsUiEffect.ShowToast("Torrent engine uninstalled"))
            } catch (e: Exception) {
                sendEffect(SettingsUiEffect.ShowToast("Failed to delete binary: ${e.message}", isError = true))
            }
        }
    }

    private fun refreshClearanceCookies() {
        viewModelScope.launch(Dispatchers.IO) {
            val cookies = CloudflareKiller.getAllStoredCookies()
            updateState { copy(clearanceState = clearanceState.copy(cookiesMap = cookies)) }
        }
    }

    private fun clearDomainCookies(domain: String) {
        viewModelScope.launch(Dispatchers.IO) {
            CloudflareKiller.clearClearanceForDomain(domain)
            refreshClearanceCookies()
            sendEffect(SettingsUiEffect.ShowToast("Cookies cleared for $domain"))
        }
    }

    private fun clearAllClearanceCookies() {
        viewModelScope.launch(Dispatchers.IO) {
            CloudflareKiller.clearAllClearance()
            refreshClearanceCookies()
            sendEffect(SettingsUiEffect.ShowToast("All cookies and clearance tokens cleared"))
        }
    }

    private fun launchManualClearance(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            updateState { copy(clearanceState = clearanceState.copy(isLaunchingManualBypass = true)) }
            sendEffect(SettingsUiEffect.ShowToast("Opening browser to solve clearance..."))
            val host = try {
                URI(url).host.orEmpty()
            } catch (e: Exception) {
                AppLogger.w("Settings: Invalid URL for manual clearance '$url': ${e.message}")
                ""
            }
            val success = if (WindowsWebView2Runtime.isAvailable && host.isNotBlank()) {
                CloudflareKiller.solveWithEmbeddedWebView(url, host)
            } else {
                false
            }
            updateState { copy(clearanceState = clearanceState.copy(isLaunchingManualBypass = false)) }
            if (success) {
                sendEffect(SettingsUiEffect.ShowToast("Clearance acquired and saved!"))
                refreshClearanceCookies()
            } else {
                sendEffect(SettingsUiEffect.ShowToast("Manual clearance window closed without clearance.", isError = true))
            }
        }
    }

    private fun observeTorrServerDownloadTask() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                AppDownloadManager.tasks.collect { tasks ->
                    val torrTask = tasks.firstOrNull { it.id == "torrserver" }
                    if (torrTask?.status == TaskStatus.COMPLETED || torrTask == null) {
                        refreshTorrServerStatus()
                    }
                }
            } catch (e: Exception) {
                AppLogger.e("SettingsViewModel", "Failed to observe TorrServer download task", e)
            }
        }
    }

    private fun unlockDeveloperMode(password: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                DeveloperModeManager.setEnabled(true)
                updateState { copy(isDevModeEnabled = true, devModeError = null) }
            } catch (e: Exception) {
                AppLogger.e("SettingsViewModel", "Failed to enable developer mode", e)
            }
        }
    }

    private fun setDeveloperMode(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                DeveloperModeManager.setEnabled(enabled)
                updateState { copy(isDevModeEnabled = enabled, devModeError = null) }
            } catch (e: Exception) {
                AppLogger.e("SettingsViewModel", "Failed to set developer mode", e)
            }
        }
    }

    private fun startProviderTests() {
        val providerNames = diagnosticEngine.startProviderTests(
            onResult = { apiName, result ->
                updateState {
                    val newResults = providerTestState.results + (apiName to result)
                    val passed = if (result.success) providerTestState.passed + 1 else providerTestState.passed
                    val failed = if (!result.success) providerTestState.failed + 1 else providerTestState.failed
                    val isRunning = newResults.size < providerTestState.total
                    copy(
                        providerTestState = providerTestState.copy(
                            results = newResults,
                            passed = passed,
                            failed = failed,
                            isRunning = isRunning,
                            currentlyTestingProvider = if (isRunning) apiName else null,
                        ),
                    )
                }
            },
            onFinished = {
                updateState {
                    copy(
                        providerTestState = providerTestState.copy(
                            isRunning = false,
                            currentlyTestingProvider = null,
                        ),
                    )
                }
            },
        )
        updateState {
            copy(
                providerTestState = ProviderTestReportState(
                    isRunning = true,
                    results = emptyMap(),
                    passed = 0,
                    failed = 0,
                    total = providerNames.size,
                    filter = providerTestState.filter,
                ),
            )
        }
    }

    private fun cancelProviderTests() {
        diagnosticEngine.cancelProviderTests()
        updateState { copy(providerTestState = providerTestState.copy(isRunning = false, currentlyTestingProvider = null)) }
    }

    private fun testSingleProvider(apiName: String) {
        val target = APIHolder.allProviders.firstOrNull { it.name.equals(apiName, ignoreCase = true) } ?: return
        updateState {
            copy(
                providerTestState = providerTestState.copy(
                    currentlyTestingProvider = target.name,
                ),
            )
        }
        diagnosticEngine.testSingleProvider(
            apiName = apiName,
            onResult = { api, result ->
                updateState {
                    val currentResults = providerTestState.results.toMutableMap()
                    currentResults[api] = result
                    val passedCount = currentResults.values.count { it.success }
                    val failedCount = currentResults.values.count { !it.success }
                    copy(
                        providerTestState = providerTestState.copy(
                            results = currentResults,
                            passed = passedCount,
                            failed = failedCount,
                        ),
                    )
                }
            },
            onFinished = {
                updateState {
                    copy(
                        providerTestState = providerTestState.copy(
                            currentlyTestingProvider = if (providerTestState.currentlyTestingProvider == target.name) null else providerTestState.currentlyTestingProvider,
                        ),
                    )
                }
            },
        )
    }

    private fun runNetworkDiagnostics() {
        updateState {
            copy(
                diagnosticsState = diagnosticsState.copy(
                    isNetworkTesting = true,
                    results = emptyList(),
                    currentTest = "Starting...",
                ),
            )
        }
        diagnosticEngine.runNetworkDiagnostics(
            onProgress = { result ->
                updateState {
                    copy(
                        diagnosticsState = diagnosticsState.copy(
                            results = diagnosticsState.results + result,
                            currentTest = result.name,
                        ),
                    )
                }
            },
            onComplete = { formattedTime ->
                updateState {
                    copy(
                        diagnosticsState = diagnosticsState.copy(
                            isNetworkTesting = false,
                            currentTest = "",
                            lastRunTime = formattedTime,
                        ),
                    )
                }
            },
        )
    }

    private fun runMetaDiagnostics() {
        updateState {
            copy(
                diagnosticsState = diagnosticsState.copy(
                    isMetaTesting = true,
                    results = diagnosticsState.results.filter { !it.name.startsWith("Provider:") },
                    currentTest = "Starting metadata tests...",
                ),
            )
        }
        diagnosticEngine.runMetaDiagnostics(
            onProgress = { result ->
                updateState {
                    copy(
                        diagnosticsState = diagnosticsState.copy(
                            results = diagnosticsState.results + result,
                            currentTest = result.name,
                        ),
                    )
                }
            },
            onComplete = { formattedTime ->
                updateState {
                    copy(
                        diagnosticsState = diagnosticsState.copy(
                            isMetaTesting = false,
                            currentTest = "",
                            lastRunTime = formattedTime,
                        ),
                    )
                }
            },
        )
    }

    private fun testDns(provider: DohProvider?, customEndpoint: String?) {
        updateState { copy(dnsTestState = DnsTestState(isTesting = true, result = null)) }
        val currentProvider = provider
            ?: uiState.value.intSettings[NetworkConfig.PREF_DOH_PROVIDER]?.let { idx -> DohProvider.entries.getOrNull(idx) }
            ?: DesktopDataStore.getKey<Int>(NetworkConfig.PREF_DOH_PROVIDER)?.let { idx -> DohProvider.entries.getOrNull(idx) }
            ?: DohProvider.NONE

        val endpoint = customEndpoint
            ?: uiState.value.stringSettings[NetworkConfig.PREF_CUSTOM_DOH_URL]
            ?: DesktopDataStore.getKey<String>(NetworkConfig.PREF_CUSTOM_DOH_URL)

        diagnosticEngine.testDns(currentProvider, endpoint) { result ->
            updateState { copy(dnsTestState = DnsTestState(isTesting = false, result = result)) }
        }
    }

    private fun updateDownloadPath(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                File(path).mkdirs()
                DesktopDataStore.setKey(DesktopDataStore.PREF_DOWNLOAD_PATH, path)
                updateState { copy(downloadPath = path) }
                sendEffect(SettingsUiEffect.ShowToast("Download directory updated"))
            } catch (e: Exception) {
                AppLogger.e("DownloadSettings", "Failed to update download directory", e)
                sendEffect(SettingsUiEffect.ShowToast("Failed to set directory: ${e.message}", isError = true))
            }
        }
    }

    private fun updateScreenshotPath(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                File(path).mkdirs()
                DesktopDataStore.setKey(PlayerConfig.PREF_SCREENSHOT_DIR, path)
                updateState { copy(screenshotPath = path) }
                sendEffect(SettingsUiEffect.ShowToast("Screenshot directory updated"))
            } catch (e: Exception) {
                AppLogger.e("ScreenshotSettings", "Failed to update screenshot directory", e)
                sendEffect(SettingsUiEffect.ShowToast("Failed to set directory: ${e.message}", isError = true))
            }
        }
    }

    private fun addClonedSite(site: CustomSite) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val (newList, jsonString) = SettingsCustomSitesEngine.addClonedSite(site)
                updateState { copy(stringSettings = stringSettings + (PreferenceKeys.USER_PROVIDER_API to jsonString), clonedSites = newList) }
                sendEffect(SettingsUiEffect.ShowToast("Added cloned site '${site.name}'", isError = false))
            } catch (e: Exception) {
                sendEffect(SettingsUiEffect.ShowToast("Failed to add cloned site: ${e.message}", isError = true))
            }
        }
    }

    private fun removeClonedSite(site: CustomSite) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val (newList, jsonString) = SettingsCustomSitesEngine.removeClonedSite(site)
                updateState { copy(stringSettings = stringSettings + (PreferenceKeys.USER_PROVIDER_API to jsonString), clonedSites = newList) }
                sendEffect(SettingsUiEffect.ShowToast("Removed cloned site '${site.name}'", isError = false))
            } catch (e: Exception) {
                sendEffect(SettingsUiEffect.ShowToast("Failed to remove cloned site: ${e.message}", isError = true))
            }
        }
    }

    private fun validateTorboxKey(apiKey: String) {
        debridCoordinator.validateTorboxKey(
            apiKey = apiKey,
            onValidating = {
                updateState {
                    copy(
                        torboxAccountState = torboxAccountState.copy(
                            isValidatingKey = true,
                            feedbackMessage = null,
                        ),
                    )
                }
            },
            onResult = { user, feedback, isError ->
                updateState {
                    copy(
                        stringSettings = if (user != null) stringSettings + (DesktopDataStore.PREF_TORBOX_API_KEY to apiKey.trim()) else stringSettings,
                        booleanSettings = if (user != null) booleanSettings + (DesktopDataStore.PREF_TORBOX_ENABLED to true) else booleanSettings,
                        torboxAccountState = torboxAccountState.copy(
                            user = user,
                            isValidatingKey = false,
                            feedbackMessage = feedback,
                            feedbackIsError = isError,
                        ),
                    )
                }
            },
        )
    }

    private fun startTorboxAuth() {
        debridCoordinator.startTorboxAuth(
            onStarting = {
                updateState {
                    copy(
                        torboxAccountState = torboxAccountState.copy(
                            isStartingAuth = true,
                            feedbackMessage = null,
                        ),
                    )
                }
            },
            onResult = { auth, error ->
                updateState {
                    copy(
                        torboxAccountState = torboxAccountState.copy(
                            isStartingAuth = false,
                            activeAuth = auth,
                            feedbackMessage = error,
                            feedbackIsError = error != null,
                        ),
                    )
                }
            },
        )
    }

    private fun completeTorboxAuth(token: String) {
        debridCoordinator.completeTorboxAuth(token) { user ->
            updateState {
                copy(
                    stringSettings = stringSettings + (DesktopDataStore.PREF_TORBOX_API_KEY to token),
                    booleanSettings = booleanSettings + (DesktopDataStore.PREF_TORBOX_ENABLED to true),
                    torboxAccountState = torboxAccountState.copy(
                        user = user,
                        activeAuth = null,
                        feedbackMessage = "Successfully connected to Torbox via WebView!",
                        feedbackIsError = false,
                    ),
                )
            }
        }
    }

    private fun setTorboxAuthError(error: String) {
        updateState {
            copy(
                torboxAccountState = torboxAccountState.copy(
                    activeAuth = null,
                    feedbackMessage = error,
                    feedbackIsError = true,
                ),
            )
        }
    }

    private fun cancelTorboxAuth() {
        updateState {
            copy(
                torboxAccountState = torboxAccountState.copy(
                    activeAuth = null,
                ),
            )
        }
    }

    private fun disconnectTorbox() {
        debridCoordinator.disconnectTorbox {
            updateState {
                copy(
                    stringSettings = stringSettings + (DesktopDataStore.PREF_TORBOX_API_KEY to ""),
                    torboxAccountState = torboxAccountState.copy(
                        user = null,
                        activeAuth = null,
                        feedbackMessage = "Disconnected from Torbox",
                        feedbackIsError = false,
                    ),
                )
            }
        }
    }

    private fun loadIgnoredCfDomains() {
        viewModelScope.launch(Dispatchers.IO) {
            val updated = DesktopDataStore.getIgnoredCfDomains().toList().sorted()
            updateState { copy(ignoredCfDomains = updated) }
        }
    }

    private fun removeIgnoredCfDomain(domain: String) {
        viewModelScope.launch(Dispatchers.IO) {
            DesktopDataStore.setDomainCfIgnored(domain, false)
            val updated = DesktopDataStore.getIgnoredCfDomains().toList().sorted()
            updateState { copy(ignoredCfDomains = updated) }
        }
    }

    private fun clearAllIgnoredCfDomains() {
        viewModelScope.launch(Dispatchers.IO) {
            DesktopDataStore.getIgnoredCfDomains().let { current ->
                current.forEach { DesktopDataStore.setDomainCfIgnored(it, false) }
                updateState { copy(ignoredCfDomains = emptyList()) }
            }
        }
    }

    private fun testAccountApiKey(idPrefix: String, apiKey: String) {
        updateState { copy(isTestingAccountApi = true, testAccountApiStatus = null) }
        diagnosticEngine.testAccountApiKey(idPrefix, apiKey) { success, message ->
            updateState {
                copy(
                    isTestingAccountApi = false,
                    testAccountApiStatus = success to message,
                )
            }
        }
    }

    override fun dispose() {
        diagnosticEngine.cancelAll()
        debridCoordinator.cancel()
        storageMetricsJob?.cancel()
        storageMetricsJob = null
        super.dispose()
    }
}
