package com.astraedus.nudge.data.preferences

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class InstagramDiscoveryBudgetPreferencesContractTest {

    private val source: String by lazy {
        val path = "src/main/java/com/astraedus/nudge/data/preferences/NudgePreferences.kt"
        val candidates = listOf(File(path), File("app/$path"))
        (candidates.firstOrNull { it.exists() }
            ?: error("NudgePreferences.kt not found from working dir ${File("").absolutePath}"))
            .readText()
    }

    @Test
    fun `custom instagram budget has its own persisted preference`() {
        assertTrue(source.contains("INSTAGRAM_REEL_LIMIT_MINUTES"))
        assertTrue(source.contains("instagramDiscoveryBudgetMinutes"))
        assertTrue(source.contains("setInstagramDiscoveryBudgetMinutes"))
        assertTrue(source.contains("prefs[Keys.INSTAGRAM_REEL_LIMIT_MINUTES] = minutes"))
    }

    @Test
    fun `custom instagram budget remains separate from generic rule daily limits`() {
        val start = source.indexOf("val instagramDiscoveryBudgetMinutes")
        val end = source.indexOf("suspend fun persistInstagramDiscoveryBudgetState")
        val section = source.substring(start, end)

        assertTrue(section.contains("Keys.INSTAGRAM_REEL_LIMIT_MINUTES"))
        assertTrue(!section.contains("DAILY_LIMIT_MINUTES"))
        assertTrue(!section.contains("BlockRule"))
    }
}
