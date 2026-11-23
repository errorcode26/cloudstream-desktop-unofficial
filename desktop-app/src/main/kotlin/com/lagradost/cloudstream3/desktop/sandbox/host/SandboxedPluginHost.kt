package com.lagradost.cloudstream3.desktop.sandbox.host

import com.lagradost.cloudstream3.desktop.sandbox.protocol.SandboxAuditReport
import com.lagradost.cloudstream3.desktop.sandbox.protocol.SandboxedProviderMetadata
import com.lagradost.cloudstream3.desktop.sandbox.protocol.WorkerProtocolSerializer
import com.lagradost.cloudstream3.desktop.sandbox.protocol.WorkerRequest
import com.lagradost.cloudstream3.desktop.sandbox.protocol.WorkerResponse
import com.lagradost.common.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Thrown when loading a plugin JAR in the sandboxed worker fails.
 * [isPermanent] indicates whether the failure is deterministic (corrupted bytecode,
 * class linking, or security violation) vs transient (timeout, IPC glitch).
 */
class PluginLoadFailureException(
    val pluginFile: File,
    val errorMessage: String,
    val isPermanent: Boolean,
    cause: Throwable? = null,
) : Exception("Failed to load plugin '${pluginFile.name}': $errorMessage", cause)

/**
 * Supervisor running in the main Compose app process.
 * Manages the lifecycle of the low-privilege sandboxed JVM worker,
 * multiplexing requests over anonymous pipes with automatic crash recovery.
 */
class SandboxedPluginHost(
    val containerName: String = "CloudStream.PluginWorker.Sandbox.v2",
    val memoryLimitBytes: Long = 3072L * 1024L * 1024L
) : Closeable {

    companion object {
        typealias ActiveLinkConsumer = com.lagradost.cloudstream3.desktop.sandbox.host.ActiveLinkConsumer

        fun isBlockedOrPrivateUrl(rawUrl: String): Boolean =
            SandboxSecurityPolicy.isBlockedOrPrivateUrl(rawUrl)

        fun pruneWorkerClasspath(rawClasspath: String): String =
            SandboxSecurityPolicy.pruneWorkerClasspath(rawClasspath)
    }

    private val logger = LoggerFactory.getLogger(SandboxedPluginHost::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val pluginLifecycleMutex = Mutex()
    private val nextRequestId = AtomicLong(1)
    private val loadedPluginPaths = ConcurrentHashMap.newKeySet<String>()
    private val activeLinkConsumers = ConcurrentHashMap<Long, ActiveLinkConsumer>()

    private val processManager: SandboxProcessManager = SandboxProcessManager(
        containerName = containerName,
        memoryLimitBytes = memoryLimitBytes,
        scope = scope,
    )

    private val callbackHandler: SandboxHostCallbackHandler = SandboxHostCallbackHandler(
        scope = scope,
        activeLinkConsumers = activeLinkConsumers,
        loadedPluginPaths = loadedPluginPaths,
        sendCallbackResponse = { resp -> processManager.sendCallbackResponse(resp) }
    )

    init {
        processManager.onCallbackRequest = { cbReq ->
            callbackHandler.handleWorkerCallback(cbReq)
        }
    }

    val isHostAvailable: Boolean
        get() = processManager.isHostAvailable

    suspend fun start() = processManager.ensureStarted {
        onWorkerStarted()
    }

    private suspend fun onWorkerStarted() {
        try {
            processManager.sendDirectAwaitLocked(
                WorkerRequest(
                    id = nextRequestId.getAndIncrement(),
                    action = WorkerRequest.ACTION_PING
                ),
                15_000L
            )
        } catch (t: Throwable) {
            AppLogger.e("SandboxedPluginHost", "Worker initial ready ping failed: ${t.message}", t)
            throw IOException("Sandboxed worker process failed readiness ping: ${t.message}", t)
        }

        val rawSettings = com.lagradost.common.storage.DesktopDataStore.rawKeyCache.toMap()
        val safeSettings = SandboxSecurityPolicy.filterSandboxSettings(rawSettings)
        if (safeSettings.isNotEmpty()) {
            runCatching {
                processManager.sendDirectAwaitLocked(
                    WorkerRequest(
                        id = nextRequestId.getAndIncrement(),
                        action = WorkerRequest.ACTION_SYNC_SETTINGS,
                        settingsMap = safeSettings
                    ),
                    10_000L
                )
            }
        }

        loadedPluginPaths.retainAll { File(it).exists() }
        for (path in loadedPluginPaths) {
            runCatching {
                processManager.sendDirectAwaitLocked(
                    WorkerRequest(
                        id = nextRequestId.getAndIncrement(),
                        action = WorkerRequest.ACTION_LOAD_PLUGIN,
                        pluginPath = path
                    ),
                    20_000L
                )
            }
        }
    }

    private suspend fun sendRequest(request: WorkerRequest, timeoutMs: Long = 30_000L): WorkerResponse {
        return processManager.sendRequest(request, timeoutMs) {
            onWorkerStarted()
        }
    }

    suspend fun ping(timeoutMs: Long = 15_000L): Boolean {
        val req = WorkerRequest(id = nextRequestId.getAndIncrement(), action = WorkerRequest.ACTION_PING)
        val resp = sendRequest(req, timeoutMs = timeoutMs)
        return resp.success
    }

    suspend fun syncSettings(
        settings: Map<String, String>,
        removedKeys: Collection<String> = emptyList(),
    ): Boolean {
        val safeSettings = SandboxSecurityPolicy.filterSandboxSettings(settings)
        val safeRemovedKeys = SandboxSecurityPolicy.filterSandboxKeys(removedKeys)
        if (safeSettings.isEmpty() && safeRemovedKeys.isEmpty()) return true
        val req = WorkerRequest(
            id = nextRequestId.getAndIncrement(),
            action = WorkerRequest.ACTION_SYNC_SETTINGS,
            settingsMap = safeSettings,
            removedSettingKeys = safeRemovedKeys
        )
        val resp = sendRequest(req, timeoutMs = 10_000L)
        return resp.success
    }

    suspend fun loadPlugin(jarFile: File, forceRetry: Boolean = false): List<SandboxedProviderMetadata>? = pluginLifecycleMutex.withLock {
        val req = WorkerRequest(
            id = nextRequestId.getAndIncrement(),
            action = WorkerRequest.ACTION_LOAD_PLUGIN,
            forceRetry = forceRetry,
            pluginPath = jarFile.absolutePath
        )
        val resp = try {
            sendRequest(req, timeoutMs = 45_000L)
        } catch (t: Throwable) {
            logger.warn("Sandboxed worker failed during plugin load for ${jarFile.name}: ${t.message}")
            throw PluginLoadFailureException(
                pluginFile = jarFile,
                errorMessage = t.message ?: "IPC request timeout or channel closed",
                isPermanent = false,
                cause = t
            )
        }
        if (!resp.success) {
            val err = resp.error ?: "Extension initialization failed inside sandbox"
            logger.warn("Sandboxed worker rejected plugin ${jarFile.name}: $err")
            val deterministicSignatures = listOf(
                "ClassNotFoundException",
                "NoClassDefFoundError",
                "VerifyError",
                "ClassFormatError",
                "UnsupportedClassVersionError",
                "ZipException",
                "SecurityException",
                "NoSuchMethodError",
                "NoSuchFieldError",
                "IncompatibleClassChangeError",
                "un-transpilable",
                "no valid JAR was produced"
            )
            val isPermanentFailure = deterministicSignatures.any { err.contains(it, ignoreCase = true) }
            throw PluginLoadFailureException(
                pluginFile = jarFile,
                errorMessage = err,
                isPermanent = isPermanentFailure
            )
        }
        loadedPluginPaths.add(jarFile.absolutePath)
        val payload = resp.payloadJson ?: return@withLock emptyList()
        val loadResult = try {
            WorkerProtocolSerializer.mapper.readValue(payload, com.lagradost.cloudstream3.desktop.sandbox.protocol.SandboxedPluginLoadResult::class.java)
        } catch (t: Throwable) {
            logger.debug("Parsing SandboxedPluginLoadResult payload fallback: ${t.message}")
            val list = WorkerProtocolSerializer.mapper.readValue<List<SandboxedProviderMetadata>>(
                payload,
                WorkerProtocolSerializer.mapper.typeFactory.constructCollectionType(
                    List::class.java, SandboxedProviderMetadata::class.java
                )
            )
            com.lagradost.cloudstream3.desktop.sandbox.protocol.SandboxedPluginLoadResult(providers = list)
        }

        // Derive settings namespace strictly on the host from the plugin's verified file/manifest identity
        val manifest = com.lagradost.cloudstream3.desktop.repo.DesktopRepositoryManager.readPluginManifest(jarFile)
        val rawIdentity = manifest?.get("internalName")?.toString()?.takeIf { it.isNotBlank() }
            ?: manifest?.get("name")?.toString()?.takeIf { it.isNotBlank() }
            ?: jarFile.nameWithoutExtension
        val sanitizedIdentity = rawIdentity.trim().replace(Regex("[^a-zA-Z0-9_]"), "_").trim('_')
        if (sanitizedIdentity.length < 3) {
            logger.error("Rejecting plugin '${jarFile.name}': Derived identity '$sanitizedIdentity' is too short (< 3 chars) for a secure settings namespace.")
            return@withLock null
        }
        val hostPrefName = "${sanitizedIdentity}_"

        loadResult.schemas.forEach { s ->
            val matchesThisPlugin = s.pluginPrefName.equals(hostPrefName, ignoreCase = true) ||
                s.pluginPrefName.trimEnd('_').equals(sanitizedIdentity, ignoreCase = true) ||
                s.pluginPrefName.trimEnd('_').equals(rawIdentity.trim(), ignoreCase = true)
            if (!matchesThisPlugin) {
                logger.debug("Discarding foreign schema key '${s.key}' (prefName: '${s.pluginPrefName}') during load of '$hostPrefName'")
                return@forEach
            }
            if (s.key.isBlank() || s.key.length < 2) {
                logger.warn("Ignoring invalid or blank schema key from plugin '$hostPrefName'")
                return@forEach
            }
            val sk = "${hostPrefName}${s.key}"
            if (SandboxSecurityPolicy.PROTECTED_HOST_KEYS.any { sk.equals(it, ignoreCase = true) || s.key.equals(it, ignoreCase = true) }) {
                logger.warn("Ignoring unauthorized worker schema registration targeting protected key '${s.key}'")
                return@forEach
            }
            com.lagradost.common.storage.PluginSettingsSchemaRegistry.register(
                pluginPrefName = hostPrefName,
                key = s.key,
                type = s.type,
                defaultValue = s.defaultValue,
                isGlobal = false,
                options = s.options,
                storageKey = sk,
            )
        }

        loadResult.providers
    }

    suspend fun search(providerName: String, query: String, page: Int = 1): String? {
        val req = WorkerRequest(
            id = nextRequestId.getAndIncrement(),
            action = WorkerRequest.ACTION_SEARCH,
            providerName = providerName,
            query = query,
            page = page,
        )
        val resp = sendRequest(req, timeoutMs = 45_000L)
        if (!resp.success) throw IOException(resp.error ?: "Search failed")
        return resp.payloadJson
    }

    suspend fun load(providerName: String, url: String): String? {
        val req = WorkerRequest(
            id = nextRequestId.getAndIncrement(),
            action = WorkerRequest.ACTION_LOAD,
            providerName = providerName,
            url = url
        )
        val resp = sendRequest(req, timeoutMs = 45_000L)
        if (!resp.success) throw IOException(resp.error ?: "Load failed")
        return resp.payloadJson
    }

    suspend fun loadLinks(
        providerName: String,
        data: String,
        isCasting: Boolean = false,
        subtitleCallback: ((com.lagradost.cloudstream3.SubtitleFile) -> Unit)? = null,
        callback: ((com.lagradost.cloudstream3.utils.ExtractorLink) -> Unit)? = null
    ): String? {
        val req = WorkerRequest(
            id = nextRequestId.getAndIncrement(),
            action = WorkerRequest.ACTION_LOAD_LINKS,
            providerName = providerName,
            data = data,
            isCasting = isCasting
        )
        if (callback != null || subtitleCallback != null) {
            activeLinkConsumers[req.id] = ActiveLinkConsumer(
                onLink = callback,
                onSubtitle = subtitleCallback
            )
        }
        return try {
            val resp = sendRequest(req, timeoutMs = 90_000L)
            if (!resp.success) throw IOException(resp.error ?: "LoadLinks failed")
            resp.payloadJson
        } finally {
            activeLinkConsumers.remove(req.id)
        }
    }

    suspend fun getMainPage(
        providerName: String,
        page: Int,
        requestName: String,
        requestData: String,
        horizontalImages: Boolean = false
    ): String? {
        val req = WorkerRequest(
            id = nextRequestId.getAndIncrement(),
            action = WorkerRequest.ACTION_MAIN_PAGE,
            providerName = providerName,
            page = page,
            requestName = requestName,
            requestData = requestData,
            horizontalImages = horizontalImages
        )
        val resp = sendRequest(req, timeoutMs = 45_000L)
        if (!resp.success) throw IOException(resp.error ?: "MainPage failed")
        return resp.payloadJson
    }

    suspend fun getLoadedProviders(): List<SandboxedProviderMetadata> {
        val req = WorkerRequest(
            id = nextRequestId.getAndIncrement(),
            action = WorkerRequest.ACTION_GET_LOADED_PROVIDERS
        )
        val resp = sendRequest(req, timeoutMs = 10_000L)
        if (!resp.success || resp.payloadJson == null) return emptyList()
        return try {
            val loadResult = WorkerProtocolSerializer.mapper.readValue(
                resp.payloadJson,
                com.lagradost.cloudstream3.desktop.sandbox.protocol.SandboxedPluginLoadResult::class.java
            )
            loadResult.providers
        } catch (t: Throwable) {
            logger.debug("Parsing getLoadedProviders payload fallback: ${t.message}")
            WorkerProtocolSerializer.mapper.readValue(
                resp.payloadJson,
                WorkerProtocolSerializer.mapper.typeFactory.constructCollectionType(List::class.java, SandboxedProviderMetadata::class.java)
            )
        }
    }

    suspend fun unloadPlugin(jarFile: File): Boolean = pluginLifecycleMutex.withLock {
        val canonical = try { jarFile.canonicalPath } catch (t: Throwable) {
            logger.debug("Failed canonicalPath resolution for ${jarFile.name}: ${t.message}")
            jarFile.absolutePath
        }
        loadedPluginPaths.remove(jarFile.absolutePath)
        loadedPluginPaths.remove(canonical)

        if (!processManager.isAlive()) return@withLock true

        val req = WorkerRequest(
            id = nextRequestId.getAndIncrement(),
            action = WorkerRequest.ACTION_UNLOAD_PLUGIN,
            pluginPath = jarFile.absolutePath
        )
        try {
            val resp = sendRequest(req, timeoutMs = 5_000L)
            if (!resp.success) {
                logger.warn("Worker unload rejected for ${jarFile.name}: ${resp.error}; recycling worker to release locks.")
                recycleWorker()
            }
        } catch (t: Throwable) {
            logger.warn("Worker unload timed out or failed for ${jarFile.name} (${t.message}); recycling worker to release locks.")
            recycleWorker()
        }
        return@withLock true
    }

    suspend fun runSecurityAudit(): SandboxAuditReport {
        val req = WorkerRequest(
            id = nextRequestId.getAndIncrement(),
            action = WorkerRequest.ACTION_SECURITY_AUDIT
        )
        val resp = sendRequest(req, timeoutMs = 15_000L)
        if (!resp.success || resp.payloadJson == null) {
            throw IOException("Security audit failed: ${resp.error ?: "Unknown error"}")
        }
        return WorkerProtocolSerializer.mapper.readValue(
            resp.payloadJson,
            SandboxAuditReport::class.java
        )
    }

    suspend fun syncClonedSites(clonedSitesJson: String): List<SandboxedProviderMetadata>? = processManager.mutex.withLock {
        processManager.ensureStartedLocked { onWorkerStarted() }
        val req = WorkerRequest(
            id = nextRequestId.getAndIncrement(),
            action = WorkerRequest.ACTION_SYNC_CLONED_SITES,
            clonedSitesJson = clonedSitesJson
        )
        val resp = processManager.sendDirectAwaitLocked(req, timeoutMs = 15_000L)
        if (!resp.success) {
            logger.warn("Worker failed syncClonedSites: ${resp.error}")
            return@withLock null
        }
        val payload = resp.payloadJson ?: return@withLock emptyList()
        try {
            val loadResult = WorkerProtocolSerializer.mapper.readValue(
                payload,
                com.lagradost.cloudstream3.desktop.sandbox.protocol.SandboxedPluginLoadResult::class.java
            )
            loadResult.providers
        } catch (t: Throwable) {
            logger.warn("Failed parsing syncClonedSites result: ${t.message}")
            null
        }
    }

    fun recycleWorker() {
        processManager.recycleWorker()
    }

    override fun close() {
        recycleWorker()
    }
}
