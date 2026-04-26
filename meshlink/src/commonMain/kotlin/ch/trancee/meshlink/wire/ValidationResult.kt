package ch.trancee.meshlink.wire

/** Outcome of [InboundValidator.validate]. */
internal sealed interface ValidationResult

/** The frame is structurally sound and decoded successfully. */
internal data class Valid(val message: WireMessage) : ValidationResult

/** Supertype for all rejection reasons. */
internal sealed interface Rejected : ValidationResult {
    val reason: RejectionReason
}

/** Categorises why a frame was rejected. */
internal enum class RejectionReason {
    BUFFER_TOO_SHORT,
    UNKNOWN_MESSAGE_TYPE,
    MALFORMED_FLATBUFFER,
    INVALID_FIELD_SIZE,
}

/** Frame is shorter than the minimum 5 bytes (1 type + 4 FlatBuffers root offset). */
internal object BufferTooShort : Rejected {
    override val reason = RejectionReason.BUFFER_TOO_SHORT
}

/** Type byte does not match any known [MessageType]. */
internal data class UnknownType(val typeByte: Byte) : Rejected {
    override val reason = RejectionReason.UNKNOWN_MESSAGE_TYPE
}

/** FlatBuffers structure is invalid (offsets out of bounds, vtable corrupt, etc.). */
internal data class MalformedStructure(val detail: String) : Rejected {
    override val reason = RejectionReason.MALFORMED_FLATBUFFER
}

/**
 * A fixed-size byte-array field has the wrong length. [expected] is the required byte count;
 * [actual] is what was present in the frame.
 */
internal data class InvalidFieldSize(val field: String, val expected: Int, val actual: Int) :
    Rejected {
    override val reason = RejectionReason.INVALID_FIELD_SIZE
}
