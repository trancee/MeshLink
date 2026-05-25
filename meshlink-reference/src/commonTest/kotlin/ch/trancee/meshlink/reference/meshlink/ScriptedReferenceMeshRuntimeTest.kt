package ch.trancee.meshlink.reference.meshlink

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.reference.model.PeerTrustState
import ch.trancee.meshlink.reference.model.ReferenceAuthorityMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ScriptedReferenceMeshRuntimeTest {
    @Test
    fun sendWithUnknownPeerRecordsBlockedDelivery() = runTest {
        // Arrange
        val runtime = scriptedRuntime()

        // Act
        runtime.sendPayload(
            peerId = "peer-unknown-999999",
            payloadText = "payload",
            priority = DeliveryPriority.NORMAL,
        )
        val snapshot = runtime.snapshot.value

        // Assert
        assertEquals("Send failed", snapshot.timeline.last().title)
        assertEquals("999999", snapshot.timeline.last().peerSuffix)
    }

    @Test
    fun startAndSendPromoteTheScriptedPeerToTrusted() = runTest {
        // Arrange
        val runtime = scriptedRuntime()

        // Act
        runtime.start()
        runtime.sendPayload(
            peerId = DEFAULT_SCRIPTED_PEER_ID,
            payloadText = "hello mesh",
            priority = DeliveryPriority.NORMAL,
        )
        val snapshot = runtime.snapshot.value

        // Assert
        assertEquals("SendResult.Sent", snapshot.session.lastOutcomeSummary)
        assertEquals(DEFAULT_SCRIPTED_PEER_ID, snapshot.session.selectedPeerId)
        assertEquals(PeerTrustState.TRUSTED, snapshot.peers.single().trustState)
        assertEquals("Delivery confirmed", snapshot.peers.single().lastDeliveryOutcome)
        assertTrue(snapshot.timeline.any { it.title == "Mesh started" })
        assertTrue(snapshot.timeline.any { it.title == "Guided message sent" })
        assertTrue(snapshot.timeline.any { it.title == "Delivery confirmed" })
    }

    @Test
    fun forgetPeerMarksTheScriptedPeerAsForgotten() = runTest {
        // Arrange
        val runtime = scriptedRuntime()
        runtime.start()
        runtime.sendPayload(
            peerId = DEFAULT_SCRIPTED_PEER_ID,
            payloadText = "hello mesh",
            priority = DeliveryPriority.NORMAL,
        )

        // Act
        runtime.forgetPeer(DEFAULT_SCRIPTED_PEER_ID)
        val snapshot = runtime.snapshot.value

        // Assert
        assertEquals(PeerTrustState.FORGOTTEN, snapshot.peers.single().trustState)
        assertEquals("ForgetPeerResult.Forgotten", snapshot.session.lastOutcomeSummary)
        assertEquals("Peer trust reset", snapshot.timeline.last().title)
    }
}

private fun scriptedRuntime(): ScriptedReferenceMeshRuntime {
    return ScriptedReferenceMeshRuntime(
        platformName = "iOS",
        authorityMode = ReferenceAuthorityMode.LIVE,
        nowProvider = { 1_000L },
        appId = "demo.meshlink.reference.automation",
        surfaceOfOrigin = "main-guided",
    )
}
