package ch.trancee.meshlink.piolium

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.crypto.JvmCryptoProvider
import ch.trancee.meshlink.crypto.NoiseIdentity
import ch.trancee.meshlink.crypto.NoiseXXHandshakeManager
import ch.trancee.meshlink.identity.LocalIdentity
import ch.trancee.meshlink.transport.BleDiscoveryContract
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/**
 * Regression for H3: the mesh domain (`appId`) is now cryptographically bound into every Noise XX
 * handshake, not just used as a collision-prone 16-bit BLE discovery hint.
 * [LocalIdentity.meshDomainHash] (derived from `appId` via [LocalIdentity.computeMeshDomainHash])
 * is mixed into the handshake transcript as the Noise prologue at
 * [NoiseXXHandshakeManager.createMessage1] /
 * [NoiseXXHandshakeManager.processMessage1AndCreateMessage2] time, before any other handshake
 * material. Two peers configured with different `appId`s now derive divergent transcript hashes and
 * fail authentication (a decryption/MAC failure) when processing message 2, instead of silently
 * completing a mutually-authenticated session across mesh boundaries. Peers configured with the
 * same `appId` are unaffected and can still complete the handshake.
 */
class H3MeshDomainNotCryptographicallyBoundConfirm441bfd37Test {
    @Test
    fun test_confirm_mesh_domain_not_cryptographically_bound_441bfd37() {
        // Arrange
        val targetAppId = "piolium.mesh.target"
        val collidingAppId = findCollisionFor(targetAppId)
        val targetHash = BleDiscoveryContract.computeMeshHash(targetAppId)
        val collidingHash = BleDiscoveryContract.computeMeshHash(collidingAppId)
        val provider = JvmCryptoProvider()
        val initiatorIdentity =
            LocalIdentity.fromNoiseIdentity(
                noiseIdentity = NoiseIdentity.generate(provider),
                provider = provider,
                peerId = PeerId("initiator-peer"),
                meshDomainHash = LocalIdentity.computeMeshDomainHash(targetAppId, provider),
            )
        val responderIdentity =
            LocalIdentity.fromNoiseIdentity(
                noiseIdentity = NoiseIdentity.generate(provider),
                provider = provider,
                peerId = PeerId("responder-peer"),
                // A colliding appId shares the same 16-bit BLE discovery hint as targetAppId, but
                // must still be treated as a different mesh by the cryptographic handshake.
                meshDomainHash = LocalIdentity.computeMeshDomainHash(collidingAppId, provider),
            )
        val initiator = NoiseXXHandshakeManager(provider)
        val responder = NoiseXXHandshakeManager(provider)

        // Act
        val message1 = initiator.createMessage1(meshDomainHash = initiatorIdentity.meshDomainHash)
        val message2 =
            responder.processMessage1AndCreateMessage2(
                responderIdentity = responderIdentity.noiseIdentity,
                message1 = message1,
                meshDomainHash = responderIdentity.meshDomainHash,
            )

        // Assert
        assertNotEquals(targetAppId, collidingAppId)
        assertEquals(
            targetHash,
            collidingHash,
            "Different appId values should still collide in the 16-bit discovery hint",
        )
        assertFailsWith<Exception>(
            "A handshake between peers with different appId-derived mesh domains must fail closed"
        ) {
            initiator.processMessage2AndCreateMessage3(initiatorIdentity.noiseIdentity, message2)
        }
        println(
            "CONFIRM H3 fixed: targetAppId=$targetAppId collidingAppId=$collidingAppId " +
                "meshHash=${targetHash.toInt()} crossMeshHandshakeRejected=true"
        )
    }

    @Test
    fun `same appId derived mesh domain still completes the handshake`() {
        // Arrange
        val sharedAppId = "piolium.mesh.same-domain"
        val provider = JvmCryptoProvider()
        val meshDomainHash = LocalIdentity.computeMeshDomainHash(sharedAppId, provider)
        val initiatorIdentity =
            LocalIdentity.fromNoiseIdentity(
                noiseIdentity = NoiseIdentity.generate(provider),
                provider = provider,
                peerId = PeerId("same-domain-initiator"),
                meshDomainHash = meshDomainHash,
            )
        val responderIdentity =
            LocalIdentity.fromNoiseIdentity(
                noiseIdentity = NoiseIdentity.generate(provider),
                provider = provider,
                peerId = PeerId("same-domain-responder"),
                meshDomainHash = meshDomainHash,
            )
        val initiator = NoiseXXHandshakeManager(provider)
        val responder = NoiseXXHandshakeManager(provider)

        // Act
        val message1 = initiator.createMessage1(meshDomainHash = initiatorIdentity.meshDomainHash)
        val message2 =
            responder.processMessage1AndCreateMessage2(
                responderIdentity = responderIdentity.noiseIdentity,
                message1 = message1,
                meshDomainHash = responderIdentity.meshDomainHash,
            )
        val initiatorResult =
            initiator.processMessage2AndCreateMessage3(initiatorIdentity.noiseIdentity, message2)
        val responderResult = responder.processMessage3(initiatorResult.message3)

        // Assert
        assertContentEquals(initiatorResult.sendKey, responderResult.receiveKey)
        assertContentEquals(initiatorResult.receiveKey, responderResult.sendKey)
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
}
