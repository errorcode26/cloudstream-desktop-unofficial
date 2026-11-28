package com.lagradost.cloudstream3.desktop.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.desktop.ui.theme.LocalDesktopButtonShapes

/**
 * Horizontally scrollable multi-select FilterChip row.
 */
@Composable
fun CategoryFilterChips(
    categories: List<String>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val chipShape = LocalDesktopButtonShapes.current.compact
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        categories.forEach { category ->
            DesktopMaterialFilterChip(
                selected = category in selected,
                onClick = { onToggle(category) },
                shape = chipShape,
                label = { Text(category) },
            )
        }
    }
}
