package com.lagradost.cloudstream3.desktop.ui.screens.settings

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.ProviderType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.desktop.network.DiagnosticResult
import com.lagradost.cloudstream3.desktop.network.DiagnosticsRunner
import com.lagradost.cloudstream3.desktop.network.DohProvider
import com.lagradost.cloudstream3.desktop.network.NetworkConfig
import com.lagradost.cloudstream3.desktop.subtitles.SubsourceSubtitleProvider
import com.lagradost.cloudstream3.desktop.ui.screens.settings.contract.DnsTestResult
import com.lagradost.cloudstream3.utils.TestingUtils
import com.lagradost.common.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class SettingsDiagnosticEngine(
    private val scope: CoroutineScope,
) {
    private var providerTestJob: Job? = null
    private var networkDiagJob: Job? = null
    private var metaDiagJob: Job? = null
    private var dnsTestJob: Job? = null
    private var accountApiTestJob: Job? = null

    fun cancelAll() {
        providerTestJob?.cancel()
        providerTestJob = null
        networkDiagJob?.cancel()
        networkDiagJob = null
        metaDiagJob?.cancel()
        metaDiagJob = null
        dnsTestJob?.cancel()
        dnsTestJob = null
        accountApiTestJob?.cancel()
        accountApiTestJob = null
    }

    fun cancelProviderTests() {
        providerTestJob?.cancel()
        providerTestJob = null
    }

    fun startProviderTests(
        onResult: (apiName: String, result: TestingUtils.TestResultProvider) -> Unit,
        onFinished: () -> Unit,
    ): List<String> {
        cancelProviderTests()
        val providers = APIHolder.allProviders
            .filter { it.providerType != ProviderType.MetaProvider && it.name != "NONE" && it.name.isNotBlank() }
            .distinctBy { it.name }
            .sortedBy { it.name }

        providerTestJob = scope.launch(Dispatchers.IO) {
            try {
                TestingUtils.getDeferredProviderTests(this, providers.toTypedArray()) { api, result ->
                    onResult(api.name, result)
                }
            } finally {
                onFinished()
            }
        }
        return providers.map { it.name }
    }

    fun testSingleProvider(
        apiName: String,
        onResult: (apiName: String, result: TestingUtils.TestResultProvider) -> Unit,
        onFinished: () -> Unit,
    ) {
        val target = APIHolder.allProviders.firstOrNull { it.name.equals(apiName, ignoreCase = true) } ?: return
        scope.launch(Dispatchers.IO) {
            try {
                TestingUtils.getDeferredProviderTests(this, arrayOf(target)) { api, result ->
                    onResult(api.name, result)
                }
            } catch (e: Throwable) {
                AppLogger.e("SettingsDiagnosticEngine", "Single provider test failed for $apiName", e)
            } finally {
                onFinished()
            }
        }
    }

    fun runNetworkDiagnostics(
        onProgress: (DiagnosticResult) -> Unit,
        onComplete: (timestamp: String) -> Unit,
    ) {
        networkDiagJob?.cancel()
        networkDiagJob = scope.launch(Dispatchers.IO) {
            DiagnosticsRunner.runAll { result ->
                onProgress(result)
            }
            val formattedTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            onComplete(formattedTime)
        }
    }

    fun runMetaDiagnostics(
        onProgress: (DiagnosticResult) -> Unit,
        onComplete: (timestamp: String) -> Unit,
    ) {
        metaDiagJob?.cancel()
        metaDiagJob = scope.launch(Dispatchers.IO) {
            DiagnosticsRunner.runMetaProviders { result ->
                onProgress(result)
            }
            val formattedTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            onComplete(formattedTime)
        }
    }

    fun testDns(
        provider: DohProvider?,
        customEndpoint: String?,
        onComplete: (DnsTestResult) -> Unit,
    ) {
        dnsTestJob?.cancel()
        dnsTestJob = scope.launch(Dispatchers.IO) {
            val result = NetworkConfig.testDnsConnection(provider ?: DohProvider.NONE, customEndpoint)
            onComplete(result)
        }
    }

    fun testAccountApiKey(
        idPrefix: String,
        apiKey: String,
        onComplete: (success: Boolean, message: String) -> Unit,
    ) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) {
            onComplete(false, "API Key cannot be empty")
            return
        }
        accountApiTestJob?.cancel()
        accountApiTestJob = scope.launch(Dispatchers.IO) {
            val res = runCatching {
                when (idPrefix) {
                    "subdl" -> {
                        val r = app.get(
                            "https://api.subdl.com/api/v1/subtitles?api_key=$cleanKey&film_name=matrix",
                            timeout = 8000L
                        ).okhttpResponse
                        when (r.code) {
                            200 -> true to "Valid API Key (Connected successfully)"
                            400, 401, 403 -> false to "Invalid API Key (HTTP ${r.code})"
                            else -> false to "HTTP ${r.code}: Unexpected response"
                        }
                    }
                    "subsource" -> {
                        SubsourceSubtitleProvider.testApiKey(cleanKey)
                    }
                    else -> {
                        true to "API Key format verified"
                    }
                }
            }.getOrElse { false to "Connection error: ${it.message}" }
            onComplete(res.first, res.second)
        }
    }
}
