package com.lagradost.cloudstream3.desktop.ui.screens.settings

import com.lagradost.cloudstream3.desktop.debrid.torbox.TorboxApiClient
import com.lagradost.cloudstream3.desktop.debrid.torbox.TorboxDeviceAuthData
import com.lagradost.cloudstream3.desktop.debrid.torbox.TorboxUserData
import com.lagradost.common.storage.DesktopDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class SettingsDebridCoordinator(
    private val scope: CoroutineScope,
) {
    private var torboxValidationJob: Job? = null

    fun cancel() {
        torboxValidationJob?.cancel()
        torboxValidationJob = null
    }

    fun validateTorboxKey(
        apiKey: String,
        onValidating: () -> Unit,
        onResult: (user: TorboxUserData?, feedback: String, isError: Boolean) -> Unit,
    ) {
        val clean = apiKey.trim()
        if (clean.isBlank()) {
            onResult(null, "API Key cannot be blank", true)
            return
        }
        torboxValidationJob?.cancel()
        onValidating()
        torboxValidationJob = scope.launch(Dispatchers.IO) {
            val user = TorboxApiClient.getUser(clean)
            if (user != null) {
                DesktopDataStore.setKey(DesktopDataStore.PREF_TORBOX_API_KEY, clean)
                DesktopDataStore.setKey(DesktopDataStore.PREF_TORBOX_ENABLED, true)
                onResult(user, "Connected successfully as ${user.email ?: "user"} (${user.planName})", false)
            } else {
                onResult(null, "Invalid API Key or server unreachable", true)
            }
        }
    }

    fun startTorboxAuth(
        onStarting: () -> Unit,
        onResult: (auth: TorboxDeviceAuthData?, error: String?) -> Unit,
    ) {
        onStarting()
        scope.launch(Dispatchers.IO) {
            val auth = TorboxApiClient.startDeviceAuthorization()
            if (auth != null) {
                onResult(auth, null)
            } else {
                onResult(null, "Failed to start Torbox authorization. Check internet connection.")
            }
        }
    }

    fun completeTorboxAuth(
        token: String,
        onComplete: (user: TorboxUserData?) -> Unit,
    ) {
        scope.launch(Dispatchers.IO) {
            DesktopDataStore.setKey(DesktopDataStore.PREF_TORBOX_API_KEY, token)
            DesktopDataStore.setKey(DesktopDataStore.PREF_TORBOX_ENABLED, true)
            val user = TorboxApiClient.getUser(token)
            onComplete(user)
        }
    }

    fun disconnectTorbox(onComplete: () -> Unit) {
        scope.launch(Dispatchers.IO) {
            DesktopDataStore.removeKey(DesktopDataStore.PREF_TORBOX_API_KEY)
            onComplete()
        }
    }
}
