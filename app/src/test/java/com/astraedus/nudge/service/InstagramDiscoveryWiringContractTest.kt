package com.astraedus.nudge.service

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source-level guards for HikaruFocus's Instagram-specific discovery-budget wiring. */
class InstagramDiscoveryWiringContractTest {

    private val path = "main/java/com/astraedus/nudge/service/NudgeAccessibilityService.kt"

    private fun source(): String {
        val file = listOf(File("src/$path"), File("app/src/$path"))
            .firstOrNull { it.exists() }
            ?: error("$path not found from ${File("").absolutePath}")
        return file.readText()
    }

    private fun bodyOf(signature: String): String {
        val text = source()
        val start = text.indexOf(signature)
        assertTrue("$signature not found in $path", start >= 0)
        var depth = 0
        var i = text.indexOf('{', start)
        assertTrue("no body for $signature", i >= 0)
        val from = i
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(from, i + 1)
                }
            }
            i++
        }
        error("unbalanced braces after $signature")
    }

    @Test
    fun `Instagram budget starts from app foreground rather than reel detection`() {
        val body = bodyOf("private fun maintainInstagramAppBudgetPresence(")
        assertTrue(body.contains("signal.packageName == InstagramSurfaces.packageName"))
        assertTrue(body.contains("startInstagramAppBudgetSession()"))
        assertTrue(!body.contains("instagramReelPresence"))
    }

    @Test
    fun `Instagram never uses opaque tab cover enforcement`() {
        val body = bodyOf("private suspend fun maintainTabCover(")
        val instagramGuard = body.substringAfter("if (packageName == InstagramSurfaces.packageName)")
            .substringBefore("val requested")
        assertTrue(
            "Instagram must explicitly hide and return before generic Tab Vanish can draw a cover",
            instagramGuard.contains("cover.hide()") && instagramGuard.contains("return")
        )
        assertTrue(
            "the old second Instagram cover must not exist",
            !source().contains("instagramExploreCover")
        )
    }

    @Test
    fun `locked discovery ejects both Reels and Explore`() {
        val body = bodyOf("private fun maybeApplyInstagramDiscoveryBackstop(")
        assertTrue(body.contains("InstagramDiscoveryPolicy.REELS"))
        assertTrue(body.contains("InstagramDiscoveryPolicy.EXPLORE"))
        assertTrue(body.contains("navigateInstagramHomeFromDiscovery"))
    }

    @Test
    fun `blocked tab taps have an eager path before debounced surface detection`() {
        val onEvent = bodyOf("override fun onAccessibilityEvent(event: AccessibilityEvent?)")
        assertTrue(onEvent.contains("maybeInterceptInstagramDiscoveryTap(event, packageName)"))

        val intercept = bodyOf("private fun maybeInterceptInstagramDiscoveryTap(")
        assertTrue(intercept.contains("InstagramSurfaces.discoveryGateTabs"))
        assertTrue(intercept.contains("InstagramSurfaces.homeTab"))
        assertTrue(intercept.contains("delay(80L)"))
    }

    @Test
    fun `service reconnect reconciles budget with Android foreground time`() {
        val connected = source().substringAfter("override fun onServiceConnected()")
            .substringBefore("override fun onAccessibilityEvent(")
        assertTrue(connected.contains("syncInstagramBudgetFromUsageStats(\"service_connected\")"))

        val sync = bodyOf("private suspend fun syncInstagramBudgetFromUsageStats(")
        assertTrue(sync.contains("getDailyForegroundTimeMs(InstagramSurfaces.packageName)"))
        assertTrue(sync.contains("InstagramDiscoveryBudget.syncAbsoluteUsage"))
    }
}
