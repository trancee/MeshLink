package io.meshlink.transfer

import kotlin.test.Test
import kotlin.test.assertEquals

class AimdControllerTest {

    @Test
    fun initialWindowAndGrowOnCleanRounds() {
        val aimd = AimdController()

        assertEquals(1, aimd.window)

        repeat(3) { aimd.onAck() }
        assertEquals(1, aimd.window)

        aimd.onAck()
        assertEquals(3, aimd.window)

        repeat(4) { aimd.onAck() }
        assertEquals(5, aimd.window)
    }

    @Test
    fun halveOnConsecutiveTimeoutsAndResetOnReconnect() {
        val aimd = AimdController()

        // Grow window to 5
        repeat(8) { aimd.onAck() }
        assertEquals(5, aimd.window)

        // Single timeout: no halve yet (need 2 consecutive)
        aimd.onTimeout()
        assertEquals(5, aimd.window)

        // An ACK resets the timeout streak
        aimd.onAck()
        aimd.onTimeout()
        assertEquals(5, aimd.window) // still no halve — streak was reset

        // Two consecutive timeouts → halve
        aimd.onTimeout()
        assertEquals(2, aimd.window) // 5/2 = 2 (floor, min 1)

        // Two more consecutive timeouts → halve again
        aimd.onTimeout()
        aimd.onTimeout()
        assertEquals(1, aimd.window) // 2/2 = 1 (min 1)

        // Two more → stays at 1 (floor)
        aimd.onTimeout()
        aimd.onTimeout()
        assertEquals(1, aimd.window)

        // Reconnect resets to initial
        aimd.onReconnect()
        assertEquals(1, aimd.window)

        // Grow again to prove reset works
        repeat(4) { aimd.onAck() }
        assertEquals(3, aimd.window)

        aimd.onReconnect()
        assertEquals(1, aimd.window)
    }

    // --- Batch 11 Cycle 5: onReconnect with custom initialWindow ---

    @Test
    fun onReconnectResetsToCustomInitialWindow() {
        val aimd = AimdController(initialWindow = 4)
        assertEquals(4, aimd.window)

        // Grow window
        repeat(8) { aimd.onAck() }
        assertEquals(8, aimd.window)

        // Reconnect resets to initial (4), not 1
        aimd.onReconnect()
        assertEquals(4, aimd.window)

        // Double reconnect is safe
        aimd.onReconnect()
        assertEquals(4, aimd.window)
    }
}
