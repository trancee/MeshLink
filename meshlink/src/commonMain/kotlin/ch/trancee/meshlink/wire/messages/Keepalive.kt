package ch.trancee.meshlink.wire

data class Keepalive(val flags: UByte, val timestampMillis: ULong, val proposedChunkSize: UShort) :
    WireMessage {
    override val type = MessageType.KEEPALIVE

    override fun encode(wb: WriteBuffer) {
        wb.startTable(3)
        wb.addUByte(0, flags)
        wb.addULong(1, timestampMillis)
        wb.addUShort(2, proposedChunkSize)
    }

    companion object {
        fun decode(rb: ReadBuffer): Keepalive =
            Keepalive(
                flags = rb.getUByte(0),
                timestampMillis = rb.getULong(1),
                proposedChunkSize = rb.getUShort(2),
            )
    }
}
