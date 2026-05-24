package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.crypto.NoiseXXHandshakeManager
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.identity.LocalIdentity
import ch.trancee.meshlink.routing.RouteCoordinator
import ch.trancee.meshlink.wire.WireCodec
import ch.trancee.meshlink.wire.WireFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class MeshEngineInboundSupportTest {
    @Test
    fun `handleEncryptedDataFrame resolves aliased peers before delivering a local message`() =
        runBlocking {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("inbound-local")
            val temporaryPeerId = PeerId("bt-aabbccddeeff")
            val canonicalPeerId = PeerId("canonical-peer")
            val originPeerId = PeerId("origin-peer")
            val sessionRegistry = MeshEngineSessionRegistry()
            seedAliasedInboundSession(
                localIdentity = localIdentity,
                sessionRegistry = sessionRegistry,
                temporaryPeerId = temporaryPeerId,
                canonicalPeerId = canonicalPeerId,
            )
            val deliveredMessages = mutableListOf<RecordedInboundSupportDelivery>()
            val fixture =
                inboundSupportFixture(
                    localIdentity = localIdentity,
                    sessionRegistry = sessionRegistry,
                    decryptedFrame =
                        WireFrame.Message(
                            messageId = "message-1",
                            originPeerId = originPeerId,
                            destinationPeerId = localIdentity.peerId,
                            priority = DeliveryPriority.HIGH,
                            ttlMillis = 1000,
                            encryptedPayload = "hello".encodeToByteArray(),
                        ),
                    deliverInnerEnvelope = {
                        immediatePeerId,
                        deliveredOriginPeerId,
                        payload,
                        priority,
                        _ ->
                        deliveredMessages +=
                            RecordedInboundSupportDelivery(
                                immediatePeerIdValue = immediatePeerId.value,
                                originPeerIdValue = deliveredOriginPeerId.value,
                                payload = payload,
                                priority = priority,
                            )
                    },
                )

            // Act
            fixture.support.handleEncryptedDataFrame(
                peerId = temporaryPeerId,
                payload = byteArrayOf(1),
            )

            // Assert
            assertEquals(
                listOf(
                    RecordedInboundSupportDelivery(
                        immediatePeerIdValue = canonicalPeerId.value,
                        originPeerIdValue = originPeerId.value,
                        payload = "hello".encodeToByteArray(),
                        priority = DeliveryPriority.HIGH,
                    )
                ),
                deliveredMessages,
            )
            assertTrue(fixture.forwardedMessages.isEmpty())
            assertTrue(fixture.failures.isEmpty())
        }

    @Test
    fun `handleEncryptedDataFrame forwards non local routed messages instead of delivering them`() =
        runBlocking {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("inbound-local")
            val peerId = PeerId("peer-abcdef")
            val destinationPeerId = PeerId("destination-peer")
            val sessionRegistry = MeshEngineSessionRegistry()
            sessionRegistry.completeResponderHandshake(
                peerId = peerId,
                pendingHandshake =
                    PendingResponderHandshake(NoiseXXHandshakeManager(localIdentity.cryptoProvider))
                        .also { pendingHandshake ->
                            sessionRegistry.storePendingResponderHandshake(peerId, pendingHandshake)
                        },
                session = HopSession(ByteArray(32) { 0x01 }, ByteArray(32) { 0x02 }),
            )
            val fixture =
                inboundSupportFixture(
                    localIdentity = localIdentity,
                    sessionRegistry = sessionRegistry,
                    decryptedFrame =
                        WireFrame.Message(
                            messageId = "message-2",
                            originPeerId = PeerId("origin-peer"),
                            destinationPeerId = destinationPeerId,
                            priority = DeliveryPriority.NORMAL,
                            ttlMillis = 1000,
                            encryptedPayload = "relay".encodeToByteArray(),
                        ),
                )

            // Act
            fixture.support.handleEncryptedDataFrame(peerId = peerId, payload = byteArrayOf(1))

            // Assert
            val forwarded = fixture.forwardedMessages.single()
            assertEquals(destinationPeerId.value, forwarded.destinationPeerIdValue)
            assertEquals("message-2", forwarded.messageId)
            assertTrue(fixture.failures.isEmpty())
        }

    @Test
    fun `handleEncryptedDataFrame emits no session when no active hop session exists`() =
        runBlocking {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("inbound-local")
            val peerId = PeerId("peer-abcdef")
            val fixture =
                inboundSupportFixture(
                    localIdentity = localIdentity,
                    sessionRegistry = MeshEngineSessionRegistry(),
                    decryptedFrame = WireFrame.TransferComplete("unused"),
                )

            // Act
            fixture.support.handleEncryptedDataFrame(peerId = peerId, payload = byteArrayOf(1))

            // Assert
            assertEquals(
                listOf(
                    RecordedInboundSupportFailure(
                        peerIdValue = peerId.value,
                        stage = "transport.data.noSession",
                        reason = DiagnosticReason.DELIVERY_FAILURE,
                        metadata = emptyMap(),
                    )
                ),
                fixture.failures,
            )
        }
}

private data class InboundSupportFixture(
    val support: MeshEngineInboundSupport,
    val failures: MutableList<RecordedInboundSupportFailure>,
    val forwardedMessages: MutableList<RecordedInboundSupportForwardedMessage>,
)

private fun inboundSupportFixture(
    localIdentity: LocalIdentity,
    sessionRegistry: MeshEngineSessionRegistry,
    decryptedFrame: WireFrame,
    deliverInnerEnvelope:
        suspend (PeerId, PeerId, ByteArray, DeliveryPriority, MeshEngineHardRunToken) -> Unit =
        { _, _, _, _, _ ->
            error("unexpected envelope delivery")
        },
): InboundSupportFixture {
    val failures = mutableListOf<RecordedInboundSupportFailure>()
    val forwardedMessages = mutableListOf<RecordedInboundSupportForwardedMessage>()
    val runtimeSurface = MeshEngineRuntimeSurface()
    val hardRunToken = runtimeSurface.beginHardRun()
    val routeCoordinator = RouteCoordinator(localIdentity.peerId)
    val routingSupport =
        MeshEngineRoutingSupport(
            routeCoordinator = routeCoordinator,
            runtimeGate = runtimeSurface.runtimeGate,
            coroutineScope =
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            emitDiagnostic = { _, _, _, _, _, _ -> Unit },
            sendEncryptedWireFrame = { _, _, _, _ -> true },
        )
    val support =
        MeshEngineInboundSupport(
            localIdentity = localIdentity,
            sessionRegistry = sessionRegistry,
            routingContext =
                MeshEngineInboundRoutingContext(
                    routeCoordinator = routeCoordinator,
                    routingSupport = routingSupport,
                ),
            transport =
                MeshEngineInboundTransport(
                    emitHopSessionFailed = { peerId, stage, reason, metadata ->
                        failures +=
                            RecordedInboundSupportFailure(
                                peerIdValue = peerId.value,
                                stage = stage,
                                reason = reason,
                                metadata = metadata,
                            )
                    },
                    decryptHopPayload = { _, _ -> WireCodec.encode(decryptedFrame) },
                ),
            messageCallbacks =
                MeshEngineInboundMessageCallbacks(
                    captureHardRunToken = { hardRunToken },
                    forwardMessageToNextHop = { frame, _ ->
                        forwardedMessages +=
                            RecordedInboundSupportForwardedMessage(
                                destinationPeerIdValue = frame.destinationPeerId.value,
                                messageId = frame.messageId,
                                payload = frame.encryptedPayload,
                            )
                    },
                    deliverInnerEnvelope = deliverInnerEnvelope,
                ),
            transferCallbacks =
                MeshEngineInboundTransferCallbacks(
                    handleTransferStart = { _, _ -> error("unexpected transfer start") },
                    handleTransferChunk = { _, _ -> error("unexpected transfer chunk") },
                    handleTransferAck = { _, _ -> error("unexpected transfer ack") },
                    handleTransferComplete = { _, _ -> error("unexpected transfer complete") },
                    handleTransferAbort = { _, _ -> error("unexpected transfer abort") },
                ),
        )
    return InboundSupportFixture(
        support = support,
        failures = failures,
        forwardedMessages = forwardedMessages,
    )
}

private suspend fun seedAliasedInboundSession(
    localIdentity: LocalIdentity,
    sessionRegistry: MeshEngineSessionRegistry,
    temporaryPeerId: PeerId,
    canonicalPeerId: PeerId,
): Unit {
    val pendingHandshake =
        PendingResponderHandshake(NoiseXXHandshakeManager(localIdentity.cryptoProvider))
    sessionRegistry.storePendingResponderHandshake(temporaryPeerId, pendingHandshake)
    sessionRegistry.rebindPendingResponderHandshake(
        fromPeerId = temporaryPeerId,
        toPeerId = canonicalPeerId,
        pendingHandshake = pendingHandshake,
    )
    sessionRegistry.completeResponderHandshake(
        peerId = canonicalPeerId,
        pendingHandshake = pendingHandshake,
        session = HopSession(ByteArray(32) { 0x01 }, ByteArray(32) { 0x02 }),
    )
}

private data class RecordedInboundSupportDelivery(
    val immediatePeerIdValue: String,
    val originPeerIdValue: String,
    val payload: ByteArray,
    val priority: DeliveryPriority,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RecordedInboundSupportDelivery) return false
        return immediatePeerIdValue == other.immediatePeerIdValue &&
            originPeerIdValue == other.originPeerIdValue &&
            payload.contentEquals(other.payload) &&
            priority == other.priority
    }

    override fun hashCode(): Int {
        var result = immediatePeerIdValue.hashCode()
        result = 31 * result + originPeerIdValue.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + priority.hashCode()
        return result
    }
}

private data class RecordedInboundSupportForwardedMessage(
    val destinationPeerIdValue: String,
    val messageId: String,
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RecordedInboundSupportForwardedMessage) return false
        return destinationPeerIdValue == other.destinationPeerIdValue &&
            messageId == other.messageId &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = destinationPeerIdValue.hashCode()
        result = 31 * result + messageId.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

private data class RecordedInboundSupportFailure(
    val peerIdValue: String,
    val stage: String,
    val reason: DiagnosticReason,
    val metadata: Map<String, String>,
)
