package io.meshlink.power

import kotlin.test.Test
import kotlin.test.assertEquals

class TieredShedderTest {

    @Test
    fun shedsInOrderAndReportsActions() {
        val shedder = TieredShedder(
            relayBufferCount = 50,
            dedupEntries = 8000,
            connectionCount = 6,
        )

        // Tier 1: shed relay buffers only
        val tier1 = shedder.shed(level = MemoryPressure.MODERATE)
        assertEquals(1, tier1.size)
        assertEquals(ShedAction.RELAY_BUFFERS_CLEARED, tier1[0].action)
        assertEquals(50, tier1[0].count)

        // Tier 2: shed relay buffers + dedup
        val tier2 = shedder.shed(level = MemoryPressure.HIGH)
        assertEquals(2, tier2.size)
        assertEquals(ShedAction.RELAY_BUFFERS_CLEARED, tier2[0].action)
        assertEquals(ShedAction.DEDUP_TRIMMED, tier2[1].action)
        assertEquals(8000, tier2[1].count)

        // Tier 3: all of the above + connections
        val tier3 = shedder.shed(level = MemoryPressure.CRITICAL)
        assertEquals(3, tier3.size)
        assertEquals(ShedAction.CONNECTIONS_DROPPED, tier3[2].action)
        assertEquals(6, tier3[2].count)
    }
}
