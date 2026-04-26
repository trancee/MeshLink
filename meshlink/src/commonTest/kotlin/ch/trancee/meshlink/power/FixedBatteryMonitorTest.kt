package ch.trancee.meshlink.power

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class FixedBatteryMonitorTest {

    @Test
    fun `default level is 1f and not charging`() {
        val monitor = FixedBatteryMonitor()
        assertEquals(1.0f, monitor.readBatteryLevel())
        assertFalse(monitor.isCharging)
    }

    @Test
    fun `custom level is returned exactly`() {
        val monitor = FixedBatteryMonitor(level = 0.42f)
        assertEquals(0.42f, monitor.readBatteryLevel())
        assertFalse(monitor.isCharging)
    }
}
