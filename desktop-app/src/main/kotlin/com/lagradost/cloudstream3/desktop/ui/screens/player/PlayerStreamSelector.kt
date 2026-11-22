package com.lagradost.cloudstream3.desktop.ui.screens.player

import com.lagradost.cloudstream3.desktop.player.QualityDataHelper
import com.lagradost.cloudstream3.desktop.torrent.DesktopTorrentEngine
import com.lagradost.cloudstream3.utils.ExtractorLink

object PlayerStreamSelector {

    fun sortLinks(
        links: List<ExtractorLink>,
        startPositionMs: Long = 0L,
    ): List<ExtractorLink> {
        return QualityDataHelper.sortLinks(links)
    }

    fun pickBestActiveLink(
        links: List<ExtractorLink>,
        failed: Set<String>,
        startPositionMs: Long = 0L,
    ): ExtractorLink? {
        val p2pReady = DesktopTorrentEngine.isP2pReady
        val available = links.filter { link ->
            link.url !in failed && (p2pReady || !DesktopTorrentEngine.isTorrentLink(link))
        }
        if (available.isEmpty()) return null
        return sortLinks(available, startPositionMs).firstOrNull()
    }
}
