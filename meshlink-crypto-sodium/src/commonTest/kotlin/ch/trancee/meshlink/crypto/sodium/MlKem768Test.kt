package ch.trancee.meshlink.crypto.sodium

import ch.trancee.meshlink.internal.CryptoFailure
import ch.trancee.meshlink.internal.CryptoProvider
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class MlKem768Test {
    private val crypto: CryptoProvider = SodiumCryptoProvider

    @Test
    fun keyPairMatchesAcvp() {
        val keyPair = crypto.mlKem768KeyPair(MlKem768Vectors.keyGenSeed)

        assertContentEquals(MlKem768Vectors.keyGenPublicKey, keyPair.publicKey)
        assertContentEquals(MlKem768Vectors.keyGenSecretKey, keyPair.secretKey)
    }

    @Test
    fun encapsulationMatchesAcvp() {
        val encapsulation =
            SodiumCryptoProvider
                .mlKem768Encapsulate(MlKem768Vectors.encapsulationPublicKey, MlKem768Vectors.encapsulationSeed)
                .ok()

        assertContentEquals(MlKem768Vectors.encapsulationCiphertext, encapsulation.ciphertext)
        assertContentEquals(MlKem768Vectors.encapsulationSharedSecret, encapsulation.sharedSecret)
    }

    @Test
    fun decapsulationMatchesAcvp() {
        assertContentEquals(
            MlKem768Vectors.decapsulationSharedSecret,
            crypto.mlKem768Decapsulate(
                MlKem768Vectors.decapsulationSecretKey,
                MlKem768Vectors.decapsulationCiphertext,
            ),
        )
    }

    @Test
    fun modifiedCiphertextGetsAcvpImplicitRejectionKey() {
        assertContentEquals(
            MlKem768Vectors.implicitRejectionSecret,
            crypto.mlKem768Decapsulate(MlKem768Vectors.modifiedSecretKey, MlKem768Vectors.modifiedCiphertext),
        )
    }

    @Test
    fun tamperedCiphertextYieldsAKeyOtherThanTheSenders() {
        val recipient = crypto.mlKem768KeyPair()
        val sent = crypto.mlKem768Encapsulate(recipient.publicKey).ok()

        assertContentEquals(sent.sharedSecret, crypto.mlKem768Decapsulate(recipient.secretKey, sent.ciphertext))
        assertFalse(
            sent.sharedSecret.contentEquals(
                crypto.mlKem768Decapsulate(recipient.secretKey, sent.ciphertext.flipped(0)),
            ),
        )
    }

    @Test
    fun publicKeyFailingTheModulusCheckIsRefused() {
        SodiumCryptoProvider
            .mlKem768Encapsulate(MlKem768Vectors.invalidPublicKey, MlKem768Vectors.encapsulationSeed)
            .assertFailed(CryptoFailure.INVALID_PUBLIC_KEY)
    }

    @Test
    fun sameSeedsGiveSameOutput() {
        val seed = ByteArray(64) { it.toByte() }
        val first = crypto.mlKem768KeyPair(seed)
        val second = crypto.mlKem768KeyPair(seed)
        val message = ByteArray(32) { 7 }

        assertContentEquals(first.secretKey, second.secretKey)
        assertContentEquals(
            crypto.mlKem768Encapsulate(first.publicKey, message).ok().ciphertext,
            crypto.mlKem768Encapsulate(second.publicKey, message).ok().ciphertext,
        )
        assertFalse(first.publicKey.contentEquals(crypto.mlKem768KeyPair().publicKey))
    }

    @Test
    fun wrongLengthsAreRejected() {
        val keyPair = crypto.mlKem768KeyPair(MlKem768Vectors.keyGenSeed)

        assertFailsWith<IllegalArgumentException> { crypto.mlKem768KeyPair(ByteArray(32)) }
        assertFailsWith<IllegalArgumentException> { crypto.mlKem768Encapsulate(ByteArray(1183)) }
        assertFailsWith<IllegalArgumentException> { crypto.mlKem768Encapsulate(keyPair.publicKey, ByteArray(31)) }
        assertFailsWith<IllegalArgumentException> { crypto.mlKem768Decapsulate(ByteArray(2399), ByteArray(1088)) }
        assertFailsWith<IllegalArgumentException> { crypto.mlKem768Decapsulate(keyPair.secretKey, ByteArray(1087)) }
        assertEquals(1184, keyPair.publicKey.size)
    }
}
