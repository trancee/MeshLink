package io.meshlink.diagnostics

import io.meshlink.power.PowerMode
import kotlin.test.Test
import kotlin.test.assertEquals

class MeshHealthSnapshotTest {

    @Test
    fun capturesOnDemandSnapshot() {
        val snapshot = MeshHealthSnapshot(
            connectedPeers = 5,
            reachablePeers = 12,
            bufferUtilizationPercent = 73,
            activeTransfers = 2,
            powerMode = PowerMode.BALANCED,
        )

        assertEquals(5, snapshot.connectedPeers)
        assertEquals(12, snapshot.reachablePeers)
        assertEquals(73, snapshot.bufferUtilizationPercent)
        assertEquals(2, snapshot.activeTransfers)
        assertEquals(PowerMode.BALANCED, snapshot.powerMode)
    }
}
