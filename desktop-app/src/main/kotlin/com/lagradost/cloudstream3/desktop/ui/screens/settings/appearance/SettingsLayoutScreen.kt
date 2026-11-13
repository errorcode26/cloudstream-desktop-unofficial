package com.lagradost.cloudstream3.desktop.ui.screens.settings.appearance

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.desktop.ui.screens.settings.SettingsHubCard
import com.lagradost.cloudstream3.desktop.ui.screens.settings.SettingsSubScreen

@Composable
fun SettingsLayoutScreen(onNavigateToSubScreen: (SettingsSubScreen) -> Unit = {}) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(top = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingsHubCard(
            icon = Icons.Default.Layers,
            title = "Details Page Layout & Sections",
            subtitle = "Modular drag-and-drop section ordering. Show or hide trailers, cast, watch history, and content recommendations on the details page.",
            badge = "Modular Sections",
            onClick = { onNavigateToSubScreen(SettingsSubScreen.DETAILS_LAYOUT) },
        )

        SettingsHubCard(
            icon = Icons.Default.Tune,
            title = "Explore & Catalog Shelves",
            subtitle = "Modular drag-and-drop shelf ordering for installed add-ons. Toggle categories on or off and customise shelf titles.",
            badge = "Modular Shelves",
            onClick = { onNavigateToSubScreen(SettingsSubScreen.EXPLORE_CATALOGS) },
        )
    }
}
