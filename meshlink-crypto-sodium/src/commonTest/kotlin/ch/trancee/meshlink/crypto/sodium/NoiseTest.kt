package ch.trancee.meshlink.crypto.sodium

import ch.trancee.meshlink.internal.CipherState
import ch.trancee.meshlink.internal.CryptoProvider
import ch.trancee.meshlink.internal.KeyPair
import ch.trancee.meshlink.internal.NoiseFailure
import ch.trancee.meshlink.internal.NoiseHandshake
import ch.trancee.meshlink.internal.NoiseKeys
import ch.trancee.meshlink.internal.NoisePattern
import ch.trancee.meshlink.internal.NoiseResult
import ch.trancee.meshlink.internal.NoiseRole
import ch.trancee.meshlink.internal.NoiseTransport
import ch.trancee.meshlink.internal.SeededRandom
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NoiseTest {
    private val crypto: CryptoProvider = SodiumCryptoProvider
    private val prologue = "prologue".encodeToByteArray()
    private val psk = ByteArray(PSK_LENGTH) { it.toByte() }
    private val payloads = listOf("one", "two", "three").map { it.encodeToByteArray() }

    /** A key pair whose public key is the low-order point u = 1: every X25519 result with it is zero. */
    private val lowOrder = KeyPair(ByteArray(KEY_LENGTH) { 1 }, ByteArray(KEY_LENGTH).also { it[0] = 1 })

    private fun keyPair(random: SeededRandom): KeyPair =
        crypto.x25519KeyPair(ByteArray(KEY_LENGTH) { random.nextInt(0, BYTE_VALUES).toByte() })

    /** Both sides of [pattern]; ephemerals come from [seed] if it is not `null`. */
    private fun sides(
        pattern: NoisePattern,
        seed: Long? = 1,
        initiatorPsk: ByteArray = psk,
        initiatorKeys: (SeededRandom?) -> KeyPair? = { it?.let(::keyPair) },
        responderStatic: KeyPair? = null,
    ): Pair<NoiseHandshake, NoiseHandshake> {
        val random = seed?.let(::SeededRandom)
        val statics = SeededRandom(0)
        val usedPsk = pattern == NoisePattern.XX_PSK3
        val initiator =
            NoiseKeys(keyPair(statics), initiatorPsk.takeIf { usedPsk }, initiatorKeys(random))
        val responder =
            NoiseKeys(responderStatic ?: keyPair(statics), psk.takeIf { usedPsk }, random?.let(::keyPair))
        return NoiseHandshake(crypto, pattern, NoiseRole.INITIATOR, prologue, initiator) to
            NoiseHandshake(crypto, pattern, NoiseRole.RESPONDER, prologue, responder)
    }

    /** The three handshake messages, with [tamper] applied to each before the reader sees it. */
    private fun handshake(
        initiator: NoiseHandshake,
        responder: NoiseHandshake,
        tamper: (Int, ByteArray) -> ByteArray = { _, message -> message },
    ): List<ByteArray> =
        payloads.mapIndexed { index, payload ->
            val (writer, reader) = if (index % 2 == 0) initiator to responder else responder to initiator
            val message = tamper(index, writer.writeMessage(payload).ok())
            assertContentEquals(payload, reader.readMessage(message).ok())
            message
        }

    /** The first failure of a handshake whose messages pass through [tamper], with both sides then refusing. */
    private fun failure(
        pattern: NoisePattern,
        sides: Pair<NoiseHandshake, NoiseHandshake> = sides(pattern),
        tamper: (Int, ByteArray) -> ByteArray,
    ): NoiseFailure {
        val (initiator, responder) = sides
        payloads.forEachIndexed { index, payload ->
            val (writer, reader) = if (index % 2 == 0) initiator to responder else responder to initiator
            val written = writer.writeMessage(payload)
            if (written is NoiseResult.Failed) return written.failure.also { writer.assertClosed() }
            val read = reader.readMessage(tamper(index, written.ok()))
            if (read is NoiseResult.Failed) return read.failure.also { reader.assertClosed() }
        }
        throw AssertionError("the handshake completed")
    }

    /** A tamper that applies [change] to message [target] only. */
    private fun at(
        target: Int,
        change: (ByteArray) -> ByteArray,
    ): (Int, ByteArray) -> ByteArray = { index, bytes -> if (index == target) change(bytes) else bytes }

    private fun NoiseHandshake.assertClosed() {
        assertFalse(isComplete)
        split().assertFailed(NoiseFailure.CLOSED)
        writeMessage(ByteArray(0)).assertFailed(NoiseFailure.CLOSED)
        readMessage(ByteArray(MESSAGE_LENGTH)).assertFailed(NoiseFailure.CLOSED)
    }

    private fun complete(pattern: NoisePattern): Pair<NoiseTransport, NoiseTransport> {
        val (initiator, responder) = sides(pattern)
        handshake(initiator, responder)
        return initiator.split().ok() to responder.split().ok()
    }

    @Test
    fun bothSuitesAgreeEndToEndAndRepeatForTheSameSeed() {
        NoisePattern.entries.forEach { pattern ->
            val (initiator, responder) = sides(pattern)
            val messages = handshake(initiator, responder)
            val repeated = sides(pattern).let { handshake(it.first, it.second) }
            assertTrue(initiator.isComplete && responder.isComplete)
            val (sending, receiving) = initiator.split().ok() to responder.split().ok()

            assertContentEquals(sending.handshakeHash, receiving.handshakeHash, pattern.protocolName)
            assertEquals(HASH_LENGTH, sending.handshakeHash.size)
            assertContentEquals(keyPair(SeededRandom(0)).publicKey, receiving.remoteStatic)
            assertEquals(messages.map { it.hex() }, repeated.map { it.hex() })
            assertEquals(listOf(LENGTH_1[pattern], LENGTH_2, LENGTH_3), messages.map { it.size })
            sending.exchange(receiving)
            receiving.exchange(sending)
        }
    }

    /** Messages from this side decrypt on [peer], before and after both rekey this direction. */
    private fun NoiseTransport.exchange(peer: NoiseTransport) {
        repeat(2) { round ->
            payloads.forEach { assertContentEquals(it, peer.decrypt(encrypt(it).ok()).ok()) }
            if (round == 0) {
                rekeySending().ok()
                peer.rekeyReceiving().ok()
            }
        }
    }

    @Test
    fun freshEphemeralsComeFromTheProvider() {
        val (initiator, responder) = sides(NoisePattern.XX, seed = null)
        handshake(initiator, responder)
        val (one, two) = sides(NoisePattern.XX, seed = null)
        handshake(one, two)

        val hash = initiator.split().ok().handshakeHash
        assertFalse(hash.contentEquals(one.split().ok().handshakeHash))
        assertContentEquals(hash, responder.split().ok().handshakeHash)
    }

    @Test
    fun rekeyIsEncryptionOfZerosAtTheMaximumNonce() {
        val key = ByteArray(KEY_LENGTH) { (it * 7).toByte() }
        val cipher = CipherState(crypto, key, nonce = 5u)
        val maxNonce = ByteArray(NONCE_LENGTH) { if (it >= ZERO_PREFIX) -1 else 0 }
        val rekeyed = crypto.chaCha20Poly1305Seal(key, maxNonce, ByteArray(KEY_LENGTH), ByteArray(0)).copyOf(KEY_LENGTH)
        val nonce5 = ByteArray(NONCE_LENGTH).also { it[ZERO_PREFIX] = 5 }

        cipher.rekey()

        val expected = crypto.chaCha20Poly1305Seal(rekeyed, nonce5, payloads[0], prologue)
        assertContentEquals(expected, cipher.encrypt(payloads[0], prologue).ok())
    }

    @Test
    fun theMaximumNonceIsNeverUsed() {
        val key = ByteArray(KEY_LENGTH)
        val last = CipherState(crypto, key, nonce = ULong.MAX_VALUE - 1u)
        val ciphertext = last.encrypt(payloads[0]).ok()

        last.encrypt(payloads[0]).assertFailed(NoiseFailure.NONCE_EXHAUSTED)
        assertContentEquals(payloads[0], CipherState(crypto, key, ULong.MAX_VALUE - 1u).decrypt(ciphertext).ok())
        CipherState(crypto, key, ULong.MAX_VALUE).decrypt(ciphertext).assertFailed(NoiseFailure.NONCE_EXHAUSTED)
        assertFailsWith<IllegalArgumentException> { CipherState(crypto, ByteArray(KEY_LENGTH - 1)) }
    }

    @Test
    fun aTransportClosesWhenItsSendingNonceRunsOut() {
        val key = ByteArray(KEY_LENGTH)
        val transport =
            NoiseTransport(
                ByteArray(HASH_LENGTH),
                ByteArray(KEY_LENGTH),
                CipherState(crypto, key, ULong.MAX_VALUE),
                CipherState(crypto, key),
            )

        transport.encrypt(payloads[0]).assertFailed(NoiseFailure.NONCE_EXHAUSTED)
        transport.assertClosed()
    }

    @Test
    fun aFlippedBitAnywhereInAnyHandshakeMessageFails() {
        NoisePattern.entries.forEach { pattern ->
            val lengths = handshake(sides(pattern).first, sides(pattern).second).map { it.size }
            lengths.forEachIndexed { target, length ->
                repeat(length) { position ->
                    val failure = failure(pattern, tamper = at(target) { it.flipped(position) })
                    assertTrue(
                        failure == NoiseFailure.AUTHENTICATION_FAILED || failure == NoiseFailure.INVALID_PUBLIC_KEY,
                        "${pattern.protocolName} message $target byte $position: $failure",
                    )
                }
            }
        }
    }

    @Test
    fun aWrongPskFailsTheThirdMessage() {
        val sides = sides(NoisePattern.XX_PSK3, initiatorPsk = psk.flipped(0))
        val (initiator, responder) = sides

        assertEquals(NoiseFailure.AUTHENTICATION_FAILED, failure(NoisePattern.XX_PSK3, sides) { _, bytes -> bytes })
        assertFalse(responder.isComplete)
        assertTrue(initiator.isComplete)
    }

    @Test
    fun truncatedAndExtendedMessagesFail() {
        NoisePattern.entries.forEach { pattern ->
            repeat(MESSAGE_COUNT) { target ->
                val shorter = failure(pattern, tamper = at(target) { it.copyOf(it.size - 1) })
                val longer = failure(pattern, tamper = at(target) { it.copyOf(it.size + 1) })
                assertEquals(NoiseFailure.AUTHENTICATION_FAILED, shorter, "${pattern.protocolName} $target")
                assertEquals(NoiseFailure.AUTHENTICATION_FAILED, longer, "${pattern.protocolName} $target")
            }
        }
    }

    @Test
    fun messagesTooShortForTheirKeysFail() {
        val shortEphemeral = failure(NoisePattern.XX, tamper = at(0) { it.copyOf(KEY_LENGTH - 1) })
        assertEquals(NoiseFailure.MESSAGE_TOO_SHORT, shortEphemeral)
        val shortStatic = failure(NoisePattern.XX, tamper = at(1) { it.copyOf(STATIC_END - 1) })
        assertEquals(NoiseFailure.MESSAGE_TOO_SHORT, shortStatic)
    }

    @Test
    fun messagesOutOfOrderFail() {
        val (initiator, responder) = sides(NoisePattern.XX)
        val first = initiator.writeMessage(payloads[0]).ok()

        initiator.writeMessage(payloads[1]).assertFailed(NoiseFailure.OUT_OF_ORDER)
        initiator.assertClosed()
        responder.writeMessage(payloads[0]).assertFailed(NoiseFailure.OUT_OF_ORDER)
        responder.readMessage(first).assertFailed(NoiseFailure.CLOSED)

        val (reader, _) = sides(NoisePattern.XX)
        reader.readMessage(first).assertFailed(NoiseFailure.OUT_OF_ORDER)
        val (early, _) = sides(NoisePattern.XX)
        early.split().assertFailed(NoiseFailure.OUT_OF_ORDER)
        early.assertClosed()
    }

    @Test
    fun aCompletedHandshakeAcceptsNoFurtherMessageAndSplitsOnce() {
        val (initiator, responder) = sides(NoisePattern.XX)
        handshake(initiator, responder)

        initiator.writeMessage(payloads[0]).assertFailed(NoiseFailure.OUT_OF_ORDER)
        initiator.assertClosed()
        responder.split().ok()
        responder.assertClosed()
    }

    @Test
    fun aReplayedTransportMessageClosesTheTransport() {
        NoisePattern.entries.forEach { pattern ->
            val (sending, receiving) = complete(pattern)
            val ciphertext = sending.encrypt(payloads[0]).ok()
            receiving.decrypt(ciphertext).ok()

            receiving.decrypt(ciphertext).assertFailed(NoiseFailure.AUTHENTICATION_FAILED)
            receiving.assertClosed()
            assertNotNull(sending.encrypt(payloads[1]).ok())
        }
    }

    private fun NoiseTransport.assertClosed() {
        encrypt(payloads[0]).assertFailed(NoiseFailure.CLOSED)
        decrypt(ByteArray(MESSAGE_LENGTH)).assertFailed(NoiseFailure.CLOSED)
        rekeySending().assertFailed(NoiseFailure.CLOSED)
        rekeyReceiving().assertFailed(NoiseFailure.CLOSED)
    }

    @Test
    fun aLowOrderPeerStaticFailsTheHandshake() {
        NoisePattern.entries.forEach { pattern ->
            val sides = sides(pattern, responderStatic = lowOrder)
            assertEquals(NoiseFailure.INVALID_PUBLIC_KEY, failure(pattern, sides) { _, bytes -> bytes })
        }
    }

    @Test
    fun aLowOrderPeerEphemeralFailsTheHandshake() {
        val sides = sides(NoisePattern.XX, initiatorKeys = { lowOrder })
        assertEquals(NoiseFailure.INVALID_PUBLIC_KEY, failure(NoisePattern.XX, sides) { _, bytes -> bytes })
    }

    @Test
    fun keysMatchThePattern() {
        val static = keyPair(SeededRandom(0))

        assertFailsWith<IllegalArgumentException> { NoiseKeys(static, ByteArray(PSK_LENGTH + 1)) }
        assertFailsWith<IllegalArgumentException> {
            NoiseHandshake(crypto, NoisePattern.XX, NoiseRole.INITIATOR, prologue, NoiseKeys(static, psk))
        }
        assertFailsWith<IllegalArgumentException> {
            NoiseHandshake(crypto, NoisePattern.XX_PSK3, NoiseRole.INITIATOR, prologue, NoiseKeys(static))
        }
    }

    private companion object {
        const val KEY_LENGTH = 32
        const val HASH_LENGTH = 32
        const val PSK_LENGTH = 32
        const val NONCE_LENGTH = 12
        const val ZERO_PREFIX = 4
        const val BYTE_VALUES = 256
        const val MESSAGE_COUNT = 3
        const val MESSAGE_LENGTH = 64
        const val STATIC_END = 32 + 48
        const val LENGTH_2 = 32 + 48 + 3 + 16
        const val LENGTH_3 = 48 + 5 + 16
        val LENGTH_1 = mapOf(NoisePattern.XX to 32 + 3, NoisePattern.XX_PSK3 to 32 + 3 + 16)
    }
}

private fun <T> NoiseResult<T>.ok(): T = assertIs<NoiseResult.Ok<T>>(this).value

private fun NoiseResult<*>.assertFailed(failure: NoiseFailure) {
    assertEquals(failure, assertIs<NoiseResult.Failed>(this).failure)
}
