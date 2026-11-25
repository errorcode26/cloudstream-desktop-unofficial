package com.lagradost.cloudstream3.desktop.ui.screens.shadow

import android.content.res.DesktopResourceRegistry
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lagradost.cloudstream3.desktop.shadowui.ShadowUi
import com.lagradost.cloudstream3.desktop.ui.components.DesktopButton
import com.lagradost.cloudstream3.desktop.ui.components.DesktopIconButton
import com.lagradost.common.logging.AppLogger
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor

internal fun Int.pxToDp() = (this / 2f).dp
internal fun Int.toComposeColor() = Color(this)

private val rawRefRegex = Regex("^@(\\d+)$")

internal fun resolveText(raw: CharSequence?): String {
    if (raw == null) return ""
    val str = raw.toString()
    val match = rawRefRegex.matchEntire(str.trim())
    if (match != null) {
        val id = match.groupValues[1].toIntOrNull()
        if (id != null) {
            val resolved = DesktopResourceRegistry.getString(id)
            if (!resolved.isNullOrBlank()) return resolved
        }
    }
    return str
}

internal fun getActionIcon(text: String): ImageVector? {
    val lower = text.lowercase()
    return when {
        lower.contains("save") -> Icons.Default.Check
        lower.contains("close") || lower.contains("dismiss") -> Icons.Default.Close
        lower.contains("clear") -> Icons.Default.Close
        lower.contains("add") || lower.contains("new") -> Icons.Default.Add
        else -> null
    }
}

@Composable
internal fun TextNode(view: android.widget.TextView) {
    val text = resolveText(view.text).let { if (view.allCaps) it.uppercase() else it }
    val textColor = if (view.textColor != 0) view.textColor.toComposeColor() else MaterialTheme.colorScheme.onSurface

    if (view.clickListener != null) {
        Surface(
            onClick = { ShadowUi.dispatch("text-click") { view.performClick() } },
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            TextViewStyle(text, view, textColor)
        }
    } else {
        TextViewStyle(text, view, textColor)
    }
}

@Composable
internal fun TextViewStyle(text: String, view: android.widget.TextView, textColor: Color) {
    Text(
        text = text,
        color = textColor,
        fontSize = (view.textSizePx / 2f).coerceIn(10f, 28f).sp,
        fontWeight = if (view.bold) FontWeight.Bold else FontWeight.Normal,
        fontStyle = if (view.italic) FontStyle.Italic else FontStyle.Normal,
        textAlign = if ((view.gravity and 0x7) == 0x1) TextAlign.Center else null,
        maxLines = if (view.maxLines > 0) view.maxLines else Int.MAX_VALUE,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(
            start = view.paddingLeft.pxToDp(),
            top = view.paddingTop.pxToDp(),
            end = view.paddingRight.pxToDp(),
            bottom = view.paddingBottom.pxToDp(),
        ),
    )
}

@Composable
internal fun ButtonNode(view: android.widget.Button) {
    val resolvedText = resolveText(view.text).let { if (view.allCaps) it.uppercase() else it }
    val actionIcon = getActionIcon(resolvedText)

    DesktopButton(
        onClick = { ShadowUi.dispatch("button-click") { view.performClick() } },
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = view.paddingLeft.pxToDp(),
                top = view.paddingTop.pxToDp(),
                end = view.paddingRight.pxToDp(),
                bottom = view.paddingBottom.pxToDp(),
            ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (actionIcon != null) {
                Icon(
                    imageVector = actionIcon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp).padding(end = 6.dp),
                )
            }
            Text(
                text = resolvedText,
                fontSize = (view.textSizePx / 2f).coerceIn(11f, 18f).sp,
                fontWeight = if (view.bold) FontWeight.Bold else FontWeight.Medium,
            )
        }
    }
}

@Composable
internal fun EditTextNode(view: android.widget.EditText, labelText: String? = null) {
    val isPassword = (view.inputType and 0x81) == 0x81 || (view.inputType and 128) == 128
    val isNumber = (view.inputType and 2) == 2

    var textValue by remember(view) { mutableStateOf(view.text?.toString().orEmpty()) }

    DisposableEffect(view) {
        val watcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val updated = s?.toString().orEmpty()
                if (textValue != updated) {
                    textValue = updated
                }
            }
        }
        view.addTextChangedListener(watcher)
        onDispose {
            view.removeTextChangedListener(watcher)
        }
    }

    val resolvedHint = resolveText(view.hint).takeIf { it.isNotBlank() }
    val effectiveLabel = labelText?.takeIf { it.isNotBlank() } ?: resolvedHint

    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        OutlinedTextField(
            value = textValue,
            onValueChange = { newValue ->
                textValue = newValue
                view.setTextFromUi(newValue)
            },
            label = effectiveLabel?.let { { Text(it) } },
            placeholder = if (labelText != null && resolvedHint != null) {
                { Text(resolvedHint) }
            } else {
                null
            },
            singleLine = view.singleLine,
            visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (isNumber) KeyboardType.Number else KeyboardType.Text),
            shape = RoundedCornerShape(12.dp),
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (textValue.isNotEmpty()) {
                        DesktopIconButton(
                            onClick = {
                                textValue = ""
                                view.setTextFromUi("")
                            },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    DesktopIconButton(
                        onClick = {
                            try {
                                val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                                if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                                    val contents = clipboard.getData(DataFlavor.stringFlavor) as? String
                                    if (!contents.isNullOrBlank()) {
                                        val trimmed = contents.trim()
                                        textValue = trimmed
                                        view.setTextFromUi(trimmed)
                                    }
                                }
                            } catch (e: Throwable) {
                                AppLogger.e("Clipboard paste failed", e)
                            }
                        },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentPaste,
                            contentDescription = "Paste from Clipboard",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun SwitchNode(view: android.widget.Switch) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable {
                ShadowUi.dispatch("switch-toggle") { view.toggle() }
            }
            .padding(vertical = 8.dp, horizontal = 4.dp),
    ) {
        Column(Modifier.weight(1f)) {
            val resolved = resolveText(view.text)
            if (resolved.isNotEmpty()) {
                Text(
                    text = resolved,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Switch(
            checked = view.isChecked,
            onCheckedChange = { checked ->
                ShadowUi.dispatch("switch-change") { view.isChecked = checked }
            },
        )
    }
}

@Composable
internal fun CheckBoxNode(view: android.widget.CheckBox) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable {
                ShadowUi.dispatch("checkbox-toggle") { view.toggle() }
            }
            .padding(vertical = 6.dp),
    ) {
        Checkbox(
            checked = view.isChecked,
            onCheckedChange = { checked ->
                ShadowUi.dispatch("checkbox-change") { view.isChecked = checked }
            },
        )
        val resolved = resolveText(view.text)
        if (resolved.isNotEmpty()) {
            Text(
                text = resolved,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
internal fun RadioButtonNode(view: android.widget.RadioButton) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable {
                ShadowUi.dispatch("radio-toggle") { view.isChecked = true }
            }
            .padding(vertical = 4.dp),
    ) {
        RadioButton(
            selected = view.isChecked,
            onClick = {
                ShadowUi.dispatch("radio-select") { view.isChecked = true }
            },
        )
        val resolved = resolveText(view.text)
        if (resolved.isNotEmpty()) {
            Text(
                text = resolved,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
internal fun IconButtonNode(view: android.widget.ImageButton) {
    DesktopIconButton(
        onClick = { ShadowUi.dispatch("icon-button") { view.performClick() } },
        modifier = Modifier.padding(4.dp),
    ) {
        Text("⚙", fontSize = 18.sp)
    }
}

@Composable
internal fun ImageNode(view: android.widget.ImageView) {
    Box(Modifier.size(48.dp))
}

@Composable
internal fun ProgressNode(view: android.widget.ProgressBar) {
    if (view.isIndeterminate) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
    } else {
        val fraction = if (view.max > 0) (view.progress.toFloat() / view.max).coerceIn(0f, 1f) else 0f
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        )
    }
}

@Composable
internal fun PlainViewNode(view: android.view.View) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (view.clickListener != null) {
                    Modifier.clickable { ShadowUi.dispatch("plain-view-click") { view.performClick() } }
                } else {
                    Modifier
                },
            ),
    )
}

@Composable
internal fun SpinnerNode(spinner: android.widget.Spinner) {
    var expanded by remember { mutableStateOf(false) }
    val version by ShadowUi.version.collectAsState()

    val items = remember(spinner.adapter, version) {
        val list = mutableListOf<String>()
        when (val adapter = spinner.adapter) {
            is android.widget.ArrayAdapter<*> -> {
                for (i in 0 until adapter.count) {
                    list.add(adapter.getItem(i)?.toString() ?: "")
                }
            }
            is android.widget.ListAdapter -> {
                for (i in 0 until adapter.getCount()) {
                    list.add(adapter.getItem(i)?.toString() ?: "")
                }
            }
        }
        list
    }

    val promptText = spinner.prompt?.toString()?.takeIf { it.isNotBlank() } ?: "Select an option"
    val selectedIndex = spinner.selectedItemPosition.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    val selectedText = items.getOrNull(selectedIndex) ?: promptText

    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Surface(
            onClick = { expanded = true },
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = selectedText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = "Dropdown",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(min = 280.dp),
        ) {
            if (items.isEmpty()) {
                DropdownMenuItem(
                    text = { Text(promptText) },
                    onClick = { expanded = false },
                )
            } else {
                items.forEachIndexed { index, item ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = item,
                                fontWeight = if (index == selectedIndex) FontWeight.Bold else FontWeight.Normal,
                                color = if (index == selectedIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        onClick = {
                            expanded = false
                            ShadowUi.dispatch("spinner-select") {
                                spinner.setSelection(index)
                            }
                        },
                    )
                }
            }
        }
    }
}
