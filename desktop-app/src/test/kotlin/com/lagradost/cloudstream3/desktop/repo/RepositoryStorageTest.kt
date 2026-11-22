package com.lagradost.cloudstream3.desktop.repo

import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RepositoryStorageTest {

    @Test
    fun testNormalizeRepositoryData() {
        val raw = RepositoryData(
            name = "  My Extension Repo  ",
            url = "https://example.com/repo.json",
            iconUrl = "   ",
        )
        val normalized = RepositoryStorage.normalizeRepositoryData(raw)
        assertEquals("My Extension Repo", normalized.name)
        assertEquals("https://example.com/repo.json", normalized.url)
        assertNull(normalized.iconUrl, "Whitespace-only icon URL must be normalized to null")

        val emptyName = RepositoryData(
            name = "",
            url = "https://example.com/repo.json",
            iconUrl = "https://example.com/icon.png",
        )
        val normalizedEmptyName = RepositoryStorage.normalizeRepositoryData(emptyName)
        assertEquals("https://example.com/repo.json", normalizedEmptyName.name, "Empty name should fall back to url")
        assertEquals("https://example.com/icon.png", normalizedEmptyName.iconUrl)
    }

    @Test
    fun testGetPluginsJsonUrlFallback() {
        val repoUrl = "https://raw.githubusercontent.com/test-org/repo/main/repo.json"
        val pluginsUrl = RepositoryStorage.getPluginsJsonUrl(repoUrl)
        assertEquals(
            "https://raw.githubusercontent.com/test-org/repo/main/builds/plugins.json",
            pluginsUrl,
            "Standard repo.json URL should transform to builds/plugins.json when uncached",
        )

        val directUrl = "https://example.com/custom/plugins.json"
        val directPluginsUrl = RepositoryStorage.getPluginsJsonUrl(directUrl)
        assertEquals(directUrl, directPluginsUrl)
    }
}
