package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.api.SendResult
import ch.trancee.meshlink.config.meshLinkConfig
import ch.trancee.meshlink.crypto.MessageSealer
import ch.trancee.meshlink.identity.LocalIdentity
import ch.trancee.meshlink.test.InMemorySecureStorage
import ch.trancee.meshlink.transport.BleTransport
import ch.trancee.meshlink.transport.OutboundFrame
import ch.trancee.meshlink.transport.TransportEvent
import ch.trancee.meshlink.transport.TransportSendResult
import ch.trancee.meshlink.trust.TofuTrustStore
import ch.trancee.meshlink.trust.TrustPublicKeys
import ch.trancee.meshlink.trust.TrustRecord
import ch.trancee.meshlink.wire.WireFrame
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
    fun `transfer assembly sendPayload uses the assembled inline delivery path`() = runBlocking {
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
        Unit
    }

    @Test
    fun `transfer assembly handleEncryptedDataFrame delivers messages through the assembled inbound path`() =
        runBlocking {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("transfer-assembly-local")
            val senderIdentity = LocalIdentity.fromAppId("transfer-assembly-sender")
            val harness = runtimeTransferAssemblyHarness(localIdentity = localIdentity)
            harness.runtimeSurface.beginHardRun()
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
                        senderFingerprintBytes = senderIdentity.identityFingerprintBytes,
                        senderEd25519PublicKey = senderIdentity.ed25519PublicKey,
                        senderX25519PublicKey = senderIdentity.x25519PublicKey,
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
    localIdentity: LocalIdentity
): RuntimeTransferAssemblyHarness {
    val runtimeSurface = MeshEngineRuntimeSurface()
    val transport = RecordingRuntimeTransferAssemblyBleTransport()
    val diagnostics = mutableListOf<RecordedRuntimeTransferAssemblyDiagnostic>()
    val environment =
        MeshEngineRuntimeAssemblyEnvironment(
            config = meshLinkConfig { appId = localIdentity.peerId.value },
            localIdentity = localIdentity,
            trustStore = TofuTrustStore(InMemorySecureStorage()),
            coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            platformBridge = MeshEnginePlatformBridge(transport),
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
    sessionRegistry.storePendingResponderHandshake(peerId, pendingHandshake)
    sessionRegistry.completeResponderHandshake(peerId, pendingHandshake, session)
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

private class RecordingRuntimeTransferAssemblyBleTransport : BleTransport {
    override val events: Flow<TransportEvent> = emptyFlow()
    val sentFrames: MutableList<RecordedRuntimeTransferAssemblyOutboundFrame> = mutableListOf()

    override suspend fun start(): Unit = Unit

    override suspend fun pause(): Unit = Unit

    override suspend fun resume(): Unit = Unit

    override suspend fun stop(): Unit = Unit

    override suspend fun setDiscoverySuspended(suspended: Boolean): Unit = Unit

    override suspend fun clearQueuedOutboundFrames(peerId: PeerId): Unit = Unit

    override suspend fun send(frame: OutboundFrame): TransportSendResult {
        sentFrames +=
            RecordedRuntimeTransferAssemblyOutboundFrame(
                peerIdValue = frame.peerId.value,
                payload = frame.payload,
            )
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
