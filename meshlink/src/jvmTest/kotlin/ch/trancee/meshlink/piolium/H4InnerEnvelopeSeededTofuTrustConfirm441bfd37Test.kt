package ch.trancee.meshlink.piolium

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.InboundMessage
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.crypto.MessageSealer
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.engine.DirectMessageEnvelope
import ch.trancee.meshlink.engine.MeshEngineMessageDeliverySupport
import ch.trancee.meshlink.engine.MeshEngineRuntimeSurface
import ch.trancee.meshlink.engine.MeshEngineTrustSupport
import ch.trancee.meshlink.identity.LocalIdentity
import ch.trancee.meshlink.test.InMemorySecureStorage
import ch.trancee.meshlink.trust.TofuTrustStore
import ch.trancee.meshlink.trust.TrustPublicKeys
import ch.trancee.meshlink.trust.TrustRecord
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Confirm H4: inner envelope sender metadata seeds TOFU trust before outer-origin binding. */
class H4InnerEnvelopeSeededTofuTrustConfirm441bfd37Test {
    @Test
    fun test_confirm_inner_envelope_seeded_tofu_trust_441bfd37() = runBlocking {
        withTimeout(60_000) {
            // Arrange
            val recipientIdentity = LocalIdentity.fromAppId("piolium.h4.recipient")
            val spoofedInnerSenderIdentity = LocalIdentity.fromAppId("piolium.h4.spoofed-sender")
            val outerOriginPeerId = PeerId("piolium.h4.outer-origin")
            val runtimeSurface = MeshEngineRuntimeSurface()
            val hardRunToken = runtimeSurface.beginHardRun()
            val trustStore = TofuTrustStore(InMemorySecureStorage())
            val mutableMessages =
                MutableSharedFlow<InboundMessage>(replay = 1, extraBufferCapacity = 1)
            val diagnostics = mutableListOf<Pair<DiagnosticCode, String>>()
            val hopFailures = mutableListOf<String>()
            val trustSupport =
                MeshEngineTrustSupport(
                    localIdentity = recipientIdentity,
                    trustStore = trustStore,
                    emitDiagnostic = { code, _, stage, _, _, _ -> diagnostics += code to stage },
                )
            val support =
                MeshEngineMessageDeliverySupport(
                    localIdentity = recipientIdentity,
                    runtimeGate = runtimeSurface.runtimeGate,
                    trustSupport = trustSupport,
                    mutableMessages = mutableMessages,
                    emitHopSessionFailed = { peerId, stage, _, _ ->
                        hopFailures += "${peerId.value}:$stage"
                    },
                    emitDiagnostic = { code, _, stage, _, _, _ -> diagnostics += code to stage },
                )
            val envelope =
                DirectMessageEnvelope(
                        senderPeerId = spoofedInnerSenderIdentity.peerId,
                        senderFingerprintBytes =
                            spoofedInnerSenderIdentity.identityFingerprintBytes,
                        senderEd25519PublicKey = spoofedInnerSenderIdentity.ed25519PublicKey,
                        senderX25519PublicKey = spoofedInnerSenderIdentity.x25519PublicKey,
                        ciphertext =
                            MessageSealer.seal(
                                plaintext = "spoofed hello".encodeToByteArray(),
                                senderIdentity = spoofedInnerSenderIdentity,
                                recipientTrust = trustRecordFor(recipientIdentity),
                            ),
                    )
                    .encode()

            // Act
            support.deliverInnerEnvelope(
                immediatePeerId = PeerId("piolium.h4.immediate-relay"),
                originPeerId = outerOriginPeerId,
                encryptedPayload = envelope,
                priority = DeliveryPriority.NORMAL,
                hardRunToken = hardRunToken,
            )
            val pinnedTrust = trustStore.read(spoofedInnerSenderIdentity.peerId.value)
            val outerOriginTrust = trustStore.read(outerOriginPeerId.value)
            val deliveredMessage = mutableMessages.replayCache.single()

            // Assert
            assertNotNull(
                pinnedTrust,
                "The inner sender identity should be pinned into TOFU storage",
            )
            assertNull(outerOriginTrust, "No trust should be recorded for the outer origin peer ID")
            assertEquals(outerOriginPeerId.value, deliveredMessage.originPeerId.value)
            assertEquals(spoofedInnerSenderIdentity.peerId.value, pinnedTrust.peerIdValue)
            assertNotEquals(deliveredMessage.originPeerId.value, pinnedTrust.peerIdValue)
            assertContentEquals(
                spoofedInnerSenderIdentity.ed25519PublicKey,
                pinnedTrust.ed25519PublicKey,
            )
            assertContentEquals(
                spoofedInnerSenderIdentity.x25519PublicKey,
                pinnedTrust.x25519PublicKey,
            )
            assertTrue(
                diagnostics.any {
                    it.first == DiagnosticCode.TRUST_ESTABLISHED && it.second == "trust.pin"
                }
            )
            assertTrue(
                diagnostics.any {
                    it.first == DiagnosticCode.DELIVERY_SUCCEEDED &&
                        it.second == "transport.data.deliver"
                }
            )
            assertTrue(hopFailures.isEmpty())
            println(
                "CONFIRM H4 outerOrigin=${deliveredMessage.originPeerId.value} pinnedInnerSender=${pinnedTrust.peerIdValue} payload=${deliveredMessage.payload.decodeToString()}"
            )
        }
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
}
