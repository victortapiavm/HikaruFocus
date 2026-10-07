package com.astraedus.nudge.service

import android.view.accessibility.AccessibilityNodeInfo
import com.astraedus.nudge.BuildConfig
import com.astraedus.nudge.util.NudgeLogger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detects in-app features (Reels, Shorts, Explore) by inspecting the accessibility tree.
 *
 * Detection is best-effort -- apps change their UI frequently. When detection fails
 * we return null (no feature detected) rather than crashing, so the service falls back
 * to whole-app rule evaluation.
 */
@Singleton
class InAppDetector @Inject constructor(
    private val logger: NudgeLogger
) : InAppDetectorApi {

    /**
     * Signatures of surfaces already reported by [dumpViewIdsForDiagnosis], so each unrecognised
     * screen is logged once per process rather than once per accessibility event. Debug builds
     * only; bounded in practice by the handful of distinct screens these apps have.
     */
    private val loggedUnknownSurfaces = mutableSetOf<String>()

    /** Last time the debug harvest actually walked the tree; see [dumpViewIdsForDiagnosis]. */
    @Volatile
    private var lastDiagnosticMs = 0L

    enum class Feature(val displayName: String, val key: String) {
        REELS("Instagram Reels", "REELS"),
        SHORTS("YouTube Shorts", "SHORTS"),
        EXPLORE("Instagram Explore", "EXPLORE"),
        TIKTOK_FEED("TikTok Feed", "TIKTOK_FEED")
    }

    /**
     * Accessibility trees are transient snapshots. A missing player id is not proof that the user
     * left Reels, so HikaruFocus must distinguish a definite non-Reel surface from an inconclusive
     * tree. UNKNOWN deliberately preserves an already-running Reel budget session.
     */
    enum class InstagramReelPresence {
        VISIBLE,
        NOT_VISIBLE,
        UNKNOWN
    }

    companion object {
        /** Packages that support in-app feature detection. */
        val SUPPORTED_PACKAGES = setOf(
            "com.instagram.android",
            "com.google.android.youtube",
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill"
        )

        /** Cap for the debug-only view-id harvest; keeps the walk off the hot path's budget. */
        private const val DIAGNOSTIC_NODE_LIMIT = 800

        /** Minimum gap between debug harvest walks. Bounds the cost to ~1 tree walk per interval. */
        private const val DIAGNOSTIC_THROTTLE_MS = 5_000L

        /**
         * Containers unique to Instagram's full-screen reel player, harvested from a real device
         * (Galaxy S24 / Android 16) while watching a reel opened from a DM.
         *
         * Checked instead of the bottom-nav tabs because the player is hosted in a modal activity
         * with no nav bar. Verified absent from the home feed (which shows inline video under
         * `media_group` / `carousel_video_media_group`) and from a DM thread, so these do not
         * over-match ordinary browsing.
         *
         * More than one is listed because the player's tree varies between entry points — the
         * DM-opened variant additionally carries a reply bar. Any single match is sufficient.
         */
        private val INSTAGRAM_CLIPS_VIEWER_IDS = listOf(
            "com.instagram.android:id/clips_viewer_view_pager",
            "com.instagram.android:id/clips_video_container",
            "com.instagram.android:id/clips_media_component"
        )

        /** Positive evidence for common Instagram surfaces that are definitely not a Reel player. */
        private val INSTAGRAM_NON_REEL_SURFACE_IDS = listOf(
            "com.instagram.android:id/title_logo",
            "com.instagram.android:id/sticky_header_list",
            "com.instagram.android:id/thread_fragment_container",
            "com.instagram.android:id/message_list",
            "com.instagram.android:id/message_composer_bar"
        )
    }

    /** True if any of [viewIds] resolves in [root]. Nodes are recycled before returning. */
    private fun findsAnyViewId(root: AccessibilityNodeInfo, viewIds: List<String>): Boolean {
        for (id in viewIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (nodes.isNotEmpty()) {
                recycleNodes(nodes)
                return true
            }
            recycleNodes(nodes)
        }
        return false
    }

    /**
     * Attempt to detect which in-app feature is active for the given package.
     *
     * @return The detected [Feature], or null if no specific feature is detected
     *   (user is in a non-feature part of the app, or detection failed).
     */
    override fun detectFeature(packageName: String, rootNode: AccessibilityNodeInfo?): Feature? {
        if (rootNode == null) {
            logger.d("feature detection skipped package=$packageName reason=null_root")
            return null
        }
        return try {
            val feature = when (packageName) {
                "com.instagram.android" -> detectInstagram(rootNode)
                "com.google.android.youtube" -> detectYouTube(rootNode)
                "com.zhiliaoapp.musically", "com.ss.android.ugc.trill" -> Feature.TIKTOK_FEED
                else -> null
            }
            if (feature == null) dumpViewIdsForDiagnosis(packageName, rootNode)
            logger.d("feature detection result package=$packageName feature=$feature")
            feature
        } catch (e: Exception) {
            logger.w("feature detection failed package=$packageName", e)
            null
        }
    }

    /**
     * Classify whether Instagram's actual full-screen Reel player is visible.
     *
     * This is intentionally narrower than [Feature.REELS]. Nudge historically treats Instagram's
     * Home feed as REELS-equivalent for generic doomscroll rules. HikaruFocus now uses this method
     * only to decide whether post-budget enforcement is currently sitting on the actual Reel player;
     * the daily budget itself is app-wide foreground time.
     */
    fun instagramReelPresence(rootNode: AccessibilityNodeInfo?): InstagramReelPresence {
        if (rootNode == null) return InstagramReelPresence.UNKNOWN
        return try {
            // Primary signal: containers observed inside the full-screen player. This keeps direct
            // arrivals (DM/profile/deep link) countable even though they have no bottom nav.
            if (findsAnyViewId(rootNode, INSTAGRAM_CLIPS_VIEWER_IDS)) {
                return InstagramReelPresence.VISIBLE
            }

            // Resilience signal: when the user entered through Instagram's Reels tab, the tab itself
            // is a stronger and considerably more stable contract than the internal player-container
            // ids. Those container ids have already varied by entry route/device, and HikaruFocus's
            // first real-device test exposed the failure mode: if none resolves, Reels can evade the
            // post-budget ejection path even though the app-wide budget is already exhausted.
            //
            // IMPORTANT: do not reuse detectInstagram() here. Generic Nudge intentionally maps the
            // HOME feed to Feature.REELS for historical doom-scroll rules. Asking specifically whether
            // clips_tab is active preserves the boundary between Home and the actual Reel player.
            when (findActiveInstagramTab(rootNode)) {
                "clips_tab" -> InstagramReelPresence.VISIBLE
                "feed_tab", "search_tab", "profile_tab", "direct_tab" ->
                    InstagramReelPresence.NOT_VISIBLE
                else -> if (findsAnyViewId(rootNode, INSTAGRAM_NON_REEL_SURFACE_IDS)) {
                    InstagramReelPresence.NOT_VISIBLE
                } else {
                    InstagramReelPresence.UNKNOWN
                }
            }
        } catch (e: Exception) {
            logger.w("instagram reel-player detection failed", e)
            InstagramReelPresence.UNKNOWN
        }
    }

    fun isInstagramReelPlayer(rootNode: AccessibilityNodeInfo?): Boolean =
        instagramReelPresence(rootNode) == InstagramReelPresence.VISIBLE

    /**
     * DIAGNOSTIC (debug builds only): log the distinct view IDs present when detection found
     * nothing, so an undetected surface can be identified from logcat.
     *
     * Exists because the usual external tools cannot see these surfaces: `uiautomator dump` waits
     * for an idle window and a continuously playing reel/short never idles, so it hangs and gets
     * Killed; `dumpsys activity top` times out on the same screens. The accessibility tree this
     * service already walks has no such constraint.
     *
     * Reads ONLY `viewIdResourceName` — never text or contentDescription, which on these screens
     * would be the user's private messages and captions. Bounded to [DIAGNOSTIC_NODE_LIMIT] nodes,
     * matching the bounded-harvest convention used by the Strict Mode escape guard.
     */
    private fun dumpViewIdsForDiagnosis(packageName: String, root: AccessibilityNodeInfo) {
        if (!BuildConfig.DEBUG) return
        // Throttle the WALK, not just the logging. Detection fails on a firehose of content-change
        // events — ~800 times in three minutes of measured Instagram use — and each call would
        // otherwise BFS up to DIAGNOSTIC_NODE_LIMIT nodes on the accessibility hot path. A new
        // surface stays on screen for far longer than this interval, so nothing is missed.
        val now = System.currentTimeMillis()
        if (now - lastDiagnosticMs < DIAGNOSTIC_THROTTLE_MS) return
        lastDiagnosticMs = now

        val ids = LinkedHashSet<String>()
        var visited = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue += root
        while (queue.isNotEmpty() && visited < DIAGNOSTIC_NODE_LIMIT) {
            val node = queue.removeFirst()
            visited++
            node.viewIdResourceName?.let { ids += it }
            for (i in 0 until node.childCount) {
                queue += node.getChild(i) ?: continue
            }
        }
        // Log each DISTINCT surface once. Detection runs on a firehose of content-change events —
        // a measured ~800 failed detections in three minutes of Instagram use — so logging every
        // miss buries the signal. What matters is "which surfaces do we not recognise", and that
        // set is tiny.
        val signature = ids.joinToString(",")
        if (!loggedUnknownSurfaces.add(signature)) return
        logger.d("undetected surface package=$packageName nodes=$visited viewIds=$signature")
    }

    private fun detectInstagram(root: AccessibilityNodeInfo): Feature? {
        // The reel PLAYER first, before any tab reasoning. A reel opened from a DM (or a share
        // link, or a profile) runs in com.instagram.modal.ModalActivity, which has NO bottom nav
        // at all — so tab-based detection cannot see it even in principle, and the user scrolled
        // reels indefinitely with a HARD_BLOCK rule active. Keying on the player's own container
        // covers every entry route, including the Reels tab, where these IDs are also present.
        if (isInstagramReelPlayer(root)) {
            logger.d("instagram clips viewer detected")
            return Feature.REELS
        }

        // Use resource IDs for reliable tab detection. Instagram's bottom nav tabs:
        //   feed_tab (Home), clips_tab (Reels), search_tab (Search/Explore), profile_tab (Profile)
        // The tab FrameLayout itself has selected=false, but its child tab_icon ImageView
        // has selected=true for the active tab.
        val activeTab = findActiveInstagramTab(root)
        logger.d("instagram active tab: $activeTab")
        return when (activeTab) {
            "clips_tab" -> Feature.REELS
            "search_tab" -> Feature.EXPLORE
            // HikaruFocus keeps Home usable after the Instagram discovery budget. The upstream Nudge
            // detector treated Home as REELS-equivalent for doom-scroll rules, but that makes an
            // ejection from blocked Reels land straight onto another REELS block. Home is therefore
            // explicitly a non-feature surface in this fork.
            "feed_tab" -> null
            else -> {
                // Fallback: text-based detection for older Instagram versions
                detectInstagramByText(root)
            }
        }
    }

    /**
     * Find which Instagram bottom-nav tab is active by walking the LIVE accessibility tree.
     *
     * Do not replace this with `findAccessibilityNodeInfosByViewId(tabId)` plus a child inspection.
     * A real-device Nudge investigation found that nodes returned by that lookup do not reliably
     * expose the selected state of the nested `tab_icon`, even though the same child is selected when
     * reached by walking from `rootInActiveWindow`. The old implementation therefore worked in mocks
     * while returning null on devices -- exactly the kind of false-green HikaruFocus must avoid.
     *
     * We carry the nearest tab id down the traversal. The first selected node inside one of the five
     * known tab subtrees names the active tab. The walk is bounded: the bottom bar is shallow, and a
     * pathological host tree must never make feature detection unbounded on the accessibility thread.
     */
    private fun findActiveInstagramTab(root: AccessibilityNodeInfo): String? {
        data class PendingNode(
            val node: AccessibilityNodeInfo,
            val owningTab: String?,
            val recycleWhenDone: Boolean
        )

        val tabIds = setOf("feed_tab", "clips_tab", "search_tab", "profile_tab", "direct_tab")
        val queue = ArrayDeque<PendingNode>()
        queue += PendingNode(root, owningTab = null, recycleWhenDone = false)
        var visited = 0

        fun recycleQueuedNodes() {
            while (queue.isNotEmpty()) {
                val pending = queue.removeFirst()
                if (pending.recycleWhenDone) recycleNode(pending.node)
            }
        }

        while (queue.isNotEmpty() && visited < DIAGNOSTIC_NODE_LIMIT) {
            val pending = queue.removeFirst()
            val node = pending.node
            visited++

            var owningTab = pending.owningTab
            try {
                val id = node.viewIdResourceName
                val suffix = id
                    ?.takeIf { it.startsWith("com.instagram.android:id/") }
                    ?.substringAfterLast('/')
                val description = node.contentDescription?.toString()
                owningTab = when {
                    suffix != null && suffix in tabIds -> suffix
                    description == "Reels" -> "clips_tab"
                    description == "Home" -> "feed_tab"
                    description == "Search and explore" -> "search_tab"
                    description == "Profile" -> "profile_tab"
                    description == "Message" || description == "Messages" -> "direct_tab"
                    else -> owningTab
                }

                if (owningTab != null && node.isSelected) {
                    recycleQueuedNodes()
                    return owningTab
                }

                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    queue += PendingNode(child, owningTab, recycleWhenDone = true)
                }
            } finally {
                if (pending.recycleWhenDone) recycleNode(node)
            }
        }

        recycleQueuedNodes()
        return null
    }

    /** Fallback text-based detection for older Instagram versions. */
    private fun detectInstagramByText(root: AccessibilityNodeInfo): Feature? {
        val reelsNodes = root.findAccessibilityNodeInfosByText("Reels")
        if (reelsNodes.isNotEmpty()) {
            for (node in reelsNodes) {
                if (node.isSelected || isInSelectedTab(node)) {
                    recycleNodes(reelsNodes)
                    return Feature.REELS
                }
            }
        }
        recycleNodes(reelsNodes)

        val exploreNodes = root.findAccessibilityNodeInfosByText("Explore")
        if (exploreNodes.isNotEmpty()) {
            for (node in exploreNodes) {
                if (node.isSelected || isInSelectedTab(node)) {
                    recycleNodes(exploreNodes)
                    return Feature.EXPLORE
                }
            }
        }
        recycleNodes(exploreNodes)

        return null
    }

    private fun detectYouTube(root: AccessibilityNodeInfo): Feature? {
        // Method 1: Check if Shorts tab is selected (user navigated via bottom tab)
        val shortsNodes = root.findAccessibilityNodeInfosByText("Shorts")
        if (shortsNodes.isNotEmpty()) {
            for (node in shortsNodes) {
                if (node.isSelected || isInSelectedTab(node) || hasSelectedChild(node)) {
                    recycleNodes(shortsNodes)
                    return Feature.SHORTS
                }
            }
        }
        recycleNodes(shortsNodes)

        // Method 2: Check for Shorts player container (user tapped a Short from home feed)
        val reelRecycler = root.findAccessibilityNodeInfosByViewId(
            "com.google.android.youtube:id/reel_recycler"
        )
        if (reelRecycler.isNotEmpty()) {
            recycleNodes(reelRecycler)
            return Feature.SHORTS
        }

        // Method 3: Check for reel player page (another common Shorts container ID)
        val reelPlayer = root.findAccessibilityNodeInfosByViewId(
            "com.google.android.youtube:id/reel_player_page_container"
        )
        if (reelPlayer.isNotEmpty()) {
            recycleNodes(reelPlayer)
            return Feature.SHORTS
        }

        return null
    }

    /**
     * Walk up the parent chain to check if any ancestor is marked as selected.
     * This handles cases where the tab text itself is not selected but its container is.
     */
    private fun isInSelectedTab(node: AccessibilityNodeInfo): Boolean {
        var current = node.parent
        var depth = 0
        while (current != null && depth < 5) {
            if (current.isSelected) return true
            val next = current.parent
            current = next
            depth++
        }
        return false
    }

    /**
     * Check if any immediate child of the node is selected.
     * Instagram sets selected=true on the child tab_icon ImageView, not the
     * parent FrameLayout that carries the content-description.
     */
    private fun hasSelectedChild(node: AccessibilityNodeInfo): Boolean {
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (child.isSelected) return true
        }
        return false
    }

    private fun recycleNodes(nodes: List<AccessibilityNodeInfo>) {
        for (node in nodes) {
            recycleNode(node)
        }
    }

    private fun recycleNode(node: AccessibilityNodeInfo) {
        try {
            @Suppress("DEPRECATION")
            node.recycle()
        } catch (_: Exception) {
            // Already recycled -- ignore
        }
    }
}
