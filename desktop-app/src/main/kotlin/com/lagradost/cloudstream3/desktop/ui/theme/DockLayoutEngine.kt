package com.lagradost.cloudstream3.desktop.ui.theme

import com.lagradost.common.storage.DesktopDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object DockLayoutEngine {
    private const val PREF_DOCK_ITEM_ORDER = "pref_dock_item_order"
    private const val PREF_DOCK_DISABLED_ITEMS = "pref_dock_disabled_items"
    private const val PREF_TOPBAR_SHOW_PROFILE = "pref_topbar_show_profile"
    private const val PREF_TOPBAR_SHOW_PROFILE_NAME = "pref_topbar_show_profile_name"
    private const val PREF_SHOW_CONTINUE_WATCHING = "pref_show_continue_watching"

    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _dockItemOrder = MutableStateFlow(
        DockItemKey.parseOrder(DesktopDataStore.getKey<String>(PREF_DOCK_ITEM_ORDER)),
    )
    val dockItemOrder: StateFlow<List<DockItemKey>> = _dockItemOrder.asStateFlow()

    private val _dockDisabledItems = MutableStateFlow(
        DockItemKey.parseDisabled(DesktopDataStore.getKey<String>(PREF_DOCK_DISABLED_ITEMS)),
    )
    val dockDisabledItems: StateFlow<Set<DockItemKey>> = _dockDisabledItems.asStateFlow()

    private val _topBarShowProfile = MutableStateFlow(
        DesktopDataStore.getKey<Boolean>(PREF_TOPBAR_SHOW_PROFILE) ?: false,
    )
    val topBarShowProfile: StateFlow<Boolean> = _topBarShowProfile.asStateFlow()

    private val _topBarShowProfileName = MutableStateFlow(
        DesktopDataStore.getKey<Boolean>(PREF_TOPBAR_SHOW_PROFILE_NAME) ?: true,
    )
    val topBarShowProfileName: StateFlow<Boolean> = _topBarShowProfileName.asStateFlow()

    private val _showContinueWatching = MutableStateFlow(
        DesktopDataStore.getKey<Boolean>(PREF_SHOW_CONTINUE_WATCHING) ?: true,
    )
    val showContinueWatching: StateFlow<Boolean> = _showContinueWatching.asStateFlow()

    private fun persist(key: String, value: Any?) {
        persistenceScope.launch {
            DesktopDataStore.setKey(key, value)
        }
    }

    private fun removeKey(key: String) {
        persistenceScope.launch {
            DesktopDataStore.removeKey(key)
        }
    }

    fun setDockItemOrder(order: List<DockItemKey>) {
        _dockItemOrder.value = order
        persist(PREF_DOCK_ITEM_ORDER, DockItemKey.serializeOrder(order))
    }

    fun toggleDockItem(key: DockItemKey, enabled: Boolean) {
        if (key.isRequired) return
        val current = _dockDisabledItems.value.toMutableSet()
        if (enabled) {
            current.remove(key)
        } else {
            current.add(key)
        }
        _dockDisabledItems.value = current
        persist(PREF_DOCK_DISABLED_ITEMS, DockItemKey.serializeDisabled(current))
    }

    fun moveDockItem(fromIndex: Int, toIndex: Int) {
        val current = _dockItemOrder.value.toMutableList()
        if (fromIndex in current.indices && toIndex in current.indices && fromIndex != toIndex) {
            val item = current.removeAt(fromIndex)
            current.add(toIndex, item)
            setDockItemOrder(current)
        }
    }

    fun resetDockItemOrder() {
        setDockItemOrder(DockItemKey.DEFAULT_ORDER)
        _dockDisabledItems.value = DockItemKey.DEFAULT_DISABLED
        removeKey(PREF_DOCK_ITEM_ORDER)
        removeKey(PREF_DOCK_DISABLED_ITEMS)
    }

    fun setTopBarShowProfile(enabled: Boolean) {
        _topBarShowProfile.value = enabled
        persist(PREF_TOPBAR_SHOW_PROFILE, enabled)
    }

    fun setTopBarShowProfileName(enabled: Boolean) {
        _topBarShowProfileName.value = enabled
        persist(PREF_TOPBAR_SHOW_PROFILE_NAME, enabled)
    }

    fun setShowContinueWatching(enabled: Boolean) {
        _showContinueWatching.value = enabled
        persist(PREF_SHOW_CONTINUE_WATCHING, enabled)
    }

    fun reloadFromDataStore() {
        _dockItemOrder.value = DockItemKey.parseOrder(DesktopDataStore.getKey<String>(PREF_DOCK_ITEM_ORDER))
        _dockDisabledItems.value = DockItemKey.parseDisabled(DesktopDataStore.getKey<String>(PREF_DOCK_DISABLED_ITEMS))
        _topBarShowProfile.value = DesktopDataStore.getKey<Boolean>(PREF_TOPBAR_SHOW_PROFILE) ?: false
        _topBarShowProfileName.value = DesktopDataStore.getKey<Boolean>(PREF_TOPBAR_SHOW_PROFILE_NAME) ?: true
        _showContinueWatching.value = DesktopDataStore.getKey<Boolean>(PREF_SHOW_CONTINUE_WATCHING) ?: true
    }
}
