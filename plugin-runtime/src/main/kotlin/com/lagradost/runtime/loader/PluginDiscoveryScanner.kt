package com.lagradost.runtime.loader

import com.lagradost.cloudstream3.app
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.storage.PluginSettingsSchemaRegistry
import java.lang.reflect.Modifier
import java.util.LinkedHashMap

object PluginDiscoveryScanner {

    fun autoDiscoverPluginSubProviders(
        classLoader: ClassLoader,
        classNames: Set<String>,
        pluginInternalName: String,
    ) {
        val prefName = "${pluginInternalName}_"
        val candidateMethodNames = listOf("buildProviders", "getProviders", "getBuiltInProviders", "getSources", "loadProviders")

        for (className in classNames) {
            val lower = className.lowercase()
            if (!lower.contains("registry") &&
                !lower.contains("provider") &&
                !lower.contains("plugin") &&
                !lower.contains("source")
            ) {
                continue
            }

            try {
                val clazz = Class.forName(className, false, classLoader)
                for (method in clazz.methods) {
                    if (method.parameterCount == 0 && candidateMethodNames.any { method.name.equals(it, ignoreCase = true) }) {
                        val isStatic = Modifier.isStatic(method.modifiers)
                        val target = if (isStatic) {
                            null
                        } else {
                            clazz.fields.firstOrNull { it.name == "INSTANCE" }?.get(null)
                        }
                        if (target == null && !isStatic) continue
                        val collection = method.invoke(target) as? Collection<*> ?: continue
                        if (collection.isEmpty()) continue

                        val optionsMap = LinkedHashMap<String, String>()
                        for (item in collection) {
                            if (item == null) continue
                            val itemClass = item.javaClass
                            val id = (itemClass.methods.firstOrNull { it.name in listOf("getId", "getKey", "getTag") && it.parameterCount == 0 }?.invoke(item) as? String)
                                ?: continue
                            val name = (itemClass.methods.firstOrNull { it.name in listOf("getName", "getTitle", "getDisplayName") && it.parameterCount == 0 }?.invoke(item) as? String)
                                ?: id
                            optionsMap[id] = name
                        }

                        if (optionsMap.isNotEmpty()) {
                            AppLogger.i("Auto-discovered ${optionsMap.size} sub-providers for $pluginInternalName via ${clazz.name}.${method.name}")

                            PluginSettingsSchemaRegistry.register(
                                pluginPrefName = prefName,
                                key = "disabled_providers",
                                type = "StringSet",
                                defaultValue = emptySet<String>(),
                                isGlobal = false,
                                options = optionsMap,
                            )
                            for ((id, _) in optionsMap) {
                                PluginSettingsSchemaRegistry.register(
                                    pluginPrefName = prefName,
                                    key = id,
                                    type = "String",
                                    defaultValue = "true",
                                    isGlobal = false,
                                )
                            }
                            return
                        }
                    }
                }
            } catch (t: Throwable) {
                AppLogger.d("[PluginLoader] Sub-provider reflection check failed for $className: ${t.message}")
            }
        }
    }

    fun synchronizePluginNetworkClients(
        classLoader: ClassLoader,
        classNames: Set<String>,
    ) {
        val globalBase = app.baseClient

        fun syncRequests(requests: Any?, source: String) {
            if (requests == null) return
            try {
                if (requests is com.lagradost.nicehttp.Requests) {
                    val hasCfKiller = requests.baseClient.interceptors.any {
                        it.javaClass.name.contains("CloudflareKiller")
                    }
                    if (!hasCfKiller) {
                        requests.baseClient = globalBase
                        com.lagradost.runtime.loader.stubs.RequestsStub.syncedClients.add(requests)
                        AppLogger.d("[PluginLoader] Synchronized Requests instance ($source) to global baseClient.")
                    }
                }
            } catch (t: Throwable) {
                AppLogger.w("[PluginLoader] Failed to sync Requests instance ($source): ${t.message}")
            }
        }

        // 1. Check candidate classes in this plugin JAR for static Requests fields (lazy, initialize = false)
        val candidateClasses = classNames.filter { name ->
            val lower = name.lowercase()
            lower.contains("client") || lower.contains("util") || lower.contains("api") ||
                lower.contains("network") || lower.contains("provider") || lower.contains("plugin")
        }
        for (className in candidateClasses) {
            try {
                val clazz = Class.forName(className, false, classLoader)
                for (field in clazz.declaredFields) {
                    if (Modifier.isStatic(field.modifiers) &&
                        com.lagradost.nicehttp.Requests::class.java.isAssignableFrom(field.type)
                    ) {
                        field.isAccessible = true
                        val req = field.get(null)
                        syncRequests(req, "static field ${clazz.name}.${field.name}")
                    }
                }
            } catch (t: Throwable) {
                AppLogger.d("[PluginLoader] Candidate class inspection failed for $className: ${t.message}")
            }
        }

        // 2. Sweep only registered providers belonging to this plugin's ClassLoader
        try {
            val providersToSync = synchronized(com.lagradost.cloudstream3.APIHolder.allProviders) {
                com.lagradost.cloudstream3.APIHolder.allProviders.filter { it.javaClass.classLoader == classLoader }
            }
            for (provider in providersToSync) {
                var currentClass: Class<*>? = provider.javaClass
                while (currentClass != null && currentClass != Any::class.java) {
                    for (field in currentClass.declaredFields) {
                        if (!Modifier.isStatic(field.modifiers) &&
                            com.lagradost.nicehttp.Requests::class.java.isAssignableFrom(field.type)
                        ) {
                            field.isAccessible = true
                            val req = field.get(provider)
                            syncRequests(req, "provider field ${provider.name}.${field.name}")
                        }
                    }
                    currentClass = currentClass.superclass
                }
            }
        } catch (t: Throwable) {
            AppLogger.d("[PluginLoader] Error sweeping provider network clients: ${t.message}")
        }

        // 3. Sweep only registered extractors belonging to this plugin's ClassLoader
        try {
            val extractorsToSync = synchronized(com.lagradost.cloudstream3.utils.extractorApis) {
                com.lagradost.cloudstream3.utils.extractorApis.filter { it.javaClass.classLoader == classLoader }
            }
            for (extractor in extractorsToSync) {
                var currentClass: Class<*>? = extractor.javaClass
                while (currentClass != null && currentClass != Any::class.java) {
                    for (field in currentClass.declaredFields) {
                        if (!Modifier.isStatic(field.modifiers) &&
                            com.lagradost.nicehttp.Requests::class.java.isAssignableFrom(field.type)
                        ) {
                            field.isAccessible = true
                            val req = field.get(extractor)
                            syncRequests(req, "extractor field ${extractor.name}.${field.name}")
                        }
                    }
                    currentClass = currentClass.superclass
                }
            }
        } catch (t: Throwable) {
            AppLogger.d("[PluginLoader] Error sweeping extractor network clients: ${t.message}")
        }
    }
}
