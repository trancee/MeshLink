package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.PeerConnectionState
import ch.trancee.meshlink.api.PeerEvent
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.crypto.NoiseXXHandshakeManager
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity
import ch.trancee.meshlink.identity.LocalIdentity
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
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking

class MeshEngineTransportSupportTest {
    @Test
    fun `handleTransportEvent emits found and prewarms an l2cap peer`() =
        runBlocking<Unit> {
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
    fun `transport mode changed to gatt keeps the peer connected`() =
        runBlocking<Unit> {
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
            assertTrue(harness.mutablePeerEvents.replayCache.isEmpty())
            assertNotNull(harness.routeCoordinator.routeFor(peerId))
            val modeChanged =
                harness.diagnostics.firstOrNull { diagnostic ->
                    diagnostic.code == DiagnosticCode.TRANSPORT_MODE_CHANGED &&
                        diagnostic.stage == "transport.modeChanged" &&
                        diagnostic.metadata["accepted"] == "true" &&
                        diagnostic.metadata["transportMode"] == TransportMode.GATT.name
                }
            val routeRetracted =
                harness.diagnostics.firstOrNull { diagnostic ->
                    diagnostic.code == DiagnosticCode.ROUTE_RETRACTED &&
                        diagnostic.stage == "transport.modeChanged.rejected.routeRetracted"
                }
            assertNotNull(modeChanged)
            assertNull(routeRetracted)
        }

    @Test
    fun `clearRuntimeView clears connected peers and routes and emits lost events`() =
        runBlocking<Unit> {
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
                harness.mutablePeerEvents.replayCache.filterIsInstance<PeerEvent.Lost>().map { event
                    ->
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

    @Test
    fun `peer discovered on gatt is accepted and prewarms`() =
        runBlocking<Unit> {
            // Arrange
            val harness = transportSupportHarness()
            val peerId = PeerId("peer-gatt")

            // Act
            harness.support.handleTransportEvent(
                TransportEvent.PeerDiscovered(peerId = peerId, transportMode = TransportMode.GATT)
            )

            // Assert
            val found = assertIs<PeerEvent.Found>(harness.mutablePeerEvents.replayCache.single())
            assertEquals(peerId.value, found.peerId.value)
            assertEquals(PeerConnectionState.CONNECTED, found.state)
            assertEquals(listOf(peerId.value), harness.prewarmedPeerIds)
            assertTrue(
                harness.diagnostics.none { diagnostic ->
                    diagnostic.code == DiagnosticCode.TRANSPORT_MODE_CHANGED &&
                        diagnostic.stage == "transport.peerDiscovered.rejected" &&
                        diagnostic.metadata["accepted"] == "false"
                }
            )
        }

    @Test
    fun `repeat l2cap discovery emits state changed after the first observation`() =
        runBlocking<Unit> {
            // Arrange
            val harness = transportSupportHarness()
            val peerId = PeerId("peer-repeat")
            harness.support.handleTransportEvent(
                TransportEvent.PeerDiscovered(peerId = peerId, transportMode = TransportMode.L2CAP)
            )

            // Act
            harness.support.handleTransportEvent(
                TransportEvent.PeerDiscovered(peerId = peerId, transportMode = TransportMode.L2CAP)
            )

            // Assert
            val events = harness.mutablePeerEvents.replayCache
            assertIs<PeerEvent.Found>(events[0])
            val stateChanged = assertIs<PeerEvent.StateChanged>(events[1])
            assertEquals(peerId.value, stateChanged.peerId.value)
            assertEquals(PeerConnectionState.CONNECTED, stateChanged.state)
            assertEquals(listOf(peerId.value, peerId.value), harness.prewarmedPeerIds)
        }

    @Test
    fun `peer lost expires routes and emits lost peer event`() =
        runBlocking<Unit> {
            // Arrange
            val harness = transportSupportHarness()
            val peerId = PeerId("peer-lost")
            harness.presenceTracker.onPeerConnected(peerId)
            harness.routeCoordinator.onPeerConnected(
                peerId = peerId,
                trustRecord = trustRecord(peerId = peerId, seed = 4),
            )

            // Act
            harness.support.handleTransportEvent(TransportEvent.PeerLost(peerId))

            // Assert
            val lost = assertIs<PeerEvent.Lost>(harness.mutablePeerEvents.replayCache.single())
            assertEquals(peerId.value, lost.peerId.value)
            assertNull(harness.routeCoordinator.routeFor(peerId))
            assertNotNull(
                harness.diagnostics.firstOrNull { diagnostic ->
                    diagnostic.code == DiagnosticCode.ROUTE_EXPIRED &&
                        diagnostic.stage == "transport.peerLost.routeExpired" &&
                        diagnostic.metadata["removedByPeerId"] == peerId.value
                }
            )
        }

    @Test
    fun `peer lost still clears an established session`() =
        runBlocking<Unit> {
            // Arrange
            val harness = transportSupportHarness()
            val peerId = PeerId("peer-churn")
            val localIdentity = LocalIdentity.fromAppId("peer-churn-test")
            val pending =
                PendingResponderHandshake(NoiseXXHandshakeManager(localIdentity.cryptoProvider))
            harness.presenceTracker.onPeerConnected(peerId)
            harness.routeCoordinator.onPeerConnected(
                peerId = peerId,
                trustRecord = trustRecord(peerId = peerId, seed = 5),
            )
            harness.sessionRegistry.storePendingResponderHandshake(peerId, pending, byteArrayOf())
            harness.sessionRegistry.completeResponderHandshake(
                peerId = peerId,
                pendingHandshake = pending,
                session = HopSession(sendKey = byteArrayOf(1), receiveKey = byteArrayOf(2)),
            )

            // Act
            harness.support.handleTransportEvent(TransportEvent.PeerLost(peerId))

            // Assert
            assertEquals(
                listOf(peerId.value),
                harness.mutablePeerEvents.replayCache.filterIsInstance<PeerEvent.Lost>().map {
                    it.peerId.value
                },
            )
            assertNull(harness.routeCoordinator.routeFor(peerId))
            assertNull(harness.sessionRegistry.hopSession(peerId))
            assertNotNull(
                harness.diagnostics.firstOrNull { diagnostic ->
                    diagnostic.code == DiagnosticCode.ROUTE_EXPIRED &&
                        diagnostic.stage == "transport.peerLost.routeExpired"
                }
            )
        }

    @Test
    fun `frame received routes all direct wire frame types to the matching callbacks`() =
        runBlocking<Unit> {
            // Arrange
            val harness = transportSupportHarness()
            val peerId = PeerId("peer-frames")
            val message1 = byteArrayOf(0x01)
            val message2 = byteArrayOf(0x02)
            val message3 = byteArrayOf(0x03)
            val data = byteArrayOf(0x04)

            // Act
            harness.support.handleTransportEvent(
                TransportEvent.FrameReceived(
                    peerId,
                    DirectWireFrame.HandshakeMessage1(message1).encode(),
                )
            )
            harness.support.handleTransportEvent(
                TransportEvent.FrameReceived(
                    peerId,
                    DirectWireFrame.HandshakeMessage2(message2).encode(),
                )
            )
            harness.support.handleTransportEvent(
                TransportEvent.FrameReceived(
                    peerId,
                    DirectWireFrame.HandshakeMessage3(message3).encode(),
                )
            )
            harness.support.handleTransportEvent(
                TransportEvent.FrameReceived(peerId, DirectWireFrame.Data(data).encode())
            )

            // Assert
            assertEquals(listOf(peerId.value to message1.toList()), harness.handshakeMessage1Calls)
            assertEquals(listOf(peerId.value to message2.toList()), harness.handshakeMessage2Calls)
            assertEquals(listOf(peerId.value to message3.toList()), harness.handshakeMessage3Calls)
            assertEquals(listOf(peerId.value to data.toList()), harness.encryptedDataCalls)
        }

    @Test
    fun `malformed frame is reported as a diagnostic instead of throwing`() =
        runBlocking<Unit> {
            // Arrange
            val harness = transportSupportHarness()
            val peerId = PeerId("peer-malformed")
            val malformedPayload = byteArrayOf(0x7f)

            // Act
            harness.support.handleTransportEvent(
                TransportEvent.FrameReceived(peerId, malformedPayload)
            )

            // Assert
            assertTrue(harness.handshakeMessage1Calls.isEmpty())
            assertTrue(harness.handshakeMessage2Calls.isEmpty())
            assertTrue(harness.handshakeMessage3Calls.isEmpty())
            assertTrue(harness.encryptedDataCalls.isEmpty())
            assertNotNull(
                harness.diagnostics.firstOrNull { diagnostic ->
                    diagnostic.code == DiagnosticCode.TRANSPORT_FRAME_REJECTED &&
                        diagnostic.stage == "transport.frame.malformed"
                }
            )
        }

    @Test
    fun `advertise failed event is reported as a diagnostic with error details`() =
        runBlocking<Unit> {
            // Arrange
            val harness = transportSupportHarness()

            // Act
            harness.support.handleTransportEvent(
                TransportEvent.AdvertiseFailed(
                    errorCode = 2,
                    errorName = "ADVERTISE_FAILED_TOO_MANY_ADVERTISERS",
                    willRetry = true,
                    attempt = 1,
                )
            )

            // Assert
            val diagnostic =
                harness.diagnostics.firstOrNull { diagnostic ->
                    diagnostic.code == DiagnosticCode.DISCOVERY_ADVERTISE_FAILED
                }
            assertNotNull(diagnostic)
            assertEquals("transport.discovery.advertiseFailed", diagnostic.stage)
            assertEquals(DiagnosticSeverity.WARN, diagnostic.severity)
            assertEquals("2", diagnostic.metadata["errorCode"])
            assertEquals("ADVERTISE_FAILED_TOO_MANY_ADVERTISERS", diagnostic.metadata["errorName"])
            assertEquals("true", diagnostic.metadata["willRetry"])
            assertEquals("1", diagnostic.metadata["attempt"])
        }

    @Test
    fun `scan failed event is reported as a diagnostic with error details`() =
        runBlocking<Unit> {
            // Arrange
            val harness = transportSupportHarness()

            // Act
            harness.support.handleTransportEvent(
                TransportEvent.ScanFailed(
                    errorCode = 3,
                    errorName = "SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES",
                    willRetry = true,
                    attempt = 1,
                )
            )

            // Assert
            val diagnostic =
                harness.diagnostics.firstOrNull { diagnostic ->
                    diagnostic.code == DiagnosticCode.DISCOVERY_SCAN_FAILED
                }
            assertNotNull(diagnostic)
            assertEquals("transport.discovery.scanFailed", diagnostic.stage)
            assertEquals(DiagnosticSeverity.WARN, diagnostic.severity)
            assertEquals("3", diagnostic.metadata["errorCode"])
            assertEquals("SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES", diagnostic.metadata["errorName"])
            assertEquals("true", diagnostic.metadata["willRetry"])
            assertEquals("1", diagnostic.metadata["attempt"])
        }
}

private data class TransportSupportHarness(
    val support: MeshEngineTransportSupport,
    val routeCoordinator: RouteCoordinator,
    val presenceTracker: PeerPresenceTracker,
    val mutablePeerEvents: MutableSharedFlow<PeerEvent>,
    val diagnostics: MutableList<RecordedTransportSupportDiagnostic>,
    val prewarmedPeerIds: MutableList<String>,
    val sessionRegistry: MeshEngineSessionRegistry,
    val handshakeMessage1Calls: MutableList<Pair<String, List<Byte>>>,
    val handshakeMessage2Calls: MutableList<Pair<String, List<Byte>>>,
    val handshakeMessage3Calls: MutableList<Pair<String, List<Byte>>>,
    val encryptedDataCalls: MutableList<Pair<String, List<Byte>>>,
)

private fun transportSupportHarness(): TransportSupportHarness {
    val routeCoordinator = RouteCoordinator(PeerId("local-transport"))
    val presenceTracker = PeerPresenceTracker()
    val mutablePeerEvents = MutableSharedFlow<PeerEvent>(replay = 16, extraBufferCapacity = 16)
    val diagnostics = mutableListOf<RecordedTransportSupportDiagnostic>()
    val prewarmedPeerIds = mutableListOf<String>()
    val sessionRegistry = MeshEngineSessionRegistry()
    val handshakeMessage1Calls = mutableListOf<Pair<String, List<Byte>>>()
    val handshakeMessage2Calls = mutableListOf<Pair<String, List<Byte>>>()
    val handshakeMessage3Calls = mutableListOf<Pair<String, List<Byte>>>()
    val encryptedDataCalls = mutableListOf<Pair<String, List<Byte>>>()
    val support =
        MeshEngineTransportSupport(
            peerState =
                MeshEngineTransportPeerState(
                    presenceTracker = presenceTracker,
                    mutablePeerEvents = mutablePeerEvents,
                    sessionRegistry = sessionRegistry,
                    endToEndSessionRegistry = MeshEngineEndToEndSessionRegistry(),
                ),
            routingContext =
                MeshEngineTransportRoutingContext(
                    routeCoordinator = routeCoordinator,
                    routingSupport = transportRoutingSupport(routeCoordinator, diagnostics),
                ),
            callbacks =
                MeshEngineTransportCallbacks(
                    prewarmHopSession = { peerId -> prewarmedPeerIds += peerId.value },
                    handleHandshakeMessage1 = { peerId, payload ->
                        handshakeMessage1Calls += peerId.value to payload.toList()
                    },
                    handleHandshakeMessage2 = { peerId, payload ->
                        handshakeMessage2Calls += peerId.value to payload.toList()
                    },
                    handleHandshakeMessage3 = { peerId, payload ->
                        handshakeMessage3Calls += peerId.value to payload.toList()
                    },
                    handleEncryptedDataFrame = { peerId, payload ->
                        encryptedDataCalls += peerId.value to payload.toList()
                    },
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
        sessionRegistry = sessionRegistry,
        handshakeMessage1Calls = handshakeMessage1Calls,
        handshakeMessage2Calls = handshakeMessage2Calls,
        handshakeMessage3Calls = handshakeMessage3Calls,
        encryptedDataCalls = encryptedDataCalls,
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
