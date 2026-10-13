package com.safe.comsafevolumefixer

import org.junit.Test
import org.junit.Assert.*

/**
 * Logic simulation suite testing v2.2.1 & v2.2.0 algorithms.
 */
class FixSimulationTest {

    // Simulates the exact handleVolumeChange decision logic from FixerService
    private fun simulateVolumeChange(
        prevVolume: Int,
        newVolume: Int,
        flags: Int,
        lastFixTimestamp: Long,
        now: Long
    ): Boolean {
        val dropAmount = prevVolume - newVolume

        // 1. If volume went UP or stayed same, do nothing
        if (dropAmount <= 0) {
            return false // No override
        }

        // 2. Check if physical hardware key was pressed (FLAG_FROM_KEY = 4096)
        val isPhysicalKeyPressed = (flags and 4096) != 0

        // 3. If user pressed physical key for a 1-step drop, allow it
        if (isPhysicalKeyPressed && dropAmount == 1) {
            return false // No override (User manual drop)
        }

        // 4. If drop >= 2 OR drop without physical key, it's a System-Forced Drop!
        if (prevVolume > 0 && (now - lastFixTimestamp > 800)) {
            return true // INTERCEPTED & RESTORED!
        }

        return false
    }

    @Test
    fun test_physical_volume_down_button_is_allowed() {
        val FLAG_SHOW_UI_AND_KEY = 1 or 4096 // UI shown + Physical Key
        val isIntercepted = simulateVolumeChange(
            prevVolume = 15,
            newVolume = 14, // 1 step down
            flags = FLAG_SHOW_UI_AND_KEY,
            lastFixTimestamp = 0L,
            now = 1000L
        )
        assertFalse("Manual 1-step physical keypress must NOT be intercepted", isIntercepted)
    }

    @Test
    fun test_system_forced_csd_drop_is_intercepted_and_restored() {
        val FLAG_SHOW_UI_ONLY = 1 // System UI flag present, but NO physical key (4096 absent)
        val isIntercepted = simulateVolumeChange(
            prevVolume = 15,
            newVolume = 9, // 6 steps forced drop by CSD
            flags = FLAG_SHOW_UI_ONLY,
            lastFixTimestamp = 0L,
            now = 1000L
        )
        assertTrue("System-forced CSD drop MUST be intercepted and restored", isIntercepted)
    }

    @Test
    fun test_volume_up_followed_immediately_by_system_drop() {
        var lastFix = 0L

        // User turns volume UP from 10 to 15
        val volumeUpIntercepted = simulateVolumeChange(
            prevVolume = 10,
            newVolume = 15, // Volume UP
            flags = 1 or 4096,
            lastFixTimestamp = lastFix,
            now = 1000L
        )
        assertFalse("Volume UP must not be intercepted", volumeUpIntercepted)

        // 100ms later, System CSD triggers a forced drop from 15 to 9
        val systemDropIntercepted = simulateVolumeChange(
            prevVolume = 15,
            newVolume = 9, // Forced CSD Drop
            flags = 1, // FLAG_SHOW_UI only
            lastFixTimestamp = lastFix,
            now = 1100L // 100ms later
        )
        assertTrue("Immediate system drop after Volume UP MUST be intercepted", systemDropIntercepted)
    }
}
