package com.lagradost.cloudstream3.desktop.ui.screens.shadow

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lagradost.cloudstream3.desktop.shadowui.ShadowUi

internal fun isTransparentWrapper(group: android.view.ViewGroup): Boolean {
    return group.childCount == 1 &&
        group.clickListener == null &&
        group.background == null &&
        group !is android.widget.ScrollView &&
        group !is androidx.recyclerview.widget.RecyclerView &&
        group !is android.widget.ListView &&
        group !is android.widget.RadioGroup
}

internal data class DetectedToggleRow(
    val title: String,
    val subtitle: String?,
    val toggle: android.widget.CompoundButton,
)

internal fun detectToggleRow(group: android.view.ViewGroup): DetectedToggleRow? {
    if (group is android.widget.ScrollView || group is androidx.recyclerview.widget.RecyclerView || group is android.widget.ListView) {
        return null
    }

    val toggles = mutableListOf<android.widget.CompoundButton>()
    val texts = mutableListOf<String>()
    var hasOtherControls = false

    fun collect(v: android.view.View) {
        if (v.visibility == android.view.View.GONE || hasOtherControls) return
        when {
            v is android.widget.CompoundButton -> toggles.add(v)
            v is android.widget.Button || v is android.widget.EditText || v is android.widget.Spinner || v is android.widget.ProgressBar -> {
                hasOtherControls = true
            }
            v is android.widget.TextView -> {
                val t = resolveText(v.text).trim()
                if (t.isNotEmpty()) texts.add(t)
            }
            v is android.view.ViewGroup -> {
                for (i in 0 until v.childCount) {
                    val child = v.getChildAt(i)
                    if (child != null) collect(child)
                }
            }
        }
    }

    for (i in 0 until group.childCount) {
        val child = group.getChildAt(i) ?: continue
        collect(child)
    }

    if (!hasOtherControls && toggles.size == 1 && texts.isNotEmpty() && texts.size <= 2) {
        val toggle = toggles.first()
        val ownText = resolveText(toggle.text).trim()
        val title = if (ownText.isNotEmpty()) ownText else texts.first()
        val subtitle = if (ownText.isNotEmpty()) {
            texts.firstOrNull()
        } else if (texts.size > 1) {
            texts[1]
        } else {
            null
        }
        return DetectedToggleRow(title, subtitle, toggle)
    }
    return null
}

@Composable
internal fun DesktopSettingsToggleTile(
    title: String,
    subtitle: String?,
    toggle: android.widget.CompoundButton,
) {
    val isChecked = toggle.isChecked
    val isEnabled = toggle.isEnabled
    val isSwitch = toggle is android.widget.Switch
    val isRadio = toggle is android.widget.RadioButton

    Surface(
        onClick = {
            if (isEnabled) {
                ShadowUi.dispatch("toggle-row") { toggle.toggle() }
            }
        },
        enabled = isEnabled,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!isSwitch) {
                if (isRadio) {
                    RadioButton(
                        selected = isChecked,
                        onClick = null,
                        enabled = isEnabled,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                } else {
                    Checkbox(
                        checked = isChecked,
                        onCheckedChange = null,
                        enabled = isEnabled,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (isEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isEnabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }

            if (isSwitch) {
                Switch(
                    checked = isChecked,
                    onCheckedChange = null,
                    enabled = isEnabled,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
    }
}

internal fun isTappableTileGroup(group: android.view.ViewGroup): Boolean {
    if (group.clickListener == null) return false
    if (group is android.widget.AdapterView<*> || group is androidx.recyclerview.widget.RecyclerView || group is android.widget.ScrollView) {
        return false
    }
    fun containsInputControls(v: android.view.View): Boolean {
        if (v.visibility == android.view.View.GONE) return false
        if (v is android.widget.EditText || v is android.widget.Button || v is android.widget.Spinner || v is android.widget.CompoundButton) {
            return true
        }
        if (v is android.view.ViewGroup) {
            for (i in 0 until v.childCount) {
                val c = v.getChildAt(i) ?: continue
                if (containsInputControls(c)) return true
            }
        }
        return false
    }
    return !containsInputControls(group)
}

@Composable
internal fun TappableContainerTile(group: android.view.ViewGroup, depth: Int) {
    Surface(
        onClick = {
            ShadowUi.dispatch("tile-click") { group.performClick() }
        },
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(Modifier.weight(1f)) {
                ViewGroupColumnNode(group, depth + 1)
            }
            Text(
                text = "›",
                fontSize = 22.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

@Composable
fun ShadowNode(view: android.view.View, depth: Int) {
    if (view.visibility == android.view.View.GONE) return
    if (depth > 24) return

    if (view is android.view.ViewGroup && isTransparentWrapper(view)) {
        val singleChild = view.getChildAt(0)
        if (singleChild != null) {
            ShadowNode(singleChild, depth)
            return
        }
    }

    if (view is android.view.ViewGroup) {
        val toggleRow = detectToggleRow(view)
        if (toggleRow != null) {
            DesktopSettingsToggleTile(toggleRow.title, toggleRow.subtitle, toggleRow.toggle)
            return
        }
    }

    if (view is android.view.ViewGroup && isTappableTileGroup(view)) {
        TappableContainerTile(view, depth)
        return
    }

    val marginParams = view.layoutParams as? android.view.ViewGroup.MarginLayoutParams
    val verticalMargin = ((marginParams?.topMargin ?: 0) + (marginParams?.bottomMargin ?: 0)).pxToDp()
    val horizontalMargin = ((marginParams?.leftMargin ?: 0) + (marginParams?.rightMargin ?: 0)).pxToDp()

    Box(modifier = Modifier.padding(horizontal = horizontalMargin / 2, vertical = verticalMargin / 2)) {
        when (view) {
            is android.widget.Spinner -> SpinnerNode(view)
            is androidx.recyclerview.widget.RecyclerView -> RecyclerViewNode(view)
            is android.widget.ListView -> ListNode(view)
            is android.widget.ScrollView -> ScrollNode(view)
            is android.widget.LinearLayout -> LinearLayoutNode(view, depth)
            is android.widget.RadioGroup -> LinearLayoutNode(view, depth)
            is android.widget.RelativeLayout -> ViewGroupColumnNode(view, depth)
            is android.widget.EditText -> EditTextNode(view)
            is android.widget.Switch -> SwitchNode(view)
            is android.widget.CheckBox -> CheckBoxNode(view)
            is android.widget.RadioButton -> RadioButtonNode(view)
            is android.widget.Button -> ButtonNode(view)
            is android.widget.ImageButton -> IconButtonNode(view)
            is android.widget.ImageView -> ImageNode(view)
            is android.widget.ProgressBar -> ProgressNode(view)
            is android.widget.TextView -> TextNode(view)
            is android.widget.Space -> Spacer(Modifier.height(((view.layoutParams?.height ?: 16) / 2f).dp.coerceAtLeast(4.dp)))
            is android.view.ViewGroup -> ViewGroupColumnNode(view, depth)
            else -> PlainViewNode(view)
        }
    }
}

@Composable
internal fun LinearLayoutNode(layout: android.widget.LinearLayout, depth: Int) {
    if (layout.orientation == android.widget.LinearLayout.HORIZONTAL) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            layout.children.forEach { child ->
                val weight = (child.layoutParams as? android.widget.LinearLayout.LayoutParams)?.weight ?: 0f
                if (weight > 0f) {
                    Box(Modifier.weight(weight)) {
                        ShadowNode(child, depth + 1)
                    }
                } else {
                    ShadowNode(child, depth + 1)
                }
            }
        }
    } else {
        ViewGroupColumnNode(layout, depth)
    }
}

@Composable
internal fun ViewGroupColumnNode(group: android.view.ViewGroup, depth: Int) {
    val visibleChildren = group.children.filter { it.visibility != android.view.View.GONE }.toList()
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        visibleChildren.forEach { child ->
            ShadowNode(child, depth + 1)
        }
    }
}

@Composable
internal fun ScrollNode(scroll: android.widget.ScrollView) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 500.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        scroll.children.forEach { ShadowNode(it, 2) }
    }
}

@Composable
internal fun ListNode(list: android.widget.ListView) {
    val adapter = list.adapter
    Column(Modifier.fillMaxWidth()) {
        if (adapter != null) {
            repeat(adapter.getCount()) { position ->
                val row = runCatching { adapter.getView(position, null, list) }.getOrNull()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            ShadowUi.dispatch("list-item") { list.performItemClick(position) }
                        }
                        .padding(vertical = 6.dp),
                ) {
                    row?.let { ShadowNode(it, 3) }
                }
            }
        }
    }
}

@Composable
internal fun RecyclerViewNode(recycler: androidx.recyclerview.widget.RecyclerView) {
    val adapter = recycler.adapter ?: return
    Column(Modifier.fillMaxWidth()) {
        repeat(adapter.getItemCount()) { position ->
            val holder = runCatching {
                adapter.onCreateViewHolder(recycler, adapter.getItemViewType(position))
            }.getOrNull()
            if (holder != null) {
                runCatching {
                    @Suppress("UNCHECKED_CAST")
                    (adapter as androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>)
                        .onBindViewHolder(holder, position)
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            ShadowUi.dispatch("rv-item") {
                                holder.itemView.performClick()
                            }
                        },
                ) {
                    ShadowNode(holder.itemView, 3)
                }
            }
        }
    }
}
