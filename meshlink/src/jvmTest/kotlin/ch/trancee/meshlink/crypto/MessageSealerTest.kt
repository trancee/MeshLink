package ch.trancee.meshlink.crypto

import ch.trancee.meshlink.api.MeshLinkException
import ch.trancee.meshlink.identity.LocalIdentity
import ch.trancee.meshlink.trust.TrustPublicKeys
import ch.trancee.meshlink.trust.TrustRecord
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    @Test
    fun `message sealer rejects an all-zero X25519 shared secret`() {
        // Arrange: a provider whose x25519() always returns a degenerate all-zero secret,
        // regardless of the (still valid) key material fed into seal()/open(). This exercises
        // MessageSealer's single remaining guard -- the shared requireValidX25519SharedSecret()
        // helper in X25519SharedSecretValidation.kt -- confirming it still rejects the
        // low-order-point/degenerate case now that MessageSealer's own duplicate,
        // short-circuiting check has been removed in favor of that shared, constant-time check.
        val degenerateSecretProvider = AllZeroSharedSecretCryptoProvider(delegate = provider)
        val senderIdentity =
            LocalIdentity.fromNoiseIdentity(
                NoiseIdentity.generate(provider),
                degenerateSecretProvider,
            )
        val recipientIdentity =
            LocalIdentity.fromNoiseIdentity(
                NoiseIdentity.generate(provider),
                degenerateSecretProvider,
            )
        val recipientTrust = trustRecordFor(recipientIdentity)

        // Act / Assert
        val failure =
            assertFailsWith<MeshLinkException.CryptoFailure> {
                MessageSealer.seal(
                    plaintext = "never sealed".encodeToByteArray(),
                    senderIdentity = senderIdentity,
                    recipientTrust = recipientTrust,
                )
            }
        assertTrue(
            actual = failure.message.orEmpty().contains("all-zero"),
            message =
                "Expected the shared X25519SharedSecretValidation failure message, got: " +
                    failure.message,
        )
    }

    /**
     * Delegates every [CryptoProvider] operation except [x25519], which always returns an all-zero
     * [ByteArray] of the delegate's own shared-secret length -- simulating the low-order-point
     * degenerate case without needing a real low-order X25519 public key vector.
     */
    private class AllZeroSharedSecretCryptoProvider(private val delegate: CryptoProvider) :
        CryptoProvider by delegate {
        override fun x25519(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
            val realSharedSecret = delegate.x25519(privateKey, publicKey)
            return ByteArray(realSharedSecret.size)
        }
    }

    private fun trustRecordFor(identity: LocalIdentity): TrustRecord {
        return TrustRecord(
            peerIdValue = identity.peerId.value,
            identityFingerprintBytes = identity.identityFingerprintBytes,
            firstSeenAtEpochMillis = 1_000L,
            lastVerifiedAtEpochMillis = 2_000L,
            publicKeys =
                TrustPublicKeys(
                    ed25519PublicKey = identity.ed25519PublicKey,
                    x25519PublicKey = identity.x25519PublicKey,
                ),
        )
    }
}
