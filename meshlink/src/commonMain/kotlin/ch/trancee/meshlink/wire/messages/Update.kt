package ch.trancee.meshlink.wire

data class Update(
    val destination: ByteArray,
    val metric: UShort,
    val seqNo: UShort,
    val ed25519PublicKey: ByteArray,
    val x25519PublicKey: ByteArray,
) : WireMessage {
    override val type = MessageType.UPDATE

    override fun encode(buffer: WriteBuffer) {
        buffer.startTable(5)
        buffer.addByteVector(0, destination)
        buffer.addUShort(1, metric)
        buffer.addUShort(2, seqNo)
        buffer.addByteVector(3, ed25519PublicKey)
        buffer.addByteVector(4, x25519PublicKey)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Update) return false
        return metric == other.metric &&
            seqNo == other.seqNo &&
            destination.contentEquals(other.destination) &&
            ed25519PublicKey.contentEquals(other.ed25519PublicKey) &&
            x25519PublicKey.contentEquals(other.x25519PublicKey)
    }

    override fun hashCode(): Int {
        var h = destination.contentHashCode()
        h = 31 * h + metric.hashCode()
        h = 31 * h + seqNo.hashCode()
        h = 31 * h + ed25519PublicKey.contentHashCode()
        h = 31 * h + x25519PublicKey.contentHashCode()
        return h
    }

    companion object {
        fun decode(buffer: ReadBuffer): Update =
            Update(
                destination = buffer.getByteArray(0) ?: ByteArray(12),
                metric = buffer.getUShort(1),
                seqNo = buffer.getUShort(2),
                ed25519PublicKey = buffer.getByteArray(3) ?: ByteArray(32),
                x25519PublicKey = buffer.getByteArray(4) ?: ByteArray(32),
            )
    }
}
