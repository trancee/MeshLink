package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity
import ch.trancee.meshlink.identity.LocalIdentity
import ch.trancee.meshlink.routing.RouteCoordinator
import ch.trancee.meshlink.transport.TransportMode
import ch.trancee.meshlink.transport.TransportSendResult
import ch.trancee.meshlink.wire.WireCodec
import ch.trancee.meshlink.wire.WireFrame
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking

class MeshEngineHopTransportSupportTest {
    @Test
    fun `sendEncryptedDirectWireFrame prefers gatt for transfer acknowledgements and increments the send nonce`() =
        runBlocking<Unit> {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("hop-transport-local")
            val peerId = PeerId("peer-abcdef")
            val session = hopSession(keyByte = 0x11)
            val frame =
                WireFrame.TransferAck(
                    transferId = "transfer-1",
                    highestContiguousAck = 3,
                    selectiveRanges = byteArrayOf(4, 5, 6),
                )
            val fixture = hopTransportFixture(localIdentity = localIdentity)

            // Act
            val result =
                fixture.support.sendEncryptedDirectWireFrame(
                    peerId = peerId,
                    session = session,
                    frame = frame,
                    action = "transfer.ack",
                )

            // Assert
            assertEquals(TransportSendResult.Delivered, result)
            assertEquals(1uL, session.sendNonce)
            val sentFrame = fixture.sentFrames.single()
            assertEquals(peerId.value, sentFrame.peerIdValue)
            assertEquals("transfer.ack", sentFrame.action)
            assertEquals(TransportMode.GATT, sentFrame.preferredMode)
            val encryptedFrame = assertIs<DirectWireFrame.Data>(sentFrame.frame)
            val decryptedPayload =
                fixture.support.decryptHopPayload(session, encryptedFrame.payload)
            val decodedFrame = WireCodec.decode(decryptedPayload)
            val decodedAck = assertIs<WireFrame.TransferAck>(decodedFrame)
            assertEquals("transfer-1", decodedAck.transferId)
            assertEquals(3, decodedAck.highestContiguousAck)
            assertContentEquals(byteArrayOf(4, 5, 6), decodedAck.selectiveRanges)
            assertEquals(1u, session.receiveNonce)
        }

    @Test
    fun `sendEncryptedWireFrame emits a hop session failure when transport delivery drops`() =
        runBlocking<Unit> {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("hop-transport-local")
            val peerId = PeerId("peer-abcdef")
            val session = hopSession(keyByte = 0x22)
            val fixture =
                hopTransportFixture(
                    localIdentity = localIdentity,
                    establishedHopSession = { session },
                    sendDirectWireFrame = { _, _, _, _ ->
                        TransportSendResult.Dropped("link not ready")
                    },
                )

            // Act
            val delivered =
                fixture.support.sendEncryptedWireFrame(
                    peerId = peerId,
                    frame = WireFrame.TransferComplete("transfer-1"),
                    action = "transfer.complete",
                )

            // Assert
            assertFalse(delivered)
            assertEquals(
                listOf(
                    RecordedHopTransportDiagnostic(
                        code = DiagnosticCode.HOP_SESSION_FAILED,
                        severity = DiagnosticSeverity.WARN,
                        stage = "transfer.complete.send",
                        peerSuffix = peerId.value.takeLast(DIAGNOSTIC_PEER_SUFFIX_LENGTH),
                        reason = DiagnosticReason.DELIVERY_FAILURE,
                        metadata =
                            mapOf(
                                "peerId" to peerId.value,
                                "topologyVersion" to "0",
                                "routeAvailable" to "false",
                            ),
                    )
                ),
                fixture.diagnostics,
            )
        }

    @Test
    fun `sendEncryptedWireFrame ensures a hop session when a running hard run token is supplied`() =
        runBlocking<Unit> {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("hop-transport-local")
            val runtimeSurface = MeshEngineRuntimeSurface()
            val hardRunToken = runtimeSurface.beginHardRun()
            val peerId = PeerId("peer-abcdef")
            val session = hopSession(keyByte = 0x33)
            val ensureCalls = mutableListOf<Pair<String, Long>>()
            val fixture =
                hopTransportFixture(
                    localIdentity = localIdentity,
                    runtimeSurface = runtimeSurface,
                    establishedHopSession = { error("unexpected established-session lookup") },
                    ensureHopSession = { ensuredPeerId, token ->
                        ensureCalls += ensuredPeerId.value to token.epoch
                        SessionEstablishmentOutcome.Established(session)
                    },
                )

            // Act
            val delivered =
                fixture.support.sendEncryptedWireFrame(
                    peerId = peerId,
                    frame = WireFrame.TransferComplete("transfer-2"),
                    action = "transfer.complete",
                    hardRunToken = hardRunToken,
                )

            // Assert
            assertTrue(delivered)
            assertEquals(listOf(peerId.value to hardRunToken.epoch), ensureCalls)
            assertEquals(1uL, session.sendNonce)
        }

    @Test
    fun `sendEncryptedWireFrame returns false when no session exists without a hard run token`() =
        runBlocking<Unit> {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("hop-transport-local")
            val peerId = PeerId("peer-abcdef")
            val fixture = hopTransportFixture(localIdentity = localIdentity)

            // Act
            val delivered =
                fixture.support.sendEncryptedWireFrame(
                    peerId = peerId,
                    frame = WireFrame.TransferComplete("transfer-none"),
                    action = "transfer.complete",
                )

            // Assert
            assertFalse(delivered)
            assertTrue(fixture.sentFrames.isEmpty())
            assertTrue(fixture.diagnostics.isEmpty())
        }

    @Test
    fun `sendEncryptedWireFrame returns false when ensuring a running hard run session stays unreachable`() =
        runBlocking<Unit> {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("hop-transport-local")
            val runtimeSurface = MeshEngineRuntimeSurface()
            val hardRunToken = runtimeSurface.beginHardRun()
            val peerId = PeerId("peer-abcdef")
            val ensureCalls = mutableListOf<Pair<String, Long>>()
            val fixture =
                hopTransportFixture(
                    localIdentity = localIdentity,
                    runtimeSurface = runtimeSurface,
                    establishedHopSession = { error("unexpected established-session lookup") },
                    ensureHopSession = { ensuredPeerId, token ->
                        ensureCalls += ensuredPeerId.value to token.epoch
                        SessionEstablishmentOutcome.Unreachable
                    },
                )

            // Act
            val delivered =
                fixture.support.sendEncryptedWireFrame(
                    peerId = peerId,
                    frame = WireFrame.TransferComplete("transfer-unreachable"),
                    action = "transfer.complete",
                    hardRunToken = hardRunToken,
                )

            // Assert
            assertFalse(delivered)
            assertEquals(listOf(peerId.value to hardRunToken.epoch), ensureCalls)
            assertTrue(fixture.sentFrames.isEmpty())
            assertTrue(fixture.diagnostics.isEmpty())
        }

    @Test
    fun `sendEncryptedDirectWireFrame does not increment the send nonce when delivery drops`() =
        runBlocking<Unit> {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("hop-transport-local")
            val peerId = PeerId("peer-abcdef")
            val session = hopSession(keyByte = 0x44)
            val fixture =
                hopTransportFixture(
                    localIdentity = localIdentity,
                    sendDirectWireFrame = { _, _, _, _ ->
                        TransportSendResult.Dropped("link not ready")
                    },
                )

            // Act
            val result =
                fixture.support.sendEncryptedDirectWireFrame(
                    peerId = peerId,
                    session = session,
                    frame = WireFrame.TransferComplete("transfer-drop"),
                    action = "transfer.complete",
                )

            // Assert
            assertIs<TransportSendResult.Dropped>(result)
            assertEquals(0uL, session.sendNonce)
            assertEquals(1, fixture.sentFrames.size)
        }

    @Test
    fun `sendEncryptedWireFrame emits a hop session failure when encrypted send throws`() =
        runBlocking<Unit> {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("hop-transport-local")
            val peerId = PeerId("peer-abcdef")
            val session = hopSession(keyByte = 0x55)
            val fixture =
                hopTransportFixture(
                    localIdentity = localIdentity,
                    establishedHopSession = { session },
                    sendDirectWireFrame = { _, _, _, _ -> throw IllegalStateException("boom") },
                )

            // Act
            val delivered =
                fixture.support.sendEncryptedWireFrame(
                    peerId = peerId,
                    frame = WireFrame.TransferComplete("transfer-throw"),
                    action = "transfer.complete",
                )

            // Assert
            assertFalse(delivered)
            assertEquals(1, fixture.sentFrames.size)
            assertEquals(
                listOf(
                    RecordedHopTransportDiagnostic(
                        code = DiagnosticCode.HOP_SESSION_FAILED,
                        severity = DiagnosticSeverity.WARN,
                        stage = "transfer.complete.encrypt",
                        peerSuffix = peerId.value.takeLast(DIAGNOSTIC_PEER_SUFFIX_LENGTH),
                        reason = DiagnosticReason.DELIVERY_FAILURE,
                        metadata =
                            mapOf(
                                "peerId" to peerId.value,
                                "topologyVersion" to "0",
                                "routeAvailable" to "false",
                                "cause" to "IllegalStateException",
                            ),
                    )
                ),
                fixture.diagnostics,
            )
        }

    @Test
    fun `sendEncryptedWireFrame returns false without ensuring a session when the hard run already ended`() =
        runBlocking<Unit> {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("hop-transport-local")
            val runtimeSurface = MeshEngineRuntimeSurface()
            val hardRunToken = runtimeSurface.captureHardRunToken()
            var ensureCalled = false
            val fixture =
                hopTransportFixture(
                    localIdentity = localIdentity,
                    runtimeSurface = runtimeSurface,
                    establishedHopSession = { error("unexpected established-session lookup") },
                    ensureHopSession = { _, _ ->
                        ensureCalled = true
                        SessionEstablishmentOutcome.Unreachable
                    },
                )

            // Act
            val delivered =
                fixture.support.sendEncryptedWireFrame(
                    peerId = PeerId("peer-abcdef"),
                    frame = WireFrame.TransferComplete("transfer-3"),
                    action = "transfer.complete",
                    hardRunToken = hardRunToken,
                )

            // Assert
            assertFalse(delivered)
            assertFalse(ensureCalled)
            assertTrue(fixture.sentFrames.isEmpty())
        }
}

private data class HopTransportFixture(
    val support: MeshEngineHopTransportSupport,
    val diagnostics: MutableList<RecordedHopTransportDiagnostic>,
    val sentFrames: MutableList<RecordedHopTransportSend>,
)

private fun hopTransportFixture(
    localIdentity: LocalIdentity,
    runtimeSurface: MeshEngineRuntimeSurface = MeshEngineRuntimeSurface(),
    establishedHopSession: suspend (PeerId) -> HopSession? = { null },
    ensureHopSession: suspend (PeerId, MeshEngineHardRunToken) -> SessionEstablishmentOutcome =
        { _, _ ->
            SessionEstablishmentOutcome.Unreachable
        },
    sendDirectWireFrame:
        suspend (PeerId, DirectWireFrame, String, TransportMode?) -> TransportSendResult =
        { _, _, _, _ ->
            TransportSendResult.Delivered
        },
): HopTransportFixture {
    val diagnostics = mutableListOf<RecordedHopTransportDiagnostic>()
    val sentFrames = mutableListOf<RecordedHopTransportSend>()
    val routingSupport =
        MeshEngineRoutingSupport(
            routeCoordinator = RouteCoordinator(localIdentity.peerId),
            runtimeGate = runtimeSurface.runtimeGate,
            coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            emitDiagnostic = { _, _, _, _, _, _ -> },
            sendEncryptedWireFrame = { _, _, _, _ -> true },
        )
    val support =
        MeshEngineHopTransportSupport(
            localIdentity = localIdentity,
            runtimeGate = runtimeSurface.runtimeGate,
            routingSupport = routingSupport,
            establishedHopSession = establishedHopSession,
            ensureHopSession = ensureHopSession,
            sendDirectWireFrame = { peerId, frame, action, preferredMode ->
                sentFrames +=
                    RecordedHopTransportSend(
                        peerIdValue = peerId.value,
                        frame = frame,
                        action = action,
                        preferredMode = preferredMode,
                    )
                sendDirectWireFrame(peerId, frame, action, preferredMode)
            },
            emitDiagnostic = { code, severity, stage, peerSuffix, reason, metadata ->
                diagnostics +=
                    RecordedHopTransportDiagnostic(
                        code = code,
                        severity = severity,
                        stage = stage,
                        peerSuffix = peerSuffix,
                        reason = reason,
                        metadata = metadata,
                    )
            },
        )
    return HopTransportFixture(
        support = support,
        diagnostics = diagnostics,
        sentFrames = sentFrames,
    )
}

private fun hopSession(keyByte: Int): HopSession {
    val key = ByteArray(32) { keyByte.toByte() }
    return HopSession(sendKey = key, receiveKey = key)
}

private data class RecordedHopTransportSend(
    val peerIdValue: String,
    val frame: DirectWireFrame,
    val action: String,
    val preferredMode: TransportMode?,
)

private data class RecordedHopTransportDiagnostic(
    val code: DiagnosticCode,
    val severity: DiagnosticSeverity,
    val stage: String,
    val peerSuffix: String?,
    val reason: DiagnosticReason?,
    val metadata: Map<String, String>,
)
