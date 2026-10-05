package com.astraedus.nudge.domain.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstagramDiscoveryBudgetTest {

    private val today = 1_000_000L

    @Test
    fun `home dm and profile time cannot be represented as reel usage by accident`() {
        val state = InstagramDiscoveryBudget.normalize(InstagramDiscoveryBudget.EMPTY, today)

        assertEquals(0L, state.usedMs)
        assertFalse(InstagramDiscoveryBudget.isLocked(state, today))
    }

    @Test
    fun `default twenty minutes locks discovery entry points`() {
        val state = InstagramDiscoveryBudget.addUsage(
            InstagramDiscoveryBudget.EMPTY,
            today,
            InstagramDiscoveryBudget.limitMs(InstagramDiscoveryBudget.DEFAULT_LIMIT_MINUTES)
        )

        assertTrue(InstagramDiscoveryBudget.isLocked(state, today))
    }

    @Test
    fun `nineteen minutes fifty nine seconds remains unlocked`() {
        val state = InstagramDiscoveryBudget.addUsage(
            InstagramDiscoveryBudget.EMPTY,
            today,
            InstagramDiscoveryBudget.limitMs(InstagramDiscoveryBudget.DEFAULT_LIMIT_MINUTES) - 1_000L
        )

        assertFalse(InstagramDiscoveryBudget.isLocked(state, today))
    }

    @Test
    fun `local midnight starts a fresh discovery budget`() {
        val exhaustedYesterday = InstagramDiscoveryBudgetState(
            dayStartMs = today,
            usedMs = InstagramDiscoveryBudget.limitMs(InstagramDiscoveryBudget.DEFAULT_LIMIT_MINUTES) + 45_000L
        )
        val tomorrow = today + 86_400_000L

        val reset = InstagramDiscoveryBudget.normalize(exhaustedYesterday, tomorrow)

        assertEquals(tomorrow, reset.dayStartMs)
        assertEquals(0L, reset.usedMs)
        assertFalse(InstagramDiscoveryBudget.isLocked(reset, tomorrow))
    }

    @Test
    fun `non positive deltas never reduce or inflate usage`() {
        val initial = InstagramDiscoveryBudgetState(today, 60_000L)

        assertEquals(initial, InstagramDiscoveryBudget.addUsage(initial, today, 0L))
        assertEquals(initial, InstagramDiscoveryBudget.addUsage(initial, today, -5_000L))
    }

    @Test
    fun `locked budget gates reels and explore entry tabs`() {
        assertTrue(InstagramDiscoveryPolicy.shouldGateTab(InstagramDiscoveryPolicy.REELS, locked = true))
        assertTrue(InstagramDiscoveryPolicy.shouldGateTab(InstagramDiscoveryPolicy.EXPLORE, locked = true))
        assertFalse(InstagramDiscoveryPolicy.shouldGateTab(InstagramDiscoveryPolicy.REELS, locked = false))
    }

    @Test
    fun `locked budget never ejects a reel player`() {
        assertFalse(InstagramDiscoveryPolicy.shouldReturnHome(InstagramDiscoveryPolicy.REELS, locked = true))
    }

    @Test
    fun `locked budget returns explore to home as swipe backstop`() {
        assertTrue(InstagramDiscoveryPolicy.shouldReturnHome(InstagramDiscoveryPolicy.EXPLORE, locked = true))
        assertFalse(InstagramDiscoveryPolicy.shouldReturnHome(InstagramDiscoveryPolicy.EXPLORE, locked = false))
    }

    @Test
    fun `absolute checkpoint merge is monotonic within a day`() {
        val newerStored = InstagramDiscoveryBudgetState(today, 90_000L)
        val staleCheckpoint = InstagramDiscoveryBudgetState(today, 60_000L)

        assertEquals(
            newerStored,
            InstagramDiscoveryBudget.mergePersisted(newerStored, staleCheckpoint)
        )
        assertEquals(
            newerStored,
            InstagramDiscoveryBudget.mergePersisted(staleCheckpoint, newerStored)
        )
    }

    @Test
    fun `an older day can never overwrite the new day`() {
        val tomorrow = today + 86_400_000L
        val newDay = InstagramDiscoveryBudgetState(tomorrow, 5_000L)
        val staleYesterday = InstagramDiscoveryBudgetState(
            today,
            InstagramDiscoveryBudget.limitMs(InstagramDiscoveryBudget.DEFAULT_LIMIT_MINUTES)
        )

        assertEquals(newDay, InstagramDiscoveryBudget.mergePersisted(newDay, staleYesterday))
    }

    @Test
    fun `custom fifteen minute limit locks before the default limit`() {
        val fifteenMinutes = InstagramDiscoveryBudget.limitMs(15)
        val state = InstagramDiscoveryBudget.addUsage(
            InstagramDiscoveryBudget.EMPTY,
            today,
            fifteenMinutes
        )

        assertTrue(InstagramDiscoveryBudget.isLocked(state, today, limitMinutes = 15))
        assertFalse(InstagramDiscoveryBudget.isLocked(state, today, limitMinutes = 20))
    }

    @Test
    fun `custom thirty minute limit stays open after twenty minutes`() {
        val state = InstagramDiscoveryBudget.addUsage(
            InstagramDiscoveryBudget.EMPTY,
            today,
            InstagramDiscoveryBudget.limitMs(20)
        )

        assertFalse(InstagramDiscoveryBudget.isLocked(state, today, limitMinutes = 30))
    }

    @Test
    fun `unsupported persisted limit falls back to twenty minutes`() {
        assertEquals(20, InstagramDiscoveryBudget.sanitizeLimitMinutes(null))
        assertEquals(20, InstagramDiscoveryBudget.sanitizeLimitMinutes(17))
        assertEquals(15, InstagramDiscoveryBudget.sanitizeLimitMinutes(15))
        assertEquals(30, InstagramDiscoveryBudget.sanitizeLimitMinutes(30))
    }
}
