package com.astraedus.nudge.service

import android.view.accessibility.AccessibilityNodeInfo
import com.astraedus.nudge.util.NudgeLogger
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression tests for the DM-opened reel bypass (device-found 2026-08-08).
 *
 * Instagram's full-screen reel player is hosted in `com.instagram.modal.ModalActivity`, which has
 * NO bottom navigation. Detection keyed exclusively on which bottom-nav tab was `selected`, so the
 * player was structurally invisible: opening a reel from a DM produced no detected feature, and a
 * user with a HARD_BLOCK rule on REELS could scroll reels indefinitely. Reels opened from the Reels
 * TAB blocked correctly, which is why the gap went unnoticed.
 *
 * An instrumented capture on a Galaxy S24 recorded ~800 detection attempts on these screens, every
 * one returning null. The fix keys on the player's own containers instead of the nav bar.
 */
class InAppDetectorClipsViewerTest {

    private val detector = InAppDetector(mockk<NudgeLogger>(relaxed = true))

    private val ig = "com.instagram.android"

    /**
     * A root whose [AccessibilityNodeInfo.findAccessibilityNodeInfosByViewId] resolves exactly the
     * ids in [presentIds] and nothing else. childCount is 0 so the debug harvest terminates.
     */
    private fun rootWith(presentIds: Set<String>): AccessibilityNodeInfo {
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { root.childCount } returns 0
        every { root.viewIdResourceName } returns null
        every { root.findAccessibilityNodeInfosByViewId(any()) } answers {
            val id = firstArg<String>()
            if (id in presentIds) listOf(mockk<AccessibilityNodeInfo>(relaxed = true)) else emptyList()
        }
        return root
    }

    /**
     * Bottom-nav fixture with one selected tab-icon child and no clips-viewer container ids.
     * This reproduces the important fallback path: a normal Reels-tab session must still spend the
     * HikaruFocus budget even if Instagram renames the internal player containers on this device.
     */
    private fun rootWithActiveTab(
        activeTabId: String,
        reelsTabUsesStableId: Boolean = true
    ): AccessibilityNodeInfo {
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { root.viewIdResourceName } returns null
        every { root.contentDescription } returns null
        every { root.isSelected } returns false
        every { root.findAccessibilityNodeInfosByViewId(any()) } returns emptyList()

        val tabIds = listOf("feed_tab", "clips_tab", "direct_tab", "search_tab", "profile_tab")
        val tabs = tabIds.map { tabId ->
            val tab = mockk<AccessibilityNodeInfo>(relaxed = true)
            val icon = mockk<AccessibilityNodeInfo>(relaxed = true)
            every { tab.viewIdResourceName } returns when {
                tabId == "clips_tab" && !reelsTabUsesStableId -> "com.instagram.android:id/renamed_reels_tab"
                else -> "com.instagram.android:id/$tabId"
            }
            every { tab.contentDescription } returns when (tabId) {
                "feed_tab" -> "Home"
                "clips_tab" -> "Reels"
                "direct_tab" -> "Message"
                "search_tab" -> "Search and explore"
                "profile_tab" -> "Profile"
                else -> null
            }
            every { tab.isSelected } returns false
            every { tab.childCount } returns 1
            every { tab.getChild(0) } returns icon
            every { icon.viewIdResourceName } returns "com.instagram.android:id/tab_icon"
            every { icon.contentDescription } returns null
            every { icon.isSelected } returns (tabId == activeTabId)
            every { icon.childCount } returns 0
            tab
        }

        every { root.childCount } returns tabs.size
        tabs.forEachIndexed { index, tab ->
            every { root.getChild(index) } returns tab
        }
        return root
    }

    /** The exact surface captured from a reel opened out of a DM thread. */
    @Test
    fun `reel opened from a DM is detected as REELS`() {
        val root = rootWith(
            setOf(
                "com.instagram.android:id/clips_viewer_view_pager",
                "com.instagram.android:id/clips_video_container",
                "com.instagram.android:id/clips_media_component"
            )
        )

        assertEquals(InAppDetector.Feature.REELS, detector.detectFeature(ig, root))
        org.junit.Assert.assertTrue(detector.isInstagramReelPlayer(root))
    }

    /**
     * The player's tree varies by entry point — the DM variant carries a reply bar, others do not.
     * Any single container is sufficient, so a partial match must still detect.
     */
    @Test
    fun `any single clips container is enough`() {
        listOf(
            "com.instagram.android:id/clips_viewer_view_pager",
            "com.instagram.android:id/clips_video_container",
            "com.instagram.android:id/clips_media_component"
        ).forEach { id ->
            assertEquals(
                "expected REELS from $id alone",
                InAppDetector.Feature.REELS,
                detector.detectFeature(ig, rootWith(setOf(id)))
            )
        }
    }

    @Test
    fun `active Reels tab spends budget even when player container ids are absent`() {
        org.junit.Assert.assertTrue(detector.isInstagramReelPlayer(rootWithActiveTab("clips_tab")))
        assertEquals(
            InAppDetector.InstagramReelPresence.VISIBLE,
            detector.instagramReelPresence(rootWithActiveTab("clips_tab"))
        )
    }

    @Test
    fun `active Home tab does not spend HikaruFocus reel budget`() {
        val root = rootWithActiveTab("feed_tab")
        org.junit.Assert.assertFalse(detector.isInstagramReelPlayer(root))
        assertEquals(
            InAppDetector.InstagramReelPresence.NOT_VISIBLE,
            detector.instagramReelPresence(root)
        )
        assertNull(detector.detectFeature(ig, root))
    }

    @Test
    fun `Reels content description survives a renamed internal tab id`() {
        assertEquals(
            InAppDetector.InstagramReelPresence.VISIBLE,
            detector.instagramReelPresence(
                rootWithActiveTab("clips_tab", reelsTabUsesStableId = false)
            )
        )
    }

    /**
     * The player check must not fire on ordinary browsing. A DM thread shows reel previews but
     * carries none of the player containers — blocking here would make Instagram unusable for
     * messaging, which is not what a REELS rule asks for.
     */
    @Test
    fun `a DM thread is not detected as REELS`() {
        val root = rootWith(
            setOf(
                "com.instagram.android:id/thread_fragment_container",
                "com.instagram.android:id/message_list",
                "com.instagram.android:id/message_composer_bar"
            )
        )

        assertNull(detector.detectFeature(ig, root))
        org.junit.Assert.assertFalse(detector.isInstagramReelPlayer(root))
        assertEquals(
            InAppDetector.InstagramReelPresence.NOT_VISIBLE,
            detector.instagramReelPresence(root)
        )
    }

    /** The player check must not hijack a surface with no clips containers and no active tab. */
    @Test
    fun `an unknown surface still returns null`() {
        val root = rootWith(emptySet())
        assertNull(detector.detectFeature(ig, root))
        assertEquals(
            InAppDetector.InstagramReelPresence.UNKNOWN,
            detector.instagramReelPresence(root)
        )
    }

    /** A null root must never throw — detection is best-effort by contract. */
    @Test
    fun `null root returns null`() {
        assertNull(detector.detectFeature(ig, null))
        org.junit.Assert.assertFalse(detector.isInstagramReelPlayer(null))
        assertEquals(
            InAppDetector.InstagramReelPresence.UNKNOWN,
            detector.instagramReelPresence(null)
        )
    }

    /** The player containers are Instagram-specific and must not leak into YouTube detection. */
    @Test
    fun `clips containers do not trigger for YouTube`() {
        val root = rootWith(setOf("com.instagram.android:id/clips_viewer_view_pager"))

        assertNull(detector.detectFeature("com.google.android.youtube", root))
    }
}
