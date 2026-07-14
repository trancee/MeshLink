package ch.trancee.meshlink.piolium

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.InboundMessage
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.crypto.MessageSealer
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.engine.assembly.MeshEngineRuntimeSurface
import ch.trancee.meshlink.engine.transfer.MeshEngineMessageDeliverySupport
import ch.trancee.meshlink.engine.transport.DirectMessageEnvelope
import ch.trancee.meshlink.engine.trust.MeshEngineTrustSupport
import ch.trancee.meshlink.identity.LocalIdentity
import ch.trancee.meshlink.identity.fromAppId
import ch.trancee.meshlink.test.InMemorySecureStorage
import ch.trancee.meshlink.trust.TofuTrustStore
import ch.trancee.meshlink.trust.TrustPublicKeys
import ch.trancee.meshlink.trust.TrustRecord
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Regression for H4: self-asserted inner envelope sender metadata can no longer seed TOFU trust.
 * [MeshEngineMessageDeliverySupport] now verifies inner-envelope sender claims against trust
 * already pinned via an authenticated channel (e.g. an end-to-end Noise handshake) using
 * [MeshEngineTrustSupport.verifyEstablishedTrust], which refuses to pin trust on first contact. An
 * envelope claiming to be from a peer with no existing trust record is rejected and never
 * delivered.
 */
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

            // Assert
            assertNull(
                pinnedTrust,
                "A self-asserted inner sender identity with no prior authenticated trust must " +
                    "never be pinned into TOFU storage",
            )
            assertNull(outerOriginTrust, "No trust should be recorded for the outer origin peer ID")
            assertTrue(
                mutableMessages.replayCache.isEmpty(),
                "The spoofed envelope must not be delivered to the application",
            )
            assertTrue(
                diagnostics.any {
                    it.first == DiagnosticCode.TRUST_FAILURE &&
                        it.second == "trust.verify.untrusted"
                },
                "A trust-verification failure diagnostic should be emitted for the unknown sender",
            )
            assertTrue(hopFailures.isEmpty())
            println(
                "CONFIRM H4 fixed: spoofedSender=${spoofedInnerSenderIdentity.peerId.value} " +
                    "pinnedTrust=$pinnedTrust delivered=${mutableMessages.replayCache.isNotEmpty()}"
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
