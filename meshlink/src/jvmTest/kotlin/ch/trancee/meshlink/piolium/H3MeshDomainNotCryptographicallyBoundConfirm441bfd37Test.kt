package ch.trancee.meshlink.piolium

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.crypto.JvmCryptoProvider
import ch.trancee.meshlink.crypto.MessageSealer
import ch.trancee.meshlink.crypto.NoiseIdentity
import ch.trancee.meshlink.crypto.NoiseXXHandshakeManager
import ch.trancee.meshlink.identity.LocalIdentity
import ch.trancee.meshlink.transport.BleDiscoveryContract
import ch.trancee.meshlink.trust.TrustPublicKeys
import ch.trancee.meshlink.trust.TrustRecord
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Confirm H3: appId mesh-domain isolation is enforced only as a 16-bit discovery hint. */
class H3MeshDomainNotCryptographicallyBoundConfirm441bfd37Test {
    @Test
    fun test_confirm_mesh_domain_not_cryptographically_bound_441bfd37() {
        // Arrange
        val targetAppId = "piolium.mesh.target"
        val collidingAppId = findCollisionFor(targetAppId)
        val targetHash = BleDiscoveryContract.computeMeshHash(targetAppId)
        val collidingHash = BleDiscoveryContract.computeMeshHash(collidingAppId)
        val provider = JvmCryptoProvider()
        val initiatorNoiseIdentity = NoiseIdentity.generate(provider)
        val responderNoiseIdentity = NoiseIdentity.generate(provider)
        val initiator = NoiseXXHandshakeManager(provider)
        val responder = NoiseXXHandshakeManager(provider)
        val initiatorIdentity =
            LocalIdentity.fromNoiseIdentity(
                noiseIdentity = initiatorNoiseIdentity,
                provider = provider,
                peerId = PeerId("initiator-peer"),
            )
        val responderIdentity =
            LocalIdentity.fromNoiseIdentity(
                noiseIdentity = responderNoiseIdentity,
                provider = provider,
                peerId = PeerId("responder-peer"),
            )
        val plaintext = "cross-domain payload".encodeToByteArray()

        // Act
        val message1 = initiator.createMessage1()
        val message2 = responder.processMessage1AndCreateMessage2(responderNoiseIdentity, message1)
        val initiatorResult =
            initiator.processMessage2AndCreateMessage3(initiatorNoiseIdentity, message2)
        val responderResult = responder.processMessage3(initiatorResult.message3)
        val sealedPayload =
            MessageSealer.seal(
                plaintext = plaintext,
                senderIdentity = initiatorIdentity,
                recipientTrust = trustRecordFor(responderIdentity),
            )
        val openedPayload =
            MessageSealer.open(
                sealedPayload = sealedPayload,
                recipientIdentity = responderIdentity,
                senderTrust = trustRecordFor(initiatorIdentity),
            )

        // Assert
        assertNotEquals(targetAppId, collidingAppId)
        assertEquals(
            targetHash,
            collidingHash,
            "Different appId values should collide in the 16-bit discovery hint",
        )
        assertContentEquals(initiatorResult.sendKey, responderResult.receiveKey)
        assertContentEquals(initiatorResult.receiveKey, responderResult.sendKey)
        assertContentEquals(plaintext, openedPayload)
        assertTrue(openedPayload.decodeToString().contains("cross-domain"))
        println(
            "CONFIRM H3 targetAppId=$targetAppId collidingAppId=$collidingAppId meshHash=${targetHash.toInt()} openedPayload=${openedPayload.decodeToString()}"
        )
    }

    private fun findCollisionFor(targetAppId: String): String {
        val targetHash = BleDiscoveryContract.computeMeshHash(targetAppId)
        for (index in 0..200_000) {
            val candidate = "piolium.mesh.collision.$index"
            if (
                candidate != targetAppId &&
                    BleDiscoveryContract.computeMeshHash(candidate) == targetHash
            ) {
                return candidate
            }
        }
        error("Failed to find a 16-bit meshHash collision within the bounded search")
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
