package com.lagradost.cloudstream3.desktop.ui.screens.settings.appearance

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.desktop.ui.screens.settings.SettingsDropdownItem
import com.lagradost.cloudstream3.desktop.ui.screens.settings.SettingsGroupCard
import com.lagradost.cloudstream3.desktop.ui.theme.AppearanceConfig
import com.lagradost.cloudstream3.desktop.ui.theme.CustomFontManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun SettingsTypographyScreen() {
    val selectedFont by AppearanceConfig.selectedFont.collectAsState()
    val availableFonts by CustomFontManager.availableFonts.collectAsState()
    val userInstalledFonts by CustomFontManager.userInstalledFonts.collectAsState()
    var fontInstallFeedback by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        SettingsGroupCard(title = "App Font") {
            SettingsDropdownItem(
                label = "Font Family",
                subtitle = "Applied globally across all titles, cards, and UI components",
                options = availableFonts.map { it to it },
                currentValue = selectedFont,
                fontFamilyForOption = { com.lagradost.cloudstream3.desktop.ui.theme.getFontFamily(it) },
                onSelectionChanged = { AppearanceConfig.setSelectedFont(it) },
            )
        }

        SettingsGroupCard(title = "Custom Font Studio") {
            Text(
                text = "Install any .ttf, .otf, or .woff font file and it will be available immediately in the picker above.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = {
                        try {
                            val chosenFile = com.lagradost.cloudstream3.desktop.utils.NativeFileDialog.open(
                                title = "Select Font File (.ttf, .otf, .woff)",
                                allowedExtensions = listOf("ttf", "otf", "woff"),
                                category = com.lagradost.cloudstream3.desktop.utils.NativeFileDialog.Category.FONT,
                            )
                            if (chosenFile != null) {
                                val result = CustomFontManager.installFont(chosenFile)
                                result.onSuccess { familyName ->
                                    AppearanceConfig.setSelectedFont(familyName)
                                    fontInstallFeedback = "Installed & applied: $familyName"
                                }.onFailure { err ->
                                    fontInstallFeedback = "Installation failed: ${err.message}"
                                }
                            }
                        } catch (e: Exception) {
                            fontInstallFeedback = "Error opening file picker: ${e.message}"
                        }
                    },
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Install Font (.ttf / .otf)")
                }

                OutlinedButton(
                    onClick = { CustomFontManager.openFontsDirectory() },
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Open Fonts Folder")
                }
            }

            fontInstallFeedback?.let { feedback ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = feedback,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (feedback.startsWith("Installed")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }

            if (userInstalledFonts.isNotEmpty()) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                )

                Text(
                    text = "Installed Fonts (${userInstalledFonts.size})",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )

                Spacer(modifier = Modifier.height(6.dp))

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    userInstalledFonts.forEach { fontName ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = fontName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        text = "The quick brown fox jumps over the lazy dog 1234567890",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        coroutineScope.launch(Dispatchers.IO) {
                                            CustomFontManager.deleteFont(fontName)
                                            if (selectedFont == fontName) AppearanceConfig.setSelectedFont("Inter")
                                        }
                                    },
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Remove font",
                                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                                        modifier = Modifier.size(18.dp),
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
