package io.meshlink.wire

/**
 * Wire format for key rotation announcements.
 *
 * This is a pure codec — encode/decode only, no cryptographic verification.
 * Signature verification is handled by SecurityEngine.
 * Wire format for key rotation announcements.
 *
 * Format:
 *   Byte 0:       TYPE_ROTATION (0x02)
 *   Bytes 1-32:   Old X25519 public key (32 bytes)
 *   Bytes 33-64:  New X25519 public key (32 bytes)
 *   Bytes 65-96:  Old Ed25519 public key (32 bytes)
 *   Bytes 97-128: New Ed25519 public key (32 bytes)
 *   Bytes 129-136: Timestamp (8 bytes, big-endian ULong, epoch millis)
 *   Bytes 137-200: Signature (64 bytes, Ed25519 signature by OLD Ed25519 key over bytes 1-136)
 *
 * Total: 201 bytes
 */
object RotationAnnouncement {

    const val TYPE_ROTATION: Byte = 0x02
    const val SIZE = 201

    private const val KEY_SIZE = 32
    private const val SIGNATURE_SIZE = 64
    private const val TIMESTAMP_SIZE = 8
    private const val SIGNABLE_PAYLOAD_SIZE = KEY_SIZE * 4 + TIMESTAMP_SIZE // 136

    data class RotationMessage(
        val oldX25519Key: ByteArray,
        val newX25519Key: ByteArray,
        val oldEd25519Key: ByteArray,
        val newEd25519Key: ByteArray,
        val timestampMillis: ULong,
        val signature: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is RotationMessage) return false
            return oldX25519Key.contentEquals(other.oldX25519Key) &&
                newX25519Key.contentEquals(other.newX25519Key) &&
                oldEd25519Key.contentEquals(other.oldEd25519Key) &&
                newEd25519Key.contentEquals(other.newEd25519Key) &&
                timestampMillis == other.timestampMillis &&
                signature.contentEquals(other.signature)
        }

        override fun hashCode(): Int {
            var result = oldX25519Key.contentHashCode()
            result = 31 * result + newX25519Key.contentHashCode()
            result = 31 * result + oldEd25519Key.contentHashCode()
            result = 31 * result + newEd25519Key.contentHashCode()
            result = 31 * result + timestampMillis.hashCode()
            result = 31 * result + signature.contentHashCode()
            return result
        }
    }

    /**
     * Encode a rotation announcement from pre-signed components.
     */
    fun encode(
        oldX25519Key: ByteArray,
        newX25519Key: ByteArray,
        oldEd25519Key: ByteArray,
        newEd25519Key: ByteArray,
        timestampMillis: ULong,
        signature: ByteArray,
    ): ByteArray {
        require(oldX25519Key.size == KEY_SIZE) { "oldX25519Key must be $KEY_SIZE bytes, got ${oldX25519Key.size}" }
        require(newX25519Key.size == KEY_SIZE) { "newX25519Key must be $KEY_SIZE bytes, got ${newX25519Key.size}" }
        require(oldEd25519Key.size == KEY_SIZE) { "oldEd25519Key must be $KEY_SIZE bytes, got ${oldEd25519Key.size}" }
        require(newEd25519Key.size == KEY_SIZE) { "newEd25519Key must be $KEY_SIZE bytes, got ${newEd25519Key.size}" }
        require(signature.size == SIGNATURE_SIZE) { "signature must be $SIGNATURE_SIZE bytes, got ${signature.size}" }

        val buf = ByteArray(SIZE)
        buf[0] = TYPE_ROTATION
        var offset = 1
        oldX25519Key.copyInto(buf, offset)
        offset += KEY_SIZE
        newX25519Key.copyInto(buf, offset)
        offset += KEY_SIZE
        oldEd25519Key.copyInto(buf, offset)
        offset += KEY_SIZE
        newEd25519Key.copyInto(buf, offset)
        offset += KEY_SIZE
        buf.putULongBE(offset, timestampMillis)
        offset += TIMESTAMP_SIZE
        signature.copyInto(buf, offset)
        return buf
    }

    /**
     * Decode a rotation announcement from wire bytes.
     */
    fun decode(data: ByteArray): RotationMessage {
        require(data.size >= SIZE) { "rotation announcement too short: ${data.size}, need $SIZE" }
        require(data[0] == TYPE_ROTATION) { "not a rotation announcement: 0x${data[0].toUByte().toString(16)}" }

        var offset = 1
        val oldX25519 = data.copyOfRange(offset, offset + KEY_SIZE)
        offset += KEY_SIZE
        val newX25519 = data.copyOfRange(offset, offset + KEY_SIZE)
        offset += KEY_SIZE
        val oldEd25519 = data.copyOfRange(offset, offset + KEY_SIZE)
        offset += KEY_SIZE
        val newEd25519 = data.copyOfRange(offset, offset + KEY_SIZE)
        offset += KEY_SIZE
        val timestampMillis = data.getULongBE(offset)
        offset += TIMESTAMP_SIZE
        val signature = data.copyOfRange(offset, offset + SIGNATURE_SIZE)

        return RotationMessage(oldX25519, newX25519, oldEd25519, newEd25519, timestampMillis, signature)
    }

    /**
     * Build the payload that needs to be signed (the 4 keys + timestamp,
     * i.e. bytes 1..136 of the wire format, WITHOUT the type byte or signature).
     */
    fun buildSignablePayload(
        oldX25519Key: ByteArray,
        newX25519Key: ByteArray,
        oldEd25519Key: ByteArray,
        newEd25519Key: ByteArray,
        timestampMillis: ULong,
    ): ByteArray {
        require(oldX25519Key.size == KEY_SIZE) { "oldX25519Key must be $KEY_SIZE bytes, got ${oldX25519Key.size}" }
        require(newX25519Key.size == KEY_SIZE) { "newX25519Key must be $KEY_SIZE bytes, got ${newX25519Key.size}" }
        require(oldEd25519Key.size == KEY_SIZE) { "oldEd25519Key must be $KEY_SIZE bytes, got ${oldEd25519Key.size}" }
        require(newEd25519Key.size == KEY_SIZE) { "newEd25519Key must be $KEY_SIZE bytes, got ${newEd25519Key.size}" }

        val buf = ByteArray(SIGNABLE_PAYLOAD_SIZE)
        var offset = 0
        oldX25519Key.copyInto(buf, offset)
        offset += KEY_SIZE
        newX25519Key.copyInto(buf, offset)
        offset += KEY_SIZE
        oldEd25519Key.copyInto(buf, offset)
        offset += KEY_SIZE
        newEd25519Key.copyInto(buf, offset)
        offset += KEY_SIZE
        buf.putULongBE(offset, timestampMillis)
        return buf
    }

    private fun ByteArray.putULongBE(offset: Int, value: ULong) {
        val v = value.toLong()
        this[offset] = (v shr 56).toByte()
        this[offset + 1] = (v shr 48).toByte()
        this[offset + 2] = (v shr 40).toByte()
        this[offset + 3] = (v shr 32).toByte()
        this[offset + 4] = (v shr 24).toByte()
        this[offset + 5] = (v shr 16).toByte()
        this[offset + 6] = (v shr 8).toByte()
        this[offset + 7] = v.toByte()
    }

    private fun ByteArray.getULongBE(offset: Int): ULong {
        return (
            ((this[offset].toLong() and 0xFF) shl 56) or
                ((this[offset + 1].toLong() and 0xFF) shl 48) or
                ((this[offset + 2].toLong() and 0xFF) shl 40) or
                ((this[offset + 3].toLong() and 0xFF) shl 32) or
                ((this[offset + 4].toLong() and 0xFF) shl 24) or
                ((this[offset + 5].toLong() and 0xFF) shl 16) or
                ((this[offset + 6].toLong() and 0xFF) shl 8) or
                (this[offset + 7].toLong() and 0xFF)
            ).toULong()
    }
}
