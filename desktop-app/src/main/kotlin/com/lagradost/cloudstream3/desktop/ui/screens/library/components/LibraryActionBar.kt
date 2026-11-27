package com.lagradost.cloudstream3.desktop.ui.screens.library.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.lagradost.cloudstream3.desktop.repo.DesktopRepositoryManager
import com.lagradost.cloudstream3.desktop.ui.DesktopDimens
import com.lagradost.cloudstream3.desktop.ui.components.AppDropdownMenu
import com.lagradost.cloudstream3.desktop.ui.components.DesktopIconButton
import com.lagradost.cloudstream3.desktop.ui.components.DesktopOutlinedButton
import com.lagradost.cloudstream3.desktop.ui.screens.library.contract.LibrarySection
import com.lagradost.cloudstream3.desktop.ui.screens.library.contract.LibraryUiState
import com.lagradost.cloudstream3.desktop.ui.screens.library.contract.SortOption
import com.lagradost.cloudstream3.desktop.ui.theme.LocalDesktopButtonShapes

@Composable
fun LibraryActionBar(
    uiState: LibraryUiState,
    isCompact: Boolean = false,
    onSearch: (String) -> Unit,
    onSortChange: (SortOption) -> Unit,
    onProviderChange: (String?) -> Unit,
    onRefreshTrackers: () -> Unit,
) {
    var sortExpanded by remember { mutableStateOf(false) }
    var providerExpanded by remember { mutableStateOf(false) }
    val isTrackerSection = uiState.selectedSection is LibrarySection.Tracker
    val activeTracker = uiState.activeTrackerDescriptor
    val showLocalProviderDropdown = !isTrackerSection && uiState.availableProviders.isNotEmpty()
    val searchPlaceholder = if (activeTracker != null) {
        "Search ${activeTracker.displayName}..."
    } else {
        "Search local bookmarks..."
    }

    if (isCompact) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = onSearch,
                placeholder = { Text(searchPlaceholder, fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search", modifier = Modifier.size(18.dp)) },
                trailingIcon = if (uiState.searchQuery.isNotEmpty()) {
                    {
                        IconButton(onClick = { onSearch("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear", modifier = Modifier.size(16.dp))
                        }
                    }
                } else null,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                ),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showLocalProviderDropdown) {
                    Box {
                        val selIcon = remember(uiState.selectedProvider) {
                            DesktopRepositoryManager.getPluginIcon(uiState.selectedProvider)
                        }
                        DesktopOutlinedButton(
                            onClick = { providerExpanded = true },
                            modifier = Modifier.height(40.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp),
                        ) {
                            if (selIcon != null) {
                                AsyncImage(
                                    model = selIcon,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp).clip(CircleShape),
                                )
                                Spacer(Modifier.width(6.dp))
                            } else {
                                Icon(Icons.Default.FilterList, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                            }
                            Text(
                                uiState.selectedProvider ?: "All Extensions",
                                maxLines = 1,
                                fontSize = 12.sp,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                        ProviderFilterMenu(
                            expanded = providerExpanded,
                            selectedProvider = uiState.selectedProvider,
                            availableProviders = uiState.availableProviders,
                            onDismiss = { providerExpanded = false },
                            onSelect = {
                                onProviderChange(it)
                                providerExpanded = false
                            },
                        )
                    }
                }

                Box {
                    DesktopOutlinedButton(
                        onClick = { sortExpanded = true },
                        modifier = Modifier.height(40.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(uiState.sortOption.title, maxLines = 1, fontSize = 12.sp, overflow = TextOverflow.Ellipsis)
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                    SortOptionsMenu(
                        expanded = sortExpanded,
                        selectedSort = uiState.sortOption,
                        onDismiss = { sortExpanded = false },
                        onSelect = {
                            onSortChange(it)
                            sortExpanded = false
                        },
                    )
                }

                if (isTrackerSection && activeTracker != null) {
                    DesktopIconButton(
                        icon = Icons.Default.Refresh,
                        contentDescription = "Refresh ${activeTracker.displayName}",
                        onClick = onRefreshTrackers,
                    )
                }
            }
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DesktopDimens.TabInnerHorizontalPadding, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = onSearch,
                placeholder = { Text(searchPlaceholder) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                trailingIcon = if (uiState.searchQuery.isNotEmpty()) {
                    {
                        IconButton(onClick = { onSearch("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear", modifier = Modifier.size(16.dp))
                        }
                    }
                } else null,
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                ),
            )

            if (showLocalProviderDropdown) {
                Box {
                    DesktopOutlinedButton(
                        onClick = { providerExpanded = true },
                        modifier = Modifier.height(40.dp),
                        shape = LocalDesktopButtonShapes.current.primary,
                    ) {
                        Icon(Icons.Default.FilterList, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(uiState.selectedProvider ?: "All Extensions", maxLines = 1)
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                    }
                    ProviderFilterMenu(
                        expanded = providerExpanded,
                        selectedProvider = uiState.selectedProvider,
                        availableProviders = uiState.availableProviders,
                        onDismiss = { providerExpanded = false },
                        onSelect = {
                            onProviderChange(it)
                            providerExpanded = false
                        },
                    )
                }
            }

            Box {
                DesktopOutlinedButton(
                    onClick = { sortExpanded = true },
                    modifier = Modifier.height(40.dp),
                    shape = LocalDesktopButtonShapes.current.primary,
                ) {
                    Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(uiState.sortOption.title, maxLines = 1)
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                }
                SortOptionsMenu(
                    expanded = sortExpanded,
                    selectedSort = uiState.sortOption,
                    onDismiss = { sortExpanded = false },
                    onSelect = {
                        onSortChange(it)
                        sortExpanded = false
                    },
                )
            }

            if (isTrackerSection && activeTracker != null) {
                DesktopIconButton(
                    icon = Icons.Default.Refresh,
                    contentDescription = "Refresh ${activeTracker.displayName}",
                    onClick = onRefreshTrackers,
                )
            }
        }
    }
}

@Composable
private fun ProviderFilterMenu(
    expanded: Boolean,
    selectedProvider: String?,
    availableProviders: List<String>,
    onDismiss: () -> Unit,
    onSelect: (String?) -> Unit,
) {
    AppDropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = {
                Text(
                    "All Extensions",
                    fontWeight = if (selectedProvider == null) FontWeight.Bold else FontWeight.Normal,
                )
            },
            onClick = { onSelect(null) },
        )
        availableProviders.forEach { prov ->
            val provIcon = DesktopRepositoryManager.getPluginIcon(prov)
            DropdownMenuItem(
                leadingIcon = {
                    if (provIcon != null) {
                        AsyncImage(
                            model = provIcon,
                            contentDescription = prov,
                            modifier = Modifier.size(20.dp).clip(CircleShape),
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = prov.take(1).uppercase(),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                            )
                        }
                    }
                },
                text = {
                    Text(
                        prov,
                        fontWeight = if (selectedProvider == prov) FontWeight.Bold else FontWeight.Normal,
                    )
                },
                onClick = { onSelect(prov) },
            )
        }
    }
}

@Composable
private fun SortOptionsMenu(
    expanded: Boolean,
    selectedSort: SortOption,
    onDismiss: () -> Unit,
    onSelect: (SortOption) -> Unit,
) {
    AppDropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        SortOption.entries.forEach { option ->
            DropdownMenuItem(
                text = {
                    Text(
                        option.title,
                        fontWeight = if (selectedSort == option) FontWeight.Bold else FontWeight.Normal,
                    )
                },
                onClick = { onSelect(option) },
            )
        }
    }
}
