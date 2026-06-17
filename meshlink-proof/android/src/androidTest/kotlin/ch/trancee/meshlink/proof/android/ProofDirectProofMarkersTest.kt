package ch.trancee.meshlink.proof.android

import org.junit.Assert.assertEquals
import org.junit.Test

class ProofDirectProofMarkersTest {
    @Test
    fun passive_peer_discovered_marker_is_stable() {
        assertEquals(
            "REFERENCE_AUTOMATION peer.discovered role=PASSIVE peer=AA:BB:CC:DD:EE:FF",
            ProofDirectProofMarkers.passivePeerDiscovered("AA:BB:CC:DD:EE:FF"),
        )
    }

    @Test
    fun passive_proof_complete_marker_is_stable() {
        assertEquals(
            "REFERENCE_AUTOMATION proof.complete role=passive peer=AA:BB:CC:DD:EE:FF token=deadbeef bytes=128",
            ProofDirectProofMarkers.passiveProofComplete(
                peer = "AA:BB:CC:DD:EE:FF",
                tokenHex = "deadbeef",
                totalBytes = 128,
            ),
        )
    }
}
