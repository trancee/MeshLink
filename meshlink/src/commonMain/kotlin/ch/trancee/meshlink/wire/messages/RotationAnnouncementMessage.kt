package ch.trancee.meshlink.wire

/**
 * Named with Message suffix to avoid clash with [ch.trancee.meshlink.crypto.RotationAnnouncement].
 *
 * Fields: 4×32-byte public keys, a monotonic rotation nonce, a wall-clock timestamp, and a 64-byte
 * Ed25519 signature over all preceding fields using the OLD Ed25519 key.
 */
internal data class RotationAnnouncementMessage(
    val oldX25519Key: ByteArray,
    val newX25519Key: ByteArray,
    val oldEd25519Key: ByteArray,
    val newEd25519Key: ByteArray,
    val rotationNonce: ULong,
    val timestampMillis: ULong,
    val signature: ByteArray,
) : WireMessage {
    override val type = MessageType.ROTATION_ANNOUNCEMENT

    override fun encode(buffer: WriteBuffer) {
        buffer.startTable(7)
        buffer.addByteVector(0, oldX25519Key)
        buffer.addByteVector(1, newX25519Key)
        buffer.addByteVector(2, oldEd25519Key)
        buffer.addByteVector(3, newEd25519Key)
        buffer.addULong(4, rotationNonce)
        buffer.addULong(5, timestampMillis)
        buffer.addByteVector(6, signature)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RotationAnnouncementMessage) return false
        return rotationNonce == other.rotationNonce &&
            timestampMillis == other.timestampMillis &&
            oldX25519Key.contentEquals(other.oldX25519Key) &&
            newX25519Key.contentEquals(other.newX25519Key) &&
            oldEd25519Key.contentEquals(other.oldEd25519Key) &&
            newEd25519Key.contentEquals(other.newEd25519Key) &&
            signature.contentEquals(other.signature)
    }

    override fun hashCode(): Int {
        var h = oldX25519Key.contentHashCode()
        h = 31 * h + newX25519Key.contentHashCode()
        h = 31 * h + oldEd25519Key.contentHashCode()
        h = 31 * h + newEd25519Key.contentHashCode()
        h = 31 * h + rotationNonce.hashCode()
        h = 31 * h + timestampMillis.hashCode()
        h = 31 * h + signature.contentHashCode()
        return h
    }

    companion object {
        fun decode(buffer: ReadBuffer): RotationAnnouncementMessage =
            RotationAnnouncementMessage(
                oldX25519Key = buffer.getByteArray(0) ?: ByteArray(32),
                newX25519Key = buffer.getByteArray(1) ?: ByteArray(32),
                oldEd25519Key = buffer.getByteArray(2) ?: ByteArray(32),
                newEd25519Key = buffer.getByteArray(3) ?: ByteArray(32),
                rotationNonce = buffer.getULong(4),
                timestampMillis = buffer.getULong(5),
                signature = buffer.getByteArray(6) ?: ByteArray(64),
            )
    }
}
