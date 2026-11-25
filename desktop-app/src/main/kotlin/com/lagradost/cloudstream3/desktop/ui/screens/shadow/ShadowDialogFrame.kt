package com.lagradost.cloudstream3.desktop.ui.screens.shadow

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lagradost.cloudstream3.desktop.shadowui.ShadowDialog
import com.lagradost.cloudstream3.desktop.shadowui.ShadowUi
import com.lagradost.cloudstream3.desktop.ui.components.CloudstreamCustomDialog
import com.lagradost.cloudstream3.desktop.ui.components.DesktopButton
import com.lagradost.cloudstream3.desktop.ui.components.DesktopIconButton
import com.lagradost.cloudstream3.desktop.ui.components.DesktopOutlinedButton
import com.lagradost.cloudstream3.desktop.ui.components.DesktopTextButton
import com.lagradost.cloudstream3.desktop.ui.components.LocalDesktopTheme

@Composable
fun ShadowDialogFrame(shadow: ShadowDialog, version: Int) {
    val isAmoled = LocalDesktopTheme.current.isAmoled
    val dialogContainerColor = if (isAmoled) Color(0xFF101010) else MaterialTheme.colorScheme.surface
    CloudstreamCustomDialog(
        show = true,
        onDismissRequest = {
            val platform = shadow.platform
            ShadowUi.dispatch("dialog-dismiss") {
                when (platform) {
                    is android.app.Dialog -> platform.cancel()
                    is androidx.fragment.app.DialogFragment -> platform.dismiss()
                    is androidx.fragment.app.Fragment -> {
                        platform.onDestroyView()
                        platform.onDestroy()
                    }
                }
                ShadowUi.pop(platform)
                ShadowUi.pop(shadow)
            }
        },
        containerColor = dialogContainerColor,
        modifier = Modifier
            .widthIn(min = 550.dp, max = 680.dp)
            .heightIn(max = 800.dp),
    ) {
        when (val platform = shadow.platform) {
            is androidx.fragment.app.Fragment -> FragmentBody(shadow, platform)
            is android.app.Dialog -> PlatformDialogBody(shadow, platform, platform.contentView ?: shadow.view)
            else -> {
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = shadow.fragmentTag ?: "Settings",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        DesktopIconButton(
                            onClick = {
                                ShadowUi.dispatch("fallback-dismiss") {
                                    ShadowUi.pop(shadow.platform)
                                    ShadowUi.pop(shadow)
                                }
                            },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    shadow.view?.let {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            ShadowNode(it, 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun FragmentBody(shadow: ShadowDialog, fragment: androidx.fragment.app.Fragment) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = shadow.fragmentTag ?: "Settings",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            DesktopIconButton(
                onClick = {
                    ShadowUi.dispatch("fragment-dismiss") {
                        when (fragment) {
                            is androidx.fragment.app.DialogFragment -> fragment.dismiss()
                            else -> {
                                fragment.onDestroyView()
                                fragment.onDestroy()
                            }
                        }
                        ShadowUi.pop(fragment)
                        ShadowUi.pop(shadow)
                    }
                },
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 640.dp)
                .padding(20.dp),
        ) {
            shadow.view?.let {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    ShadowNode(it, 1)
                }
            }
        }
    }
}

@Composable
internal fun PlatformDialogBody(shadow: ShadowDialog, dialog: android.app.Dialog, view: android.view.View?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            dialog.title?.let {
                Text(
                    text = it.toString(),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            } ?: Spacer(Modifier.width(1.dp))

            DesktopIconButton(
                onClick = {
                    ShadowUi.dispatch("dialog-dismiss-x") {
                        dialog.cancel()
                        ShadowUi.pop(dialog)
                        ShadowUi.pop(shadow)
                    }
                },
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        dialog.message?.let {
            Text(
                text = it.toString(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp,
                modifier = Modifier.padding(bottom = 14.dp),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .heightIn(max = 580.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                view?.let { ShadowNode(it, 1) }

                dialog.items?.let { items ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    ) {
                        items.forEachIndexed { index, label ->
                            val selected = dialog.checkedItem == index
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        ShadowUi.dispatch("dialog-item") {
                                            if (dialog.isSingleChoice) {
                                                dialog.checkedItem = index
                                                dialog.itemsListener?.onClick(dialog, index)
                                            } else if (dialog.isMultiChoice) {
                                                dialog.multiChoiceListener?.onClick(dialog, index, true)
                                            } else {
                                                dialog.itemsListener?.onClick(dialog, index)
                                                dialog.dismiss()
                                            }
                                        }
                                    }
                                    .padding(vertical = 10.dp, horizontal = 12.dp),
                            ) {
                                if (dialog.isSingleChoice) {
                                    RadioButton(
                                        selected = selected,
                                        onClick = null,
                                        modifier = Modifier.padding(end = 12.dp),
                                    )
                                } else if (dialog.isMultiChoice) {
                                    Checkbox(
                                        checked = dialog.checkedItems?.getOrNull(index) == true,
                                        onCheckedChange = null,
                                        modifier = Modifier.padding(end = 12.dp),
                                    )
                                }
                                Text(
                                    text = label.toString(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
            }
        }

        ShadowDialogButtons(
            positiveText = dialog.positiveText,
            negativeText = dialog.negativeText,
            neutralText = dialog.neutralText,
            onPositive = {
                ShadowUi.dispatch("dialog-positive") {
                    dialog.positiveListener?.onClick(dialog, -1)
                    dialog.dismiss()
                }
            },
            onNegative = {
                ShadowUi.dispatch("dialog-negative") {
                    dialog.negativeListener?.onClick(dialog, -2)
                    dialog.dismiss()
                }
            },
            onNeutral = {
                ShadowUi.dispatch("dialog-neutral") {
                    dialog.neutralListener?.onClick(dialog, -3)
                    dialog.dismiss()
                }
            },
        )
    }
}

@Composable
internal fun ShadowDialogButtons(
    positiveText: CharSequence?,
    negativeText: CharSequence?,
    neutralText: CharSequence?,
    onPositive: () -> Unit,
    onNegative: () -> Unit,
    onNeutral: () -> Unit,
) {
    if (positiveText == null && negativeText == null && neutralText == null) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 18.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        neutralText?.let {
            DesktopTextButton(onClick = onNeutral) {
                Text(it.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
        }
        negativeText?.let {
            DesktopOutlinedButton(
                onClick = onNegative,
            ) {
                Text(it.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(10.dp))
        }
        positiveText?.let {
            DesktopButton(
                onClick = onPositive,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Text(it.toString(), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
