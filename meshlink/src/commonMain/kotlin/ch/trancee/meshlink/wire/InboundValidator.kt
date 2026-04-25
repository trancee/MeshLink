package ch.trancee.meshlink.wire

/**
 * Security boundary between raw wire bytes and the protocol engine.
 *
 * Every inbound frame must pass [validate] before any field is accessed. Validation failure returns
 * a typed [Rejected] subtype — the validator never throws.
 *
 * Validation pipeline:
 * 1. Minimum size check — frame must be ≥ 5 bytes (1 type + 4 FlatBuffers root offset).
 * 2. Type-byte lookup — unknown codes → [UnknownType].
 * 3. FlatBuffers structural check — [ReadBuffer] construction validates root offset and vtable.
 * 4. Per-type field-size checks — fixed-size byte-array fields must have exact sizes.
 * 5. Decode via [WireCodec] and wrap in [Valid].
 */
object InboundValidator {

    private const val MIN_FRAME_SIZE = 5 // 1 type byte + 4 bytes minimum FlatBuffers root offset

    /**
     * Validates [data] and returns a [ValidationResult]. Never throws; all error paths return a
     * [Rejected] subtype.
     */
    fun validate(data: ByteArray): ValidationResult {
        // Step 1 — minimum size
        if (data.size < MIN_FRAME_SIZE) return BufferTooShort

        // Step 2 — type discriminator
        val typeCode = data[0].toUByte()
        val messageType = MessageType.fromByte(typeCode)
        if (messageType == MessageType.UNKNOWN) return UnknownType(data[0])

        // Steps 3–5 — structural + field-size validation, then decode.
        // IllegalArgumentException from ReadBuffer init or getByteArray is caught here.
        val payload = data.copyOfRange(1, data.size)
        return try {
            val buffer = ReadBuffer(payload)
            checkFields(messageType, buffer) ?: Valid(WireCodec.decode(data))
        } catch (e: IllegalArgumentException) {
            MalformedStructure("Invalid FlatBuffers structure")
        }
    }

    // ── Per-type field constraints ────────────────────────────────────────────

    /**
     * Returns a [Rejected] if any fixed-size byte-array field in [buffer] has the wrong length,
     * null if all checks pass.
     *
     * May propagate [IllegalArgumentException] if a vector's data extends past the buffer end —
     * callers must catch.
     */
    private fun checkFields(type: MessageType, buffer: ReadBuffer): Rejected? =
        when (type) {
            MessageType.ROTATION_ANNOUNCEMENT ->
                buffer.checkSize(0, "oldX25519Key", 32)
                    ?: buffer.checkSize(1, "newX25519Key", 32)
                    ?: buffer.checkSize(2, "oldEd25519Key", 32)
                    ?: buffer.checkSize(3, "newEd25519Key", 32)
                    ?: buffer.checkSize(6, "signature", 64)
            MessageType.HELLO -> buffer.checkSize(0, "sender", 12)
            MessageType.UPDATE ->
                buffer.checkSize(0, "destination", 12)
                    ?: buffer.checkSize(3, "ed25519PublicKey", 32)
                    ?: buffer.checkSize(4, "x25519PublicKey", 32)
            MessageType.CHUNK -> buffer.checkSize(0, "messageId", 16)
            MessageType.CHUNK_ACK -> buffer.checkSize(0, "messageId", 16)
            MessageType.NACK -> buffer.checkSize(0, "messageId", 16)
            MessageType.RESUME_REQUEST -> buffer.checkSize(0, "messageId", 16)
            MessageType.BROADCAST ->
                buffer.checkSize(0, "messageId", 16)
                    ?: buffer.checkSize(1, "origin", 12)
                    ?: buffer.checkSize(6, "signature", 64)
                    ?: buffer.checkSize(7, "signerKey", 32)
            MessageType.ROUTED_MESSAGE ->
                buffer.checkSize(0, "messageId", 16)
                    ?: buffer.checkSize(1, "origin", 12)
                    ?: buffer.checkSize(2, "destination", 12)
                    ?: buffer.checkVisitedList(4)
            MessageType.DELIVERY_ACK ->
                buffer.checkSize(0, "messageId", 16)
                    ?: buffer.checkSize(1, "recipientId", 12)
                    ?: buffer.checkSize(3, "signature", 64)
            // HANDSHAKE and KEEPALIVE have no fixed-size byte-array constraints.
            // UNKNOWN is unreachable — validate() returns UnknownType before reaching here.
            // Using else covers all remaining enum values so UNKNOWN maps to the same target
            // as HANDSHAKE/KEEPALIVE — exercised by their test cases, no dead branch.
            else -> null
        }

    /**
     * Returns [InvalidFieldSize] if field [fieldIndex] is present and its byte count ≠ [expected].
     * Returns null if the field is absent (optional field not encoded) or has the correct size.
     */
    private fun ReadBuffer.checkSize(fieldIndex: Int, name: String, expected: Int): Rejected? {
        val bytes = getByteArray(fieldIndex) ?: return null
        if (bytes.size != expected) return InvalidFieldSize(name, expected, bytes.size)
        return null
    }

    /**
     * Validates that the flattened visited-list field (N × 12 bytes) has a length divisible by 12.
     * Returns [InvalidFieldSize] if not. Returns null if the field is absent or valid.
     */
    private fun ReadBuffer.checkVisitedList(fieldIndex: Int): Rejected? {
        val bytes = getByteArray(fieldIndex) ?: return null
        if (bytes.size % 12 != 0) {
            val nextValid = (bytes.size / 12 + 1) * 12
            return InvalidFieldSize("visitedList", nextValid, bytes.size)
        }
        return null
    }
}
