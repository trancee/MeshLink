package ch.trancee.meshlink.internal

import ch.trancee.meshlink.NodeId
import ch.trancee.meshlink.crypto.sodium.SodiumCryptoProvider
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The global half-open bound (spec "Handshake limits", D-20), which the link budget keeps links from
 * reaching, so only this test shows it holds; and the certificates a verified payload proves.
 */
class HandshakesTest {
    @Test
    fun theNinthHalfOpenHandshakeBreaksTheGlobalBoundOnEitherSideUntilOneFinishes() {
        // Arrange
        val crypto = SodiumCryptoProvider
        val credentials =
            LinkCredentials.issue(crypto, Fixed, crypto.ed25519KeyPair(), WireProfile.CLASSICAL, ByteArray(DIGEST))
        val handshakes = Handshakes(crypto, Fixed, credentials, null, MutableSharedFlow())
        val admitted = List(HALF_OPEN_GLOBAL) { handshakes.admit(Handle()) }

        // Act
        val responder = handshakes.admit(Handle())
        val initiator = handshakes.link(Handle(), VERSIONS).start(VERSION)
        handshakes.finish()
        val freed = handshakes.link(Handle(), VERSIONS).start(VERSION)

        // Assert
        assertEquals(List(HALF_OPEN_GLOBAL) { null }, admitted)
        assertEquals(HandshakeLimit.GLOBAL, responder)
        assertEquals(HandshakeStep.Dropped, initiator)
        assertIs<HandshakeStep.Send>(freed)
    }

    @Test
    fun aVerifiedPayloadPassesOnThePeersCertificateAndARefusedOneNothing() {
        // Arrange
        val crypto = SodiumCryptoProvider
        val issue = {
            LinkCredentials.issue(crypto, Fixed, crypto.ed25519KeyPair(), WireProfile.CLASSICAL, ByteArray(DIGEST))
        }
        val peer = issue()
        val own = issue()
        val certified = mutableListOf<Pair<NodeId, IdentityCertificate>>()
        val handshakes =
            Handshakes(crypto, Fixed, own, null, MutableSharedFlow()) { id, cert -> certified += id to cert }
        val payload = HandshakePayload.of(peer.certificate.encode(), null).encode()

        // Act
        val refused = handshakes.verify(payload, own.static.publicKey)
        val verified = handshakes.verify(payload, peer.static.publicKey)

        // Assert
        assertEquals(null, refused)
        val nodeId = NodeId(Derivations(crypto).nodeId(peer.certificate.ed25519Key))
        assertEquals(nodeId, verified)
        assertEquals(listOf(nodeId), certified.map { it.first })
        assertContentEquals(peer.certificate.encode(), certified.single().second.encode())
    }

    private class Handle : PeerHandle

    private object Fixed : Clock {
        override fun monotonicMillis(): Long = 0

        override fun wallClockMillis(): Long = START_MILLIS
    }

    private companion object {
        const val HALF_OPEN_GLOBAL = 8
        const val DIGEST = 32
        const val VERSION = 1
        val VERSIONS = listOf(VERSION)
        const val START_MILLIS = 1_767_225_600_000
    }
}
