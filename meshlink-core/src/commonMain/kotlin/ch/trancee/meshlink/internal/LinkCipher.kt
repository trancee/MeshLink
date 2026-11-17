package ch.trancee.meshlink.internal

/**
 * A secured link's record cipher (spec "Link records" and "Rekey", D-21): it seals each frame as
 * one record with [transport] and opens the peer's records in order. A sending key seals at most
 * [REKEY_RECORDS] records, the last of them a `REKEY`; [open] refuses a peer that sends more than
 * twice as many with one key. Not thread-safe: one writer seals, holding the link's write lock,
 * and the link's reader opens.
 */
public class LinkCipher(
    private val transport: NoiseTransport,
    private val clock: Clock,
) {
    private var sent = 0
    private var received = 0
    private var rekeyedMillis = clock.monotonicMillis()

    /** When the sending key is due its timed rekey, on [Clock.monotonicMillis]. */
    public val rekeyDueMillis: Long get() = rekeyedMillis + REKEY_INTERVAL_MILLIS

    /**
     * [frame] as link records: one, or a `REKEY` first when the sending key has only that record
     * left. Sealing a `REKEY` rekeys the sending direction after it.
     */
    public fun seal(frame: ByteArray): ByteArray =
        when {
            frame.contentEquals(REKEY) -> record(frame).also { rekey() }
            sent == REKEY_RECORDS - 1 -> record(REKEY).also { rekey() } + record(frame)
            else -> record(frame)
        }

    /** What record [body] carries. A `REKEY` rekeys the receiving direction after it. */
    public fun open(body: ByteArray): Opened {
        if (received == 2 * REKEY_RECORDS) return Opened.Overdue
        return when (val plain = transport.decrypt(body)) {
            is NoiseResult.Ok -> Opened.Frame(accepted(plain.value))
            is NoiseResult.Failed -> Opened.Failed
        }
    }

    private fun accepted(frame: ByteArray): ByteArray {
        received++
        if (frame.contentEquals(REKEY)) {
            transport.rekeyReceiving()
            received = 0
        }
        return frame
    }

    private fun record(frame: ByteArray): ByteArray {
        sent++
        // A sending cipher state fails only after 2^64 - 1 records.
        return LinkRecord.encode((transport.encrypt(frame) as NoiseResult.Ok).value)
    }

    private fun rekey() {
        transport.rekeySending()
        sent = 0
        rekeyedMillis = clock.monotonicMillis()
    }

    /** What [open] found in a record. */
    public sealed interface Opened {
        /** The record decrypted to [bytes], one frame. */
        public class Frame(
            public val bytes: ByteArray,
        ) : Opened

        /** The record failed to decrypt: the bearer closes. */
        public data object Failed : Opened

        /**
         * The peer sent more than twice [REKEY_RECORDS] records with one key: the link closes with
         * `PROTOCOL_ERROR`.
         */
        public data object Overdue : Opened
    }

    public companion object {
        /** The most records one sending key seals, its `REKEY` included (spec `rekey-records`). */
        public const val REKEY_RECORDS: Int = 65_536

        /** How long one sending key is used at most (spec `rekey-interval`). */
        public const val REKEY_INTERVAL_MILLIS: Long = 3_600_000

        private val REKEY = LinkFrame.Rekey.encode()
    }
}
