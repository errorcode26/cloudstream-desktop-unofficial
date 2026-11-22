package com.lagradost.runtime.loader

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.platform.PlatformPaths
import com.lagradost.runtime.dex.DexConverter
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

object PluginDexTranspiler {
    private val mapper = ObjectMapper().registerModule(kotlinModule())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    private val dex2JarLock = Any()
    private val failedDexCache: MutableSet<String> = ConcurrentHashMap.newKeySet()

    data class TranspiledPluginInfo(
        val jarToLoad: File,
        val pluginClassName: String,
        val internalName: String,
        val displayName: String?,
        val isTrusted: Boolean,
    )

    fun copyWithRetry(source: File, destination: File, maxRetries: Int = 3) {
        var lastException: Throwable? = null
        for (attempt in 1..maxRetries) {
            try {
                Files.copy(
                    source.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
                return
            } catch (t: Throwable) {
                lastException = t
                if (attempt < maxRetries) {
                    @Suppress("ExplicitGarbageCollectionCall")
                    System.gc()
                    Thread.sleep(50L * attempt)
                }
            }
        }
        lastException?.let { throw it }
    }

    fun clearFailedMarker(jarFile: File) {
        try {
            val baseName = jarFile.nameWithoutExtension.substringBefore("-jvm").substringBefore("-secure")
            File(PlatformPaths.pluginCacheDir, "$baseName.d2j_failed").let { if (it.exists()) it.delete() }
            val failedMarker = File(jarFile.parentFile, "$baseName.d2j_failed")
            if (failedMarker.exists()) failedMarker.delete()
            failedDexCache.remove(baseName)
        } catch (t: Throwable) {
            AppLogger.d("[PluginLoader] Could not clear failure markers for ${jarFile.name}: ${t.message}")
        }
    }

    fun checkJarHasClass(jar: File, className: String): Boolean {
        return try {
            val entryPath = className.replace('.', '/') + ".class"
            ZipFile(jar).use { zip ->
                zip.getEntry(entryPath) != null
            }
        } catch (e: Exception) {
            false
        }
    }

    fun checkJarHasTransformerVersion(jar: File): Boolean {
        return try {
            ZipFile(jar).use { zip ->
                val entry = zip.getEntry("META-INF/shadowui.version")
                if (entry != null) {
                    val ver = zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8).trim() }
                    ver == "3"
                } else {
                    false
                }
            }
        } catch (e: Exception) {
            AppLogger.d("[PluginLoader] Failed reading shadowui transformer version: ${e.message}")
            false
        }
    }

    fun transpileOrVerify(
        jarFile: File,
        fallbackPluginClassName: String? = null,
        forceBypassSecurity: Boolean = false,
        forceRetry: Boolean = false,
    ): TranspiledPluginInfo {
        if (!jarFile.exists()) {
            throw IllegalArgumentException("Jar file does not exist: ${jarFile.absolutePath}")
        }

        var pluginClassName = fallbackPluginClassName
        var internalNameFromManifest: String? = null
        var nameFromManifest: String? = null
        var jarToLoad = jarFile

        ZipFile(jarFile).use { zip ->
            val manifestEntry = zip.getEntry("manifest.json")
            if (manifestEntry != null) {
                zip.getInputStream(manifestEntry).use { input ->
                    val manifestData = mapper.readValue(input, Map::class.java)
                    val className = manifestData["pluginClassName"] as? String
                    if (className != null) {
                        pluginClassName = className
                    }
                    internalNameFromManifest = manifestData["internalName"] as? String
                    nameFromManifest = manifestData["name"] as? String
                }
            }

            val hasJvmClasses = zip.entries().asSequence().any { it.name.endsWith(".class") }
            val dexEntry = zip.getEntry("classes.dex")

            if (hasJvmClasses) {
                val secureJar = if (jarFile.name.endsWith("-secure.jar")) {
                    jarFile
                } else {
                    val target = File(PlatformPaths.pluginCacheDir, jarFile.nameWithoutExtension.substringBefore("-secure") + "-secure.jar")
                    val legacy = File(jarFile.parentFile, jarFile.nameWithoutExtension.substringBefore("-secure") + "-secure.jar")
                    if (!target.exists() && legacy.exists() && legacy != jarFile) {
                        try {
                            Files.move(legacy.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        } catch (t: Throwable) {
                            AppLogger.d("[PluginLoader] Legacy cache move note: ${t.message}")
                        }
                    }
                    target
                }
                val targetClass = pluginClassName
                val isCacheValid = secureJar == jarFile || (
                    secureJar.exists() && secureJar.lastModified() >= jarFile.lastModified() &&
                        checkJarHasTransformerVersion(secureJar) &&
                        (targetClass == null || checkJarHasClass(secureJar, targetClass))
                    )

                if (!isCacheValid) {
                    AppLogger.i("[PluginLoader] Securing Native JVM JAR: ${jarFile.name}...")
                    val targetToSecurify = try {
                        copyWithRetry(jarFile, secureJar)
                        secureJar
                    } catch (e: Throwable) {
                        throw IllegalStateException(
                            "Cannot replace locked secure cache '${secureJar.name}' for ${jarFile.name}: ${e.message}",
                            e,
                        )
                    }
                    PluginBytecodeTransformer.transform(targetToSecurify)
                    jarToLoad = targetToSecurify
                } else if (secureJar != jarFile) {
                    AppLogger.i("[PluginLoader] Using cached Secure JVM JAR: ${secureJar.name}")
                }
                if (isCacheValid) jarToLoad = secureJar
            } else if (dexEntry != null) {
                val convertedJar = run {
                    val target = File(PlatformPaths.pluginCacheDir, jarFile.nameWithoutExtension + "-jvm.jar")
                    val legacy = File(jarFile.parentFile, jarFile.nameWithoutExtension + "-jvm.jar")
                    if (!target.exists() && legacy.exists()) {
                        try {
                            Files.move(legacy.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        } catch (t: Throwable) {
                            AppLogger.d("[PluginLoader] Legacy cache move note: ${t.message}")
                        }
                    }
                    val legacyTmp = File(jarFile.parentFile, jarFile.nameWithoutExtension + "-jvm.jar.tmp")
                    if (legacyTmp.exists()) {
                        try { legacyTmp.delete() } catch (t: Throwable) {
                            AppLogger.d("[PluginLoader] Legacy tmp delete note: ${t.message}")
                        }
                    }
                    target
                }
                val baseName = jarFile.nameWithoutExtension.substringBefore("-jvm").substringBefore("-secure")
                val failedMarker = File(PlatformPaths.pluginCacheDir, "$baseName.d2j_failed")
                val legacyFailedMarker = File(jarFile.parentFile, "$baseName.d2j_failed")
                val isFailedMarkerPresent = failedMarker.exists() || legacyFailedMarker.exists()

                if (forceRetry || (convertedJar.exists() && checkJarHasTransformerVersion(convertedJar))) {
                    try {
                        failedMarker.delete()
                        legacyFailedMarker.delete()
                    } catch (err: Throwable) {
                        AppLogger.d("[PluginLoader] Could not delete failedMarker for $baseName: ${err.message}")
                    }
                    failedDexCache.remove(baseName)
                } else if (isFailedMarkerPresent || failedDexCache.contains(baseName)) {
                    throw IllegalStateException("Skipping previously failed Dalvik DEX translation for ${jarFile.name} (un-transpilable bytecode)")
                }

                val dexTargetClass = pluginClassName
                val isCacheValid = !forceRetry && convertedJar.exists() && convertedJar.lastModified() >= jarFile.lastModified() &&
                    checkJarHasTransformerVersion(convertedJar) &&
                    (dexTargetClass == null || checkJarHasClass(convertedJar, dexTargetClass))

                if (!isCacheValid) {
                    synchronized(dex2JarLock) {
                        val isFailed = failedMarker.exists() || legacyFailedMarker.exists() || failedDexCache.contains(baseName)
                        if (!forceRetry && isFailed) {
                            throw IllegalStateException("Skipping previously failed Dalvik DEX translation for ${jarFile.name}")
                        }
                        if (forceRetry || !convertedJar.exists() || convertedJar.lastModified() < jarFile.lastModified() || !checkJarHasTransformerVersion(convertedJar)) {
                            var translationSucceeded = false
                            AppLogger.i("[PluginLoader] Transpiling Dalvik DEX -> JVM JAR for ${jarFile.name}...")
                            try {
                                AppLogger.i("[PluginLoader] Starting high-efficiency DEX translation (bitset liveness)...")
                                val result = DexConverter.convert(jarFile, convertedJar)
                                if (result.errors.isNotEmpty()) {
                                    AppLogger.w("[PluginLoader] DEX translation warnings for ${jarFile.name}: ${result.errors.joinToString("; ")}")
                                }
                                if (result.failedMethods.isNotEmpty()) {
                                    AppLogger.w("[PluginLoader] DEX translation could not translate ${result.failedMethods.size} methods in ${jarFile.name}: ${result.failedMethods.take(5).joinToString("; ")}")
                                }
                                AppLogger.i("[PluginLoader] DEX translation completed: ${result.classCount} classes in ${result.millis}ms (${result.compactedMethods.size} compacted).")
                                translationSucceeded = true
                            } catch (t: Throwable) {
                                val cacheStillCurrent = convertedJar.exists() &&
                                    convertedJar.lastModified() >= jarFile.lastModified() &&
                                    checkJarHasTransformerVersion(convertedJar) &&
                                    (dexTargetClass == null || checkJarHasClass(convertedJar, dexTargetClass))
                                if (cacheStillCurrent) {
                                    AppLogger.w("[PluginLoader] Could not rewrite ${convertedJar.name} (${t.message}); existing cache already matches source timestamp. Reusing.")
                                } else {
                                    AppLogger.e("[PluginLoader] DEX translation failed for ${jarFile.name}!", t)
                                    convertedJar.delete()
                                    try {
                                        failedMarker.createNewFile()
                                    } catch (err: Throwable) {
                                        AppLogger.d("[PluginLoader] Could not write failedMarker for $baseName: ${err.message}")
                                    }
                                    failedDexCache.add(baseName)
                                    if (t is VirtualMachineError) {
                                        System.gc()
                                    }
                                    throw IllegalStateException("Failed to transpile Dalvik DEX to JVM bytecode for ${jarFile.name}: cache is stale or locked (${t.message})", t)
                                }
                            }

                            if (!convertedJar.exists() || convertedJar.length() == 0L) {
                                convertedJar.delete()
                                try {
                                    failedMarker.createNewFile()
                                } catch (err: Throwable) {
                                    AppLogger.d("[PluginLoader] Could not write failedMarker for $baseName: ${err.message}")
                                }
                                failedDexCache.add(baseName)
                                throw IllegalStateException("DEX translation finished but no valid JAR was produced at ${convertedJar.absolutePath}")
                            } else if (translationSucceeded) {
                                PluginBytecodeTransformer.transform(convertedJar)
                            }
                        }
                    }
                } else {
                    AppLogger.i("[PluginLoader] Using cached JVM JAR: ${convertedJar.name}")
                }

                jarToLoad = convertedJar
            }
        }

        if (pluginClassName == null) {
            throw IllegalArgumentException("Could not determine pluginClassName from manifest.json and no fallback provided.")
        }

        val finalInternalName = internalNameFromManifest ?: nameFromManifest ?: pluginClassName.split(".").lastOrNull() ?: jarFile.nameWithoutExtension.removeSuffix("-jvm")

        val isPluginTrusted = forceBypassSecurity || PluginTrustManager.isTrusted(jarToLoad, finalInternalName, pluginClassName, nameFromManifest)
        if (forceBypassSecurity) {
            PluginTrustManager.addTrusted(jarToLoad, finalInternalName, pluginClassName, nameFromManifest)
        }

        AppLogger.i("Running static bytecode security verification on ${jarToLoad.name} (Trusted: $isPluginTrusted)...")
        com.lagradost.runtime.security.PluginSecurityVerifier.verifyJar(jarToLoad, finalInternalName, isPluginTrusted)

        return TranspiledPluginInfo(
            jarToLoad = jarToLoad,
            pluginClassName = pluginClassName,
            internalName = finalInternalName,
            displayName = nameFromManifest,
            isTrusted = isPluginTrusted,
        )
    }
}
