package ch.trancee.meshlink.wire

data class Update(
    val destination: ByteArray,
    val metric: UShort,
    val seqNo: UShort,
    val ed25519PublicKey: ByteArray,
    val x25519PublicKey: ByteArray,
) : WireMessage {
    override val type = MessageType.UPDATE

    override fun encode(wb: WriteBuffer) {
        wb.startTable(5)
        wb.addByteVector(0, destination)
        wb.addUShort(1, metric)
        wb.addUShort(2, seqNo)
        wb.addByteVector(3, ed25519PublicKey)
        wb.addByteVector(4, x25519PublicKey)
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
        fun decode(rb: ReadBuffer): Update =
            Update(
                destination = rb.getByteArray(0) ?: ByteArray(12),
                metric = rb.getUShort(1),
                seqNo = rb.getUShort(2),
                ed25519PublicKey = rb.getByteArray(3) ?: ByteArray(32),
                x25519PublicKey = rb.getByteArray(4) ?: ByteArray(32),
            )
    }
}
