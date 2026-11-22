package com.lagradost.cloudstream3.desktop.sandbox.host

import com.lagradost.cloudstream3.desktop.sandbox.protocol.WorkerCallbackRequest
import com.lagradost.cloudstream3.desktop.sandbox.protocol.WorkerCallbackResponse
import com.lagradost.cloudstream3.desktop.sandbox.protocol.WorkerProtocolSerializer
import com.lagradost.cloudstream3.desktop.sandbox.protocol.WorkerRequest
import com.lagradost.cloudstream3.desktop.sandbox.protocol.WorkerResponse
import com.lagradost.cloudstream3.desktop.sandbox.windows.WindowsAppContainerApi
import com.lagradost.cloudstream3.desktop.sandbox.windows.WindowsAppContainerLauncher
import com.lagradost.cloudstream3.desktop.sandbox.windows.WindowsJobObject
import com.lagradost.cloudstream3.desktop.sandbox.windows.WindowsSandboxAcl
import com.lagradost.common.platform.PlatformPaths
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

class SandboxProcessManager(
    val containerName: String = "CloudStream.PluginWorker.Sandbox.v2",
    val memoryLimitBytes: Long = 3072L * 1024L * 1024L,
    private val scope: CoroutineScope,
    var onCallbackRequest: (suspend (WorkerCallbackRequest) -> Unit)? = null,
) : Closeable {

    companion object {
        private val grantedSids = ConcurrentHashMap.newKeySet<String>()
    }

    private val logger = LoggerFactory.getLogger(SandboxProcessManager::class.java)

    val mutex = Mutex()
    private val pendingRequests = ConcurrentHashMap<Long, CompletableDeferred<WorkerResponse>>()
    private val activeWorkerNetworkRequests = ConcurrentHashMap<Long, Long>()

    private var currentJobObject: WindowsJobObject? = null
    private var currentProcess: WindowsAppContainerLauncher.SandboxedProcess? = null
    private var processWriter: BufferedWriter? = null

    @Volatile
    var isHostAvailable: Boolean = true
        private set

    fun isAlive(): Boolean = currentProcess?.isAlive() == true

    suspend fun ensureStarted(onPostLaunch: suspend () -> Unit) = mutex.withLock {
        ensureStartedLocked(onPostLaunch)
    }

    suspend fun ensureStartedLocked(onPostLaunch: (suspend () -> Unit)?) {
        if (isAlive()) return
        launchWorkerInternal()
        onPostLaunch?.invoke()
    }

    private fun launchWorkerInternal() {
        logger.info("Initializing sandboxed extension worker in container '$containerName' (1 GB Cap)...")

        val sid = WindowsAppContainerApi.getOrCreateAppContainer(
            containerName = containerName,
            displayName = "CloudStream Extension Sandbox",
            description = "Sandboxed low-privilege runtime for CloudStream plugins",
            capabilities = listOf(
                WindowsAppContainerApi.CAPABILITY_INTERNET_CLIENT
            )
        ) ?: throw IOException("Failed to obtain AppContainer SID for '$containerName'")

        if (grantedSids.add(sid)) {
            val extensionsDir = PlatformPaths.extensionsDir
            if (!extensionsDir.exists()) extensionsDir.mkdirs()
            WindowsSandboxAcl.grantFolderAccess(extensionsDir, sid, readOnly = true, allowExecute = true)

            val sandboxDir = PlatformPaths.sandboxDir
            if (!sandboxDir.exists()) sandboxDir.mkdirs()
            WindowsSandboxAcl.grantFolderAccess(sandboxDir, sid, readOnly = false, allowExecute = false)

            val sandboxTmp = File(sandboxDir, "tmp")
            if (!sandboxTmp.exists()) sandboxTmp.mkdirs()
            WindowsSandboxAcl.grantFolderAccess(sandboxTmp, sid, readOnly = false, allowExecute = false)

            val pluginCacheDir = PlatformPaths.pluginCacheDir
            if (!pluginCacheDir.exists()) pluginCacheDir.mkdirs()
            WindowsSandboxAcl.grantFolderAccess(pluginCacheDir, sid, readOnly = false, allowExecute = false)

            val topRoots = mutableSetOf<File>()

            val gradleCache = File(System.getProperty("user.home"), ".gradle/caches")
            if (gradleCache.exists()) {
                val aclMarker = File(PlatformPaths.sandboxDir, ".gradle_acl_${sid.hashCode()}")
                if (!aclMarker.exists()) {
                    topRoots.add(gradleCache)
                    try {
                        aclMarker.createNewFile()
                    } catch (_: Throwable) {}
                }
            }

            val javaHome = File(System.getProperty("java.home"))
            if (javaHome.exists()) topRoots.add(javaHome)

            for (root in topRoots) {
                try {
                    WindowsSandboxAcl.grantFolderAccess(root, sid, readOnly = true, allowExecute = true)
                } catch (t: Throwable) {
                    logger.warn("Failed granting ACL to root ${root.absolutePath}: ${t.message}")
                }
            }

            val classpath = SandboxSecurityPolicy.pruneWorkerClasspath(System.getProperty("java.class.path") ?: "")
            val classpathEntries = classpath.split(File.pathSeparator).filter { it.isNotBlank() }
            val extraRoots = mutableSetOf<String>()

            for (entry in classpathEntries) {
                val file = File(entry)
                val absPath = file.absolutePath
                val alreadyCovered = topRoots.any { root ->
                    absPath.startsWith(root.absolutePath, ignoreCase = true)
                }
                if (!alreadyCovered) {
                    val parent = (if (file.isDirectory) file else file.parentFile)?.absolutePath ?: continue
                    if (extraRoots.add(parent)) {
                        try {
                            WindowsSandboxAcl.grantFolderAccess(File(parent), sid, readOnly = true, allowExecute = true)
                        } catch (t: Throwable) {
                            logger.debug("Failed granting external classpath ACL to $parent: ${t.message}")
                        }
                    }
                }
            }
        }

        val javaHome = File(System.getProperty("java.home"))
        val candidateFiles = mutableListOf<File>()

        candidateFiles.add(File(javaHome, "bin/CloudStream-Worker.exe"))
        candidateFiles.add(File(javaHome, "CloudStream-Worker.exe"))
        candidateFiles.add(File(javaHome, "bin/javaw.exe"))
        candidateFiles.add(File(javaHome, "javaw.exe"))
        candidateFiles.add(File(javaHome, "bin/java.exe"))
        candidateFiles.add(File(javaHome, "java.exe"))

        javaHome.parentFile?.let { appRoot ->
            candidateFiles.add(File(appRoot, "runtime/bin/CloudStream-Worker.exe"))
            candidateFiles.add(File(appRoot, "runtime/bin/javaw.exe"))
            candidateFiles.add(File(appRoot, "runtime/bin/java.exe"))
            candidateFiles.add(File(appRoot, "bin/CloudStream-Worker.exe"))
        }

        try {
            val codeSource = SandboxedPluginHost::class.java.protectionDomain?.codeSource?.location
            if (codeSource != null) {
                val loc = File(codeSource.toURI())
                val appBase = if (loc.isDirectory) loc else loc.parentFile
                appBase?.parentFile?.let { installRoot ->
                    candidateFiles.add(File(installRoot, "runtime/bin/CloudStream-Worker.exe"))
                    candidateFiles.add(File(installRoot, "runtime/bin/javaw.exe"))
                    candidateFiles.add(File(installRoot, "runtime/bin/java.exe"))
                }
            }
        } catch (e: Throwable) { logger.debug("CodeSource worker candidate lookup note: ${e.message}") }

        System.getenv("JAVA_HOME")?.let { envHome ->
            candidateFiles.add(File(envHome, "bin/CloudStream-Worker.exe"))
            candidateFiles.add(File(envHome, "bin/javaw.exe"))
            candidateFiles.add(File(envHome, "bin/java.exe"))
        }

        val javaExeFile = candidateFiles.firstOrNull { it.isFile && it.exists() }
        if (javaExeFile == null) {
            isHostAvailable = false
            val checkedPaths = candidateFiles.joinToString("; ") { it.absolutePath }
            val runtimeContents = try {
                File(javaHome, "bin").listFiles()?.joinToString { it.name } ?: "none"
            } catch (e: Throwable) { logger.debug("Runtime bin contents lookup note: ${e.message}"); "unreadable" }
            logger.error("Sandbox worker executable not found. Checked: [$checkedPaths]. runtime/bin contents: [$runtimeContents]")
            throw java.io.FileNotFoundException(
                "Sandbox worker executable not found at ${File(javaHome, "bin/CloudStream-Worker.exe").absolutePath}. " +
                "Checked ${candidateFiles.size} candidate locations."
            )
        }

        val exeDir = javaExeFile.parentFile
        if (exeDir != null && exeDir.exists()) {
            try {
                WindowsSandboxAcl.grantFolderAccess(exeDir, sid, readOnly = true, allowExecute = true)
                exeDir.parentFile?.let { exeRoot ->
                    WindowsSandboxAcl.grantFolderAccess(exeRoot, sid, readOnly = true, allowExecute = true)
                }
            } catch (t: Throwable) {
                logger.debug("Granting ACL for worker binary dir ${exeDir.absolutePath}: ${t.message}")
            }
        }
        val javaExe = javaExeFile.absolutePath

        val job = WindowsJobObject(
            jobName = "CS3_PluginWorker_Job_${System.currentTimeMillis()}",
            memoryLimitBytes = memoryLimitBytes
        )
        this.currentJobObject = job

        val sandboxTmp = File(PlatformPaths.sandboxDir, "tmp").apply {
            try {
                if (exists()) {
                    listFiles()?.forEach { file ->
                        try { file.deleteRecursively() } catch (t: Throwable) {
                            logger.debug("Failed cleaning sandbox tmp file ${file.name}: ${t.message}")
                        }
                    }
                } else {
                    mkdirs()
                }
            } catch (t: Throwable) {
                logger.debug("Failed preparing sandbox tmp dir: ${t.message}")
            }
        }
        var classpath = System.getProperty("java.class.path") ?: ""
        if (classpath.isBlank() || !classpath.contains(File.pathSeparator)) {
            try {
                val codeSource = SandboxedPluginHost::class.java.protectionDomain?.codeSource?.location
                if (codeSource != null) {
                    val locationFile = File(codeSource.toURI())
                    val appDir = if (locationFile.isDirectory) locationFile else locationFile.parentFile
                    if (appDir != null && appDir.isDirectory) {
                        val bundledJars = appDir.listFiles { f -> f.extension.equals("jar", ignoreCase = true) }
                        if (!bundledJars.isNullOrEmpty()) {
                            val resolvedCp = bundledJars.joinToString(File.pathSeparator) { it.absolutePath }
                            if (resolvedCp.isNotBlank()) {
                                classpath = resolvedCp
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                logger.warn("Could not dynamically resolve packaged app classpath: ${t.message}")
            }
        }
        val workerClasspath = SandboxSecurityPolicy.pruneWorkerClasspath(classpath)
        val commandLine = "\"$javaExe\" " +
            "-Djava.awt.headless=true " +
            "-Dcloudstream.sandbox.worker=true " +
            "-Dcloudstream.data.dir=\"${PlatformPaths.appDataDir.absolutePath}\" " +
            "-Djava.io.tmpdir=\"${sandboxTmp.absolutePath}\" " +
            "-Djna.tmpdir=\"${sandboxTmp.absolutePath}\" " +
            "-Djava.util.prefs.PreferencesFactory=com.lagradost.cloudstream3.desktop.sandbox.worker.NoOpPreferencesFactory " +
            "-XX:+DisableAttachMechanism -Djdk.attach.allowAttachSelf=false " +
            "-XX:TieredStopAtLevel=1 -Xss256k -Xms16m -Xmx256m -XX:MaxMetaspaceSize=80m -XX:ReservedCodeCacheSize=20m -XX:+UseSerialGC -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=20 -XX:+ExitOnOutOfMemoryError " +
            "-cp \"$workerClasspath\" " +
            "com.lagradost.cloudstream3.desktop.sandbox.worker.PluginWorkerMain"

        val process = WindowsAppContainerLauncher.launch(
            commandLine = commandLine,
            containerName = containerName,
            workingDir = PlatformPaths.sandboxDir,
            capabilities = listOf(
                WindowsAppContainerApi.CAPABILITY_INTERNET_CLIENT
            ),
            jobObject = job
        )

        this.currentProcess = process
        this.processWriter = BufferedWriter(OutputStreamWriter(process.stdin, StandardCharsets.UTF_8), 65536)

        // Background stdout reader
        scope.launch {
            val reader = java.io.BufferedReader(java.io.InputStreamReader(process.stdout, StandardCharsets.UTF_8), 65536)
            try {
                while (isActive) {
                    val line = reader.readLine() ?: break
                    val trimmed = line.trim()
                    if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
                        logger.debug("Ignoring non-JSON line from worker stdout: {}", trimmed)
                        continue
                    }

                    if (trimmed.contains("\"type\":\"CALLBACK_REQUEST\"") || trimmed.contains("\"callbackId\"")) {
                        try {
                            val cbReq = WorkerProtocolSerializer.deserializeCallbackRequest(trimmed)
                            scope.launch {
                                onCallbackRequest?.invoke(cbReq)
                            }
                        } catch (e: Throwable) {
                            logger.error("Failed to parse callback request from worker: $trimmed", e)
                        }
                        continue
                    }

                    try {
                        val resp = WorkerProtocolSerializer.deserializeResponse(trimmed)
                        val deferred = pendingRequests.remove(resp.id)
                        deferred?.complete(resp)
                    } catch (e: Throwable) {
                        logger.error("Failed to parse response JSON from worker: $line", e)
                    }
                }
            } catch (e: Throwable) {
                logger.warn("Worker stdout reader stream closed: ${e.message}")
            } finally {
                handleWorkerTermination()
            }
        }

        // Background stderr logger
        scope.launch {
            val errReader = process.stderr.bufferedReader(StandardCharsets.UTF_8)
            try {
                while (isActive) {
                    val errLine = errReader.readLine() ?: break
                    val trimmed = errLine.trim()
                    if (trimmed.isEmpty()) continue

                    if (trimmed.startsWith("[CS_NET] START|")) {
                        val payload = trimmed.removePrefix("[CS_NET] START|")
                        val parts = payload.split("|", limit = 5)
                        if (parts.size >= 5) {
                            val workerId = parts[0].toLongOrNull() ?: 0L
                            val method = parts[1]
                            val host = parts[2]
                            val path = parts[3]
                            val url = parts[4]
                            val hostId = com.lagradost.common.net.NetworkTrafficBuffer.recordStart(
                                method = method,
                                url = url,
                                host = host,
                                path = path,
                                requestHeaders = emptyMap(),
                                requestBody = null,
                            )
                            activeWorkerNetworkRequests[workerId] = hostId
                            com.lagradost.common.logging.AppLogger.d("Sandbox:HTTP", "-> $method $url")
                        }
                    } else if (trimmed.startsWith("[CS_NET] END|")) {
                        val payload = trimmed.removePrefix("[CS_NET] END|")
                        val parts = payload.split("|", limit = 4)
                        if (parts.size >= 3) {
                            val workerId = parts[0].toLongOrNull() ?: 0L
                            val statusCode = parts[1].toIntOrNull() ?: -1
                            val durationMs = parts[2].toLongOrNull() ?: 0L
                            val error = if (parts.size >= 4 && parts[3].isNotBlank()) parts[3] else null
                            val hostId = activeWorkerNetworkRequests.remove(workerId)
                            if (hostId != null) {
                                com.lagradost.common.net.NetworkTrafficBuffer.recordComplete(
                                    id = hostId,
                                    statusCode = statusCode,
                                    statusMessage = if (statusCode > 0) "HTTP $statusCode" else (error ?: "Failed"),
                                    durationMs = durationMs,
                                    responseHeaders = emptyMap(),
                                    responseBody = null,
                                    responseSize = 0L,
                                    contentType = null,
                                    error = error,
                                )
                            }
                            val statusLabel = if (statusCode > 0) "$statusCode" else (error ?: "FAILED")
                            com.lagradost.common.logging.AppLogger.d("Sandbox:HTTP", "<- $statusLabel (${durationMs}ms)")
                        }
                    } else {
                        when {
                            trimmed.startsWith("[ERROR]") || trimmed.contains(" ERROR ") -> com.lagradost.common.logging.AppLogger.e("Sandbox:Worker", trimmed)
                            trimmed.startsWith("[WARN]") || trimmed.contains(" WARN ") -> com.lagradost.common.logging.AppLogger.w("Sandbox:Worker", trimmed)
                            trimmed.startsWith("[INFO]") || trimmed.contains(" INFO ") -> com.lagradost.common.logging.AppLogger.i("Sandbox:Worker", trimmed)
                            else -> com.lagradost.common.logging.AppLogger.d("Sandbox:Worker", trimmed)
                        }
                    }
                }
            } catch (t: Throwable) {
                logger.debug("Worker stderr reader stopped: ${t.message}")
            }
        }

        logger.info("Sandboxed worker process started successfully (PID: ${process.pid})")
    }

    private fun handleWorkerTermination() {
        val pending = pendingRequests.values.toList()
        pendingRequests.clear()
        activeWorkerNetworkRequests.clear()

        val exit = try { currentProcess?.exitValue() } catch (_: Throwable) { null }
        val exitHex = exit?.let { "0x" + Integer.toHexString(it).uppercase() } ?: "unknown"

        for (p in pending) {
            p.completeExceptionally(IOException("Sandboxed worker process terminated unexpectedly (exit: $exitHex)"))
        }

        currentProcess?.close()
        currentProcess = null
        processWriter = null

        scope.launch(Dispatchers.IO) {
            try {
                File(PlatformPaths.sandboxDir, "tmp").listFiles()?.forEach { file ->
                    try { file.deleteRecursively() } catch (t: Throwable) {
                        logger.debug("Failed cleaning sandbox tmp during termination: ${t.message}")
                    }
                }
            } catch (t: Throwable) {
                logger.debug("Failed accessing sandbox tmp during termination: ${t.message}")
            }
        }
        currentJobObject?.close()
        currentJobObject = null

        logger.warn("Sandboxed worker process terminated. Exit code: $exit ($exitHex). Ready for auto-recovery on next query.")
    }

    suspend fun sendRequest(
        request: WorkerRequest,
        timeoutMs: Long = 30_000L,
        onEnsureStarted: (suspend () -> Unit)? = null
    ): WorkerResponse {
        val deferred = CompletableDeferred<WorkerResponse>()
        pendingRequests[request.id] = deferred

        try {
            mutex.withLock {
                if (!isAlive()) {
                    ensureStartedLocked(onEnsureStarted)
                }
                sendDirect(request)
            }

            return withTimeout(timeoutMs) {
                deferred.await()
            }
        } finally {
            pendingRequests.remove(request.id)
        }
    }

    suspend fun sendDirectAwaitLocked(request: WorkerRequest, timeoutMs: Long): WorkerResponse {
        val deferred = CompletableDeferred<WorkerResponse>()
        pendingRequests[request.id] = deferred
        sendDirect(request)
        return try {
            withTimeout(timeoutMs) {
                deferred.await()
            }
        } finally {
            pendingRequests.remove(request.id)
        }
    }

    fun sendDirect(request: WorkerRequest) {
        val writer = processWriter ?: throw IOException("Process stdin writer is not available")
        val json = WorkerProtocolSerializer.serializeRequest(request)
        synchronized(writer) {
            writer.write(json)
            writer.newLine()
            writer.flush()
        }
    }

    fun sendCallbackResponse(response: WorkerCallbackResponse) {
        val writer = processWriter ?: return
        val json = WorkerProtocolSerializer.serializeCallbackResponse(response)
        synchronized(writer) {
            writer.write(json)
            writer.newLine()
            writer.flush()
        }
    }

    fun recycleWorker() {
        try {
            currentProcess?.destroy()
        } catch (t: Throwable) {
            logger.debug("Worker process destroy: ${t.message}")
        }
        currentProcess = null
        currentJobObject?.close()
        currentJobObject = null
    }

    override fun close() {
        recycleWorker()
    }
}
