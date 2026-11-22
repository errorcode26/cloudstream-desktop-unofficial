package com.lagradost.runtime.loader

import android.content.DesktopContextProvider
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.platform.PlatformPaths
import com.lagradost.common.storage.DesktopDataStore
import com.lagradost.common.storage.PluginSettingsSchemaRegistry
import java.io.File
import java.net.URLClassLoader
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

object ExtensionLoader {

    init {
        try {
            java.net.URLConnection.setDefaultUseCaches("jar", false)
        } catch (t: Throwable) {
            AppLogger.d("[PluginLoader] Could not disable jar URL cache: ${t.message}")
        }
    }

    private val mapper = ObjectMapper().registerModule(kotlinModule())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    // Keep track of loaded plugins by absolute and canonical paths
    val plugins: MutableMap<String, BasePlugin> = ConcurrentHashMap()
    val settingsOnlyPlugins: MutableMap<String, Plugin> = ConcurrentHashMap()
    private val pluginLoadLocks: ConcurrentHashMap<String, Any> = ConcurrentHashMap()

    // Map class loader to plugin name
    val classLoaders: MutableMap<ClassLoader, String> = ConcurrentHashMap()

    // Map class loader to jar file
    val classLoaderToJar: MutableMap<ClassLoader, File> = ConcurrentHashMap()

    // Map class loader to class names loaded from its jar
    val classLoaderToClassNames: MutableMap<ClassLoader, Set<String>> = ConcurrentHashMap()

    // Master registry of all registered providers across all loaded plugins
    val masterProviders: ConcurrentHashMap<String, com.lagradost.cloudstream3.MainAPI> = ConcurrentHashMap()

    /**
     * Filters APIHolder.allProviders and APIHolder.apis against the user's active studio/provider settings.
     */
    fun filterActiveProviders() {
        try {
            synchronized(com.lagradost.cloudstream3.APIHolder.allProviders) {
                com.lagradost.cloudstream3.APIHolder.allProviders.forEach { p ->
                    masterProviders[p.name] = p
                }
                if (masterProviders.isEmpty()) return

                val active = masterProviders.values.filter { provider ->
                    isProviderActive(provider)
                }

                com.lagradost.cloudstream3.APIHolder.allProviders.clear()
                com.lagradost.cloudstream3.APIHolder.allProviders.addAll(active)

                com.lagradost.cloudstream3.APIHolder.apis = com.lagradost.cloudstream3.utils.Coroutines.atomicListOf(*active.toTypedArray())
                com.lagradost.cloudstream3.APIHolder.apiMap = null
            }
        } catch (t: Throwable) {
            AppLogger.w("[PluginLoader] Failed filtering active providers: ${t.message}")
        }
    }

    private fun isProviderActive(provider: com.lagradost.cloudstream3.MainAPI): Boolean {
        try {
            for ((_, schemas) in PluginSettingsSchemaRegistry.schemas) {
                for ((schemaKey, schema) in schemas) {
                    if (schema.type == "StringSet") {
                        val fullStorageKey = schema.storageKey
                        val savedSet = DesktopDataStore.getKey<Set<String>>(fullStorageKey)
                            ?: DesktopDataStore.getKey<Set<String>>(schemaKey)
                        if (savedSet != null) {
                            val defaultSet = (schema.defaultValue as? Set<*>)?.mapNotNull { it?.toString() }?.toSet()
                            val isDisablingKey = schemaKey.contains("disable", ignoreCase = true)
                            if (isDisablingKey) {
                                if (provider.name in savedSet) return false
                            } else if (defaultSet != null && defaultSet.contains(provider.name)) {
                                if (provider.name !in savedSet) return false
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            AppLogger.d("[PluginLoader] Error evaluating isProviderActive for ${provider.name}: ${t.message}")
        }
        return true
    }

    fun createCompositeClassLoader(fallback: ClassLoader): ClassLoader {
        val pluginLoaders = classLoaders.keys.toList()
        return object : ClassLoader(fallback) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                for (loader in pluginLoaders) {
                    try {
                        return loader.loadClass(name)
                    } catch (_: ClassNotFoundException) {
                    }
                }
                return super.loadClass(name, resolve)
            }
        }
    }

    fun getCallingPluginName(): String? {
        try {
            val walker = java.lang.StackWalker.getInstance(java.lang.StackWalker.Option.RETAIN_CLASS_REFERENCE)
            val name = walker.walk { stream ->
                stream.map { it.declaringClass }
                    .filter { clazz ->
                        val loader = clazz.classLoader
                        loader != null && classLoaders.containsKey(loader)
                    }
                    .map { clazz -> classLoaders[clazz.classLoader] }
                    .findFirst()
                    .orElse(null)
            }
            if (name != null) return name
        } catch (e: Throwable) {
            AppLogger.d("[PluginLoader] StackWalker resolution failed, using stackTrace fallback: ${e.message}")
        }

        val stackTrace = Thread.currentThread().stackTrace
        for (element in stackTrace) {
            val className = element.className
            if (className.startsWith("com.lagradost.") || className.startsWith("java.") || className.startsWith("kotlin.")) continue

            for ((loader, name) in classLoaders) {
                val classes = classLoaderToClassNames[loader]
                if (classes != null && classes.contains(className)) {
                    return name
                }
            }
        }
        return null
    }

    var nativePluginInterceptor: ((String) -> BasePlugin?)? = null
    var sandboxLoadInterceptor: ((jarFile: File, forceRetry: Boolean) -> BasePlugin?)? = null
    var sandboxUnloadInterceptor: ((jarFile: File) -> Unit)? = null

    // Backward-compatible delegates
    typealias TranspiledPluginInfo = PluginDexTranspiler.TranspiledPluginInfo

    fun clearFailedMarker(jarFile: File) = PluginDexTranspiler.clearFailedMarker(jarFile)

    fun transpileOrVerify(
        jarFile: File,
        fallbackPluginClassName: String? = null,
        forceBypassSecurity: Boolean = false,
        forceRetry: Boolean = false,
    ): PluginDexTranspiler.TranspiledPluginInfo =
        PluginDexTranspiler.transpileOrVerify(jarFile, fallbackPluginClassName, forceBypassSecurity, forceRetry)

    fun getPluginAliases(
        jarFile: File? = null,
        internalName: String? = null,
        pluginClassName: String? = null,
        manifestName: String? = null,
    ): Set<String> = PluginTrustManager.getPluginAliases(jarFile, internalName, pluginClassName, manifestName)

    fun isTrusted(
        jarFile: File,
        internalName: String? = null,
        pluginClassName: String? = null,
        manifestName: String? = null,
    ): Boolean = PluginTrustManager.isTrusted(jarFile, internalName, pluginClassName, manifestName)

    fun addTrusted(
        jarFile: File,
        internalName: String? = null,
        pluginClassName: String? = null,
        manifestName: String? = null,
    ) = PluginTrustManager.addTrusted(jarFile, internalName, pluginClassName, manifestName)

    fun removeTrusted(
        jarFile: File? = null,
        internalName: String? = null,
        pluginClassName: String? = null,
        manifestName: String? = null,
    ) = PluginTrustManager.removeTrusted(jarFile, internalName, pluginClassName, manifestName)

    fun loadJar(
        jarFile: File,
        fallbackPluginClassName: String? = null,
        forceBypassSecurity: Boolean = false,
        forceRetry: Boolean = false,
    ): BasePlugin {
        if (sandboxLoadInterceptor != null) {
            throw SecurityException(
                "Security boundary enforced: Direct in-process loadJar('${jarFile.name}') is permanently forbidden when sandbox isolation is active."
            )
        }
        val info = transpileOrVerify(jarFile, fallbackPluginClassName, forceBypassSecurity, forceRetry)
        val jarToLoad = info.jarToLoad
        val pluginClassName = info.pluginClassName
        val finalInternalName = info.internalName
        val isPluginTrusted = info.isTrusted

        AppLogger.i("[PluginLoader] Initializing class $pluginClassName from ${jarToLoad.name}")

        val nativeIntercept = nativePluginInterceptor?.invoke(pluginClassName)
        val pluginInstance: BasePlugin = if (nativeIntercept != null) {
            AppLogger.i("Intercepted plugin $pluginClassName! Injecting native JVM implementation.")
            nativeIntercept
        } else {
            val safeParentLoader = SafePluginClassLoader(this::class.java.classLoader, isPluginTrusted)
            val classLoader = CompatPluginClassLoader(arrayOf(jarToLoad.toURI().toURL()), safeParentLoader)
            val pluginClass = classLoader.loadClass(pluginClassName)

            val instance = pluginClass.getDeclaredConstructor().newInstance() as BasePlugin
            classLoaders[classLoader] = finalInternalName
            classLoaderToJar[classLoader] = jarFile

            val classNames = mutableSetOf<String>()
            try {
                ZipFile(jarToLoad).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.name.endsWith(".class")) {
                            val cName = entry.name.removeSuffix(".class").replace("/", ".")
                            classNames.add(cName)
                        }
                    }
                }
            } catch (t: Throwable) {
                AppLogger.w("[PluginLoader] Failed scanning class names from ${jarToLoad.name}: ${t.message}")
            }
            classLoaderToClassNames[classLoader] = classNames

            PluginSettingsSchemaRegistry.schemas["${finalInternalName}_"]?.clear()

            PluginXmlPreferenceScanner.scanAllXmlPreferences(jarToLoad, finalInternalName)
            PluginDiscoveryScanner.autoDiscoverPluginSubProviders(classLoader, classNames, finalInternalName)

            instance
        }

        pluginInstance.filename = jarFile.absolutePath
        plugins[jarFile.absolutePath] = pluginInstance
        try {
            plugins[jarFile.canonicalPath] = pluginInstance
        } catch (t: Throwable) {
            AppLogger.d("[PluginLoader] Canonical path registration skipped for ${jarFile.name}: ${t.message}")
        }

        try {
            synchronized(com.lagradost.cloudstream3.APIHolder.allProviders) {
                com.lagradost.cloudstream3.APIHolder.allProviders.forEach { provider ->
                    if (provider.sourcePlugin == null && provider.sourcePlugin != "built-in") {
                        provider.sourcePlugin = jarFile.absolutePath
                    }
                }
                val seenKeys = mutableSetOf<String>()
                val toKeep = mutableListOf<com.lagradost.cloudstream3.MainAPI>()
                for (provider in com.lagradost.cloudstream3.APIHolder.allProviders.reversed()) {
                    val uniqueKey = provider.name
                    if (seenKeys.add(uniqueKey)) {
                        toKeep.add(provider)
                    } else {
                        try {
                            com.lagradost.cloudstream3.APIHolder.removePluginMapping(provider)
                        } catch (ignored: Throwable) {
                            AppLogger.d("[PluginLoader] Failed removing duplicate mapping for ${provider.name}: ${ignored.message}")
                        }
                    }
                }
                com.lagradost.cloudstream3.APIHolder.allProviders.clear()
                com.lagradost.cloudstream3.APIHolder.allProviders.addAll(toKeep.reversed())
                toKeep.forEach { masterProviders[it.name] = it }
                filterActiveProviders()
            }
            com.lagradost.cloudstream3.APIHolder.apis.forEach { provider ->
                if (provider.sourcePlugin == null && provider.sourcePlugin != "built-in") {
                    provider.sourcePlugin = jarFile.absolutePath
                }
            }
            synchronized(com.lagradost.cloudstream3.utils.extractorApis) {
                com.lagradost.cloudstream3.utils.extractorApis.forEach { extractor ->
                    if (extractor.sourcePlugin == null) {
                        extractor.sourcePlugin = jarFile.absolutePath
                    }
                }
                val seenExtKeys = mutableSetOf<String>()
                val extsToKeep = mutableListOf<com.lagradost.cloudstream3.utils.ExtractorApi>()
                for (ext in com.lagradost.cloudstream3.utils.extractorApis.reversed()) {
                    val uniqueKey = ext.name
                    if (seenExtKeys.add(uniqueKey)) {
                        extsToKeep.add(ext)
                    }
                }
                com.lagradost.cloudstream3.utils.extractorApis.clear()
                com.lagradost.cloudstream3.utils.extractorApis.addAll(extsToKeep.reversed())
            }
        } catch (t: Throwable) {
            AppLogger.i("Failed to backfill sourcePlugin or deduplicate for ${jarFile.name}: ${t.message}")
        }

        return pluginInstance
    }

    fun getSettingsOnlyPluginInstance(jarFile: File): Plugin? {
        AppLogger.w("[ExtensionLoader] In-process execution of '${jarFile.name}' for settings is permanently forbidden. Dynamic settings are executed inside the AppContainer sandbox.")
        return null
    }

    fun loadAndInit(
        jarFile: File,
        fallbackPluginClassName: String? = null,
        forceBypassSecurity: Boolean = false,
        forceRetry: Boolean = false,
    ): BasePlugin {
        val canonicalPath = try {
            jarFile.canonicalPath
        } catch (_: Throwable) {
            jarFile.absolutePath
        }
        val lock = pluginLoadLocks.computeIfAbsent(canonicalPath) { Any() }

        synchronized(lock) {
            try {
                if (!forceRetry) {
                    val existing = plugins[canonicalPath] ?: plugins[jarFile.absolutePath]
                    if (existing != null) {
                        AppLogger.i("[ExtensionLoader] Plugin already loaded: ${jarFile.name}. Reusing active instance.")
                        return existing
                    }
                }

                val interceptor = sandboxLoadInterceptor
                if (interceptor != null) {
                    val sandboxed = try {
                        interceptor(jarFile, forceRetry)
                    } catch (e: Throwable) {
                        throw SecurityException(
                            "Security boundary enforced: Plugin '${jarFile.name}' failed AppContainer sandbox isolation (${e.message}). In-process execution is permanently forbidden.",
                            e,
                        )
                    }
                    if (sandboxed != null) {
                        plugins[canonicalPath] = sandboxed
                        plugins[jarFile.absolutePath] = sandboxed
                        return sandboxed
                    } else {
                        throw SecurityException("Security boundary enforced: Plugin '${jarFile.name}' was rejected by the AppContainer sandbox host. In-process fallback is permanently disabled.")
                    }
                }

                val pluginInstance = loadJar(jarFile, fallbackPluginClassName, forceBypassSecurity, forceRetry)
                plugins[canonicalPath] = pluginInstance
                plugins[jarFile.absolutePath] = pluginInstance
                initializePlugin(pluginInstance)
                return pluginInstance
            } finally {
                pluginLoadLocks.remove(canonicalPath)
            }
        }
    }

    fun initializePlugin(pluginInstance: BasePlugin) {
        val loader = pluginInstance.javaClass.classLoader
        val pluginName = (loader?.let { classLoaders[it] })
            ?: pluginInstance.filename?.let { File(it).nameWithoutExtension.removeSuffix("-jvm").removeSuffix("-secure") }
            ?: "Plugin"

        if (pluginInstance is Plugin) {
            val storageRoot = com.lagradost.runtime.loader.stubs.PluginFileSecurityStub.getStorageRootForPlugin(pluginName)
            val pluginContext = DesktopContextProvider.createPluginContext(pluginName, storageRoot)
            pluginInstance.activity = pluginContext
            pluginInstance.resources = pluginContext.resources
            pluginInstance.load(pluginContext)
        } else {
            pluginInstance.load()
        }
        if (loader != null) {
            val names = classLoaderToClassNames[loader] ?: emptySet()
            PluginDiscoveryScanner.synchronizePluginNetworkClients(loader, names)
            val internalName = classLoaders[loader]
            if (internalName != null) {
                com.lagradost.runtime.executor.PluginCircuitBreaker.resetProvider(internalName)
            }
        }
        val pName = pluginInstance.filename?.let { File(it).nameWithoutExtension.removeSuffix("-jvm") } ?: pluginName
        com.lagradost.runtime.executor.PluginCircuitBreaker.resetProvider(pName)
    }

    fun unloadPlugin(absolutePath: String) {
        val normPath = File(absolutePath).absolutePath
        val canonicalPath = try {
            File(absolutePath).canonicalPath
        } catch (_: Throwable) {
            normPath
        }
        val plugin = plugins[normPath] ?: plugins[absolutePath] ?: plugins[canonicalPath]

        if (plugin != null) {
            try {
                plugin.beforeUnload()
            } catch (t: Throwable) {
                AppLogger.i("Failed to run beforeUnload for $absolutePath: ${t.message}")
            }
        }

        val settingsPlugin = settingsOnlyPlugins[normPath] ?: settingsOnlyPlugins[absolutePath] ?: settingsOnlyPlugins[canonicalPath]
        if (settingsPlugin != null) {
            try {
                settingsPlugin.beforeUnload()
            } catch (t: Throwable) {
                AppLogger.i("Failed to run beforeUnload for settings plugin $absolutePath: ${t.message}")
            }
        }

        val pathsToRemove = setOfNotNull(normPath, absolutePath, canonicalPath, plugin?.filename)

        val loadersToClose = buildSet<ClassLoader> {
            plugin?.javaClass?.classLoader?.let { own ->
                if (own is CompatPluginClassLoader) add(own)
            }
            classLoaderToJar.entries.forEach { e ->
                val matches = pathsToRemove.any { p ->
                    p.equals(e.value.absolutePath, ignoreCase = true) ||
                        p.equals(e.value.canonicalPath, ignoreCase = true) ||
                        File(p).name.equals(e.value.name, ignoreCase = true) ||
                        File(p).nameWithoutExtension.substringBefore("-secure").substringBefore("-jvm").equals(
                            e.value.nameWithoutExtension.substringBefore("-secure").substringBefore("-jvm"),
                            ignoreCase = true,
                        )
                }
                if (matches) add(e.key)
            }
        }
        loadersToClose.forEach { classLoader ->
            classLoaders.remove(classLoader)
            classLoaderToJar.remove(classLoader)
            classLoaderToClassNames.remove(classLoader)
            if (classLoader is java.io.Closeable) {
                try {
                    classLoader.close()
                } catch (t: Throwable) {
                    AppLogger.i("Failed to close URLClassLoader for $absolutePath: ${t.message}")
                }
            }
        }

        try {
            com.lagradost.cloudstream3.APIHolder.apis.filter { p -> pathsToRemove.any { p.sourcePlugin?.equals(it, ignoreCase = true) == true } }.forEach {
                com.lagradost.cloudstream3.APIHolder.removePluginMapping(it)
            }
            synchronized(com.lagradost.cloudstream3.APIHolder.allProviders) {
                com.lagradost.cloudstream3.APIHolder.allProviders.removeIf { p -> pathsToRemove.any { p.sourcePlugin?.equals(it, ignoreCase = true) == true } }
            }
            masterProviders.entries.removeIf { e -> pathsToRemove.any { e.value.sourcePlugin?.equals(it, ignoreCase = true) == true } }
        } catch (t: Throwable) {
            AppLogger.i("Failed to remove plugin mappings for $absolutePath: ${t.message}")
        }

        try {
            synchronized(com.lagradost.cloudstream3.utils.extractorApis) {
                com.lagradost.cloudstream3.utils.extractorApis.removeIf { e -> pathsToRemove.any { e.sourcePlugin?.equals(it, ignoreCase = true) == true } }
            }
        } catch (t: Throwable) {
            AppLogger.w("ExtensionLoader: Failed to unregister extractorApis for $absolutePath: ${t.message}")
        }

        try {
            com.lagradost.cloudstream3.actions.VideoClickActionHolder.allVideoClickActions.removeIf { a -> pathsToRemove.any { a.sourcePlugin?.equals(it, ignoreCase = true) == true } }
        } catch (t: Throwable) {
            AppLogger.w("ExtensionLoader: Failed to unregister videoClickActions for $absolutePath: ${t.message}")
        }

        pathsToRemove.forEach {
            plugins.remove(it)
            settingsOnlyPlugins.remove(it)
        }
        try {
            sandboxUnloadInterceptor?.invoke(File(absolutePath))
        } catch (t: Throwable) {
            AppLogger.d("[PluginLoader] Sandbox unload interceptor error for $absolutePath: ${t.message}")
        }
        try {
            clearFailedMarker(File(absolutePath))
        } catch (t: Throwable) {
            AppLogger.d("[PluginLoader] clearFailedMarker error for $absolutePath: ${t.message}")
        }
    }

    fun unloadAllPlugins() {
        val allPaths = plugins.keys.toList()
        for (path in allPaths) {
            try {
                unloadPlugin(path)
            } catch (t: Throwable) {
                AppLogger.e("Failed to unload plugin $path: ${t.message}")
            }
        }
        for ((loader, _) in classLoaders.toList()) {
            if (loader is java.io.Closeable) {
                try {
                    loader.close()
                } catch (t: Throwable) {
                    AppLogger.d("[PluginLoader] Error closing Closeable resource: ${t.message}")
                }
            }
        }
        classLoaders.clear()
        classLoaderToJar.clear()
        classLoaderToClassNames.clear()
        plugins.clear()
        settingsOnlyPlugins.clear()
    }

    fun isPluginLoaded(absolutePath: String): Boolean {
        val file = File(absolutePath)
        val canonical = try {
            file.canonicalPath
        } catch (_: Throwable) {
            file.absolutePath
        }
        return plugins.containsKey(absolutePath) ||
            plugins.containsKey(file.absolutePath) ||
            plugins.containsKey(canonical) ||
            pluginLoadLocks.containsKey(canonical) ||
            pluginLoadLocks.containsKey(file.absolutePath)
    }

    fun getPlugin(absolutePath: String): BasePlugin? = plugins[absolutePath]

    fun rescanAndLoadNewPlugins(extensionsDir: File): Int {
        if (!extensionsDir.exists()) return 0

        var loaded = 0
        extensionsDir.walkTopDown()
            .filter { it.isFile && (it.extension == "jar" || it.extension == "cs3") }
            .filter { !it.name.endsWith("-jvm.jar") }
            .sortedBy { it.lastModified() }
            .forEach { jar ->
                if (!isPluginLoaded(jar.absolutePath)) {
                    try {
                        loadAndInit(jar)
                        loaded++
                        AppLogger.i("Rescan: loaded ${jar.name}")
                    } catch (e: Throwable) {
                        AppLogger.e("Rescan: failed ${jar.name}", e)
                    }
                }
            }
        return loaded
    }

    @JvmStatic
    fun parsePluginPreferences(fragment: Any, resId: Int) =
        PluginXmlPreferenceScanner.parsePluginPreferences(fragment, resId)

    @JvmStatic
    fun scanAllXmlPreferences(jarFile: File, pluginPrefName: String) =
        PluginXmlPreferenceScanner.scanAllXmlPreferences(jarFile, pluginPrefName)

    fun autoDiscoverPluginSubProviders(
        classLoader: ClassLoader,
        classNames: Set<String>,
        pluginInternalName: String,
    ) = PluginDiscoveryScanner.autoDiscoverPluginSubProviders(classLoader, classNames, pluginInternalName)

    fun synchronizePluginNetworkClients(
        classLoader: ClassLoader,
        classNames: Set<String>,
    ) = PluginDiscoveryScanner.synchronizePluginNetworkClients(classLoader, classNames)
}
