package com.lagradost.cloudstream3.desktop.sandbox.host

import java.io.File
import java.net.InetAddress
import java.net.URI

object SandboxSecurityPolicy {

    val PROTECTED_HOST_KEYS = setOf(
        "trusted_plugins",
        "preferred_player",
        "home_active_providers",
        "preferred_provider_name",
        "last_active_plugin_jar",
        "last_plugin_sync_timestamp",
        "search_history",
        "search_preference_providers",
        "pref_allow_cf_bypass",
        "dns_provider",
        "dns_over_https",
        "pref_dns_mode",
        "user_agent"
    )

    fun sandboxSettingFilter(): (String) -> Boolean {
        val allowedExactKeys = setOf(
            com.lagradost.common.storage.DesktopDataStore.PREF_ALLOW_CF_BYPASS,
            com.lagradost.cloudstream3.network.CloudflareKiller.PREF_TLS_BOUND_HOSTS,
            com.lagradost.cloudstream3.desktop.core.preference.PreferenceKeys.USER_PROVIDER_API,
            "user_custom_sites",
            "trusted_plugins_set",
            "dns_provider",
            "dns_over_https",
            "pref_dns_mode",
            "user_agent"
        )
        val schemaStorageKeys = com.lagradost.common.storage.PluginSettingsSchemaRegistry.schemas.values
            .flatMap { it.values }.map { it.storageKey }.toSet()
        val schemaPrefixes = com.lagradost.common.storage.PluginSettingsSchemaRegistry.schemas.keys

        return { key ->
            allowedExactKeys.contains(key) ||
                schemaStorageKeys.contains(key) ||
                schemaPrefixes.any { prefix -> key.startsWith(prefix) } ||
                key.startsWith("cf_silenced_") ||
                key.startsWith("pref_plugin_")
        }
    }

    fun filterSandboxSettings(settings: Map<String, String>): Map<String, String> {
        val allowed = sandboxSettingFilter()
        return settings.filterKeys(allowed)
    }

    fun filterSandboxKeys(keys: Collection<String>): List<String> {
        val allowed = sandboxSettingFilter()
        return keys.filter(allowed)
    }

    fun isBlockedOrPrivateUrl(rawUrl: String): Boolean {
        val lower = rawUrl.lowercase().trim()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return true

        val host = try {
            URI(rawUrl).host?.lowercase()?.trim() ?: return true
        } catch (_: Throwable) {
            return true
        }

        if (host == "localhost" || host == "0.0.0.0" || host == "::1" || host == "127.0.0.1") return true
        if (host.endsWith(".local") || host.endsWith(".internal") || host.endsWith(".lan") || host.endsWith(".home") || host.endsWith(".arpa")) return true

        return try {
            val addresses = InetAddress.getAllByName(host)
            addresses.any { addr ->
                addr.isLoopbackAddress ||
                addr.isSiteLocalAddress ||
                addr.isLinkLocalAddress ||
                addr.isAnyLocalAddress ||
                isCarrierGradeNat(addr) ||
                isIpv6UniqueLocal(addr)
            }
        } catch (_: Throwable) {
            true
        }
    }

    fun isCarrierGradeNat(addr: InetAddress): Boolean {
        val bytes = addr.address
        if (bytes.size == 4) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            if (b0 == 100 && (b1 in 64..127)) return true
        }
        return false
    }

    fun isIpv6UniqueLocal(addr: InetAddress): Boolean {
        val bytes = addr.address
        return bytes.size == 16 && (bytes[0].toInt() and 0xFE) == 0xFC
    }

    fun pruneWorkerClasspath(rawClasspath: String): String {
        if (rawClasspath.isBlank()) return ""
        val entries = rawClasspath.split(File.pathSeparator).filter { it.isNotBlank() }
        val pruned = entries.filter { entry ->
            val lower = entry.lowercase()
            val name = File(entry).name.lowercase()
            val isUiDependency = name.contains("skiko") ||
                name.contains("compose") ||
                name.contains("haze") ||
                name.contains("coil") ||
                name.contains("decompose") ||
                name.contains("essenty") ||
                name.contains("sqldelight") ||
                name.contains("kdiscordipc") ||
                name.contains("webview2") ||
                name.startsWith("runtime-desktop") ||
                name.startsWith("ui-desktop") ||
                name.startsWith("ui-graphics-desktop") ||
                name.startsWith("ui-text-desktop") ||
                name.startsWith("ui-unit-desktop") ||
                name.startsWith("foundation-desktop") ||
                name.startsWith("material-desktop") ||
                name.startsWith("material3-desktop") ||
                name.startsWith("material-ripple-desktop") ||
                name.startsWith("material-icons-") ||
                lower.contains("org.jetbrains.compose") ||
                lower.contains("androidx.compose") ||
                lower.contains("org.jetbrains.skiko") ||
                lower.contains("dev.chrisbanes.haze") ||
                lower.contains("io.coil-kt") ||
                lower.contains("com.arkivanov.decompose") ||
                lower.contains("com.arkivanov.essenty") ||
                lower.contains("app.cash.sqldelight")
            !isUiDependency
        }
        return pruned.joinToString(File.pathSeparator)
    }
}
