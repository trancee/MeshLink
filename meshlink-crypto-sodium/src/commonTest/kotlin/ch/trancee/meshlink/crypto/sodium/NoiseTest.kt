package ch.trancee.meshlink.crypto.sodium

import ch.trancee.meshlink.internal.CipherState
import ch.trancee.meshlink.internal.CryptoProvider
import ch.trancee.meshlink.internal.CryptoResult
import ch.trancee.meshlink.internal.KemEncapsulation
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

    private fun bytes(
        random: SeededRandom,
        count: Int,
    ): ByteArray = ByteArray(count) { random.nextInt(0, BYTE_VALUES).toByte() }

    private fun keyPair(random: SeededRandom): KeyPair = crypto.x25519KeyPair(bytes(random, KEY_LENGTH))

    /**
     * Both sides of [pattern]; ephemerals and KEM inputs come from [seed] if it is not `null`, and
     * from the provider otherwise. An `IK` initiator knows [knownResponder], by default the
     * responder's static key.
     */
    private fun sides(
        pattern: NoisePattern,
        seed: Long? = 1,
        initiatorPsk: ByteArray = psk,
        initiatorKeys: (SeededRandom?) -> KeyPair? = { it?.let(::keyPair) },
        responderStatic: KeyPair? = null,
        provider: CryptoProvider = crypto,
        knownResponder: ByteArray? = null,
    ): Pair<NoiseHandshake, NoiseHandshake> {
        val random = seed?.let(::SeededRandom)
        val statics = SeededRandom(0)
        val initiatorStatic = keyPair(statics)
        val responderKeyPair = responderStatic ?: keyPair(statics)
        val usedPsk = pattern in PSK_PATTERNS
        val initiator =
            NoiseKeys(
                initiatorStatic,
                initiatorPsk.takeIf { usedPsk },
                initiatorKeys(random),
                kemKeyPair = random?.let { crypto.mlKem768KeyPair(bytes(it, KEM_SEED_LENGTH)) },
                remoteStatic = (knownResponder ?: responderKeyPair.publicKey).takeIf { pattern in IK_PATTERNS },
            )
        val responder =
            NoiseKeys(
                responderKeyPair,
                psk.takeIf { usedPsk },
                random?.let(::keyPair),
                encapsulationSeed = random?.let { bytes(it, ENCAPSULATION_SEED_LENGTH) },
            )
        return NoiseHandshake(provider, pattern, NoiseRole.INITIATOR, prologue, initiator) to
            NoiseHandshake(provider, pattern, NoiseRole.RESPONDER, prologue, responder)
    }

    /** The handshake messages, with [tamper] applied to each before the reader sees it. */
    private fun handshake(
        initiator: NoiseHandshake,
        responder: NoiseHandshake,
        tamper: (Int, ByteArray) -> ByteArray = { _, message -> message },
    ): List<ByteArray> =
        buildList {
            while (!(initiator.isComplete && responder.isComplete)) {
                val (writer, reader) = if (size % 2 == 0) initiator to responder else responder to initiator
                val message = tamper(size, writer.writeMessage(payloads[size]).ok())
                assertContentEquals(payloads[size], reader.readMessage(message).ok())
                add(message)
            }
        }

    /** The first failure of a handshake whose messages pass through [tamper], with both sides then refusing. */
    private fun failure(
        pattern: NoisePattern,
        sides: Pair<NoiseHandshake, NoiseHandshake> = sides(pattern),
        tamper: (Int, ByteArray) -> ByteArray,
    ): NoiseFailure {
        val (initiator, responder) = sides
        var index = 0
        while (!(initiator.isComplete && responder.isComplete)) {
            val (writer, reader) = if (index % 2 == 0) initiator to responder else responder to initiator
            val written = writer.writeMessage(payloads[index])
            if (written is NoiseResult.Failed) return written.failure.also { writer.assertClosed() }
            val read = reader.readMessage(tamper(index, written.ok()))
            if (read is NoiseResult.Failed) return read.failure.also { reader.assertClosed() }
            index++
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
            val statics = SeededRandom(0)
            assertContentEquals(keyPair(statics).publicKey, receiving.remoteStatic)
            assertContentEquals(keyPair(statics).publicKey, sending.remoteStatic)
            assertEquals(messages.map { it.hex() }, repeated.map { it.hex() })
            assertEquals(LENGTHS[pattern], messages.map { it.size }, pattern.protocolName)
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
    fun freshEphemeralsAndKemInputsComeFromTheProvider() {
        listOf(NoisePattern.XX, NoisePattern.XX_HFS).forEach { pattern ->
            val (initiator, responder) = sides(pattern, seed = null)
            handshake(initiator, responder)
            val (one, two) = sides(pattern, seed = null)
            handshake(one, two)

            val hash = initiator.split().ok().handshakeHash
            assertFalse(hash.contentEquals(one.split().ok().handshakeHash))
            assertContentEquals(hash, responder.split().ok().handshakeHash)
        }
    }

    /**
     * Measured sizes with the 1,357 B post-quantum certificate payload of `handshake-hfs.json`.
     * Message 1 is `e` (32) and `e1` (the 1,184 B KEM key), plus a tag on each of `e1` and the empty
     * payload once the psk pattern mixes `e` into the key. Message 2 is `e`, `ekem1` (1,088 + 16),
     * `s` (32 + 16) and the payload (+ 16); message 3 is `s` and the payload.
     */
    @Test
    fun postQuantumMessagesFitTheHandshakeLimit() {
        val certificatePayload = ByteArray(PQ_PAYLOAD_LENGTH)
        val expected =
            mapOf(
                NoisePattern.XX_HFS to listOf(1_216, 2_557, 1_421),
                NoisePattern.XX_HFS_PSK3 to listOf(1_248, 2_557, 1_421),
            )
        expected.forEach { (pattern, lengths) ->
            val (initiator, responder) = sides(pattern)
            val messages =
                listOf(ByteArray(0), certificatePayload, certificatePayload).mapIndexed { index, payload ->
                    val (writer, reader) = if (index % 2 == 0) initiator to responder else responder to initiator
                    writer.writeMessage(payload).ok().also { reader.readMessage(it).ok() }
                }

            assertEquals(lengths, messages.map { it.size }, pattern.protocolName)
            assertTrue(messages.all { it.size <= HANDSHAKE_MESSAGE_MAX })
        }
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
        PSK_PATTERNS.forEach { pattern ->
            val sides = sides(pattern, initiatorPsk = psk.flipped(0))
            val (initiator, responder) = sides

            assertEquals(NoiseFailure.AUTHENTICATION_FAILED, failure(pattern, sides) { _, bytes -> bytes })
            assertFalse(responder.isComplete)
            assertTrue(initiator.isComplete)
        }
    }

    @Test
    fun truncatedAndExtendedMessagesFail() {
        NoisePattern.entries.forEach { pattern ->
            repeat(LENGTHS.getValue(pattern).size) { target ->
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

    /** The first failure of an `XXhfs` handshake over [counting] whose messages pass through [tamper]. */
    private fun hfsFailure(
        counting: CountingKem,
        tamper: (Int, ByteArray) -> ByteArray,
    ): NoiseFailure = failure(NoisePattern.XX_HFS, sides(NoisePattern.XX_HFS, provider = counting), tamper)

    @Test
    fun kemFieldsTooShortFailBeforeAnyKemCall() {
        val counting = CountingKem(crypto)
        val shortKey = hfsFailure(counting, at(0) { it.copyOf(KEM_KEY_END - 1) })
        assertEquals(NoiseFailure.MESSAGE_TOO_SHORT, shortKey)
        assertEquals(0, counting.encapsulations)

        val shortCiphertext =
            hfsFailure(counting, at(1) { it.copyOf(KEM_CIPHERTEXT_END - 1) })
        assertEquals(NoiseFailure.MESSAGE_TOO_SHORT, shortCiphertext)
        assertEquals(0, counting.decapsulations)
    }

    @Test
    fun aChangedOrResizedKemCiphertextFailsBeforeDecapsulation() {
        val changes =
            listOf<(ByteArray) -> ByteArray>(
                { it.flipped(KEY_LENGTH) },
                { it.flipped(KEM_CIPHERTEXT_END - 1) },
                { it.copyOf(KEY_LENGTH + 1) + it.copyOfRange(KEY_LENGTH, it.size) },
                { it.copyOf(KEY_LENGTH) + it.copyOfRange(KEY_LENGTH + 1, it.size) },
            )
        changes.forEach { change ->
            val counting = CountingKem(crypto)
            val failure = hfsFailure(counting, at(1, change))

            assertEquals(NoiseFailure.AUTHENTICATION_FAILED, failure)
            assertEquals(1, counting.encapsulations)
            assertEquals(0, counting.decapsulations)
        }
    }

    @Test
    fun aKemKeyFailingTheModulusCheckFailsTheResponder() {
        // Coefficient 0xFFF exceeds q = 3329, so FIPS 203's modulus check refuses the key.
        val invalid =
            failure(
                NoisePattern.XX_HFS,
                tamper =
                    at(0) {
                        it.copyOf().also { m ->
                            m[KEY_LENGTH] = -1
                            m[KEY_LENGTH + 1] = -1
                        }
                    },
            )
        assertEquals(NoiseFailure.INVALID_PUBLIC_KEY, invalid)
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
    fun anIkMessageToAnotherResponderKeyFailsItsFirstRead() {
        IK_PATTERNS.forEach { pattern ->
            val other = keyPair(SeededRandom(2)).publicKey
            val sides = sides(pattern, knownResponder = other)

            assertEquals(NoiseFailure.AUTHENTICATION_FAILED, failure(pattern, sides) { _, bytes -> bytes })
            assertFalse(sides.second.isComplete)
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
            NoiseKeys(static, encapsulationSeed = ByteArray(ENCAPSULATION_SEED_LENGTH + 1))
        }
        assertFailsWith<IllegalArgumentException> {
            NoiseHandshake(crypto, NoisePattern.XX, NoiseRole.INITIATOR, prologue, NoiseKeys(static, psk))
        }
        assertFailsWith<IllegalArgumentException> {
            NoiseHandshake(crypto, NoisePattern.XX_PSK3, NoiseRole.INITIATOR, prologue, NoiseKeys(static))
        }
        val known = NoiseKeys(static, remoteStatic = static.publicKey)
        listOf(
            Triple(NoisePattern.IK, NoiseRole.INITIATOR, NoiseKeys(static)),
            Triple(NoisePattern.IK, NoiseRole.RESPONDER, known),
            Triple(NoisePattern.XX, NoiseRole.INITIATOR, known),
        ).forEach { (pattern, role, keys) ->
            assertFailsWith<IllegalArgumentException> { NoiseHandshake(crypto, pattern, role, prologue, keys) }
        }
    }

    private companion object {
        const val KEY_LENGTH = 32
        const val HASH_LENGTH = 32
        const val PSK_LENGTH = 32
        const val NONCE_LENGTH = 12
        const val ZERO_PREFIX = 4
        const val BYTE_VALUES = 256
        const val MESSAGE_LENGTH = 64
        const val STATIC_END = 32 + 48
        const val KEM_SEED_LENGTH = 64
        const val ENCAPSULATION_SEED_LENGTH = 32
        const val KEM_KEY_END = 32 + 1_184
        const val KEM_CIPHERTEXT_END = 32 + 1_088 + 16
        const val PQ_PAYLOAD_LENGTH = 1_357
        const val HANDSHAKE_MESSAGE_MAX = 4_096
        val PSK_PATTERNS = setOf(NoisePattern.XX_PSK3, NoisePattern.XX_HFS_PSK3)
        val IK_PATTERNS = setOf(NoisePattern.IK, NoisePattern.IK_HFS)
        private val CLASSICAL_2_3 = listOf(32 + 48 + 3 + 16, 48 + 5 + 16)
        private val HFS_2_3 = listOf(32 + 1_088 + 16 + 48 + 3 + 16, 48 + 5 + 16)
        val LENGTHS =
            mapOf(
                NoisePattern.XX to listOf(32 + 3) + CLASSICAL_2_3,
                NoisePattern.XX_PSK3 to listOf(32 + 3 + 16) + CLASSICAL_2_3,
                NoisePattern.XX_HFS to listOf(32 + 1_184 + 3) + HFS_2_3,
                NoisePattern.XX_HFS_PSK3 to listOf(32 + 1_184 + 16 + 3 + 16) + HFS_2_3,
                NoisePattern.IK to listOf(32 + 48 + 3 + 16, 32 + 3 + 16),
                NoisePattern.IK_HFS to listOf(32 + 1_184 + 16 + 48 + 3 + 16, 32 + 1_088 + 16 + 3 + 16),
            )
    }
}

/** [crypto] counting its ML-KEM encapsulations and decapsulations. */
private class CountingKem(
    private val crypto: CryptoProvider,
) : CryptoProvider by crypto {
    var encapsulations = 0
    var decapsulations = 0

    override fun mlKem768Encapsulate(publicKey: ByteArray): CryptoResult<KemEncapsulation> =
        crypto.mlKem768Encapsulate(publicKey).also { encapsulations++ }

    override fun mlKem768Encapsulate(
        publicKey: ByteArray,
        seed: ByteArray,
    ): CryptoResult<KemEncapsulation> = crypto.mlKem768Encapsulate(publicKey, seed).also { encapsulations++ }

    override fun mlKem768Decapsulate(
        secretKey: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray = crypto.mlKem768Decapsulate(secretKey, ciphertext).also { decapsulations++ }
}

private fun <T> NoiseResult<T>.ok(): T = assertIs<NoiseResult.Ok<T>>(this).value

private fun NoiseResult<*>.assertFailed(failure: NoiseFailure) {
    assertEquals(failure, assertIs<NoiseResult.Failed>(this).failure)
}
