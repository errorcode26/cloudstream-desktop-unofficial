package com.lagradost.cloudstream3.desktop.ui.screens.settings.network

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.desktop.network.DohProvider
import com.lagradost.cloudstream3.desktop.network.NetworkConfig
import com.lagradost.cloudstream3.desktop.ui.screens.settings.MviSettingsDropdown
import com.lagradost.cloudstream3.desktop.ui.screens.settings.MviSettingsToggle
import com.lagradost.cloudstream3.desktop.ui.screens.settings.SettingsGroupCard
import com.lagradost.cloudstream3.desktop.ui.screens.settings.contract.SettingsUiEvent
import com.lagradost.cloudstream3.desktop.ui.screens.settings.contract.SettingsUiState

@Composable
fun DnsSettingsSection(
    uiState: SettingsUiState,
    onEvent: (SettingsUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsGroupCard(
        title = "DNS over HTTPS (DoH)",
        modifier = modifier,
    ) {
        Text(
            text = "Bypass ISP DNS blocking by encrypting your DNS queries. Changing this will instantly hot-reload the app's networking.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(16.dp))

        MviSettingsDropdown(
            key = NetworkConfig.PREF_DOH_PROVIDER,
            label = "Provider",
            options = DohProvider.entries.mapIndexed { index, provider -> index to provider.title },
            uiState = uiState,
            onEvent = onEvent,
            defaultValue = 0,
        )

        DnsCustomAndTestSection(
            uiState = uiState,
            onEvent = onEvent,
        )

        Spacer(modifier = Modifier.height(14.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
        Spacer(modifier = Modifier.height(14.dp))

        MviSettingsToggle(
            key = NetworkConfig.PREF_DOH_AUTO_FALLBACK,
            label = "Automatic System DNS Fallback",
            subtitle = "If your selected DoH provider times out (>2.5s) or is blocked by your network, automatically resolve via System DNS with a 60s cooldown to prevent 20+ second connection freezes.",
            uiState = uiState,
            onEvent = onEvent,
            defaultValue = true,
        )
    }
}

@Composable
fun DnsCustomAndTestSection(
    uiState: SettingsUiState,
    onEvent: (SettingsUiEvent) -> Unit,
) {
    val currentProviderIndex = uiState.intSettings[NetworkConfig.PREF_DOH_PROVIDER] ?: 0
    val currentProvider = DohProvider.entries.getOrNull(currentProviderIndex) ?: DohProvider.NONE
    val isCustomProvider = currentProviderIndex == DohProvider.CUSTOM.ordinal
    val customEndpoint = uiState.stringSettings[NetworkConfig.PREF_CUSTOM_DOH_URL] ?: ""

    if (isCustomProvider) {
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = customEndpoint,
            onValueChange = {
                onEvent(SettingsUiEvent.OnUpdateString(NetworkConfig.PREF_CUSTOM_DOH_URL, it))
            },
            label = { Text("Custom DNS Endpoint") },
            placeholder = { Text("e.g. dns.adguard-dns.com, https://dns.nextdns.io/id, or 1.1.1.1") },
            supportingText = { Text("Accepts DoH URL, Android Private DNS hostname, or DNS server IP") },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        )
    }

    Spacer(modifier = Modifier.height(14.dp))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedButton(
            onClick = {
                onEvent(SettingsUiEvent.TestDns(provider = currentProvider, customEndpoint = if (isCustomProvider) customEndpoint else null))
            },
            enabled = !uiState.dnsTestState.isTesting,
            shape = RoundedCornerShape(10.dp),
        ) {
            if (uiState.dnsTestState.isTesting) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Pinging DNS...")
            } else {
                Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Test DNS Connection")
            }
        }

        uiState.dnsTestState.result?.let { result ->
            if (result.isSuccess) {
                Surface(
                    color = Color(0xFF1B3B2B),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, Color(0xFF2E7D32)),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            "Online (${result.latencyMs}ms) • ${result.serverDisplay.ifBlank { result.resolvedIp ?: "OK" }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF81C784),
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            } else {
                Surface(
                    color = Color(0xFF3B1B1B),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, Color(0xFFC62828)),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            Icons.Default.Error,
                            contentDescription = null,
                            tint = Color(0xFFEF5350),
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            "Failed: ${result.errorMessage ?: "Could not resolve"}${if (result.serverDisplay.isNotBlank()) " • ${result.serverDisplay}" else ""}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFE57373),
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}
