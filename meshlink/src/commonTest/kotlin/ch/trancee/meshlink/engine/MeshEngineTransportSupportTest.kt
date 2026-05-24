package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.PeerConnectionState
import ch.trancee.meshlink.api.PeerEvent
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity
import ch.trancee.meshlink.presence.PeerPresenceTracker
import ch.trancee.meshlink.routing.RouteCoordinator
import ch.trancee.meshlink.transport.TransportEvent
import ch.trancee.meshlink.transport.TransportMode
import ch.trancee.meshlink.trust.TrustPublicKeys
import ch.trancee.meshlink.trust.TrustRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking

class MeshEngineTransportSupportTest {
    @Test
    fun `handleTransportEvent emits found and prewarms an l2cap peer`() = runBlocking {
        // Arrange
        val harness = transportSupportHarness()
        val peerId = PeerId("peer-abcdef")

        // Act
        harness.support.handleTransportEvent(
            TransportEvent.PeerDiscovered(peerId = peerId, transportMode = TransportMode.L2CAP)
        )

        // Assert
        val found = assertIs<PeerEvent.Found>(harness.mutablePeerEvents.replayCache.single())
        assertEquals(peerId.value, found.peerId.value)
        assertEquals(PeerConnectionState.CONNECTED, found.state)
        assertEquals(listOf(peerId.value), harness.prewarmedPeerIds)
    }

    @Test
    fun `transport mode changed to gatt retracts routes and emits a lost peer event`() =
        runBlocking {
            // Arrange
            val harness = transportSupportHarness()
            val peerId = PeerId("peer-abcdef")
            harness.presenceTracker.onPeerConnected(peerId)
            harness.routeCoordinator.onPeerConnected(
                peerId = peerId,
                trustRecord = trustRecord(peerId = peerId, seed = 1),
            )

            // Act
            harness.support.handleTransportEvent(
                TransportEvent.TransportModeChanged(
                    peerId = peerId,
                    transportMode = TransportMode.GATT,
                )
            )

            // Assert
            val lost = assertIs<PeerEvent.Lost>(harness.mutablePeerEvents.replayCache.single())
            assertEquals(peerId.value, lost.peerId.value)
            assertNull(harness.routeCoordinator.routeFor(peerId))
            val modeChanged =
                harness.diagnostics.firstOrNull { diagnostic ->
                    diagnostic.code == DiagnosticCode.TRANSPORT_MODE_CHANGED &&
                        diagnostic.stage == "transport.modeChanged" &&
                        diagnostic.metadata["accepted"] == "false" &&
                        diagnostic.metadata["transportMode"] == TransportMode.GATT.name
                }
            val routeRetracted =
                harness.diagnostics.firstOrNull { diagnostic ->
                    diagnostic.code == DiagnosticCode.ROUTE_RETRACTED &&
                        diagnostic.stage == "transport.modeChanged.rejected.routeRetracted" &&
                        diagnostic.metadata["removedByPeerId"] == peerId.value
                }
            assertNotNull(modeChanged)
            assertNotNull(routeRetracted)
            Unit
        }

    @Test
    fun `clearRuntimeView clears connected peers and routes and emits lost events`() = runBlocking {
        // Arrange
        val harness = transportSupportHarness()
        val firstPeerId = PeerId("peer-first")
        val secondPeerId = PeerId("peer-second")
        harness.presenceTracker.onPeerConnected(firstPeerId)
        harness.presenceTracker.onPeerConnected(secondPeerId)
        harness.routeCoordinator.onPeerConnected(
            peerId = firstPeerId,
            trustRecord = trustRecord(peerId = firstPeerId, seed = 1),
        )
        harness.routeCoordinator.onPeerConnected(
            peerId = secondPeerId,
            trustRecord = trustRecord(peerId = secondPeerId, seed = 2),
        )

        // Act
        harness.support.clearRuntimeView(
            stage = "lifecycle.stop",
            removalCode = DiagnosticCode.ROUTE_RETRACTED,
            metadata = mapOf("runtimeBoundary" to "stop"),
        )

        // Assert
        assertNull(harness.routeCoordinator.routeFor(firstPeerId))
        assertNull(harness.routeCoordinator.routeFor(secondPeerId))
        val lostPeerIds =
            harness.mutablePeerEvents.replayCache.filterIsInstance<PeerEvent.Lost>().map { event ->
                event.peerId.value
            }
        assertEquals(listOf(firstPeerId.value, secondPeerId.value), lostPeerIds)
        val retractedDiagnostics =
            harness.diagnostics.filter { diagnostic ->
                diagnostic.code == DiagnosticCode.ROUTE_RETRACTED &&
                    diagnostic.stage == "lifecycle.stop.routeRetracted" &&
                    diagnostic.metadata["runtimeBoundary"] == "stop"
            }
        assertEquals(2, retractedDiagnostics.size)
    }
}

private data class TransportSupportHarness(
    val support: MeshEngineTransportSupport,
    val routeCoordinator: RouteCoordinator,
    val presenceTracker: PeerPresenceTracker,
    val mutablePeerEvents: MutableSharedFlow<PeerEvent>,
    val diagnostics: MutableList<RecordedTransportSupportDiagnostic>,
    val prewarmedPeerIds: MutableList<String>,
)

private fun transportSupportHarness(): TransportSupportHarness {
    val routeCoordinator = RouteCoordinator(PeerId("local-transport"))
    val presenceTracker = PeerPresenceTracker()
    val mutablePeerEvents = MutableSharedFlow<PeerEvent>(replay = 16, extraBufferCapacity = 16)
    val diagnostics = mutableListOf<RecordedTransportSupportDiagnostic>()
    val prewarmedPeerIds = mutableListOf<String>()
    val support =
        MeshEngineTransportSupport(
            peerState =
                MeshEngineTransportPeerState(
                    presenceTracker = presenceTracker,
                    mutablePeerEvents = mutablePeerEvents,
                    sessionRegistry = MeshEngineSessionRegistry(),
                ),
            routingContext =
                MeshEngineTransportRoutingContext(
                    routeCoordinator = routeCoordinator,
                    routingSupport = transportRoutingSupport(routeCoordinator, diagnostics),
                ),
            callbacks =
                MeshEngineTransportCallbacks(
                    prewarmHopSession = { peerId -> prewarmedPeerIds += peerId.value },
                    handleHandshakeMessage1 = { _, _ -> error("unexpected handshake message 1") },
                    handleHandshakeMessage2 = { _, _ -> error("unexpected handshake message 2") },
                    handleHandshakeMessage3 = { _, _ -> error("unexpected handshake message 3") },
                    handleEncryptedDataFrame = { _, _ -> error("unexpected encrypted data") },
                ),
            emitDiagnostic = { code, severity, stage, peerSuffix, reason, metadata ->
                diagnostics +=
                    RecordedTransportSupportDiagnostic(
                        code = code,
                        severity = severity,
                        stage = stage,
                        peerSuffix = peerSuffix,
                        reason = reason,
                        metadata = metadata,
                    )
            },
        )
    return TransportSupportHarness(
        support = support,
        routeCoordinator = routeCoordinator,
        presenceTracker = presenceTracker,
        mutablePeerEvents = mutablePeerEvents,
        diagnostics = diagnostics,
        prewarmedPeerIds = prewarmedPeerIds,
    )
}

private fun transportRoutingSupport(
    routeCoordinator: RouteCoordinator,
    diagnostics: MutableList<RecordedTransportSupportDiagnostic>,
): MeshEngineRoutingSupport {
    return MeshEngineRoutingSupport(
        routeCoordinator = routeCoordinator,
        runtimeGate = MeshEngineRuntimeSurface().runtimeGate,
        coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        emitDiagnostic = { code, severity, stage, peerSuffix, reason, metadata ->
            diagnostics +=
                RecordedTransportSupportDiagnostic(
                    code = code,
                    severity = severity,
                    stage = stage,
                    peerSuffix = peerSuffix,
                    reason = reason,
                    metadata = metadata,
                )
        },
        sendEncryptedWireFrame = { _, _, _, _ -> true },
    )
}

private fun trustRecord(peerId: PeerId, seed: Int): TrustRecord {
    return TrustRecord(
        peerIdValue = peerId.value,
        identityFingerprintBytes = repeatedByteArray(seed + 100),
        firstSeenAtEpochMillis = seed.toLong(),
        lastVerifiedAtEpochMillis = seed.toLong(),
        publicKeys =
            TrustPublicKeys(
                ed25519PublicKey = repeatedByteArray(seed),
                x25519PublicKey = repeatedByteArray(seed + 50),
            ),
    )
}

private fun repeatedByteArray(seed: Int): ByteArray {
    return ByteArray(32) { index -> ((seed + index) and 0xFF).toByte() }
}

private data class RecordedTransportSupportDiagnostic(
    val code: DiagnosticCode,
    val severity: DiagnosticSeverity,
    val stage: String,
    val peerSuffix: String?,
    val reason: DiagnosticReason?,
    val metadata: Map<String, String>,
)
