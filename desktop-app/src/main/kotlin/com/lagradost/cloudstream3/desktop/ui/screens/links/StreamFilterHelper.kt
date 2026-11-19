package com.lagradost.cloudstream3.desktop.ui.screens.links

import com.lagradost.cloudstream3.utils.ExtractorLink

internal object StreamFilterHelper {
    fun isAddonStream(link: ExtractorLink, addonNames: List<String> = emptyList()): Boolean {
        return try {
            val src = link.source.trim()
            val name = link.name
            name.startsWith("⚡") ||
                name.contains("[TB ⚡]") ||
                name.contains("[RD ⚡]") ||
                name.contains("[AD ⚡]") ||
                src.contains("AIOStreams", ignoreCase = true) ||
                src.contains("Torrentio", ignoreCase = true) ||
                src.contains("Comet", ignoreCase = true) ||
                src.contains("MediaFusion", ignoreCase = true) ||
                addonNames.any { addonName ->
                    addonName.isNotBlank() && (
                        src.equals(addonName, ignoreCase = true) ||
                            src.contains(addonName, ignoreCase = true) ||
                            addonName.contains(src, ignoreCase = true)
                    )
                }
        } catch (_: Exception) {
            false
        }
    }

    fun getAddonName(link: ExtractorLink, providerName: String = "", addonNames: List<String> = emptyList()): String {
        return try {
            val src = link.source.trim()
            val matchingAddon = addonNames.firstOrNull { addonName ->
                addonName.isNotBlank() && (
                    src.equals(addonName, ignoreCase = true) ||
                        src.contains(addonName, ignoreCase = true) ||
                        addonName.contains(src, ignoreCase = true)
                )
            }
            when {
                matchingAddon != null -> matchingAddon
                src.contains("AIOStreams", ignoreCase = true) -> "Duck Streams (AIOStreams)"
                src.isNotBlank() && !src.equals(providerName, ignoreCase = true) -> src
                else -> "Addons"
            }
        } catch (_: Exception) {
            "Addons"
        }
    }
}
