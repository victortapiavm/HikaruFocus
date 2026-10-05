package com.astraedus.nudge.domain.lock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which Settings flips the commitment lock challenges (v1.10.0).
 *
 * The asymmetry is the whole point: weakening protection costs a typed challenge, strengthening it
 * is always free, and while Strict Mode is off nothing is gated in either direction. For the escape
 * hatch this replaces the old "frozen + hidden while Strict Mode is on" design — see
 * `EmergencyPassVisibilityTest` for the overlay half of the new contract.
 */
class SettingsWeakeningTest {

    // --- Strict Mode ON: weakening is gated, strengthening is free ---

    @Test
    fun `turning the escape hatch ON under strict mode requires the unlock`() {
        assertTrue(
            SettingsWeakening.requiresUnlock(
                LockedToggle.EMERGENCY_PASS, enable = true, strictModeEnabled = true
            )
        )
    }

    @Test
    fun `turning the escape hatch OFF under strict mode is free`() {
        assertFalse(
            "removing your own bypass strengthens protection — never gate it",
            SettingsWeakening.requiresUnlock(
                LockedToggle.EMERGENCY_PASS, enable = false, strictModeEnabled = true
            )
        )
    }

    @Test
    fun `turning strict mode OFF requires the unlock`() {
        assertTrue(
            SettingsWeakening.requiresUnlock(
                LockedToggle.STRICT_MODE, enable = false, strictModeEnabled = true
            )
        )
    }

    @Test
    fun `turning strict mode ON is free`() {
        assertFalse(
            SettingsWeakening.requiresUnlock(
                LockedToggle.STRICT_MODE, enable = true, strictModeEnabled = false
            )
        )
        // Defensive: even if the screen somehow asks while already on, opting in is never gated.
        assertFalse(
            SettingsWeakening.requiresUnlock(
                LockedToggle.STRICT_MODE, enable = true, strictModeEnabled = true
            )
        )
    }

    @Test
    fun `raising instagram discovery budget under strict mode requires the unlock`() {
        assertTrue(
            SettingsWeakening.requiresUnlockForInstagramBudget(
                currentMinutes = 20,
                requestedMinutes = 30,
                strictModeEnabled = true
            )
        )
    }

    @Test
    fun `lowering instagram discovery budget under strict mode is free`() {
        assertFalse(
            SettingsWeakening.requiresUnlockForInstagramBudget(
                currentMinutes = 30,
                requestedMinutes = 15,
                strictModeEnabled = true
            )
        )
    }

    @Test
    fun `instagram discovery budget changes are free while strict mode is off`() {
        assertFalse(
            SettingsWeakening.requiresUnlockForInstagramBudget(
                currentMinutes = 15,
                requestedMinutes = 30,
                strictModeEnabled = false
            )
        )
    }

    // --- Strict Mode OFF: nothing is ever gated ---

    @Test
    fun `strict mode off gates nothing in either direction for any toggle`() {
        for (toggle in LockedToggle.entries) {
            for (enable in listOf(true, false)) {
                assertFalse(
                    "$toggle enable=$enable must be free while strict mode is off",
                    SettingsWeakening.requiresUnlock(toggle, enable, strictModeEnabled = false)
                )
            }
        }
    }
}
