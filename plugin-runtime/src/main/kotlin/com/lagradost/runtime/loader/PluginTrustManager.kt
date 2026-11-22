package com.lagradost.runtime.loader

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.storage.DesktopDataStore
import java.io.File
import java.util.prefs.Preferences

object PluginTrustManager {
    private val mapper = jacksonObjectMapper()

    fun getTrustedList(): MutableList<String> {
        val list = mutableListOf<String>()
        try {
            list.addAll(DesktopDataStore.getTrustedPlugins())
        } catch (t: Throwable) {
            AppLogger.d("[PluginLoader] Error reading trusted plugins from DataStore: ${t.message}")
        }
        try {
            val prefs = Preferences.userRoot().node("cloudstream_desktop_prefs")
            val json = prefs.get("trusted_plugins", "[]")
            val legacy = mapper.readValue(json, object : TypeReference<List<String>>() {})
            for (item in legacy) {
                if (!list.contains(item)) list.add(item)
            }
        } catch (t: Throwable) {
            AppLogger.d("[PluginLoader] Error reading legacy trusted plugins from Preferences: ${t.message}")
        }
        return list
    }

    fun getPluginAliases(
        jarFile: File? = null,
        internalName: String? = null,
        pluginClassName: String? = null,
        manifestName: String? = null,
    ): Set<String> {
        val keys = mutableSetOf<String>()
        val repoDir = jarFile?.parentFile?.name?.lowercase()?.trim()

        fun addKey(k: String?) {
            if (k.isNullOrBlank()) return
            val clean = k.removeSuffix(".jar").removeSuffix(".cs3").removeSuffix("-jvm").removeSuffix("-secure").lowercase().trim()
            if (clean.isNotBlank()) {
                keys.add(clean)
                val stripped = clean.removeSuffix("provider").removeSuffix("plugin").removePrefix("com.")
                if (stripped.isNotBlank()) keys.add(stripped)
                val lastSegment = clean.substringAfterLast('.')
                if (lastSegment.isNotBlank()) keys.add(lastSegment)
                val lastStripped = lastSegment.removeSuffix("provider").removeSuffix("plugin")
                if (lastStripped.isNotBlank()) keys.add(lastStripped)

                if (repoDir != null && repoDir != "extensions") {
                    keys.add("$repoDir/$clean")
                    if (stripped.isNotBlank()) keys.add("$repoDir/$stripped")
                    if (lastSegment.isNotBlank()) keys.add("$repoDir/$lastSegment")
                    if (lastStripped.isNotBlank()) keys.add("$repoDir/$lastStripped")

                    val repoWithSpaces = repoDir.replace('_', ' ')
                    val repoClean = repoDir.replace(Regex("[^a-z0-9]"), "")
                    if (repoWithSpaces != repoDir) {
                        keys.add("$repoWithSpaces/$clean")
                        if (stripped.isNotBlank()) keys.add("$repoWithSpaces/$stripped")
                    }
                    if (repoClean.isNotBlank() && repoClean != repoDir) {
                        keys.add("$repoClean/$clean")
                        if (stripped.isNotBlank()) keys.add("$repoClean/$stripped")
                    }
                }
            }
        }

        jarFile?.nameWithoutExtension?.let { addKey(it) }
        internalName?.let { addKey(it) }
        manifestName?.let { addKey(it) }
        pluginClassName?.let {
            addKey(it)
            addKey(it.substringAfterLast('.'))
            addKey(it.substringBeforeLast('.'))
        }

        jarFile?.let {
            keys.add(it.absolutePath.lowercase().replace('\\', '/'))
            val relPath = "${repoDir ?: ""}/${it.nameWithoutExtension.removeSuffix("-jvm").removeSuffix("-secure")}".lowercase().trim('/')
            if (relPath.isNotBlank()) keys.add(relPath)
        }

        return keys
    }

    fun isTrusted(
        jarFile: File,
        internalName: String? = null,
        pluginClassName: String? = null,
        manifestName: String? = null,
    ): Boolean {
        val candidateKeys = getPluginAliases(jarFile, internalName, pluginClassName, manifestName)
        val list = getTrustedList().map { it.lowercase().trim() }

        val inList = candidateKeys.any { list.contains(it) }
        val inDataStore = candidateKeys.any { DesktopDataStore.isPluginTrusted(it) }
        return inList || inDataStore
    }

    fun addTrusted(
        jarFile: File,
        internalName: String? = null,
        pluginClassName: String? = null,
        manifestName: String? = null,
    ) {
        val keysToAdd = getPluginAliases(jarFile, internalName, pluginClassName, manifestName)
        val trusted = getTrustedList()
        var changed = false

        for (k in keysToAdd) {
            if (!trusted.any { it.equals(k, ignoreCase = true) }) {
                trusted.add(k)
                changed = true
            }
        }
        if (System.getProperty("cloudstream.sandbox.worker") != "true") {
            DesktopDataStore.setPluginsTrusted(keysToAdd, true)
        }

        if (changed) {
            try {
                val json = mapper.writeValueAsString(trusted)
                if (json.length < 8192) {
                    val prefs = Preferences.userRoot().node("cloudstream_desktop_prefs")
                    prefs.put("trusted_plugins", json)
                }
            } catch (t: Throwable) {
                AppLogger.w("ExtensionLoader: Failed to sync trusted plugins to OS preferences: ${t.message}")
            }
        }
    }

    fun removeTrusted(
        jarFile: File? = null,
        internalName: String? = null,
        pluginClassName: String? = null,
        manifestName: String? = null,
    ) {
        val keysToRemove = getPluginAliases(jarFile, internalName, pluginClassName, manifestName)
        val trusted = getTrustedList()
        var changed = false

        for (k in keysToRemove) {
            if (trusted.removeAll { it.equals(k, ignoreCase = true) }) {
                changed = true
            }
        }
        DesktopDataStore.setPluginsTrusted(keysToRemove, false)

        if (changed) {
            try {
                val json = mapper.writeValueAsString(trusted)
                if (json.length < 8192) {
                    val prefs = Preferences.userRoot().node("cloudstream_desktop_prefs")
                    prefs.put("trusted_plugins", json)
                }
            } catch (t: Throwable) {
                AppLogger.w("ExtensionLoader: Failed to sync trusted plugins to OS preferences: ${t.message}")
            }
        }
    }
}
