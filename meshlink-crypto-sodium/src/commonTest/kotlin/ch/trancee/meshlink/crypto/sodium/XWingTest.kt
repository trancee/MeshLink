package ch.trancee.meshlink.crypto.sodium

import ch.trancee.meshlink.internal.CryptoFailure
import ch.trancee.meshlink.internal.CryptoProvider
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

// The draft-ietf-hpke-pq-05 Appendix A X-Wing vectors run through the vector runner
// (third_party/vectors/hpke-pq); these tests cover what a vector can't.
class XWingTest {
    private val crypto: CryptoProvider = SodiumCryptoProvider
    private val recipient = crypto.xWingKeyPair()
    private val sent = crypto.xWingEncapsulate(recipient.publicKey).ok()

    @Test
    fun tamperedCiphertextYieldsAKeyOtherThanTheSenders() {
        assertContentEquals(sent.sharedSecret, crypto.xWingDecapsulate(recipient.secretKey, sent.ciphertext).ok())
        listOf(0, ML_KEM_CIPHERTEXT_LENGTH).forEach { index ->
            val tampered = crypto.xWingDecapsulate(recipient.secretKey, sent.ciphertext.flipped(index)).ok()
            assertFalse(sent.sharedSecret.contentEquals(tampered), "byte $index")
        }
    }

    @Test
    fun lowOrderX25519ShareIsRefused() {
        val ciphertext = sent.ciphertext.copyOf().also { it.fill(0, ML_KEM_CIPHERTEXT_LENGTH, it.size) }

        crypto.xWingDecapsulate(recipient.secretKey, ciphertext).assertFailed(CryptoFailure.INVALID_CIPHERTEXT)
    }

    @Test
    fun invalidPublicKeysAreRefused() {
        val lowOrderX25519 = recipient.publicKey.copyOf().also { it.fill(0, ML_KEM_PUBLIC_KEY_LENGTH, it.size) }
        val x25519Part = recipient.publicKey.copyOfRange(ML_KEM_PUBLIC_KEY_LENGTH, PUBLIC_KEY_LENGTH)
        val nonCanonicalMlKem = MlKem768Vectors.invalidPublicKey + x25519Part

        listOf(lowOrderX25519, nonCanonicalMlKem).forEach {
            crypto.xWingEncapsulate(it, ByteArray(SEED_LENGTH)).assertFailed(CryptoFailure.INVALID_PUBLIC_KEY)
        }
    }

    @Test
    fun sameSeedsGiveSameOutput() {
        val first = crypto.xWingKeyPair(ByteArray(KEY_SEED_LENGTH) { it.toByte() })
        val second = crypto.xWingKeyPair(ByteArray(KEY_SEED_LENGTH) { it.toByte() })
        val seed = ByteArray(SEED_LENGTH) { 7 }

        assertContentEquals(first.publicKey, second.publicKey)
        assertContentEquals(
            crypto.xWingEncapsulate(first.publicKey, seed).ok().ciphertext,
            crypto.xWingEncapsulate(second.publicKey, seed).ok().ciphertext,
        )
        assertFalse(first.publicKey.contentEquals(recipient.publicKey))
    }

    @Test
    fun wrongLengthsAreRejected() {
        assertFailsWith<IllegalArgumentException> { crypto.xWingKeyPair(ByteArray(64)) }
        assertFailsWith<IllegalArgumentException> { crypto.xWingEncapsulate(ByteArray(PUBLIC_KEY_LENGTH - 1)) }
        assertFailsWith<IllegalArgumentException> { crypto.xWingEncapsulate(recipient.publicKey, ByteArray(32)) }
        assertFailsWith<IllegalArgumentException> { crypto.xWingDecapsulate(ByteArray(31), sent.ciphertext) }
        assertFailsWith<IllegalArgumentException> { crypto.xWingDecapsulate(recipient.secretKey, ByteArray(1119)) }
    }

    private companion object {
        const val ML_KEM_PUBLIC_KEY_LENGTH = 1184
        const val ML_KEM_CIPHERTEXT_LENGTH = 1088
        const val PUBLIC_KEY_LENGTH = 1216
        const val KEY_SEED_LENGTH = 32
        const val SEED_LENGTH = 64
    }
}
