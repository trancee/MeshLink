package ch.trancee.meshlink.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NoiseXXHandshakeManagerTest {
    private val provider = JvmCryptoProvider()

    @Test
    fun `noise xx handshake derives matching transport keys`() {
        // Arrange
        val initiatorIdentity = NoiseIdentity.generate(provider)
        val responderIdentity = NoiseIdentity.generate(provider)
        val initiator = NoiseXXHandshakeManager(provider)
        val responder = NoiseXXHandshakeManager(provider)

        // Act
        val message1 = initiator.createMessage1(initiatorIdentity)
        val message2 = responder.processMessage1AndCreateMessage2(responderIdentity, message1)
        val initiatorResult = initiator.processMessage2AndCreateMessage3(initiatorIdentity, message2)
        val responderResult = responder.processMessage3(responderIdentity, initiatorResult.message3)

        // Assert
        assertContentEquals(initiatorResult.sendKey, responderResult.receiveKey)
        assertContentEquals(initiatorResult.receiveKey, responderResult.sendKey)
        assertEquals(responderIdentity.x25519KeyPair.publicKey.toList(), initiatorResult.remoteStaticPublicKey.toList())
        assertEquals(initiatorIdentity.x25519KeyPair.publicKey.toList(), responderResult.remoteStaticPublicKey.toList())
    }

    @Test
    fun `tampered message 2 fails closed`() {
        // Arrange
        val initiatorIdentity = NoiseIdentity.generate(provider)
        val responderIdentity = NoiseIdentity.generate(provider)
        val initiator = NoiseXXHandshakeManager(provider)
        val responder = NoiseXXHandshakeManager(provider)
        val message1 = initiator.createMessage1(initiatorIdentity)
        val message2 = responder.processMessage1AndCreateMessage2(responderIdentity, message1)
        val tamperedMessage2 = message2.copyOf().also { bytes ->
            val lastIndex = bytes.lastIndex
            bytes[lastIndex] = (bytes[lastIndex].toInt() xor 0x01).toByte()
        }

        // Act / Assert
        assertFailsWith<Exception> {
            initiator.processMessage2AndCreateMessage3(initiatorIdentity, tamperedMessage2)
        }
    }
}
