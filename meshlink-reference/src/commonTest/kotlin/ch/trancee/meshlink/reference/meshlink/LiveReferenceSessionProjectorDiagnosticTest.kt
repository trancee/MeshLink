package ch.trancee.meshlink.reference.meshlink

import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticEvent
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity
import ch.trancee.meshlink.reference.model.PeerTrustState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiveReferenceSessionProjectorDiagnosticTest {
    @Test
    fun `recordDiagnostic marks trusted peers as trusted and emits a runtime log`() {
        // Arrange
        val stateStore = referenceStateStore()
        val runtimeLogs = mutableListOf<String>()
        val projector =
            LiveReferenceSessionProjector(stateStore = stateStore, runtimeLogger = runtimeLogs::add)
        val event =
            DiagnosticEvent(
                code = DiagnosticCode.TRUST_ESTABLISHED,
                severity = DiagnosticSeverity.INFO,
                stage = "handshake.complete",
                peerSuffix = TEST_PEER_SUFFIX,
                metadata = mapOf("cipher" to "Noise_XX_25519_ChaChaPoly_SHA256"),
            )

        // Act
        projector.recordDiagnostic(event)

        // Assert
        val snapshot = stateStore.currentSnapshot
        assertEquals(PeerTrustState.TRUSTED, snapshot.peers.single().trustState)
        assertEquals(DiagnosticCode.TRUST_ESTABLISHED.name, snapshot.timeline.last().title)
        assertEquals(1, runtimeLogs.size)
        assertTrue(runtimeLogs.single().contains("code=TRUST_ESTABLISHED"))
    }

    @Test
    fun `recordDiagnostic marks trust failures as changed`() {
        // Arrange
        val stateStore = referenceStateStore()
        val projector = LiveReferenceSessionProjector(stateStore = stateStore)
        val event =
            DiagnosticEvent(
                code = DiagnosticCode.TRUST_FAILURE,
                severity = DiagnosticSeverity.ERROR,
                stage = "handshake.failed",
                peerSuffix = TEST_PEER_SUFFIX,
            )

        // Act
        projector.recordDiagnostic(event)

        // Assert
        assertEquals(PeerTrustState.CHANGED, stateStore.currentSnapshot.peers.single().trustState)
        assertEquals(
            DiagnosticCode.TRUST_FAILURE.name,
            stateStore.currentSnapshot.timeline.last().title,
        )
    }

    @Test
    fun `recordDiagnostic updates the active power mode label when the tier is present`() {
        // Arrange
        val stateStore = referenceStateStore()
        val projector = LiveReferenceSessionProjector(stateStore = stateStore)
        val event =
            DiagnosticEvent(
                code = DiagnosticCode.POWER_MODE_CHANGED,
                severity = DiagnosticSeverity.INFO,
                stage = "power.mode",
                metadata = mapOf("tier" to "Performance"),
            )

        // Act
        projector.recordDiagnostic(event)

        // Assert
        assertEquals("Performance", stateStore.currentSnapshot.activePowerModeLabel)
        assertEquals(
            DiagnosticCode.POWER_MODE_CHANGED.name,
            stateStore.currentSnapshot.timeline.last().title,
        )
    }

    @Test
    fun `recordDiagnostic leaves peer and power state unchanged for unrelated codes`() {
        // Arrange
        val stateStore = referenceStateStore(trustState = PeerTrustState.UNKNOWN)
        val projector = LiveReferenceSessionProjector(stateStore = stateStore)
        val event =
            DiagnosticEvent(
                code = DiagnosticCode.DELIVERY_QUEUED,
                severity = DiagnosticSeverity.INFO,
                stage = "delivery.queue",
                peerSuffix = TEST_PEER_SUFFIX,
            )

        // Act
        projector.recordDiagnostic(event)

        // Assert
        val snapshot = stateStore.currentSnapshot
        assertEquals(PeerTrustState.UNKNOWN, snapshot.peers.single().trustState)
        assertEquals("Automatic", snapshot.activePowerModeLabel)
        assertEquals(DiagnosticCode.DELIVERY_QUEUED.name, snapshot.timeline.last().title)
    }
}
