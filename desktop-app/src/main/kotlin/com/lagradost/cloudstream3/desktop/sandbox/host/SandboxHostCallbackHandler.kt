package com.lagradost.cloudstream3.desktop.sandbox.host

import com.lagradost.cloudstream3.desktop.sandbox.protocol.SerializableCookie
import com.lagradost.cloudstream3.desktop.sandbox.protocol.WorkerCallbackRequest
import com.lagradost.cloudstream3.desktop.sandbox.protocol.WorkerCallbackResponse
import com.lagradost.cloudstream3.desktop.sandbox.protocol.WorkerProtocolSerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class ActiveLinkConsumer(
    val onLink: ((com.lagradost.cloudstream3.utils.ExtractorLink) -> Unit)?,
    val onSubtitle: ((com.lagradost.cloudstream3.SubtitleFile) -> Unit)?,
)

class SandboxHostCallbackHandler(
    private val scope: CoroutineScope,
    private val activeLinkConsumers: ConcurrentHashMap<Long, ActiveLinkConsumer>,
    private val loadedPluginPaths: Set<String>,
    private val sendCallbackResponse: (WorkerCallbackResponse) -> Unit,
) {
    private val logger = LoggerFactory.getLogger(SandboxHostCallbackHandler::class.java)

    private val lastSolveAttempts = ConcurrentHashMap<String, Long>()
    private var repoCatalogSyncJob: Job? = null
    private val repoCatalogSyncMutex = Mutex()

    private fun scheduleBridgedCatalogSync() {
        scope.launch(Dispatchers.IO) {
            repoCatalogSyncMutex.withLock {
                repoCatalogSyncJob?.cancel()
                repoCatalogSyncJob = scope.launch(Dispatchers.IO) {
                    delay(3500L)
                    logger.info("Triggering debounced catalog sync on host for bridged repositories...")
                    try {
                        com.lagradost.cloudstream3.desktop.repo.DesktopRepositoryManager.rebuildRemotePluginCatalog()
                        com.lagradost.cloudstream3.desktop.repo.DesktopRepositoryManager.incrementSyncGeneration()
                    } catch (e: Throwable) {
                        logger.error("Debounced catalog sync failed on host", e)
                    }
                }
            }
        }
    }

    suspend fun handleWorkerCallback(cbReq: WorkerCallbackRequest) {
        if (cbReq.action == WorkerCallbackRequest.ACTION_EMIT_LINK) {
            val reqId = cbReq.requestId ?: return
            val linkJson = cbReq.linkJson ?: return
            val consumer = activeLinkConsumers[reqId] ?: return
            try {
                val link = WorkerProtocolSerializer.mapper.readValue(linkJson, com.lagradost.cloudstream3.utils.ExtractorLink::class.java)
                if (link != null) {
                    consumer.onLink?.invoke(link)
                }
            } catch (e: Throwable) {
                logger.warn("Failed to deserialize streamed ExtractorLink: ${e.message}")
            }
            return
        }

        if (cbReq.action == WorkerCallbackRequest.ACTION_ADD_REPOSITORY) {
            val url = cbReq.repoUrl ?: return
            if (SandboxSecurityPolicy.isBlockedOrPrivateUrl(url)) {
                logger.warn("Rejected worker repository callback targeting a private or invalid URL: $url")
                return
            }
            val name = cbReq.repoName?.takeIf { it.isNotBlank() } ?: url
            val icon = cbReq.repoIcon
            val normalized = com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData(iconUrl = icon, name = name, url = url)
            logger.info("Host handling ACTION_ADD_REPOSITORY from worker for: $name ($url)")
            scope.launch(Dispatchers.IO) {
                try {
                    val isNew = com.lagradost.cloudstream3.desktop.repo.DesktopRepositoryManager.saveRepository(normalized)
                    if (isNew) {
                        logger.info("Host saved newly bridged repository: $name. Scheduling debounced sync...")
                        com.lagradost.cloudstream3.desktop.repo.DesktopRepositoryManager.incrementSyncGeneration()
                        scheduleBridgedCatalogSync()
                    }
                } catch (t: Throwable) {
                    logger.warn("Host failed to save bridged repository $name: ${t.message}")
                }
            }
            return
        }

        if (cbReq.action == WorkerCallbackRequest.ACTION_EMIT_SUBTITLE) {
            val reqId = cbReq.requestId ?: return
            val subJson = cbReq.subtitleJson ?: return
            val consumer = activeLinkConsumers[reqId] ?: return
            try {
                val sub = WorkerProtocolSerializer.mapper.readValue(subJson, com.lagradost.cloudstream3.SubtitleFile::class.java)
                if (sub != null) {
                    consumer.onSubtitle?.invoke(sub)
                }
            } catch (e: Throwable) {
                logger.warn("Failed to deserialize streamed SubtitleFile: ${e.message}")
            }
            return
        }

        if (cbReq.action == WorkerCallbackRequest.ACTION_SAVE_SETTING) {
            val key = cbReq.key ?: return
            val valueJson = cbReq.valueJson ?: return
            if (SandboxSecurityPolicy.PROTECTED_HOST_KEYS.any { key.equals(it, ignoreCase = true) }) {
                logger.error("Rejected unauthorized worker write to protected host key: $key")
                return
            }

            // Derive host prefixes of all currently loaded plugins
            val validPrefixes = loadedPluginPaths.mapNotNull { path ->
                val f = File(path)
                val m = com.lagradost.cloudstream3.desktop.repo.DesktopRepositoryManager.readPluginManifest(f)
                val id = m?.get("internalName")?.toString()?.takeIf { it.isNotBlank() }
                    ?: m?.get("name")?.toString()?.takeIf { it.isNotBlank() }
                    ?: f.nameWithoutExtension
                val clean = id.trim().replace(Regex("[^a-zA-Z0-9_]"), "_").trim('_')
                if (clean.length >= 3) "${clean}_" else null
            }.toSet()

            val isAllowed = (key.startsWith("cf_silenced_") && key.length > "cf_silenced_".length) ||
                validPrefixes.any { p -> key.startsWith(p) && key.length > p.length } ||
                com.lagradost.common.storage.PluginSettingsSchemaRegistry.schemas.values.flatMap { it.values }.any { it.storageKey == key }

            if (isAllowed) {
                com.lagradost.common.storage.DesktopDataStore.setRawJson(key, valueJson)
            } else {
                logger.warn("Rejected unauthorized worker setting write to key: $key")
            }
            return
        }

        if (cbReq.action == WorkerCallbackRequest.ACTION_REMOVE_SETTING) {
            val key = cbReq.key ?: return
            if (SandboxSecurityPolicy.PROTECTED_HOST_KEYS.any { key.equals(it, ignoreCase = true) }) {
                logger.error("Rejected unauthorized worker write to protected host key: $key")
                return
            }

            val validPrefixes = loadedPluginPaths.mapNotNull { path ->
                val f = File(path)
                val m = com.lagradost.cloudstream3.desktop.repo.DesktopRepositoryManager.readPluginManifest(f)
                val id = m?.get("internalName")?.toString()?.takeIf { it.isNotBlank() }
                    ?: m?.get("name")?.toString()?.takeIf { it.isNotBlank() }
                    ?: f.nameWithoutExtension
                val clean = id.trim().replace(Regex("[^a-zA-Z0-9_]"), "_").trim('_')
                if (clean.length >= 3) "${clean}_" else null
            }.toSet()

            val isAllowed = (key.startsWith("cf_silenced_") && key.length > "cf_silenced_".length) ||
                validPrefixes.any { p -> key.startsWith(p) && key.length > p.length } ||
                com.lagradost.common.storage.PluginSettingsSchemaRegistry.schemas.values.flatMap { it.values }.any { it.storageKey == key }

            if (isAllowed) {
                com.lagradost.common.storage.DesktopDataStore.removeKey(key)
            } else {
                logger.warn("Rejected unauthorized worker setting delete for key: $key")
            }
            return
        }

        if (cbReq.action == WorkerCallbackRequest.ACTION_PROXY_FETCH) {
            val url = cbReq.url ?: return
            val headers = cbReq.headers ?: emptyMap()
            val method = cbReq.method ?: "GET"
            val body = cbReq.body

            if (SandboxSecurityPolicy.isBlockedOrPrivateUrl(url)) {
                logger.warn("Rejected unauthorized SSRF/private proxy fetch callback to: $url")
                sendCallbackResponse(
                    WorkerCallbackResponse(
                        callbackId = cbReq.callbackId,
                        success = false,
                        statusCode = 403,
                        error = "SSRF blocked: Requests to loopback, private LAN, or invalid network destinations are permanently forbidden."
                    )
                )
                return
            }

            logger.info("Handling browser proxy fetch callback from sandboxed worker for $url")
            val (statusCode, contentType, respBody, err) = withContext(Dispatchers.IO) {
                try {
                    val initialHost = try { java.net.URI(url).host?.lowercase()?.trim() } catch (_: Throwable) { null }
                    val pinnedAddresses = if (initialHost != null) {
                        try {
                            java.net.InetAddress.getAllByName(initialHost).filter { a ->
                                !a.isLoopbackAddress && !a.isSiteLocalAddress && !a.isLinkLocalAddress && !a.isAnyLocalAddress && !SandboxSecurityPolicy.isCarrierGradeNat(a) && !SandboxSecurityPolicy.isIpv6UniqueLocal(addr = a)
                            }
                        } catch (_: Throwable) { emptyList() }
                    } else emptyList()

                    if (initialHost != null && pinnedAddresses.isEmpty()) {
                        return@withContext listOf(403, "text/plain", "", "SSRF blocked: resolved addresses are all private or unavailable")
                    }

                    // Strict pinned DNS resolver to prevent DNS rebinding TOCTOU
                    val pinnedDns = okhttp3.Dns { hostname ->
                        if (initialHost != null && hostname.equals(initialHost, ignoreCase = true)) {
                            pinnedAddresses
                        } else {
                            val resolved = java.net.InetAddress.getAllByName(hostname).filter { a ->
                                !a.isLoopbackAddress && !a.isSiteLocalAddress && !a.isLinkLocalAddress && !a.isAnyLocalAddress && !SandboxSecurityPolicy.isCarrierGradeNat(a) && !SandboxSecurityPolicy.isIpv6UniqueLocal(addr = a)
                            }
                            if (resolved.isEmpty()) {
                                throw java.net.UnknownHostException("SSRF blocked: Host $hostname resolved to private or invalid address")
                            }
                            resolved
                        }
                    }

                    val fetchClient = okhttp3.OkHttpClient.Builder()
                        .dns(pinnedDns)
                        .followRedirects(false)
                        .followSslRedirects(false)
                        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                        .build()

                    var currentUrl = url
                    var redirectCount = 0
                    var finalCode = 500
                    var finalContentType = "text/plain"
                    var finalBody = ""

                    while (redirectCount <= 5) {
                        if (SandboxSecurityPolicy.isBlockedOrPrivateUrl(currentUrl)) {
                            return@withContext listOf(403, "text/plain", "", "SSRF blocked on redirect hop to: $currentUrl")
                        }

                        val reqBuilder = okhttp3.Request.Builder().url(currentUrl)
                        val reqBody = if (redirectCount == 0) body?.toRequestBody(null) else null
                        val reqMethod = if (redirectCount == 0) method else "GET"
                        if (reqMethod.equals("POST", ignoreCase = true) || reqMethod.equals("PUT", ignoreCase = true)) {
                            reqBuilder.method(reqMethod, reqBody ?: ByteArray(0).toRequestBody(null))
                        } else {
                            reqBuilder.method(reqMethod, null)
                        }
                        headers.forEach { (k, v) ->
                            if (!k.equals("content-length", ignoreCase = true) && !k.equals("host", ignoreCase = true)) {
                                reqBuilder.header(k, v)
                            }
                        }
                        val okReq = reqBuilder.build()
                        val response = fetchClient.newCall(okReq).execute()

                        if (response.isRedirect) {
                            val loc = response.header("Location")
                            response.close()
                            if (loc.isNullOrBlank()) {
                                return@withContext listOf(502, "text/plain", "", "Redirect without Location header")
                            }
                            val resolvedLocation = try {
                                java.net.URI(currentUrl).resolve(loc).toASCIIString()
                            } catch (_: Throwable) {
                                return@withContext listOf(400, "text/plain", "", "Invalid redirect location: $loc")
                            }
                            currentUrl = resolvedLocation
                            redirectCount++
                        } else {
                            finalCode = response.code
                            finalContentType = response.header("content-type") ?: "text/html"
                            finalBody = response.body.string()
                            response.close()
                            break
                        }
                    }

                    if (redirectCount > 5) {
                        listOf(508, "text/plain", "", "Too many redirects")
                    } else {
                        listOf(finalCode, finalContentType, finalBody, null)
                    }
                } catch (t: Throwable) {
                    listOf(500, "text/plain", "", t.message)
                }
            }

            val cbResp = WorkerCallbackResponse(
                callbackId = cbReq.callbackId,
                success = (statusCode as Int) in 200..299,
                statusCode = statusCode,
                contentType = contentType as String?,
                body = respBody as String?,
                error = err as String?
            )
            sendCallbackResponse(cbResp)
            return
        }

        if (cbReq.action == WorkerCallbackRequest.ACTION_SOLVE_CLOUDFLARE) {
            val url = cbReq.url ?: return
            val host = cbReq.host ?: return
            val headers = cbReq.headers ?: emptyMap()

            if (SandboxSecurityPolicy.isBlockedOrPrivateUrl(url) || SandboxSecurityPolicy.isBlockedOrPrivateUrl("https://$host")) {
                logger.warn("Rejecting SSRF/private Cloudflare solve callback for host: $host (url: $url)")
                sendCallbackResponse(
                    WorkerCallbackResponse(
                        callbackId = cbReq.callbackId,
                        success = false,
                        error = "SSRF blocked: Cloudflare solver cannot target loopback, private LAN, or invalid network destinations."
                    )
                )
                return
            }

            val now = System.currentTimeMillis()
            val lastAttempt = lastSolveAttempts[host] ?: 0L
            if (now - lastAttempt < 3_000L) {
                logger.warn("Throttling excessive Cloudflare solve callback requests for host: $host")
                sendCallbackResponse(
                    WorkerCallbackResponse(
                        callbackId = cbReq.callbackId,
                        success = false,
                        error = "Throttled: solve request too frequent"
                    )
                )
                return
            }
            lastSolveAttempts[host] = now

            if (com.lagradost.cloudstream3.desktop.init.PluginWarmupState.progress.value.isWarmingUp) {
                logger.warn("Suppressing interactive Cloudflare verification modal for $host during background warmup.")
                sendCallbackResponse(
                    WorkerCallbackResponse(
                        callbackId = cbReq.callbackId,
                        success = false,
                        error = "Suppressed during background warmup"
                    )
                )
                return
            }

            val isBypassAllowed = com.lagradost.common.storage.DesktopDataStore.getKey<Boolean>(
                com.lagradost.common.storage.DesktopDataStore.PREF_ALLOW_CF_BYPASS
            ) ?: false
            if (!isBypassAllowed) {
                logger.info("Cloudflare solver skipped for host $host: Disabled in Settings.")
                sendCallbackResponse(
                    WorkerCallbackResponse(
                        callbackId = cbReq.callbackId,
                        success = false,
                        error = "Cloudflare solver is disabled by user in Settings."
                    )
                )
                return
            }

            logger.info("Handling Cloudflare solve callback from sandboxed worker for host: $host")
            val solved = withContext(Dispatchers.IO) {
                com.lagradost.cloudstream3.network.CloudflareKiller.solveWithEmbeddedWebView(url, host, headers)
            }

            val clearanceCookies = com.lagradost.cloudstream3.network.CloudflareKiller.getClearance(host)?.cookies.orEmpty()
            val vaultCookies = com.lagradost.cloudstream3.desktop.network.vault.WindowsCookieVault.getUnexpiredForHost(host)
            val cookies = (clearanceCookies + vaultCookies).distinctBy { "${it.name}|${it.domain}" }

            val serializable = cookies.map { c ->
                SerializableCookie(
                    name = c.name,
                    value = c.value,
                    domain = c.domain,
                    path = c.path,
                    expiresAt = c.expiresAt,
                    secure = c.secure,
                    httpOnly = c.httpOnly
                )
            }

            val userAgent = com.lagradost.cloudstream3.desktop.network.webview.WindowsWebView2Runtime.userAgent
            val cbResp = WorkerCallbackResponse(
                callbackId = cbReq.callbackId,
                success = solved,
                cookies = serializable,
                userAgent = userAgent
            )

            sendCallbackResponse(cbResp)
        }
    }
}
