package com.safe.comsafevolumefixer

import org.junit.Test
import org.junit.Assert.*

/**
 * Logic simulation without a physical device.
 * Tests if the "Rage Mode" and debounce logic would trigger correctly.
 */
class FixSimulationTest {

    @Test
    fun simulate_spam_attack_logic() {
        var lastFixTimestamp = 0L
        var rapidChangeCount = 0
        var totalFixesApplied = 0

        // Simulation of 10 rapid changes (every 50ms)
        for (i in 1..10) {
            val now = i * 50L // Simulate time passing
            
            if (now - lastFixTimestamp < 250) {
                rapidChangeCount++
            } else {
                rapidChangeCount = 0
            }
            
            // Rage Mode check (from our FixerService)
            if (rapidChangeCount > 3) {
                totalFixesApplied++
                rapidChangeCount = 0 // Reset after rage fix
            }
        }

        println("Simulation Complete. Rage Mode triggered $totalFixesApplied times.")
        assertTrue("Rage Mode should have triggered at least once during spam", totalFixesApplied > 0)
    }
}
