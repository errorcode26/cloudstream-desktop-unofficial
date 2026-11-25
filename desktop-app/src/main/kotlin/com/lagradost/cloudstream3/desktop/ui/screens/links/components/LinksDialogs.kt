package com.lagradost.cloudstream3.desktop.ui.screens.links.components

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.desktop.downloader.DesktopDownloadManager
import com.lagradost.cloudstream3.desktop.ui.components.CloudstreamAlertDialog
import com.lagradost.cloudstream3.desktop.ui.components.P2pTorrentDisclaimerDialog
import com.lagradost.cloudstream3.desktop.ui.components.TorrServerInstallRequiredDialog
import com.lagradost.cloudstream3.desktop.ui.screens.links.dialogs.DownloadConfirmationDialog
import com.lagradost.cloudstream3.desktop.ui.screens.player.SourcePriorityDialog
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.common.storage.WatchHistory

@Composable
fun LinksDialogHost(
    linkToDownload: ExtractorLink?,
    onDismissDownloadDialog: () -> Unit,
    displayTitle: String,
    history: WatchHistory,
    loadResponse: LoadResponse?,
    providerName: String,
    showP2pDisclaimer: Boolean,
    onDismissP2pDisclaimer: () -> Unit,
    onConfirmP2pDisclaimer: () -> Unit,
    showTorrServerInstall: Boolean,
    onDismissTorrServerInstall: () -> Unit,
    onTorrServerInstalled: () -> Unit,
    showPriorityDialog: Boolean,
    onDismissPriorityDialog: () -> Unit,
    playerLaunchError: String?,
    onDismissPlayerLaunchError: () -> Unit,
) {
    DownloadConfirmationDialog(
        show = linkToDownload != null,
        onDismiss = onDismissDownloadDialog,
        link = linkToDownload,
        displayTitle = displayTitle,
        history = history,
        loadResponse = loadResponse,
        providerName = providerName,
        onConfirmDownload = {
            val targetLink = linkToDownload ?: return@DownloadConfirmationDialog
            onDismissDownloadDialog()
            DesktopDownloadManager.enqueue(
                canonicalKey = history.showUrl,
                showName = history.showName,
                showUrl = history.showUrl,
                episodeTitle = history.episodeId,
                posterUrl = history.posterUrl,
                backdropUrl = loadResponse?.backgroundPosterUrl,
                season = history.season,
                episode = history.episode,
                link = targetLink,
                apiName = providerName,
            )
        },
    )

    P2pTorrentDisclaimerDialog(
        show = showP2pDisclaimer,
        onDismiss = onDismissP2pDisclaimer,
        onConfirm = onConfirmP2pDisclaimer,
    )

    TorrServerInstallRequiredDialog(
        show = showTorrServerInstall,
        onDismiss = onDismissTorrServerInstall,
        onInstalled = onTorrServerInstalled,
    )

    SourcePriorityDialog(
        show = showPriorityDialog,
        onDismissRequest = onDismissPriorityDialog,
    )

    CloudstreamAlertDialog(
        show = playerLaunchError != null,
        onDismissRequest = onDismissPlayerLaunchError,
        title = { Text("Player error") },
        text = { Text(playerLaunchError ?: "") },
        confirmButton = {
            com.lagradost.cloudstream3.desktop.ui.components.DesktopTextButton(onClick = onDismissPlayerLaunchError) { Text("OK") }
        },
    )
}
