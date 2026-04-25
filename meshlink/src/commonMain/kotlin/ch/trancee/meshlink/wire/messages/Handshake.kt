package ch.trancee.meshlink.wire

data class Handshake(val step: UByte, val noiseMessage: ByteArray) : WireMessage {
    override val type = MessageType.HANDSHAKE

    override fun encode(buffer: WriteBuffer) {
        buffer.startTable(2)
        buffer.addUByte(0, step)
        buffer.addByteVector(1, noiseMessage)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Handshake) return false
        return step == other.step && noiseMessage.contentEquals(other.noiseMessage)
    }

    override fun hashCode(): Int = 31 * step.hashCode() + noiseMessage.contentHashCode()

    companion object {
        fun decode(buffer: ReadBuffer): Handshake =
            Handshake(
                step = buffer.getUByte(0),
                noiseMessage = buffer.getByteArray(1) ?: ByteArray(0),
            )
    }
}
