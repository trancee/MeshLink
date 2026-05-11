package ch.trancee.meshlink.crypto

import ch.trancee.meshlink.identity.LocalIdentity
import ch.trancee.meshlink.trust.TrustRecord
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class MessageSealerTest {
    private val provider = JvmCryptoProvider()

    @Test
    fun `message sealer round trips between trusted peers`() {
        // Arrange
        val senderIdentity =
            LocalIdentity.fromNoiseIdentity(NoiseIdentity.generate(provider), provider)
        val recipientIdentity =
            LocalIdentity.fromNoiseIdentity(NoiseIdentity.generate(provider), provider)
        val senderTrust = trustRecordFor(senderIdentity)
        val recipientTrust = trustRecordFor(recipientIdentity)
        val plaintext = "hello end to end mesh".encodeToByteArray()

        // Act
        val sealedPayload =
            MessageSealer.seal(
                plaintext = plaintext,
                senderIdentity = senderIdentity,
                recipientTrust = recipientTrust,
            )
        val decryptedPayload =
            MessageSealer.open(
                sealedPayload = sealedPayload,
                recipientIdentity = recipientIdentity,
                senderTrust = senderTrust,
            )

        // Assert
        assertFalse(
            actual = sealedPayload.decodeToString().contains("hello end to end mesh"),
            message = "Ciphertext bytes must not expose the plaintext payload",
        )
        assertContentEquals(plaintext, decryptedPayload)
    }

    @Test
    fun `message sealer fails closed for tampered payloads`() {
        // Arrange
        val senderIdentity =
            LocalIdentity.fromNoiseIdentity(NoiseIdentity.generate(provider), provider)
        val recipientIdentity =
            LocalIdentity.fromNoiseIdentity(NoiseIdentity.generate(provider), provider)
        val senderTrust = trustRecordFor(senderIdentity)
        val recipientTrust = trustRecordFor(recipientIdentity)
        val sealedPayload =
            MessageSealer.seal(
                plaintext = "tamper me".encodeToByteArray(),
                senderIdentity = senderIdentity,
                recipientTrust = recipientTrust,
            )
        val tamperedPayload =
            sealedPayload.copyOf().also { payload ->
                payload[payload.lastIndex] = (payload.last().toInt() xor 0x01).toByte()
            }

        // Act / Assert
        assertFailsWith<Exception> {
            MessageSealer.open(
                sealedPayload = tamperedPayload,
                recipientIdentity = recipientIdentity,
                senderTrust = senderTrust,
            )
        }
    }

    private fun trustRecordFor(identity: LocalIdentity): TrustRecord {
        return TrustRecord(
            peerIdValue = identity.peerId.value,
            identityFingerprint = identity.identityFingerprint,
            ed25519PublicKey = identity.ed25519PublicKey,
            x25519PublicKey = identity.x25519PublicKey,
        )
    }
}
