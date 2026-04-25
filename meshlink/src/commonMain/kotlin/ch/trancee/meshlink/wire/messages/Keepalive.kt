package ch.trancee.meshlink.wire

data class Keepalive(val flags: UByte, val timestampMillis: ULong, val proposedChunkSize: UShort) :
    WireMessage {
    override val type = MessageType.KEEPALIVE

    override fun encode(buffer: WriteBuffer) {
        buffer.startTable(3)
        buffer.addUByte(0, flags)
        buffer.addULong(1, timestampMillis)
        buffer.addUShort(2, proposedChunkSize)
    }

    companion object {
        fun decode(buffer: ReadBuffer): Keepalive =
            Keepalive(
                flags = buffer.getUByte(0),
                timestampMillis = buffer.getULong(1),
                proposedChunkSize = buffer.getUShort(2),
            )
    }
}
