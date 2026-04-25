package ch.trancee.meshlink.wire

data class Handshake(val step: UByte, val noiseMessage: ByteArray) : WireMessage {
    override val type = MessageType.HANDSHAKE

    override fun encode(wb: WriteBuffer) {
        wb.startTable(2)
        wb.addUByte(0, step)
        wb.addByteVector(1, noiseMessage)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Handshake) return false
        return step == other.step && noiseMessage.contentEquals(other.noiseMessage)
    }

    override fun hashCode(): Int = 31 * step.hashCode() + noiseMessage.contentHashCode()

    companion object {
        fun decode(rb: ReadBuffer): Handshake =
            Handshake(step = rb.getUByte(0), noiseMessage = rb.getByteArray(1) ?: ByteArray(0))
    }
}
