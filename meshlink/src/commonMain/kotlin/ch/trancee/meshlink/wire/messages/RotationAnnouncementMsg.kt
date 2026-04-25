package ch.trancee.meshlink.wire

/**
 * Named with Msg suffix to avoid clash with [ch.trancee.meshlink.crypto.RotationAnnouncement].
 *
 * Fields: 4×32-byte public keys, a monotonic rotation nonce, a wall-clock timestamp, and a 64-byte
 * Ed25519 signature over all preceding fields using the OLD Ed25519 key.
 */
data class RotationAnnouncementMsg(
    val oldX25519Key: ByteArray,
    val newX25519Key: ByteArray,
    val oldEd25519Key: ByteArray,
    val newEd25519Key: ByteArray,
    val rotationNonce: ULong,
    val timestampMillis: ULong,
    val signature: ByteArray,
) : WireMessage {
    override val type = MessageType.ROTATION_ANNOUNCEMENT

    override fun encode(wb: WriteBuffer) {
        wb.startTable(7)
        wb.addByteVector(0, oldX25519Key)
        wb.addByteVector(1, newX25519Key)
        wb.addByteVector(2, oldEd25519Key)
        wb.addByteVector(3, newEd25519Key)
        wb.addULong(4, rotationNonce)
        wb.addULong(5, timestampMillis)
        wb.addByteVector(6, signature)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RotationAnnouncementMsg) return false
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
        fun decode(rb: ReadBuffer): RotationAnnouncementMsg =
            RotationAnnouncementMsg(
                oldX25519Key = rb.getByteArray(0) ?: ByteArray(32),
                newX25519Key = rb.getByteArray(1) ?: ByteArray(32),
                oldEd25519Key = rb.getByteArray(2) ?: ByteArray(32),
                newEd25519Key = rb.getByteArray(3) ?: ByteArray(32),
                rotationNonce = rb.getULong(4),
                timestampMillis = rb.getULong(5),
                signature = rb.getByteArray(6) ?: ByteArray(64),
            )
    }
}
