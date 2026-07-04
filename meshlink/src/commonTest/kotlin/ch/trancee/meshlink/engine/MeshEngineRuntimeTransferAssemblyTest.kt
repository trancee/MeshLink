package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.api.SendResult
import ch.trancee.meshlink.config.meshLinkConfig
import ch.trancee.meshlink.crypto.MessageSealer
import ch.trancee.meshlink.identity.LocalIdentity
import ch.trancee.meshlink.power.NoOpBatteryMonitor
import ch.trancee.meshlink.test.InMemorySecureStorage
import ch.trancee.meshlink.transport.BleTransport
import ch.trancee.meshlink.transport.OutboundFrame
import ch.trancee.meshlink.transport.TransportEvent
import ch.trancee.meshlink.transport.TransportSendResult
import ch.trancee.meshlink.trust.TofuTrustStore
import ch.trancee.meshlink.trust.TrustPublicKeys
import ch.trancee.meshlink.trust.TrustRecord
import ch.trancee.meshlink.wire.WireCodec
import ch.trancee.meshlink.wire.WireFrame
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class MeshEngineRuntimeTransferAssemblyTest {
    @Test
    fun `transfer assembly sendPayload uses the assembled inline delivery path`() =
        runBlocking<Unit> {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("transfer-assembly-local")
            val recipientIdentity = LocalIdentity.fromAppId("transfer-assembly-recipient")
            val harness = runtimeTransferAssemblyHarness(localIdentity = localIdentity)
            val hardRunToken = harness.runtimeSurface.beginHardRun()
            seedEstablishedHopSession(
                localIdentity = localIdentity,
                sessionRegistry = harness.foundation.sharedState.sessionRegistry,
                peerId = recipientIdentity.peerId,
                session = hopSession(keyByte = 0x11),
            )
            harness.environment.trustStore.write(trustRecordFor(recipientIdentity))

            // Act
            val result =
                harness.transferAndInbound.sendPayload(
                    MeshEngineOutboundDeliveryMode.INLINE,
                    recipientIdentity.peerId,
                    "hello".encodeToByteArray(),
                    DeliveryPriority.NORMAL,
                    hardRunToken,
                )

            // Assert
            assertEquals(SendResult.Sent, result)
            val outbound = harness.transport.sentFrames.single()
            assertEquals(recipientIdentity.peerId.value, outbound.peerIdValue)
            assertIs<DirectWireFrame.Data>(DirectWireFrame.decode(outbound.payload))
        }

    @Test
    fun `transfer assembly handleEncryptedDataFrame delivers messages through the assembled inbound path`() =
        runBlocking<Unit> {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("transfer-assembly-local")
            val senderIdentity = LocalIdentity.fromAppId("transfer-assembly-sender")
            val harness = runtimeTransferAssemblyHarness(localIdentity = localIdentity)
            harness.runtimeSurface.beginHardRun()
            // The sender's trust must already be pinned via an authenticated channel (e.g. an
            // end-to-end handshake) before an inner envelope from that sender can be accepted.
            harness.environment.trustStore.write(trustRecordFor(senderIdentity))
            val hopSession = hopSession(keyByte = 0x22)
            seedEstablishedHopSession(
                localIdentity = localIdentity,
                sessionRegistry = harness.foundation.sharedState.sessionRegistry,
                peerId = senderIdentity.peerId,
                session = hopSession,
            )
            val envelope =
                DirectMessageEnvelope(
                        senderPeerId = senderIdentity.peerId,
                        ciphertext =
                            MessageSealer.seal(
                                plaintext = "hello".encodeToByteArray(),
                                senderIdentity = senderIdentity,
                                recipientTrust = trustRecordFor(localIdentity),
                            ),
                    )
                    .encode()
            val outerFrame =
                WireFrame.Message(
                    messageId = "message-1",
                    originPeerId = senderIdentity.peerId,
                    destinationPeerId = localIdentity.peerId,
                    priority = DeliveryPriority.HIGH,
                    ttlMillis = 1234,
                    encryptedPayload = envelope,
                )
            val deliveredMessage =
                async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(1_000) { harness.runtimeSurface.messages.first() }
                }
            harness.session.sendEncryptedDirectWireFrame(
                senderIdentity.peerId,
                hopSession,
                outerFrame,
                "loopback.send",
            )
            val directFrame =
                assertIs<DirectWireFrame.Data>(
                    DirectWireFrame.decode(harness.transport.sentFrames.single().payload)
                )

            // Act
            harness.transferAndInbound.handleEncryptedDataFrame(
                senderIdentity.peerId,
                directFrame.payload,
            )
            val message = deliveredMessage.await()

            // Assert
            assertEquals(senderIdentity.peerId.value, message.originPeerId.value)
            assertEquals(DeliveryPriority.HIGH, message.priority)
            assertContentEquals("hello".encodeToByteArray(), message.payload)
            assertTrue(
                harness.diagnostics.any { diagnostic ->
                    diagnostic.stage == "transport.data.deliver" &&
                        diagnostic.metadata["originPeerId"] == senderIdentity.peerId.value
                }
            )
        }

    @Test
    fun `transfer assembly sendPayload uses the assembled large transfer delivery path`() =
        runBlocking<Unit> {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("transfer-assembly-local")
            val recipientIdentity = LocalIdentity.fromAppId("transfer-assembly-recipient")
            lateinit var harness: RuntimeTransferAssemblyHarness
            val snifferSession = hopSession(keyByte = 0x33)
            val observedFrames = mutableListOf<String>()
            var acknowledgedTransferId: String? = null
            harness =
                runtimeTransferAssemblyHarness(localIdentity = localIdentity) { outboundFrame ->
                    val directFrame =
                        assertIs<DirectWireFrame.Data>(
                            DirectWireFrame.decode(outboundFrame.payload)
                        )
                    val decryptedPayload =
                        harness.session.decryptHopPayload(snifferSession, directFrame.payload)
                    when (val outerFrame = WireCodec.decode(decryptedPayload)) {
                        is WireFrame.TransferStart -> observedFrames += "start"
                        is WireFrame.TransferChunk -> {
                            observedFrames += "chunk:${outerFrame.chunkIndex}"
                            val activeSession =
                                harness.foundation.sharedState
                                    .outboundTransfers()[outerFrame.transferId]
                            if (
                                activeSession != null &&
                                    acknowledgedTransferId == null &&
                                    outerFrame.chunkIndex == activeSession.totalChunks - 1
                            ) {
                                acknowledgedTransferId = activeSession.transferId
                                activeSession.markAcknowledged(
                                    WireFrame.TransferAck(
                                        transferId = activeSession.transferId,
                                        highestContiguousAck = activeSession.totalChunks - 1,
                                        selectiveRanges = byteArrayOf(),
                                    )
                                )
                            }
                        }
                        is WireFrame.TransferComplete -> observedFrames += "complete"
                        else -> observedFrames += outerFrame::class.simpleName.orEmpty()
                    }
                }
            val hardRunToken = harness.runtimeSurface.beginHardRun()
            seedEstablishedHopSession(
                localIdentity = localIdentity,
                sessionRegistry = harness.foundation.sharedState.sessionRegistry,
                peerId = recipientIdentity.peerId,
                session = hopSession(keyByte = 0x33),
            )
            harness.environment.trustStore.write(trustRecordFor(recipientIdentity))
            val payload = ByteArray(2_048) { 0x55.toByte() }

            // Act
            val result =
                harness.transferAndInbound.sendPayload(
                    MeshEngineOutboundDeliveryMode.LARGE_TRANSFER,
                    recipientIdentity.peerId,
                    payload,
                    DeliveryPriority.HIGH,
                    hardRunToken,
                )

            // Assert
            assertEquals(SendResult.Sent, result)
            assertNotNull(acknowledgedTransferId)
            assertTrue(observedFrames.contains("start"))
            assertTrue(observedFrames.any { frame -> frame.startsWith("chunk:") })
            assertTrue(observedFrames.contains("complete"))
            assertEquals(
                recipientIdentity.peerId.value,
                harness.transport.clearedQueuedPeerIds.single(),
            )
            assertTrue(harness.foundation.sharedState.outboundTransfers().isEmpty())
        }

    @Test
    fun `transfer assembly relays transfer frames through the assembled downstream path`() =
        runBlocking<Unit> {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("transfer-assembly-local")
            val upstreamIdentity = LocalIdentity.fromAppId("transfer-assembly-upstream")
            val downstreamIdentity = LocalIdentity.fromAppId("transfer-assembly-downstream")
            val harness = runtimeTransferAssemblyHarness(localIdentity = localIdentity)
            harness.runtimeSurface.beginHardRun()
            val upstreamProducerSession = hopSession(keyByte = 0x44)
            val downstreamSnifferSession = hopSession(keyByte = 0x55)
            seedEstablishedHopSession(
                localIdentity = localIdentity,
                sessionRegistry = harness.foundation.sharedState.sessionRegistry,
                peerId = upstreamIdentity.peerId,
                session = hopSession(keyByte = 0x44),
            )
            seedEstablishedHopSession(
                localIdentity = localIdentity,
                sessionRegistry = harness.foundation.sharedState.sessionRegistry,
                peerId = downstreamIdentity.peerId,
                session = hopSession(keyByte = 0x55),
            )
            harness.foundation.sharedState.routeCoordinator.onPeerConnected(
                downstreamIdentity.peerId,
                trustRecordFor(downstreamIdentity),
            )
            val transferStart =
                WireFrame.TransferStart(
                    route =
                        WireFrame.TransferStartRoute(
                            transferId = "relay-transfer-1",
                            messageId = "relay-message-1",
                            originPeerId = upstreamIdentity.peerId,
                            destinationPeerId = downstreamIdentity.peerId,
                        ),
                    sizing =
                        WireFrame.TransferStartSizing(
                            totalBytes = 5,
                            totalChunks = 1,
                            maxChunkPayloadBytes = 5,
                        ),
                )
            val transferChunk =
                WireFrame.TransferChunk(
                    transferId = "relay-transfer-1",
                    chunkIndex = 0,
                    payload = "hello".encodeToByteArray(),
                )
            val transferComplete = WireFrame.TransferComplete("relay-transfer-1")

            // Act
            harness.transferAndInbound.handleEncryptedDataFrame(
                upstreamIdentity.peerId,
                encryptInboundDataFrame(
                    harness = harness,
                    peerId = upstreamIdentity.peerId,
                    session = upstreamProducerSession,
                    outerFrame = transferStart,
                ),
            )
            harness.transferAndInbound.handleEncryptedDataFrame(
                upstreamIdentity.peerId,
                encryptInboundDataFrame(
                    harness = harness,
                    peerId = upstreamIdentity.peerId,
                    session = upstreamProducerSession,
                    outerFrame = transferChunk,
                ),
            )
            harness.transferAndInbound.handleEncryptedDataFrame(
                upstreamIdentity.peerId,
                encryptInboundDataFrame(
                    harness = harness,
                    peerId = upstreamIdentity.peerId,
                    session = upstreamProducerSession,
                    outerFrame = transferComplete,
                ),
            )
            val forwardedFrames =
                harness.transport.sentFrames.map { outboundFrame ->
                    val directFrame =
                        assertIs<DirectWireFrame.Data>(
                            DirectWireFrame.decode(outboundFrame.payload)
                        )
                    WireCodec.decode(
                        harness.session.decryptHopPayload(
                            downstreamSnifferSession,
                            directFrame.payload,
                        )
                    )
                }

            // Assert
            assertEquals(3, forwardedFrames.size)
            assertIs<WireFrame.TransferStart>(forwardedFrames[0])
            val forwardedChunk = assertIs<WireFrame.TransferChunk>(forwardedFrames[1])
            assertEquals(0, forwardedChunk.chunkIndex)
            assertContentEquals("hello".encodeToByteArray(), forwardedChunk.payload)
            assertIs<WireFrame.TransferComplete>(forwardedFrames[2])
            assertTrue(harness.foundation.sharedState.relayTransfers().isEmpty())
        }
}

private data class RuntimeTransferAssemblyHarness(
    val environment: MeshEngineRuntimeAssemblyEnvironment,
    val support: MeshEngineRuntimeAssemblySupport,
    val foundation: MeshEngineRuntimeFoundationAssembly,
    val session: MeshEngineRuntimeSessionAssembly,
    val transferAndInbound: MeshEngineRuntimeTransferAndInboundPhase,
    val runtimeSurface: MeshEngineRuntimeSurface,
    val transport: RecordingRuntimeTransferAssemblyBleTransport,
    val diagnostics: MutableList<RecordedRuntimeTransferAssemblyDiagnostic>,
)

private fun runtimeTransferAssemblyHarness(
    localIdentity: LocalIdentity,
    onSend: (suspend (RecordedRuntimeTransferAssemblyOutboundFrame) -> Unit)? = null,
): RuntimeTransferAssemblyHarness {
    val runtimeSurface = MeshEngineRuntimeSurface()
    val transport = RecordingRuntimeTransferAssemblyBleTransport(onSend = onSend)
    val diagnostics = mutableListOf<RecordedRuntimeTransferAssemblyDiagnostic>()
    val environment =
        MeshEngineRuntimeAssemblyEnvironment(
            config = meshLinkConfig { appId = localIdentity.peerId.value },
            localIdentity = localIdentity,
            trustStore = TofuTrustStore(InMemorySecureStorage()),
            coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            platformBridge = MeshEnginePlatformBridge(transport),
            batteryMonitor = NoOpBatteryMonitor,
            publishedSurface = runtimeSurface,
            compatibilitySurface = runtimeSurface,
        )
    val support =
        MeshEngineRuntimeAssemblySupport(
            emitDiagnostic = { code, severity, stage, peerSuffix, reason, metadata ->
                diagnostics +=
                    RecordedRuntimeTransferAssemblyDiagnostic(
                        code = code,
                        stage = stage,
                        peerSuffix = peerSuffix,
                        reason = reason,
                        metadata = metadata,
                    )
                runtimeSurface.emitDiagnostic(code, severity, stage, peerSuffix, reason, metadata)
            },
            sendDirectWireFrame = { peerId, frame, action, preferredMode ->
                environment.platformBridge.send(
                    OutboundFrame(
                        peerId = peerId,
                        payload = frame.encode(),
                        preferredMode = preferredMode,
                    ),
                    action,
                )
            },
        )
    val lateBindingContext = MeshEngineRuntimeLateBindingContext()
    val foundation =
        buildMeshEngineRuntimeFoundationAssembly(
            environment = environment,
            support = support,
            lateBindingContext = lateBindingContext,
        )
    val session =
        buildMeshEngineRuntimeSessionAssembly(
            environment = environment,
            support = support,
            foundation = foundation,
            lateBindingContext = lateBindingContext,
        )
    val transferAndInbound =
        buildMeshEngineRuntimeTransferAndInboundPhase(
            environment = environment,
            support = support,
            foundation = foundation,
            session = session,
        )
    return RuntimeTransferAssemblyHarness(
        environment = environment,
        support = support,
        foundation = foundation,
        session = session,
        transferAndInbound = transferAndInbound,
        runtimeSurface = runtimeSurface,
        transport = transport,
        diagnostics = diagnostics,
    )
}

private suspend fun seedEstablishedHopSession(
    localIdentity: LocalIdentity,
    sessionRegistry: MeshEngineSessionRegistry,
    peerId: PeerId,
    session: HopSession,
) {
    val pendingHandshake =
        PendingResponderHandshake(
            ch.trancee.meshlink.crypto.NoiseXXHandshakeManager(localIdentity.cryptoProvider)
        )
    sessionRegistry.storePendingResponderHandshake(peerId, pendingHandshake, byteArrayOf())
    sessionRegistry.completeResponderHandshake(peerId, pendingHandshake, session)
}

private suspend fun encryptInboundDataFrame(
    harness: RuntimeTransferAssemblyHarness,
    peerId: PeerId,
    session: HopSession,
    outerFrame: WireFrame,
): ByteArray {
    harness.session.sendEncryptedDirectWireFrame(peerId, session, outerFrame, "loopback.send")
    val recordedFrame =
        harness.transport.sentFrames.removeAt(harness.transport.sentFrames.lastIndex)
    val directFrame = assertIs<DirectWireFrame.Data>(DirectWireFrame.decode(recordedFrame.payload))
    return directFrame.payload
}

private fun hopSession(keyByte: Int): HopSession {
    val key = ByteArray(32) { keyByte.toByte() }
    return HopSession(sendKey = key, receiveKey = key)
}

private fun trustRecordFor(identity: LocalIdentity): TrustRecord {
    return TrustRecord(
        peerIdValue = identity.peerId.value,
        identityFingerprintBytes = identity.identityFingerprintBytes,
        firstSeenAtEpochMillis = 1L,
        lastVerifiedAtEpochMillis = 1L,
        publicKeys =
            TrustPublicKeys(
                ed25519PublicKey = identity.ed25519PublicKey,
                x25519PublicKey = identity.x25519PublicKey,
            ),
    )
}

private class RecordingRuntimeTransferAssemblyBleTransport(
    private val onSend: (suspend (RecordedRuntimeTransferAssemblyOutboundFrame) -> Unit)? = null
) : BleTransport {
    override val events: Flow<TransportEvent> = emptyFlow()
    val sentFrames: MutableList<RecordedRuntimeTransferAssemblyOutboundFrame> = mutableListOf()
    val clearedQueuedPeerIds: MutableList<String> = mutableListOf()

    override suspend fun start(): Unit = Unit

    override suspend fun pause(): Unit = Unit

    override suspend fun resume(): Unit = Unit

    override suspend fun stop(): Unit = Unit

    override suspend fun setDiscoverySuspended(suspended: Boolean): Unit = Unit

    override suspend fun clearQueuedOutboundFrames(peerId: PeerId): Unit {
        clearedQueuedPeerIds += peerId.value
    }

    override suspend fun send(frame: OutboundFrame): TransportSendResult {
        val recordedFrame =
            RecordedRuntimeTransferAssemblyOutboundFrame(
                peerIdValue = frame.peerId.value,
                payload = frame.payload,
            )
        sentFrames += recordedFrame
        onSend?.invoke(recordedFrame)
        return TransportSendResult.Delivered
    }
}

private data class RecordedRuntimeTransferAssemblyOutboundFrame(
    val peerIdValue: String,
    val payload: ByteArray,
)

private data class RecordedRuntimeTransferAssemblyDiagnostic(
    val code: ch.trancee.meshlink.diagnostics.DiagnosticCode,
    val stage: String,
    val peerSuffix: String?,
    val reason: ch.trancee.meshlink.diagnostics.DiagnosticReason?,
    val metadata: Map<String, String>,
)
