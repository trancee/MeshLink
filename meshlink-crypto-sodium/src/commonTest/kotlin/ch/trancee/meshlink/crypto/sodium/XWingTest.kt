package ch.trancee.meshlink.crypto.sodium

import ch.trancee.meshlink.internal.CryptoFailure
import ch.trancee.meshlink.internal.CryptoProvider
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

// draft-ietf-hpke-pq-05 A.5.1 runs X-Wing inside HPKE. Its skRm, pkRm, ikmE, enc and shared_secret
// are the raw KEM's; deriving skRm from ikmR and the HPKE key schedule belong to the HPKE layer.
class XWingTest {
    private val crypto: CryptoProvider = SodiumCryptoProvider

    @Test
    fun keyPairMatchesHpkePqA5() {
        val keyPair = crypto.xWingKeyPair(XWingVectors.secretKey)

        assertContentEquals(XWingVectors.publicKey, keyPair.publicKey)
        assertContentEquals(XWingVectors.secretKey, keyPair.secretKey)
    }

    @Test
    fun encapsulationMatchesHpkePqA5() {
        val encapsulation = crypto.xWingEncapsulate(XWingVectors.publicKey, XWingVectors.encapsulationSeed).ok()

        assertContentEquals(XWingVectors.ciphertext, encapsulation.ciphertext)
        assertContentEquals(XWingVectors.sharedSecret, encapsulation.sharedSecret)
    }

    @Test
    fun decapsulationMatchesHpkePqA5() {
        assertContentEquals(
            XWingVectors.sharedSecret,
            crypto.xWingDecapsulate(XWingVectors.secretKey, XWingVectors.ciphertext).ok(),
        )
    }

    @Test
    fun tamperedCiphertextYieldsAKeyOtherThanTheSenders() {
        val recipient = crypto.xWingKeyPair()
        val sent = crypto.xWingEncapsulate(recipient.publicKey).ok()

        assertContentEquals(sent.sharedSecret, crypto.xWingDecapsulate(recipient.secretKey, sent.ciphertext).ok())
        listOf(0, ML_KEM_CIPHERTEXT_LENGTH).forEach { index ->
            val tampered = crypto.xWingDecapsulate(recipient.secretKey, sent.ciphertext.flipped(index)).ok()
            assertFalse(sent.sharedSecret.contentEquals(tampered), "byte $index")
        }
    }

    @Test
    fun lowOrderX25519ShareIsRefused() {
        val ciphertext = XWingVectors.ciphertext.copyOf().also { it.fill(0, ML_KEM_CIPHERTEXT_LENGTH, it.size) }

        crypto.xWingDecapsulate(XWingVectors.secretKey, ciphertext).assertFailed(CryptoFailure.INVALID_CIPHERTEXT)
    }

    @Test
    fun invalidPublicKeysAreRefused() {
        val lowOrderX25519 = XWingVectors.publicKey.copyOf().also { it.fill(0, ML_KEM_PUBLIC_KEY_LENGTH, it.size) }
        val x25519Part = XWingVectors.publicKey.copyOfRange(ML_KEM_PUBLIC_KEY_LENGTH, 1216)
        val nonCanonicalMlKem = MlKem768Vectors.invalidPublicKey + x25519Part

        listOf(lowOrderX25519, nonCanonicalMlKem).forEach {
            crypto.xWingEncapsulate(it, XWingVectors.encapsulationSeed).assertFailed(CryptoFailure.INVALID_PUBLIC_KEY)
        }
    }

    @Test
    fun sameSeedsGiveSameOutput() {
        val first = crypto.xWingKeyPair(XWingVectors.secretKey)
        val again = crypto.xWingEncapsulate(first.publicKey, XWingVectors.encapsulationSeed).ok()

        assertContentEquals(XWingVectors.ciphertext, again.ciphertext)
        assertFalse(first.publicKey.contentEquals(crypto.xWingKeyPair().publicKey))
    }

    @Test
    fun wrongLengthsAreRejected() {
        assertFailsWith<IllegalArgumentException> { crypto.xWingKeyPair(ByteArray(64)) }
        assertFailsWith<IllegalArgumentException> { crypto.xWingEncapsulate(ByteArray(1215)) }
        assertFailsWith<IllegalArgumentException> { crypto.xWingEncapsulate(XWingVectors.publicKey, ByteArray(32)) }
        assertFailsWith<IllegalArgumentException> { crypto.xWingDecapsulate(ByteArray(31), XWingVectors.ciphertext) }
        assertFailsWith<IllegalArgumentException> { crypto.xWingDecapsulate(XWingVectors.secretKey, ByteArray(1119)) }
    }

    private companion object {
        const val ML_KEM_PUBLIC_KEY_LENGTH = 1184
        const val ML_KEM_CIPHERTEXT_LENGTH = 1088
    }
}
