package com.lagradost.cloudstream3.syncproviders

import com.lagradost.cloudstream3.syncproviders.providers.AniListApi
import com.lagradost.cloudstream3.syncproviders.providers.MalApi
import com.lagradost.cloudstream3.syncproviders.providers.PrimaryStremioSubApi
import com.lagradost.cloudstream3.syncproviders.providers.SimklApi
import com.lagradost.cloudstream3.syncproviders.providers.SubDlApi
import com.lagradost.cloudstream3.syncproviders.providers.SubsourceApi
import com.lagradost.cloudstream3.syncproviders.providers.SubtitleCatApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AccountManager {
    companion object {
        @JvmStatic
        val PrimaryStremioSubApi = PrimaryStremioSubApi()

        @JvmStatic
        val subDlApi = SubDlApi()

        @JvmStatic
        val subsourceApi = SubsourceApi()

        @JvmStatic
        val subtitleCatApi = SubtitleCatApi()

        @JvmStatic
        val aniListApi = AniListApi()

        @JvmStatic
        val malApi = MalApi()

        @JvmStatic
        val simklApi = SimklApi()

        val subtitleProviders = listOf(PrimaryStremioSubApi, subDlApi, subsourceApi, subtitleCatApi)
        val allApis = listOf(PrimaryStremioSubApi, subDlApi, subsourceApi, subtitleCatApi, aniListApi, malApi, simklApi)

        var cachedAccounts: MutableMap<String, Array<AuthData>> = mutableMapOf()

        private val _accountsFlow = MutableStateFlow<Map<String, Array<AuthData>>>(emptyMap())
        val accountsFlow: StateFlow<Map<String, Array<AuthData>>> = _accountsFlow.asStateFlow()

        const val ACCOUNT_TOKEN = "auth_tokens"
        private val TRACKER_PREFIXES = setOf("anilist", "mal", "simkl")

        val currentAccount: String
            get() = "profile_${com.lagradost.common.storage.DesktopDataStore.activeProfileId}"

        private fun storageKeyForPrefix(prefix: String, profileId: Int? = null): String {
            val scope = if (prefix in TRACKER_PREFIXES) {
                "profile_${profileId ?: com.lagradost.common.storage.DesktopDataStore.activeProfileId}"
            } else {
                "default"
            }
            return "${ACCOUNT_TOKEN}_${prefix}_$scope"
        }

        fun accounts(prefix: String): Array<AuthData> {
            require(prefix != "NONE")
            return com.lagradost.common.storage.DesktopDataStore.getKey<Array<AuthData>>(
                storageKeyForPrefix(prefix),
            ) ?: arrayOf()
        }

        fun updateAccounts(prefix: String, array: Array<AuthData>) {
            require(prefix != "NONE")
            com.lagradost.common.storage.DesktopDataStore.setKey(storageKeyForPrefix(prefix), array)
            synchronized(cachedAccounts) {
                cachedAccounts[prefix] = array
                _accountsFlow.value = cachedAccounts.toMap()
            }
        }

        fun reloadFromDataStore() {
            val data = mutableMapOf<String, Array<AuthData>>()
            for (api in allApis) {
                data[api.idPrefix] = accounts(api.idPrefix)
            }
            synchronized(cachedAccounts) {
                cachedAccounts = data
                _accountsFlow.value = data.toMap()
            }
        }

        fun clearTrackerAccountsForProfile(profileId: Int) {
            for (prefix in TRACKER_PREFIXES) {
                com.lagradost.common.storage.DesktopDataStore.removeKey(storageKeyForPrefix(prefix, profileId))
            }
            if (com.lagradost.common.storage.DesktopDataStore.activeProfileId == profileId) {
                reloadFromDataStore()
            }
        }

        init {
            reloadFromDataStore()
        }
    }
}
