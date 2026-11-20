package ch.trancee.meshlink.crypto.sodium

import ch.trancee.meshlink.internal.CipherState
import ch.trancee.meshlink.internal.Clock
import ch.trancee.meshlink.internal.LinkCipher
import ch.trancee.meshlink.internal.LinkCipher.Opened
import ch.trancee.meshlink.internal.LinkFrame
import ch.trancee.meshlink.internal.LinkRecord
import ch.trancee.meshlink.internal.NoiseResult
import ch.trancee.meshlink.internal.NoiseTransport
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class LinkCipherTest {
    private val clock = SteppedClock()
    private val toResponder = ByteArray(KEY_LENGTH) { 1 }
    private val toInitiator = ByteArray(KEY_LENGTH) { 2 }

    private fun transport(
        sending: ByteArray,
        receiving: ByteArray,
    ) = NoiseTransport(ByteArray(KEY_LENGTH), ByteArray(KEY_LENGTH), cipher(sending), cipher(receiving))

    private fun cipher(key: ByteArray) = CipherState(SodiumCryptoProvider, key)

    private fun initiator() = LinkCipher(transport(toResponder, toInitiator), clock)

    private fun responder() = LinkCipher(transport(toInitiator, toResponder), clock)

    private val frame = byteArrayOf(0x3F, 1, 2)
    private val rekey = LinkFrame.Rekey.encode()

    /** The record bodies in [records], a stream of whole records. */
    private fun bodies(records: ByteArray): List<ByteArray> {
        val bodies = mutableListOf<ByteArray>()
        var at = 0
        while (at < records.size) {
            val length = ((records[at].toInt() and BYTE) shl BITS) or (records[at + 1].toInt() and BYTE)
            bodies += records.copyOfRange(at + 2, at + 2 + length)
            at += 2 + length
        }
        return bodies
    }

    private fun LinkCipher.frames(records: ByteArray): List<ByteArray> {
        val opened = bodies(records).map { open(it) }
        return opened.map { assertIs<Opened.Frame>(it).bytes }
    }

    @Test
    fun sealingAddsTagLengthBytesToTheBody() {
        val body = bodies(initiator().seal(frame)).single()

        assertEquals(frame.size + LinkCipher.TAG_LENGTH, body.size)
    }

    @Test
    fun aRekeyChangesTheKeyOfLaterRecordsInThatDirectionOnly() {
        // Arrange
        val (initiator, responder) = initiator() to responder()

        // Act
        val records = initiator.seal(frame) + initiator.seal(rekey) + initiator.seal(frame)
        val reply = responder.seal(frame)

        // Assert
        assertEquals(listOf(frame, rekey, frame).map { it.toList() }, responder.frames(records).map { it.toList() })
        assertContentEquals(frame, initiator.frames(reply).single())
    }

    @Test
    fun aRecordUnderTheOldKeyAfterARekeyFailsToOpen() {
        // Arrange
        val peer = transport(toResponder, toInitiator)
        val responder = responder()
        val record = { plain: ByteArray -> LinkRecord.encode((peer.encrypt(plain) as NoiseResult.Ok).value) }
        responder.frames(record(frame) + record(rekey))

        // Act
        val opened = responder.open(bodies(record(frame)).single())

        // Assert
        assertSame(Opened.Failed, opened)
    }

    @Test
    fun aSendingKeySealsAtMostRekeyRecordsTheLastOfThemARekey() {
        // Arrange
        val (initiator, responder) = initiator() to responder()
        repeat(LinkCipher.REKEY_RECORDS - 1) { responder.frames(initiator.seal(frame)) }

        // Act
        val records = initiator.seal(frame)

        // Assert
        val frames = responder.frames(records)
        assertEquals(listOf(rekey.toList(), frame.toList()), frames.map { it.toList() })
        repeat(LinkCipher.REKEY_RECORDS - 2) { assertEquals(1, bodies(initiator.seal(frame)).size) }
        assertEquals(2, bodies(initiator.seal(frame)).size)
    }

    @Test
    fun theTimedRekeyIsDueAnIntervalAfterTheLastRekey() {
        // Arrange
        val initiator = initiator()
        val opened = clock.millis
        clock.millis += HALF_HOUR

        // Act
        val before = initiator.rekeyDueMillis
        initiator.seal(frame)
        val unchanged = initiator.rekeyDueMillis
        initiator.seal(rekey)

        // Assert
        assertEquals(opened + LinkCipher.REKEY_INTERVAL_MILLIS, before)
        assertEquals(before, unchanged)
        assertEquals(clock.millis + LinkCipher.REKEY_INTERVAL_MILLIS, initiator.rekeyDueMillis)
    }

    @Test
    fun aPeerThatNeverRekeysIsOverdueAfterTwiceRekeyRecordsAndNotBefore() {
        // Arrange
        val peer = transport(toResponder, toInitiator)
        val responder = responder()
        val record = { LinkRecord.encode((peer.encrypt(frame) as NoiseResult.Ok).value) }
        repeat(2 * LinkCipher.REKEY_RECORDS) { responder.frames(record()) }

        // Act
        val opened = responder.open(bodies(record()).single())

        // Assert
        assertSame(Opened.Overdue, opened)
    }

    @Test
    fun aRekeyRestartsTheReceivedCount() {
        // Arrange
        val peer = transport(toResponder, toInitiator)
        val responder = responder()
        val record = { plain: ByteArray -> LinkRecord.encode((peer.encrypt(plain) as NoiseResult.Ok).value) }
        repeat(2 * LinkCipher.REKEY_RECORDS - 1) { responder.frames(record(frame)) }
        responder.frames(record(rekey))
        peer.rekeySending()

        // Act
        val frames = (1..2 * LinkCipher.REKEY_RECORDS).map { responder.open(bodies(record(frame)).single()) }

        // Assert
        frames.forEach { assertIs<Opened.Frame>(it) }
    }
}

/** A [Clock] whose monotonic clock reads [millis]. */
private class SteppedClock : Clock {
    var millis = 1_000L

    override fun monotonicMillis(): Long = millis

    override fun wallClockMillis(): Long = millis
}

private const val KEY_LENGTH = 32
private const val BYTE = 0xFF
private const val BITS = 8
private const val HALF_HOUR = 1_800_000L
