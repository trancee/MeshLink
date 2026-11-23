package ch.trancee.meshlink.crypto.sodium

import ch.trancee.meshlink.internal.CipherState
import ch.trancee.meshlink.internal.Container
import ch.trancee.meshlink.internal.EndToEndSession
import ch.trancee.meshlink.internal.NoiseFailure
import ch.trancee.meshlink.internal.NoiseHandshake
import ch.trancee.meshlink.internal.NoiseKeys
import ch.trancee.meshlink.internal.NoisePattern
import ch.trancee.meshlink.internal.NoiseResult
import ch.trancee.meshlink.internal.NoiseRole
import ch.trancee.meshlink.internal.SeededRandom
import ch.trancee.meshlink.internal.SessionRefusal
import ch.trancee.meshlink.internal.session
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class EndToEndSessionTest {
    private val crypto = SodiumCryptoProvider
    private val prologue = "e2e".encodeToByteArray()

    private fun bytes(
        random: SeededRandom,
        count: Int,
    ): ByteArray = ByteArray(count) { random.nextInt(0, BYTE_VALUES).toByte() }

    /** Both handshakes of [pattern], with keys from a fixed seed; the initiator knows the responder. */
    private fun handshakes(pattern: NoisePattern): Pair<NoiseHandshake, NoiseHandshake> {
        val random = SeededRandom(SEED)
        val initiatorStatic = crypto.x25519KeyPair(bytes(random, KEY))
        val responderStatic = crypto.x25519KeyPair(bytes(random, KEY))
        val initiator =
            NoiseKeys(
                initiatorStatic,
                ephemeral = crypto.x25519KeyPair(bytes(random, KEY)),
                kemKeyPair = crypto.mlKem768KeyPair(bytes(random, KEM_SEED)),
                remoteStatic = responderStatic.publicKey,
            )
        val responder =
            NoiseKeys(
                responderStatic,
                ephemeral = crypto.x25519KeyPair(bytes(random, KEY)),
                encapsulationSeed = bytes(random, KEY),
            )
        return NoiseHandshake(crypto, pattern, NoiseRole.INITIATOR, prologue, initiator) to
            NoiseHandshake(crypto, pattern, NoiseRole.RESPONDER, prologue, responder)
    }

    /** The initiator's and the responder's sessions after message 1 and message 2 of [pattern]. */
    private fun sessions(pattern: NoisePattern = NoisePattern.IK): Pair<EndToEndSession, EndToEndSession> {
        val (initiator, responder) = handshakes(pattern)
        responder.readMessage(initiator.writeMessage(MESSAGE_1).ok()).ok()
        initiator.readMessage(responder.writeMessage(MESSAGE_2).ok()).ok()
        return initiator.session().ok() to responder.session().ok()
    }

    /** [count] frames sealed in order by [sender], each carrying its index. */
    private fun frames(
        sender: EndToEndSession,
        count: Int,
    ): List<Container.Transport> = List(count) { assertNotNullFrame(sender.seal(plaintext(it))) }

    private fun EndToEndSession.opens(frame: Container.Transport) {
        val opened = assertIs<EndToEndSession.Opened.Frame>(open(frame), "nonce ${frame.nonce}")
        assertContentEquals(plaintext(frame.nonce.toInt()), opened.plaintext)
    }

    private fun EndToEndSession.refuses(
        frame: Container.Transport,
        refusal: SessionRefusal,
    ) {
        assertEquals(refusal, assertIs<EndToEndSession.Opened.Refused>(open(frame), "nonce ${frame.nonce}").refusal)
    }

    @Test
    fun bothSidesSealAndOpenFramesEachWayAtTheirSendCounters() {
        listOf(NoisePattern.IK, NoisePattern.IK_HFS).forEach { pattern ->
            val (initiator, responder) = sessions(pattern)
            assertContentEquals(initiator.handshakeHash, responder.handshakeHash)
            val forth = frames(initiator, 3)
            val back = frames(responder, 2)
            assertEquals(listOf(0uL, 1uL, 2uL), forth.map { it.nonce })
            assertEquals(listOf(0uL, 1uL), back.map { it.nonce })
            forth.forEach { responder.opens(it) }
            back.forEach { initiator.opens(it) }
        }
    }

    @Test
    fun framesOpenOutOfOrderOnceEach() {
        val (initiator, responder) = sessions()
        val sent = frames(initiator, 3)
        listOf(2, 0, 1).forEach { responder.opens(sent[it]) }
        sent.forEach { responder.refuses(it, SessionRefusal.REPLAYED_NONCE) }
    }

    @Test
    fun theWindowKeepsTheLast1024NoncesBelowTheHighest() {
        val (initiator, responder) = sessions()
        val sent = frames(initiator, WINDOW + 3)
        responder.opens(sent[0])
        responder.opens(sent[WINDOW + 2])
        responder.refuses(sent[0], SessionRefusal.NONCE_BELOW_WINDOW)
        responder.refuses(sent[2], SessionRefusal.NONCE_BELOW_WINDOW)
        responder.opens(sent[3])
        responder.refuses(sent[3], SessionRefusal.REPLAYED_NONCE)
        responder.refuses(sent[2], SessionRefusal.NONCE_BELOW_WINDOW)
        responder.opens(sent[WINDOW + 1])
    }

    @Test
    fun aNonceFarAheadForgetsEveryNonceBehindTheNewWindow() {
        val (initiator, responder) = sessions()
        val sent = frames(initiator, 2 * WINDOW + 1)
        responder.opens(sent[1])
        responder.opens(sent[2 * WINDOW])
        responder.refuses(sent[1], SessionRefusal.NONCE_BELOW_WINDOW)
        responder.refuses(sent[WINDOW], SessionRefusal.NONCE_BELOW_WINDOW)
        responder.opens(sent[WINDOW + 1])
    }

    @Test
    fun aJumpPastTheWindowForgetsTheSlotJustBehindTheNewHighest() {
        val (initiator, responder) = sessions()
        val sent = frames(initiator, WINDOW + 2)
        responder.opens(sent[0])
        responder.opens(sent[WINDOW + 1])
        responder.opens(sent[WINDOW])
    }

    @Test
    fun aJumpToTheLastUsableNonceIsAcceptedAtOnce() {
        val key = ByteArray(KEY) { it.toByte() }
        val last = ULong.MAX_VALUE - 1uL
        val session = { start: ULong ->
            EndToEndSession(ByteArray(KEY), ByteArray(KEY), CipherState(crypto, key, start), CipherState(crypto, key))
        }
        val responder = session(0uL)
        val first = assertNotNullFrame(responder.seal(plaintext(0)))
        val late = session(last)
        responder.opens(first)
        responder.opens(assertNotNullFrame(late.seal(plaintext(last.toInt()))))
        responder.refuses(first, SessionRefusal.NONCE_BELOW_WINDOW)
    }

    @Test
    fun advancingTheWindowClearsOnlyTheSlotsItPasses() {
        val (initiator, responder) = sessions()
        val sent = frames(initiator, WINDOW + 2)
        responder.opens(sent[1])
        responder.opens(sent[WINDOW - 1])
        responder.refuses(sent[1], SessionRefusal.REPLAYED_NONCE)
        responder.opens(sent[WINDOW + 1])
        responder.refuses(sent[1], SessionRefusal.NONCE_BELOW_WINDOW)
        responder.refuses(sent[WINDOW - 1], SessionRefusal.REPLAYED_NONCE)
        responder.opens(sent[2])
    }

    @Test
    fun aFrameThatFailsToOpenChangesNoState() {
        val (initiator, responder) = sessions()
        val sent = frames(initiator, 2)
        val tampered = sent[1].ciphertext.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }
        responder.refuses(Container.Transport(1uL, tampered), SessionRefusal.OPEN_FAILED)
        responder.refuses(Container.Transport(0uL, sent[1].ciphertext), SessionRefusal.OPEN_FAILED)
        responder.opens(sent[1])
        responder.opens(sent[0])
    }

    @Test
    fun aRefusedNonceChangesNoState() {
        val (initiator, responder) = sessions()
        val sent = frames(initiator, WINDOW + 2)
        responder.opens(sent[WINDOW + 1])
        responder.refuses(sent[0], SessionRefusal.NONCE_BELOW_WINDOW)
        responder.refuses(sent[WINDOW + 1], SessionRefusal.REPLAYED_NONCE)
        responder.opens(sent[WINDOW])
        responder.opens(sent[2])
    }

    @Test
    fun theReservedNonceNeitherSealsNorOpens() {
        val key = ByteArray(KEY) { it.toByte() }
        val exhausted = CipherState(crypto, key, ULong.MAX_VALUE)
        val session = EndToEndSession(ByteArray(KEY), ByteArray(KEY), exhausted, CipherState(crypto, key))
        assertNull(session.seal(plaintext(0)))
        val reserved = ByteArray(NONCE).also { it.fill(-1, 4) }
        val sealed = crypto.chaCha20Poly1305Seal(key, reserved, plaintext(0), ByteArray(0))
        session.refuses(Container.Transport(ULong.MAX_VALUE, sealed), SessionRefusal.OPEN_FAILED)
    }

    @Test
    fun aHandshakeSplitsIntoASessionOnlyOnceAndOnlyWhenComplete() {
        val (initiator, responder) = handshakes(NoisePattern.IK)
        initiator.session().assertFailed(NoiseFailure.OUT_OF_ORDER)
        initiator.session().assertFailed(NoiseFailure.CLOSED)
        responder.readMessage(handshakes(NoisePattern.IK).first.writeMessage(MESSAGE_1).ok()).ok()
        responder.writeMessage(MESSAGE_2).ok()
        responder.session().ok()
        responder.session().assertFailed(NoiseFailure.CLOSED)
    }

    private companion object {
        const val SEED = 46L
        const val KEY = 32
        const val KEM_SEED = 64
        const val NONCE = 12
        const val BYTE_VALUES = 256
        const val WINDOW = 1_024
        val MESSAGE_1 = "message 1".encodeToByteArray()
        val MESSAGE_2 = "message 2".encodeToByteArray()

        fun plaintext(index: Int): ByteArray = "frame $index".encodeToByteArray()

        fun assertNotNullFrame(frame: Container.Transport?): Container.Transport = kotlin.test.assertNotNull(frame)
    }
}

private fun <T> NoiseResult<T>.ok(): T = assertIs<NoiseResult.Ok<T>>(this).value

private fun NoiseResult<*>.assertFailed(failure: NoiseFailure) {
    assertEquals(failure, assertIs<NoiseResult.Failed>(this).failure)
}
