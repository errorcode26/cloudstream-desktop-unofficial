package com.lagradost.cloudstream3.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.lagradost.cloudstream3.desktop.network.NetworkMonitor
import kotlinx.coroutines.launch

@Composable
internal fun DesktopShellOfflineBanner(
    modifier: Modifier = Modifier,
) {
    val isOnline by NetworkMonitor.isOnline.collectAsState()
    val isCheckingNetwork by NetworkMonitor.isChecking.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    AnimatedVisibility(
        visible = !isOnline,
        enter = fadeIn() + slideInVertically { -it },
        exit = fadeOut() + slideOutVertically { -it },
        modifier = modifier.padding(top = 58.dp).zIndex(99f),
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF1E140A).copy(alpha = 0.95f),
            border = BorderStroke(1.dp, Color(0xFFFF9800).copy(alpha = 0.55f)),
            shadowElevation = 8.dp,
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.WifiOff,
                    contentDescription = "Offline Mode",
                    tint = Color(0xFFFFB74D),
                    modifier = Modifier.size(20.dp),
                )
                Column {
                    Text(
                        text = "Offline Mode Active",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                    )
                    Text(
                        text = "No internet connection detected. Local playback, saved bookmarks, and downloaded content are available.",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 11.sp,
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                FilledTonalButton(
                    onClick = { GlobalMediaLauncher.openLocalFileDialog() },
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Color(0xFF3B2810),
                        contentColor = Color(0xFFFFCC80),
                    ),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Play Local File", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
                FilledTonalButton(
                    onClick = {
                        coroutineScope.launch { NetworkMonitor.checkConnectivity() }
                    },
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Color(0xFFFF9800).copy(alpha = 0.25f),
                        contentColor = Color(0xFFFFE0B2),
                    ),
                ) {
                    if (isCheckingNetwork) {
                        CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp, color = Color(0xFFFFE0B2))
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Retry", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
