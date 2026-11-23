package ch.trancee.meshlink.crypto.sodium

import ch.trancee.meshlink.internal.CryptoFailure
import ch.trancee.meshlink.internal.Hpke
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class HpkeTest {
    private val hpke = Hpke(SodiumCryptoProvider)
    private val recipient = SodiumCryptoProvider.xWingKeyPair(ByteArray(SEED_LENGTH) { 7 })
    private val info = "info".encodeToByteArray()
    private val plaintext = ByteArray(PLAINTEXT_LENGTH) { it.toByte() }

    @Test
    fun aFreshSealAddsEncAndTagAndOpensToItsPlaintext() {
        val payload = hpke.seal(recipient.publicKey, info, plaintext).ok()

        assertEquals(PLAINTEXT_LENGTH + Hpke.OVERHEAD, payload.size)
        assertContentEquals(plaintext, hpke.open(recipient.secretKey, info, payload).ok())
    }

    @Test
    fun twoFreshSealsOfOnePlaintextDiffer() {
        val first = hpke.seal(recipient.publicKey, info, plaintext).ok()

        val second = hpke.seal(recipient.publicKey, info, plaintext).ok()

        assertFalse(first.contentEquals(second))
    }

    @Test
    fun anEmptyPlaintextSealsToExactlyEncAndTagAndOpens() {
        val payload = hpke.seal(recipient.publicKey, info, ByteArray(0)).ok()

        val opened = hpke.open(recipient.secretKey, info, payload).ok()

        assertEquals(Hpke.OVERHEAD, payload.size)
        assertContentEquals(ByteArray(0), opened)
    }

    @Test
    fun aPayloadShorterThanEncAndTagDoesNotOpen() {
        val payload = hpke.seal(recipient.publicKey, info, ByteArray(0)).ok()

        val opened = hpke.open(recipient.secretKey, info, payload.copyOf(payload.size - 1))

        opened.assertFailed(CryptoFailure.AUTHENTICATION_FAILED)
    }

    @Test
    fun aLowOrderX25519PartInTheRecipientKeyIsRefused() {
        val publicKey = recipient.publicKey.copyOf().also { it.fill(0, ML_KEM_PUBLIC_KEY_LENGTH, it.size) }

        hpke.seal(publicKey, info, plaintext).assertFailed(CryptoFailure.INVALID_PUBLIC_KEY)
    }

    @Test
    fun aLowOrderX25519PartInEncIsRefused() {
        val payload = hpke.seal(recipient.publicKey, info, plaintext).ok()
        payload.fill(0, ML_KEM_CIPHERTEXT_LENGTH, Hpke.ENC_LENGTH)

        hpke.open(recipient.secretKey, info, payload).assertFailed(CryptoFailure.INVALID_CIPHERTEXT)
    }

    @Test
    fun aFailedOpenLeavesTheSequenceWhereItWas() {
        val sender = hpke.sender(recipient.publicKey, info, ByteArray(ENCAPSULATION_SEED_LENGTH) { 3 }).ok()
        val first = sender.context.seal(ByteArray(0), plaintext)
        val second = sender.context.seal(ByteArray(0), plaintext)
        val receiver = hpke.receiver(recipient.secretKey, sender.enc, info).ok()
        receiver.open(ByteArray(0), second).assertFailed(CryptoFailure.AUTHENTICATION_FAILED)

        val opened = listOf(first, second).map { receiver.open(ByteArray(0), it).ok() }

        opened.forEach { assertContentEquals(plaintext, it) }
    }

    @Test
    fun aRefusedRecipientKeyGivesNoSender() {
        val publicKey = recipient.publicKey.copyOf().also { it.fill(0, ML_KEM_PUBLIC_KEY_LENGTH, it.size) }

        val sender = hpke.sender(publicKey, info, ByteArray(ENCAPSULATION_SEED_LENGTH))

        sender.assertFailed(CryptoFailure.INVALID_PUBLIC_KEY)
    }

    private companion object {
        const val SEED_LENGTH = 32
        const val ENCAPSULATION_SEED_LENGTH = 64
        const val PLAINTEXT_LENGTH = 64
        const val ML_KEM_PUBLIC_KEY_LENGTH = 1184
        const val ML_KEM_CIPHERTEXT_LENGTH = 1088
    }
}
