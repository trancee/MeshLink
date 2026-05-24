package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.crypto.NoiseXXHandshakeManager
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.identity.LocalIdentity
import ch.trancee.meshlink.identity.toHexString
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
                fixture.deliveredMessages,
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
            seedInboundSession(
                localIdentity = localIdentity,
                sessionRegistry = sessionRegistry,
                peerId = peerId,
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
    fun `handleEncryptedDataFrame delivers a local message addressed to the advertisement hash`() =
        runBlocking {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("inbound-local")
            val peerId = PeerId("peer-abcdef")
            val originPeerId = PeerId("origin-peer")
            val sessionRegistry = MeshEngineSessionRegistry()
            seedInboundSession(
                localIdentity = localIdentity,
                sessionRegistry = sessionRegistry,
                peerId = peerId,
            )
            val advertisementPeerId = PeerId(localIdentity.advertisementKeyHash.toHexString())
            val fixture =
                inboundSupportFixture(
                    localIdentity = localIdentity,
                    sessionRegistry = sessionRegistry,
                    decryptedFrame =
                        WireFrame.Message(
                            messageId = "message-hash",
                            originPeerId = originPeerId,
                            destinationPeerId = advertisementPeerId,
                            priority = DeliveryPriority.NORMAL,
                            ttlMillis = 1000,
                            encryptedPayload = "hashed".encodeToByteArray(),
                        ),
                )

            // Act
            fixture.support.handleEncryptedDataFrame(peerId = peerId, payload = byteArrayOf(1))

            // Assert
            assertEquals(
                listOf(
                    RecordedInboundSupportDelivery(
                        immediatePeerIdValue = peerId.value,
                        originPeerIdValue = originPeerId.value,
                        payload = "hashed".encodeToByteArray(),
                        priority = DeliveryPriority.NORMAL,
                    )
                ),
                fixture.deliveredMessages,
            )
            assertTrue(fixture.forwardedMessages.isEmpty())
            assertTrue(fixture.failures.isEmpty())
        }

    @Test
    fun `handleEncryptedDataFrame emits decrypt failure when hop payload decryption throws`() =
        runBlocking {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("inbound-local")
            val peerId = PeerId("peer-abcdef")
            val sessionRegistry = MeshEngineSessionRegistry()
            seedInboundSession(
                localIdentity = localIdentity,
                sessionRegistry = sessionRegistry,
                peerId = peerId,
            )
            val fixture =
                inboundSupportFixture(
                    localIdentity = localIdentity,
                    sessionRegistry = sessionRegistry,
                    decryptHopPayload = { _, _ -> throw IllegalStateException("boom") },
                )

            // Act
            fixture.support.handleEncryptedDataFrame(peerId = peerId, payload = byteArrayOf(1))

            // Assert
            assertEquals(
                listOf(
                    RecordedInboundSupportFailure(
                        peerIdValue = peerId.value,
                        stage = "transport.data.decrypt",
                        reason = DiagnosticReason.DELIVERY_FAILURE,
                        metadata = mapOf("cause" to "IllegalStateException"),
                    )
                ),
                fixture.failures,
            )
            assertTrue(fixture.forwardedMessages.isEmpty())
            assertTrue(fixture.deliveredMessages.isEmpty())
            assertTrue(fixture.transferEvents.isEmpty())
        }

    @Test
    fun `handleEncryptedDataFrame emits decode failure when a decrypted payload is invalid`() =
        runBlocking {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("inbound-local")
            val peerId = PeerId("peer-abcdef")
            val sessionRegistry = MeshEngineSessionRegistry()
            seedInboundSession(
                localIdentity = localIdentity,
                sessionRegistry = sessionRegistry,
                peerId = peerId,
            )
            val fixture =
                inboundSupportFixture(
                    localIdentity = localIdentity,
                    sessionRegistry = sessionRegistry,
                    decryptHopPayload = { _, _ -> byteArrayOf(0x01, 0x02, 0x03) },
                )

            // Act
            fixture.support.handleEncryptedDataFrame(peerId = peerId, payload = byteArrayOf(1))

            // Assert
            val failure = fixture.failures.single()
            assertEquals(peerId.value, failure.peerIdValue)
            assertEquals("transport.data.decode", failure.stage)
            assertEquals(DiagnosticReason.DELIVERY_FAILURE, failure.reason)
            assertTrue(failure.metadata.containsKey("cause"))
            assertTrue(fixture.forwardedMessages.isEmpty())
            assertTrue(fixture.deliveredMessages.isEmpty())
            assertTrue(fixture.transferEvents.isEmpty())
        }

    @Test
    fun `handleEncryptedDataFrame dispatches transfer start frames to transfer callbacks`() =
        runBlocking {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("inbound-local")
            val peerId = PeerId("peer-abcdef")
            val sessionRegistry = MeshEngineSessionRegistry()
            seedInboundSession(
                localIdentity = localIdentity,
                sessionRegistry = sessionRegistry,
                peerId = peerId,
            )
            val frame =
                WireFrame.TransferStart(
                    route =
                        WireFrame.TransferStartRoute(
                            transferId = "transfer-start",
                            messageId = "message-1",
                            originPeerId = PeerId("origin-peer"),
                            destinationPeerId = localIdentity.peerId,
                        ),
                    sizing =
                        WireFrame.TransferStartSizing(
                            totalBytes = 10,
                            totalChunks = 1,
                            maxChunkPayloadBytes = 10,
                        ),
                )
            val fixture =
                inboundSupportFixture(
                    localIdentity = localIdentity,
                    sessionRegistry = sessionRegistry,
                    decryptedFrame = frame,
                )

            // Act
            fixture.support.handleEncryptedDataFrame(peerId = peerId, payload = byteArrayOf(1))

            // Assert
            assertEquals(
                listOf(
                    RecordedInboundTransferEvent(
                        kind = "start",
                        peerIdValue = peerId.value,
                        transferId = "transfer-start",
                    )
                ),
                fixture.transferEvents,
            )
        }

    @Test
    fun `handleEncryptedDataFrame dispatches transfer chunk frames to transfer callbacks`() =
        runBlocking {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("inbound-local")
            val peerId = PeerId("peer-abcdef")
            val sessionRegistry = MeshEngineSessionRegistry()
            seedInboundSession(
                localIdentity = localIdentity,
                sessionRegistry = sessionRegistry,
                peerId = peerId,
            )
            val frame =
                WireFrame.TransferChunk(
                    transferId = "transfer-chunk",
                    chunkIndex = 2,
                    payload = "chunk".encodeToByteArray(),
                )
            val fixture =
                inboundSupportFixture(
                    localIdentity = localIdentity,
                    sessionRegistry = sessionRegistry,
                    decryptedFrame = frame,
                )

            // Act
            fixture.support.handleEncryptedDataFrame(peerId = peerId, payload = byteArrayOf(1))

            // Assert
            assertEquals(
                listOf(
                    RecordedInboundTransferEvent(
                        kind = "chunk",
                        peerIdValue = peerId.value,
                        transferId = "transfer-chunk",
                        chunkIndex = 2,
                    )
                ),
                fixture.transferEvents,
            )
        }

    @Test
    fun `handleEncryptedDataFrame dispatches transfer ack frames to transfer callbacks`() =
        runBlocking {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("inbound-local")
            val peerId = PeerId("peer-abcdef")
            val sessionRegistry = MeshEngineSessionRegistry()
            seedInboundSession(
                localIdentity = localIdentity,
                sessionRegistry = sessionRegistry,
                peerId = peerId,
            )
            val frame =
                WireFrame.TransferAck(
                    transferId = "transfer-ack",
                    highestContiguousAck = 4,
                    selectiveRanges = byteArrayOf(5, 6),
                )
            val fixture =
                inboundSupportFixture(
                    localIdentity = localIdentity,
                    sessionRegistry = sessionRegistry,
                    decryptedFrame = frame,
                )

            // Act
            fixture.support.handleEncryptedDataFrame(peerId = peerId, payload = byteArrayOf(1))

            // Assert
            assertEquals(
                listOf(
                    RecordedInboundTransferEvent(
                        kind = "ack",
                        peerIdValue = peerId.value,
                        transferId = "transfer-ack",
                        highestContiguousAck = 4,
                    )
                ),
                fixture.transferEvents,
            )
        }

    @Test
    fun `handleEncryptedDataFrame dispatches transfer complete frames to transfer callbacks`() =
        runBlocking {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("inbound-local")
            val peerId = PeerId("peer-abcdef")
            val sessionRegistry = MeshEngineSessionRegistry()
            seedInboundSession(
                localIdentity = localIdentity,
                sessionRegistry = sessionRegistry,
                peerId = peerId,
            )
            val fixture =
                inboundSupportFixture(
                    localIdentity = localIdentity,
                    sessionRegistry = sessionRegistry,
                    decryptedFrame = WireFrame.TransferComplete("transfer-complete"),
                )

            // Act
            fixture.support.handleEncryptedDataFrame(peerId = peerId, payload = byteArrayOf(1))

            // Assert
            assertEquals(
                listOf(
                    RecordedInboundTransferEvent(
                        kind = "complete",
                        peerIdValue = peerId.value,
                        transferId = "transfer-complete",
                    )
                ),
                fixture.transferEvents,
            )
        }

    @Test
    fun `handleEncryptedDataFrame dispatches transfer abort frames to transfer callbacks`() =
        runBlocking {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("inbound-local")
            val peerId = PeerId("peer-abcdef")
            val sessionRegistry = MeshEngineSessionRegistry()
            seedInboundSession(
                localIdentity = localIdentity,
                sessionRegistry = sessionRegistry,
                peerId = peerId,
            )
            val fixture =
                inboundSupportFixture(
                    localIdentity = localIdentity,
                    sessionRegistry = sessionRegistry,
                    decryptedFrame =
                        WireFrame.TransferAbort(transferId = "transfer-abort", reasonCode = 9),
                )

            // Act
            fixture.support.handleEncryptedDataFrame(peerId = peerId, payload = byteArrayOf(1))

            // Assert
            assertEquals(
                listOf(
                    RecordedInboundTransferEvent(
                        kind = "abort",
                        peerIdValue = peerId.value,
                        transferId = "transfer-abort",
                        reasonCode = 9,
                    )
                ),
                fixture.transferEvents,
            )
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
    val deliveredMessages: MutableList<RecordedInboundSupportDelivery>,
    val transferEvents: MutableList<RecordedInboundTransferEvent>,
)

private fun inboundSupportFixture(
    localIdentity: LocalIdentity,
    sessionRegistry: MeshEngineSessionRegistry,
    decryptedFrame: WireFrame? = null,
    decryptHopPayload: ((HopSession, ByteArray) -> ByteArray)? = null,
    deliverInnerEnvelope:
        (suspend (PeerId, PeerId, ByteArray, DeliveryPriority, MeshEngineHardRunToken) -> Unit)? =
        null,
    transferCallbacks: MeshEngineInboundTransferCallbacks? = null,
): InboundSupportFixture {
    val failures = mutableListOf<RecordedInboundSupportFailure>()
    val forwardedMessages = mutableListOf<RecordedInboundSupportForwardedMessage>()
    val deliveredMessages = mutableListOf<RecordedInboundSupportDelivery>()
    val transferEvents = mutableListOf<RecordedInboundTransferEvent>()
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
    val recordedDeliverInnerEnvelope =
        deliverInnerEnvelope
            ?: { immediatePeerId, deliveredOriginPeerId, payload, priority, _ ->
                deliveredMessages +=
                    RecordedInboundSupportDelivery(
                        immediatePeerIdValue = immediatePeerId.value,
                        originPeerIdValue = deliveredOriginPeerId.value,
                        payload = payload,
                        priority = priority,
                    )
            }
    val recordedTransferCallbacks =
        transferCallbacks
            ?: MeshEngineInboundTransferCallbacks(
                handleTransferStart = { peerId, frame ->
                    transferEvents +=
                        RecordedInboundTransferEvent(
                            kind = "start",
                            peerIdValue = peerId.value,
                            transferId = frame.transferId,
                        )
                },
                handleTransferChunk = { peerId, frame ->
                    transferEvents +=
                        RecordedInboundTransferEvent(
                            kind = "chunk",
                            peerIdValue = peerId.value,
                            transferId = frame.transferId,
                            chunkIndex = frame.chunkIndex,
                        )
                },
                handleTransferAck = { peerId, frame ->
                    transferEvents +=
                        RecordedInboundTransferEvent(
                            kind = "ack",
                            peerIdValue = peerId.value,
                            transferId = frame.transferId,
                            highestContiguousAck = frame.highestContiguousAck,
                        )
                },
                handleTransferComplete = { peerId, frame ->
                    transferEvents +=
                        RecordedInboundTransferEvent(
                            kind = "complete",
                            peerIdValue = peerId.value,
                            transferId = frame.transferId,
                        )
                },
                handleTransferAbort = { peerId, frame ->
                    transferEvents +=
                        RecordedInboundTransferEvent(
                            kind = "abort",
                            peerIdValue = peerId.value,
                            transferId = frame.transferId,
                            reasonCode = frame.reasonCode,
                        )
                },
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
                    decryptHopPayload =
                        decryptHopPayload
                            ?: { _, _ ->
                                WireCodec.encode(checkNotNull(decryptedFrame))
                            },
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
                    deliverInnerEnvelope = recordedDeliverInnerEnvelope,
                ),
            transferCallbacks = recordedTransferCallbacks,
        )
    return InboundSupportFixture(
        support = support,
        failures = failures,
        forwardedMessages = forwardedMessages,
        deliveredMessages = deliveredMessages,
        transferEvents = transferEvents,
    )
}

private suspend fun seedInboundSession(
    localIdentity: LocalIdentity,
    sessionRegistry: MeshEngineSessionRegistry,
    peerId: PeerId,
): Unit {
    val pendingHandshake =
        PendingResponderHandshake(NoiseXXHandshakeManager(localIdentity.cryptoProvider))
    sessionRegistry.storePendingResponderHandshake(peerId, pendingHandshake)
    sessionRegistry.completeResponderHandshake(
        peerId = peerId,
        pendingHandshake = pendingHandshake,
        session = HopSession(ByteArray(32) { 0x01 }, ByteArray(32) { 0x02 }),
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

private data class RecordedInboundTransferEvent(
    val kind: String,
    val peerIdValue: String,
    val transferId: String,
    val chunkIndex: Int? = null,
    val highestContiguousAck: Int? = null,
    val reasonCode: Int? = null,
)
