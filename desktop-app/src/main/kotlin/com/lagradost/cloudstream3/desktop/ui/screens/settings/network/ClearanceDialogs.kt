package com.lagradost.cloudstream3.desktop.ui.screens.settings.network

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.desktop.ui.components.CloudstreamAlertDialog
import com.lagradost.cloudstream3.desktop.ui.components.CloudstreamCustomDialog
import com.lagradost.cloudstream3.desktop.ui.components.LocalDesktopTheme
import com.lagradost.cloudstream3.desktop.ui.screens.settings.SettingsViewModel
import com.lagradost.cloudstream3.desktop.ui.screens.settings.contract.SettingsUiEvent
import com.lagradost.cloudstream3.desktop.ui.theme.AppearanceConfig
import com.lagradost.cloudstream3.desktop.ui.theme.accentColorFromName
import com.lagradost.cloudstream3.desktop.ui.theme.buildColorScheme
import com.lagradost.cloudstream3.desktop.ui.theme.buildDesktopColors

@Composable
fun ManageCookiesDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    viewModel: SettingsViewModel,
) {
    if (!show) return

    val uiState by viewModel.uiState.collectAsState()
    val cookiesMap = uiState.clearanceState.cookiesMap
    var searchQuery by remember { mutableStateOf("") }
    var showClearAllConfirm by remember { mutableStateOf(false) }

    val isLightMode by AppearanceConfig.isLightMode.collectAsState()
    val amoledMode by AppearanceConfig.amoledMode.collectAsState()
    val themeAccent by AppearanceConfig.themeAccent.collectAsState()
    val customThemeAccent by AppearanceConfig.customThemeAccent.collectAsState()
    val appThemeBackground by AppearanceConfig.appThemeBackground.collectAsState()
    val customAppThemeBackground by AppearanceConfig.customAppThemeBackground.collectAsState()
    val uiCardOpacity by AppearanceConfig.uiCardOpacity.collectAsState()

    val primaryColor = accentColorFromName(themeAccent, customThemeAccent)
    val desktopColors = buildDesktopColors(
        primaryColor = primaryColor,
        isLightMode = isLightMode,
        isAmoled = amoledMode,
        appThemeBackground = appThemeBackground,
        customBgHex = customAppThemeBackground,
    ).copy(cardOpacity = uiCardOpacity)
    val colorScheme = buildColorScheme(primaryColor, desktopColors, isLightMode)
    val dialogContainerColor = if (amoledMode) Color(0xFF101010) else desktopColors.SurfaceCard

    LaunchedEffect(show) {
        if (show) viewModel.onEvent(SettingsUiEvent.RefreshClearanceCookies)
    }

    CompositionLocalProvider(LocalDesktopTheme provides desktopColors) {
        MaterialTheme(colorScheme = colorScheme) {
            CloudstreamCustomDialog(
                show = show,
                onDismissRequest = onDismiss,
                containerColor = dialogContainerColor,
                modifier = Modifier.fillMaxWidth(0.85f).fillMaxHeight(0.85f),
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(20.dp),
                    color = dialogContainerColor,
                    tonalElevation = 6.dp,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text(
                                    text = "Saved Cookies & Clearance",
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                val totalCookies = cookiesMap.values.sumOf { it.size }
                                Text(
                                    text = "${cookiesMap.size} domains stored • $totalCookies total cookies",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (cookiesMap.isNotEmpty()) {
                                    Button(
                                        onClick = { showClearAllConfirm = true },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.error,
                                            contentColor = MaterialTheme.colorScheme.onError,
                                        ),
                                        shape = RoundedCornerShape(10.dp),
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Clear All")
                                    }
                                }

                                FilledTonalButton(
                                    onClick = onDismiss,
                                    shape = RoundedCornerShape(10.dp),
                                ) {
                                    Text("Done")
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Filter by provider domain...") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(Icons.Default.Close, contentDescription = "Clear search")
                                    }
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        val filtered = remember(cookiesMap, searchQuery) {
                            val query = searchQuery.trim().lowercase()
                            if (query.isEmpty()) {
                                cookiesMap.toList().sortedBy { it.first }
                            } else {
                                cookiesMap.filter { it.key.lowercase().contains(query) }.toList().sortedBy { it.first }
                            }
                        }

                        if (filtered.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = if (searchQuery.isEmpty()) "No saved cookies or clearance tokens." else "No domains match '$searchQuery'.",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                items(filtered, key = { it.first }) { (domain, cookies) ->
                                    val hasClearance = cookies.any { it.name.equals("cf_clearance", ignoreCase = true) }

                                    Card(
                                        shape = RoundedCornerShape(14.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = if (amoledMode) Color(0xFF181818) else desktopColors.SurfaceElevated,
                                        ),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Column(modifier = Modifier.padding(16.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                ) {
                                                    Text(
                                                        text = domain,
                                                        style = MaterialTheme.typography.titleMedium,
                                                        fontWeight = FontWeight.Bold,
                                                        color = desktopColors.TextPrimary,
                                                    )

                                                    if (hasClearance) {
                                                        Surface(
                                                            color = Color(0xFF1B5E20).copy(alpha = 0.25f),
                                                            shape = RoundedCornerShape(6.dp),
                                                            border = BorderStroke(1.dp, Color(0xFF4CAF50).copy(alpha = 0.5f)),
                                                        ) {
                                                            Text(
                                                                text = "Clearance Active",
                                                                style = MaterialTheme.typography.labelSmall,
                                                                color = Color(0xFF81C784),
                                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                            )
                                                        }
                                                    }
                                                }

                                                IconButton(
                                                    onClick = {
                                                        viewModel.onEvent(SettingsUiEvent.ClearDomainCookies(domain))
                                                    },
                                                ) {
                                                    Icon(
                                                        Icons.Default.Delete,
                                                        contentDescription = "Clear domain cookies",
                                                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
                                                    )
                                                }
                                            }

                                            Spacer(modifier = Modifier.height(8.dp))

                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            ) {
                                                cookies.forEach { cookie ->
                                                    val isCf = cookie.name.equals("cf_clearance", ignoreCase = true)
                                                    val now = System.currentTimeMillis()
                                                    val hoursLeft = ((cookie.expiresAt - now) / (1000 * 60 * 60)).coerceAtLeast(0)
                                                    val expiryLabel = if (cookie.expiresAt > now && hoursLeft < 10000) "${hoursLeft}h left" else "session"

                                                    Surface(
                                                        color = if (isCf) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                                                        shape = RoundedCornerShape(6.dp),
                                                        border = BorderStroke(
                                                            1.dp,
                                                            if (isCf) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                                        ),
                                                    ) {
                                                        Text(
                                                            text = "${cookie.name} ($expiryLabel)",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = if (isCf) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            CloudstreamAlertDialog(
                show = showClearAllConfirm,
                onDismissRequest = { showClearAllConfirm = false },
                title = { Text("Clear All Cookies?") },
                text = {
                    Text("This will remove all saved session cookies and Cloudflare clearance tokens across all providers.")
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showClearAllConfirm = false
                            viewModel.onEvent(SettingsUiEvent.ClearAllClearanceCookies)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    ) {
                        Text("Clear All")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearAllConfirm = false }) {
                        Text("Cancel")
                    }
                },
            )
        }
    }
}

@Composable
fun ManualClearanceDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    viewModel: SettingsViewModel,
) {
    if (!show) return

    val uiState by viewModel.uiState.collectAsState()
    val isLaunching = uiState.clearanceState.isLaunchingManualBypass
    var urlInput by remember { mutableStateOf("") }

    CloudstreamAlertDialog(
        show = show,
        onDismissRequest = {
            if (!isLaunching) onDismiss()
        },
        title = { Text("Manual Cloudflare Clearance") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Enter a provider website or URL to solve Cloudflare Turnstile on demand using the embedded WebView2 solver. Clearance cookies will be automatically saved.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    placeholder = { Text("https://example-provider.com") },
                    singleLine = true,
                    enabled = !isLaunching,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val rawUrl = urlInput.trim()
                    if (rawUrl.isNotBlank()) {
                        onDismiss()
                        viewModel.onEvent(SettingsUiEvent.LaunchManualClearance(rawUrl))
                    }
                },
                enabled = urlInput.trim().isNotBlank() && !isLaunching,
            ) {
                Text(if (isLaunching) "Launching..." else "Launch Solver")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isLaunching,
            ) {
                Text("Cancel")
            }
        },
    )
}

@Composable
fun ManageIgnoredDomainsDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    viewModel: SettingsViewModel,
) {
    if (!show) return

    var searchQuery by remember { mutableStateOf("") }
    val uiState by viewModel.uiState.collectAsState()
    val ignoredList = uiState.ignoredCfDomains
    var showClearAllConfirm by remember { mutableStateOf(false) }

    val isLightMode by AppearanceConfig.isLightMode.collectAsState()
    val amoledMode by AppearanceConfig.amoledMode.collectAsState()
    val themeAccent by AppearanceConfig.themeAccent.collectAsState()
    val customThemeAccent by AppearanceConfig.customThemeAccent.collectAsState()
    val appThemeBackground by AppearanceConfig.appThemeBackground.collectAsState()
    val customAppThemeBackground by AppearanceConfig.customAppThemeBackground.collectAsState()
    val uiCardOpacity by AppearanceConfig.uiCardOpacity.collectAsState()

    val primaryColor = accentColorFromName(themeAccent, customThemeAccent)
    val desktopColors = buildDesktopColors(
        primaryColor = primaryColor,
        isLightMode = isLightMode,
        isAmoled = amoledMode,
        appThemeBackground = appThemeBackground,
        customBgHex = customAppThemeBackground,
    ).copy(cardOpacity = uiCardOpacity)
    val colorScheme = buildColorScheme(primaryColor, desktopColors, isLightMode)
    val dialogContainerColor = if (amoledMode) Color(0xFF101010) else desktopColors.SurfaceCard

    val filteredList = remember(ignoredList, searchQuery) {
        if (searchQuery.isBlank()) ignoredList
        else ignoredList.filter { it.contains(searchQuery.trim(), ignoreCase = true) }
    }

    LaunchedEffect(show) {
        if (show) viewModel.onEvent(SettingsUiEvent.LoadIgnoredCfDomains)
    }

    CompositionLocalProvider(LocalDesktopTheme provides desktopColors) {
        MaterialTheme(colorScheme = colorScheme) {
            CloudstreamCustomDialog(
                show = show,
                onDismissRequest = onDismiss,
                containerColor = dialogContainerColor,
                modifier = Modifier.fillMaxWidth(0.85f).fillMaxHeight(0.85f),
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(20.dp),
                    color = dialogContainerColor,
                    tonalElevation = 6.dp,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text(
                                    text = "Ignored Verification Sites",
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = "Sites configured with 'Don't ask again'. Challenge verification can be re-enabled anytime.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (ignoredList.isNotEmpty()) {
                                    OutlinedButton(
                                        onClick = { showClearAllConfirm = true },
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = MaterialTheme.colorScheme.error,
                                        ),
                                    ) {
                                        Text("Clear All Sites")
                                    }
                                }

                                IconButton(onClick = onDismiss) {
                                    Icon(Icons.Default.Close, contentDescription = "Close")
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Filter ignored domains...") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        if (filteredList.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxWidth().weight(1f),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = if (searchQuery.isNotBlank()) "No domains matching '$searchQuery'" else "No ignored verification sites.",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(filteredList) { domain ->
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                text = domain,
                                                style = MaterialTheme.typography.bodyLarge,
                                                fontWeight = FontWeight.Medium,
                                                color = MaterialTheme.colorScheme.onSurface,
                                            )

                                            IconButton(
                                                onClick = {
                                                    viewModel.onEvent(SettingsUiEvent.RemoveIgnoredCfDomain(domain))
                                                },
                                            ) {
                                                Icon(
                                                    Icons.Default.Delete,
                                                    contentDescription = "Re-enable verification for $domain",
                                                    tint = MaterialTheme.colorScheme.error,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (showClearAllConfirm) {
                CloudstreamAlertDialog(
                    show = true,
                    onDismissRequest = { showClearAllConfirm = false },
                    title = { Text("Clear All Ignored Sites?") },
                    text = { Text("This will re-enable challenge verification for all previously ignored sites.") },
                    confirmButton = {
                        Button(
                            onClick = {
                                showClearAllConfirm = false
                                viewModel.onEvent(SettingsUiEvent.ClearAllIgnoredCfDomains)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        ) {
                            Text("Clear All")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showClearAllConfirm = false }) {
                            Text("Cancel")
                        }
                    },
                )
            }
        }
    }
}
