package com.lagradost.cloudstream3.desktop.ui.screens.player

import com.lagradost.cloudstream3.desktop.player.webview.NativePlayerBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PlayerSleepTimer(
    private val scope: CoroutineScope,
    private val onTick: (remainingSeconds: Int) -> Unit,
    private val onModeChanged: (mode: String, remainingSeconds: Int?) -> Unit,
    private val onTimerExpired: () -> Unit,
) {
    private var sleepTimerJob: Job? = null

    fun cancel() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
    }

    fun setSleepTimer(modeStr: String) {
        cancel()
        val cleanMode = modeStr.lowercase().trim()

        when {
            cleanMode == "off" || cleanMode == "0" -> {
                onModeChanged("OFF", null)
                NativePlayerBridge.safePostMessage("""{"type":"sync_sleep_timer","value":"off"}""")
            }
            cleanMode == "end_of_episode" || cleanMode == "end" -> {
                onModeChanged("END_OF_EPISODE", null)
                NativePlayerBridge.safePostMessage("""{"type":"sync_sleep_timer","value":"end_of_episode"}""")
            }
            else -> {
                val mins = cleanMode.toIntOrNull() ?: 30
                val totalSec = mins * 60
                onModeChanged("TIMED", totalSec)
                NativePlayerBridge.safePostMessage("""{"type":"sync_sleep_timer","value":"$mins"}""")
                sleepTimerJob = scope.launch(Dispatchers.Main) {
                    var remaining = totalSec
                    while (remaining > 0 && isActive) {
                        delay(1000L)
                        remaining--
                        onTick(remaining)
                    }
                    if (isActive && remaining <= 0) {
                        onModeChanged("OFF", null)
                        NativePlayerBridge.safePostMessage("""{"type":"sync_sleep_timer","value":"off"}""")
                        NativePlayerBridge.safePostMessage("""{"type":"show_toast","message":"Playback paused: Sleep timer expired"}""")
                        onTimerExpired()
                    }
                }
            }
        }
    }
}
