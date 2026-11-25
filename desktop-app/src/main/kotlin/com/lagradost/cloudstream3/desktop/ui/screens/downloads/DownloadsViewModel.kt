package com.lagradost.cloudstream3.desktop.ui.screens.downloads

import com.lagradost.cloudstream3.desktop.downloader.DesktopDownloadManager
import com.lagradost.cloudstream3.desktop.downloader.DownloadTask
import com.lagradost.cloudstream3.desktop.ui.base.BaseMviViewModel
import com.lagradost.cloudstream3.desktop.ui.components.AppToastManager
import com.lagradost.cloudstream3.desktop.ui.screens.downloads.contract.DownloadsTab
import com.lagradost.cloudstream3.desktop.ui.screens.downloads.contract.DownloadsUiEffect
import com.lagradost.cloudstream3.desktop.ui.screens.downloads.contract.DownloadsUiEvent
import com.lagradost.cloudstream3.desktop.ui.screens.downloads.contract.DownloadsUiState
import com.lagradost.common.storage.DesktopDataStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

typealias DownloadsTab = com.lagradost.cloudstream3.desktop.ui.screens.downloads.contract.DownloadsTab
typealias DownloadsUiState = com.lagradost.cloudstream3.desktop.ui.screens.downloads.contract.DownloadsUiState

class DownloadsViewModel(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val readFloatPref: (String) -> Float? = { key -> DesktopDataStore.getKey<Float>(key) },
    private val writeFloatPref: (String, Float) -> Unit = { key, value -> DesktopDataStore.setKey(key, value) },
    private val writeStringPref: (String, String) -> Unit = { key, value -> DesktopDataStore.setKey(key, value) },
    private val resolveDownloadPath: () -> String = { DesktopDownloadManager.downloadsDir.absolutePath },
    private val dispatchDownloadQueue: () -> Unit = { DesktopDownloadManager.dispatchNextTasks() },
    initialDownloadPath: String = DesktopDownloadManager.downloadsDir.absolutePath,
    observeDownloadManager: Boolean = true,
) : BaseMviViewModel<DownloadsUiState, DownloadsUiEvent, DownloadsUiEffect>(
    initialState = DownloadsUiState(
        downloadPath = initialDownloadPath,
        downloadThreads = 8f,
        maxConcurrent = 2f,
    ),
) {
    private val prefIoMutex = Mutex()
    private val threadsEpoch = AtomicLong(0L)
    private val maxConcurrentEpoch = AtomicLong(0L)
    private val pathEpoch = AtomicLong(0L)

    init {
        loadDownloadPreferences()
        if (observeDownloadManager) {
            viewModelScope.launch {
                DesktopDownloadManager.tasks.collect { taskList ->
                    updateState { copy(tasks = taskList) }
                }
            }
            viewModelScope.launch {
                DesktopDownloadManager.activeSpeed.collect { speed ->
                    updateState { copy(totalActiveSpeed = speed) }
                }
            }
        }
    }

    override fun handleEvent(event: DownloadsUiEvent) {
        when (event) {
            is DownloadsUiEvent.SelectTab -> updateState { copy(activeTab = event.tab) }
            is DownloadsUiEvent.UpdateSearchQuery -> updateState { copy(searchQuery = event.query) }
            is DownloadsUiEvent.PauseTask -> DesktopDownloadManager.pause(event.taskId)
            is DownloadsUiEvent.ResumeTask -> DesktopDownloadManager.resume(event.taskId)
            is DownloadsUiEvent.CancelTask -> DesktopDownloadManager.cancel(event.taskId)
            is DownloadsUiEvent.DeleteTask -> deleteTaskInternal(event.task, event.deleteFile)
            is DownloadsUiEvent.DeleteShow -> deleteShowInternal(event.showName, event.deleteFiles)
            is DownloadsUiEvent.PauseAll -> DesktopDownloadManager.pauseAll()
            is DownloadsUiEvent.ResumeAll -> DesktopDownloadManager.resumeAll()
            is DownloadsUiEvent.CancelAll -> DesktopDownloadManager.cancelAll()
            is DownloadsUiEvent.CleanOrphanedJunk -> cleanOrphanedJunkInternal()
            is DownloadsUiEvent.DismissJunkMessage -> updateState { copy(reclaimedBytesMessage = null) }
            is DownloadsUiEvent.ToggleSettingsDialog -> toggleSettings(event.open)
            is DownloadsUiEvent.UpdateDownloadPath -> updateDownloadPathInternal(event.path)
            is DownloadsUiEvent.UpdateDownloadThreads -> updateDownloadThreadsInternal(event.threads)
            is DownloadsUiEvent.UpdateMaxConcurrent -> updateMaxConcurrentInternal(event.max)
        }
    }

    private fun deleteTaskInternal(task: DownloadTask, deleteFile: Boolean) {
        viewModelScope.launch(ioDispatcher) {
            val ok = DesktopDownloadManager.delete(task.id, deleteFile)
            val msg = if (ok) "Deleted '${task.displayTitle}'" else "Removed '${task.displayTitle}' from downloads. File is currently locked by a player."
            sendEffect(DownloadsUiEffect.ShowToast(msg, isError = !ok))
            withContext(Dispatchers.Main) {
                if (ok) {
                    AppToastManager.showInfo(msg)
                } else {
                    AppToastManager.showWarning(msg)
                }
            }
        }
    }

    private fun deleteShowInternal(showName: String, deleteFiles: Boolean) {
        viewModelScope.launch(ioDispatcher) {
            val showTasks = uiState.value.tasks.filter { it.showName.equals(showName, ignoreCase = true) }
            var deletedCount = 0
            var totalBytesReclaimed = 0L
            for (t in showTasks) {
                totalBytesReclaimed += t.downloadedBytes
                val ok = DesktopDownloadManager.delete(t.id, deleteFiles)
                if (ok) deletedCount++
            }
            if (deletedCount > 0) {
                val msg = "Deleted $deletedCount episodes of '$showName' (${formatSize(totalBytesReclaimed)})"
                sendEffect(DownloadsUiEffect.ShowToast(msg))
                withContext(Dispatchers.Main) {
                    AppToastManager.showInfo(msg)
                }
            }
        }
    }

    private fun cleanOrphanedJunkInternal() {
        viewModelScope.launch(ioDispatcher) {
            val reclaimed = DesktopDownloadManager.cleanOrphanedTempFiles()
            val msg = if (reclaimed > 0) {
                "Successfully purged ${formatSize(reclaimed)} of orphaned temp chunks and dangling files."
            } else {
                "No orphaned temporary files found. Download storage is 100% clean."
            }
            updateState { copy(reclaimedBytesMessage = msg) }
        }
    }

    private fun loadDownloadPreferences() {
        val loadThreadsEpoch = threadsEpoch.get()
        val loadMaxEpoch = maxConcurrentEpoch.get()
        viewModelScope.launch(ioDispatcher) {
            val (loadedThreads, loadedMaxConcurrent) = prefIoMutex.withLock {
                val t = readFloatPref(DesktopDataStore.PREF_DOWNLOAD_THREADS) ?: 8f
                val m = readFloatPref(DesktopDataStore.PREF_DOWNLOAD_MAX_CONCURRENT) ?: 2f
                t to m
            }
            // Guard against overwriting newer user edits completed while preference I/O was in flight
            updateState {
                copy(
                    downloadThreads = if (threadsEpoch.get() == loadThreadsEpoch) loadedThreads else this.downloadThreads,
                    maxConcurrent = if (maxConcurrentEpoch.get() == loadMaxEpoch) loadedMaxConcurrent else this.maxConcurrent,
                )
            }
        }
    }

    private fun toggleSettings(open: Boolean) {
        if (open) {
            val openPathEpoch = pathEpoch.get()
            val currentPath = resolveDownloadPath()
            updateState {
                copy(
                    isSettingsOpen = true,
                    downloadPath = if (pathEpoch.get() == openPathEpoch) currentPath else downloadPath,
                )
            }
            loadDownloadPreferences()
        } else {
            updateState { copy(isSettingsOpen = false) }
        }
    }

    private fun updateDownloadPathInternal(newPath: String) {
        val epoch = pathEpoch.incrementAndGet()
        updateState { copy(downloadPath = newPath) }
        viewModelScope.launch(ioDispatcher) {
            prefIoMutex.withLock {
                if (pathEpoch.get() == epoch) {
                    writeStringPref(DesktopDataStore.PREF_DOWNLOAD_PATH, newPath)
                }
            }
        }
    }

    private fun updateDownloadThreadsInternal(threads: Float) {
        val epoch = threadsEpoch.incrementAndGet()
        updateState { copy(downloadThreads = threads) }
        viewModelScope.launch(ioDispatcher) {
            prefIoMutex.withLock {
                if (threadsEpoch.get() == epoch) {
                    writeFloatPref(DesktopDataStore.PREF_DOWNLOAD_THREADS, threads)
                }
            }
        }
    }

    private fun updateMaxConcurrentInternal(max: Float) {
        val epoch = maxConcurrentEpoch.incrementAndGet()
        updateState { copy(maxConcurrent = max) }
        viewModelScope.launch(ioDispatcher) {
            prefIoMutex.withLock {
                if (maxConcurrentEpoch.get() == epoch) {
                    writeFloatPref(DesktopDataStore.PREF_DOWNLOAD_MAX_CONCURRENT, max)
                }
            }
            dispatchDownloadQueue()
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1f MB".format(mb)
        val gb = mb / 1024.0
        return "%.2f GB".format(gb)
    }

    override fun dispose() {
        super.dispose()
    }
}
