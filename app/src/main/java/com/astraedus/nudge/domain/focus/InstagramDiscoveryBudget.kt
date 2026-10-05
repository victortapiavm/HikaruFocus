package com.astraedus.nudge.domain.focus

/**
 * The one persisted number HikaruFocus needs for Instagram: how much time the user has spent in
 * Instagram's actual full-screen Reel player today.
 *
 * This is intentionally NOT Instagram foreground time. Home, DMs and profiles are social/productive
 * surfaces and must never spend the discovery budget. It is also intentionally not an enforcement
 * decision for the Reel player itself: once the budget is exhausted HikaruFocus closes discovery
 * entry points (Reels + Search/Explore), while a Reel reached from a DM, WhatsApp, a profile or a
 * direct link remains playable.
 *
 * The state is keyed by local-day start rather than by a date string so DataStore can keep it in two
 * primitive longs. [normalize] makes reset-on-midnight explicit and testable.
 */
data class InstagramDiscoveryBudgetState(
    val dayStartMs: Long,
    val usedMs: Long
)

object InstagramDiscoveryBudget {
    const val DEFAULT_LIMIT_MINUTES: Int = 20
    val SUPPORTED_LIMIT_MINUTES: List<Int> = listOf(15, 20, 30)

    val EMPTY = InstagramDiscoveryBudgetState(dayStartMs = 0L, usedMs = 0L)

    fun normalize(
        state: InstagramDiscoveryBudgetState,
        todayStartMs: Long
    ): InstagramDiscoveryBudgetState {
        require(todayStartMs >= 0L) { "todayStartMs must be non-negative" }
        return if (state.dayStartMs == todayStartMs) {
            state.copy(usedMs = state.usedMs.coerceAtLeast(0L))
        } else {
            InstagramDiscoveryBudgetState(dayStartMs = todayStartMs, usedMs = 0L)
        }
    }

    fun addUsage(
        state: InstagramDiscoveryBudgetState,
        todayStartMs: Long,
        deltaMs: Long
    ): InstagramDiscoveryBudgetState {
        val current = normalize(state, todayStartMs)
        if (deltaMs <= 0L) return current
        val safeUsed = if (Long.MAX_VALUE - current.usedMs < deltaMs) {
            Long.MAX_VALUE
        } else {
            current.usedMs + deltaMs
        }
        return current.copy(usedMs = safeUsed)
    }

    fun isLocked(
        state: InstagramDiscoveryBudgetState,
        todayStartMs: Long,
        limitMinutes: Int = DEFAULT_LIMIT_MINUTES
    ): Boolean = normalize(state, todayStartMs).usedMs >= limitMs(limitMinutes)

    fun sanitizeLimitMinutes(limitMinutes: Int?): Int =
        limitMinutes?.takeIf { it in SUPPORTED_LIMIT_MINUTES } ?: DEFAULT_LIMIT_MINUTES

    fun limitMs(limitMinutes: Int): Long =
        sanitizeLimitMinutes(limitMinutes) * 60L * 1000L

    /**
     * Merge two absolute persisted snapshots without ever moving a day or its usage backwards.
     * Newer local day wins; same day keeps the larger usage value. This makes async checkpoints and
     * teardown flushes idempotent even when their writes complete out of order.
     */
    fun mergePersisted(
        stored: InstagramDiscoveryBudgetState,
        candidate: InstagramDiscoveryBudgetState
    ): InstagramDiscoveryBudgetState = when {
        stored.dayStartMs > candidate.dayStartMs -> stored
        candidate.dayStartMs > stored.dayStartMs -> candidate.copy(usedMs = candidate.usedMs.coerceAtLeast(0L))
        else -> candidate.copy(usedMs = maxOf(stored.usedMs, candidate.usedMs, 0L))
    }
}

/** Pure policy for what the exhausted budget is allowed to do. */
object InstagramDiscoveryPolicy {
    const val REELS = "REELS"
    const val EXPLORE = "EXPLORE"

    /** Both algorithmic launchpads disappear after the budget. */
    fun shouldGateTab(featureKey: String?, locked: Boolean): Boolean =
        locked && (featureKey == REELS || featureKey == EXPLORE)

    /**
     * Explore may be reached by swiping even when its tab is covered, so it is returned to Home.
     * Reels deliberately NEVER returns true here: externally/shared/profile-opened Reels remain
     * playable after the discovery budget is spent.
     */
    fun shouldReturnHome(featureKey: String?, locked: Boolean): Boolean =
        locked && featureKey == EXPLORE
}
