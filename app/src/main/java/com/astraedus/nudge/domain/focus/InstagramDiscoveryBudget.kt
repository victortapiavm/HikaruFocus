package com.astraedus.nudge.domain.focus

/**
 * The one persisted number HikaruFocus needs for Instagram: how much foreground time the user has
 * spent in Instagram today.
 *
 * The budget is deliberately app-wide. Home, DMs, profiles, Reels and Explore all consume the same
 * allowance because the user's intent is "20 minutes of Instagram, then remove algorithmic discovery"
 * rather than "20 minutes of Reels specifically". Enforcement is still selective: DMs/profile/Home
 * remain usable after the limit, while Reels and Explore are ejected back to Home.
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

    /**
     * Replace the cached reading with an absolute UsageStats foreground-time sample for today.
     * Within a day the value is monotonic: a transient platform under-read must never unlock an
     * already-exhausted budget. A new local day still resets through [normalize].
     */
    fun syncAbsoluteUsage(
        state: InstagramDiscoveryBudgetState,
        todayStartMs: Long,
        absoluteUsageMs: Long
    ): InstagramDiscoveryBudgetState {
        val current = normalize(state, todayStartMs)
        return current.copy(usedMs = maxOf(current.usedMs, absoluteUsageMs.coerceAtLeast(0L)))
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

    /** Both algorithmic launchpads are blocked after the budget. */
    fun shouldGateTab(featureKey: String?, locked: Boolean): Boolean =
        locked && (featureKey == REELS || featureKey == EXPLORE)

    /**
     * Once locked, both discovery surfaces are returned to Home. This is the reliable enforcement
     * layer: Accessibility cannot actually mutate Instagram's own view hierarchy to remove buttons,
     * and overlay covers proved visually and lifecycle-fragile on real devices.
     */
    fun shouldReturnHome(featureKey: String?, locked: Boolean): Boolean =
        locked && (featureKey == REELS || featureKey == EXPLORE)
}
