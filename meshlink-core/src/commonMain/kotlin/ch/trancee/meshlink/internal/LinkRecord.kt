package ch.trancee.meshlink.internal

/**
 * A link record (spec "Link records"; ADR 0012 decision 3): a `u16` length of 1 to 1,024, then
 * the body, which carries one frame. Records follow each other on a link's ordered byte stream.
 */
public object LinkRecord {
    /** The shortest body (spec `record-body-min`). */
    public const val BODY_MIN: Int = 1

    /** The longest body (spec `record-body-max`). */
    public const val BODY_MAX: Int = 1_024

    internal const val LENGTH_BYTES: Int = 2

    /** [body] framed as one record. */
    public fun encode(body: ByteArray): ByteArray {
        require(body.size in BODY_MIN..BODY_MAX) { "a record body is $BODY_MIN to $BODY_MAX bytes, not ${body.size}" }
        return u16(body.size) + body
    }

    /** Decodes [bytes] as exactly one record and returns its body; never throws. */
    public fun decode(bytes: ByteArray): Decoded<ByteArray> {
        val length = if (bytes.size >= LENGTH_BYTES) bytes.u16(0) else -1
        return when {
            length < 0 -> Decoded.Invalid(DecodeError.TRUNCATED)
            length !in BODY_MIN..BODY_MAX -> Decoded.Invalid(DecodeError.OUT_OF_RANGE)
            bytes.size < LENGTH_BYTES + length -> Decoded.Invalid(DecodeError.TRUNCATED)
            bytes.size > LENGTH_BYTES + length -> Decoded.Invalid(DecodeError.TRAILING_BYTES)
            else -> Decoded.Valid(bytes.copyOfRange(LENGTH_BYTES, bytes.size))
        }
    }
}

/**
 * Cuts one direction of a link's byte stream into record bodies, wherever the writes or
 * notifications split it. A length outside 1 to 1,024 is fatal: from then on every read is
 * [DecodeError.OUT_OF_RANGE], and the node closes the bearer.
 */
public class RecordReader {
    private var pending = ByteArray(0)
    private var failed = false

    /** Adds [chunk] to the stream and returns the bodies it completed, in order. */
    public fun read(chunk: ByteArray): Decoded<List<ByteArray>> {
        pending += chunk
        val bodies = mutableListOf<ByteArray>()
        while (!failed && pending.size >= LinkRecord.LENGTH_BYTES) {
            val end = LinkRecord.LENGTH_BYTES + pending.u16(0)
            failed = end - LinkRecord.LENGTH_BYTES !in LinkRecord.BODY_MIN..LinkRecord.BODY_MAX
            if (failed || pending.size < end) break
            bodies += pending.copyOfRange(LinkRecord.LENGTH_BYTES, end)
            pending = pending.copyOfRange(end, pending.size)
        }
        return if (failed) Decoded.Invalid(DecodeError.OUT_OF_RANGE) else Decoded.Valid(bodies)
    }
}
