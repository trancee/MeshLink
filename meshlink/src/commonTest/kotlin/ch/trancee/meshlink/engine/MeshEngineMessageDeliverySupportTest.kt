package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.InboundMessage
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.crypto.MessageSealer
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity
import ch.trancee.meshlink.identity.LocalIdentity
import ch.trancee.meshlink.test.InMemorySecureStorage
import ch.trancee.meshlink.trust.TofuTrustStore
import ch.trancee.meshlink.trust.TrustPublicKeys
import ch.trancee.meshlink.trust.TrustRecord
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking

class MeshEngineMessageDeliverySupportTest {
    @Test
    fun `deliverInnerEnvelope emits an inbound message when the hard run is active`() =
        runBlocking<Unit> {
            // Arrange
            val recipientIdentity = LocalIdentity.fromAppId("message-delivery-recipient")
            val senderIdentity = LocalIdentity.fromAppId("message-delivery-sender")
            val runtimeSurface = MeshEngineRuntimeSurface()
            val hardRunToken = runtimeSurface.beginHardRun()
            val trustStore = TofuTrustStore(InMemorySecureStorage())
            // The sender's trust must already be pinned via an authenticated channel (e.g. an
            // end-to-end handshake) before an inner envelope from that sender can be accepted.
            trustStore.write(trustRecordFor(senderIdentity))
            val fixture =
                messageDeliveryFixture(
                    recipientIdentity = recipientIdentity,
                    runtimeSurface = runtimeSurface,
                    trustStore = trustStore,
                )
            val envelope =
                sealedEnvelopeFor(
                    senderIdentity = senderIdentity,
                    recipientIdentity = recipientIdentity,
                    plaintext = "hello".encodeToByteArray(),
                )

            // Act
            fixture.support.deliverInnerEnvelope(
                immediatePeerId = PeerId("relay-peer"),
                originPeerId = senderIdentity.peerId,
                encryptedPayload = envelope,
                priority = DeliveryPriority.HIGH,
                hardRunToken = hardRunToken,
            )

            // Assert
            val message = fixture.mutableMessages.replayCache.single()
            assertEquals(senderIdentity.peerId.value, message.originPeerId.value)
            assertContentEquals("hello".encodeToByteArray(), message.payload)
            assertEquals(DeliveryPriority.HIGH, message.priority)
            assertTrue(
                fixture.diagnostics.any { diagnostic ->
                    diagnostic.code == DiagnosticCode.DELIVERY_SUCCEEDED &&
                        diagnostic.stage == "transport.data.deliver" &&
                        diagnostic.metadata["immediatePeerId"] == "relay-peer"
                }
            )
            assertTrue(fixture.hopFailures.isEmpty())
        }

    @Test
    fun `deliverInnerEnvelope returns early when the hard run already ended`() =
        runBlocking<Unit> {
            // Arrange
            val recipientIdentity = LocalIdentity.fromAppId("message-delivery-recipient")
            val senderIdentity = LocalIdentity.fromAppId("message-delivery-sender")
            val runtimeSurface = MeshEngineRuntimeSurface()
            val hardRunToken = runtimeSurface.captureHardRunToken()
            val fixture =
                messageDeliveryFixture(
                    recipientIdentity = recipientIdentity,
                    runtimeSurface = runtimeSurface,
                )
            val envelope =
                sealedEnvelopeFor(
                    senderIdentity = senderIdentity,
                    recipientIdentity = recipientIdentity,
                    plaintext = "hello".encodeToByteArray(),
                )

            // Act
            fixture.support.deliverInnerEnvelope(
                immediatePeerId = PeerId("relay-peer"),
                originPeerId = senderIdentity.peerId,
                encryptedPayload = envelope,
                priority = DeliveryPriority.NORMAL,
                hardRunToken = hardRunToken,
            )

            // Assert
            assertTrue(fixture.mutableMessages.replayCache.isEmpty())
            assertTrue(fixture.hopFailures.isEmpty())
            assertTrue(fixture.diagnostics.isEmpty())
        }

    @Test
    fun `deliverInnerEnvelope emits a hop session failure when envelope decoding fails`() =
        runBlocking<Unit> {
            // Arrange
            val recipientIdentity = LocalIdentity.fromAppId("message-delivery-recipient")
            val runtimeSurface = MeshEngineRuntimeSurface()
            val hardRunToken = runtimeSurface.beginHardRun()
            val fixture =
                messageDeliveryFixture(
                    recipientIdentity = recipientIdentity,
                    runtimeSurface = runtimeSurface,
                )

            // Act
            fixture.support.deliverInnerEnvelope(
                immediatePeerId = PeerId("relay-peer"),
                originPeerId = PeerId("origin-peer"),
                encryptedPayload = byteArrayOf(1, 2, 3),
                priority = DeliveryPriority.NORMAL,
                hardRunToken = hardRunToken,
            )

            // Assert
            assertEquals(
                listOf(
                    RecordedMessageDeliveryHopFailure(
                        peerIdValue = "relay-peer",
                        stage = "transport.data.messageEnvelope",
                        reason = DiagnosticReason.DELIVERY_FAILURE,
                        metadata = mapOf("cause" to "IllegalStateException"),
                    )
                ),
                fixture.hopFailures,
            )
            assertTrue(fixture.mutableMessages.replayCache.isEmpty())
        }

    @Test
    fun `deliverInnerEnvelope emits trust failure when the ciphertext cannot be opened`() =
        runBlocking<Unit> {
            // Arrange
            val recipientIdentity = LocalIdentity.fromAppId("message-delivery-recipient")
            val senderIdentity = LocalIdentity.fromAppId("message-delivery-sender")
            val runtimeSurface = MeshEngineRuntimeSurface()
            val hardRunToken = runtimeSurface.beginHardRun()
            val trustStore = TofuTrustStore(InMemorySecureStorage())
            // The sender's trust must already be pinned via an authenticated channel so that
            // sender-trust verification itself succeeds and the tampered ciphertext is the only
            // remaining failure.
            trustStore.write(trustRecordFor(senderIdentity))
            val fixture =
                messageDeliveryFixture(
                    recipientIdentity = recipientIdentity,
                    runtimeSurface = runtimeSurface,
                    trustStore = trustStore,
                )
            val envelope =
                tamperedEnvelope(
                    sealedEnvelopeFor(
                        senderIdentity = senderIdentity,
                        recipientIdentity = recipientIdentity,
                        plaintext = "hello".encodeToByteArray(),
                    )
                )

            // Act
            fixture.support.deliverInnerEnvelope(
                immediatePeerId = PeerId("relay-peer"),
                originPeerId = senderIdentity.peerId,
                encryptedPayload = envelope,
                priority = DeliveryPriority.NORMAL,
                hardRunToken = hardRunToken,
            )

            // Assert
            assertTrue(fixture.mutableMessages.replayCache.isEmpty())
            assertTrue(
                fixture.diagnostics.any { diagnostic ->
                    diagnostic.code == DiagnosticCode.TRUST_FAILURE &&
                        diagnostic.stage == "transport.data.open" &&
                        diagnostic.reason == DiagnosticReason.TRUST_FAILURE
                }
            )
        }
}

private data class MessageDeliveryFixture(
    val support: MeshEngineMessageDeliverySupport,
    val mutableMessages: MutableSharedFlow<InboundMessage>,
    val hopFailures: MutableList<RecordedMessageDeliveryHopFailure>,
    val diagnostics: MutableList<RecordedMessageDeliveryDiagnostic>,
)

private fun messageDeliveryFixture(
    recipientIdentity: LocalIdentity,
    runtimeSurface: MeshEngineRuntimeSurface,
    trustStore: TofuTrustStore = TofuTrustStore(InMemorySecureStorage()),
): MessageDeliveryFixture {
    val mutableMessages = MutableSharedFlow<InboundMessage>(replay = 1, extraBufferCapacity = 1)
    val hopFailures = mutableListOf<RecordedMessageDeliveryHopFailure>()
    val diagnostics = mutableListOf<RecordedMessageDeliveryDiagnostic>()
    val trustSupport =
        MeshEngineTrustSupport(
            localIdentity = recipientIdentity,
            trustStore = trustStore,
            emitDiagnostic = { code, severity, stage, peerSuffix, reason, metadata ->
                diagnostics +=
                    RecordedMessageDeliveryDiagnostic(
                        code = code,
                        severity = severity,
                        stage = stage,
                        peerSuffix = peerSuffix,
                        reason = reason,
                        metadata = metadata,
                    )
            },
        )
    val support =
        MeshEngineMessageDeliverySupport(
            localIdentity = recipientIdentity,
            runtimeGate = runtimeSurface.runtimeGate,
            trustSupport = trustSupport,
            mutableMessages = mutableMessages,
            emitHopSessionFailed = { peerId, stage, reason, metadata ->
                hopFailures +=
                    RecordedMessageDeliveryHopFailure(
                        peerIdValue = peerId.value,
                        stage = stage,
                        reason = reason,
                        metadata = metadata,
                    )
            },
            emitDiagnostic = { code, severity, stage, peerSuffix, reason, metadata ->
                diagnostics +=
                    RecordedMessageDeliveryDiagnostic(
                        code = code,
                        severity = severity,
                        stage = stage,
                        peerSuffix = peerSuffix,
                        reason = reason,
                        metadata = metadata,
                    )
            },
        )
    return MessageDeliveryFixture(
        support = support,
        mutableMessages = mutableMessages,
        hopFailures = hopFailures,
        diagnostics = diagnostics,
    )
}

private fun sealedEnvelopeFor(
    senderIdentity: LocalIdentity,
    recipientIdentity: LocalIdentity,
    plaintext: ByteArray,
): ByteArray {
    val recipientTrust = trustRecordFor(recipientIdentity)
    val sealedPayload =
        MessageSealer.seal(
            plaintext = plaintext,
            senderIdentity = senderIdentity,
            recipientTrust = recipientTrust,
        )
    return DirectMessageEnvelope(
            senderPeerId = senderIdentity.peerId,
            senderFingerprintBytes = senderIdentity.identityFingerprintBytes,
            senderEd25519PublicKey = senderIdentity.ed25519PublicKey,
            senderX25519PublicKey = senderIdentity.x25519PublicKey,
            ciphertext = sealedPayload,
        )
        .encode()
}

private fun tamperedEnvelope(encodedEnvelope: ByteArray): ByteArray {
    val envelope = DirectMessageEnvelope.decode(encodedEnvelope)
    val tamperedCiphertext =
        envelope.ciphertext.copyOf().also { ciphertext ->
            ciphertext[ciphertext.lastIndex] = (ciphertext.last().toInt() xor 0x01).toByte()
        }
    return DirectMessageEnvelope(
            senderPeerId = envelope.senderPeerId,
            senderFingerprintBytes = envelope.senderFingerprintBytes,
            senderEd25519PublicKey = envelope.senderEd25519PublicKey,
            senderX25519PublicKey = envelope.senderX25519PublicKey,
            ciphertext = tamperedCiphertext,
        )
        .encode()
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

private data class RecordedMessageDeliveryHopFailure(
    val peerIdValue: String,
    val stage: String,
    val reason: DiagnosticReason,
    val metadata: Map<String, String>,
)

private data class RecordedMessageDeliveryDiagnostic(
    val code: DiagnosticCode,
    val severity: DiagnosticSeverity,
    val stage: String,
    val peerSuffix: String?,
    val reason: DiagnosticReason?,
    val metadata: Map<String, String>,
)
