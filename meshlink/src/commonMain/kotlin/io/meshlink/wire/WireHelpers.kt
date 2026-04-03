package io.meshlink.wire

/** Little-endian byte helpers shared across wire codecs. */

internal fun ByteArray.putUShortLE(offset: Int, value: UShort) {
    val v = value.toInt()
    this[offset] = v.toByte()
    this[offset + 1] = (v shr 8).toByte()
}

internal fun ByteArray.getUShortLE(offset: Int): UShort =
    (
        (this[offset].toInt() and 0xFF) or
            ((this[offset + 1].toInt() and 0xFF) shl 8)
        ).toUShort()

internal fun ByteArray.putULongLE(offset: Int, value: ULong) {
    val v = value.toLong()
    for (i in 0..7) {
        this[offset + i] = (v shr (i * 8)).toByte()
    }
}

internal fun ByteArray.getULongLE(offset: Int): ULong {
    var result = 0L
    for (i in 0..7) {
        result = result or ((this[offset + i].toLong() and 0xFF) shl (i * 8))
    }
    return result.toULong()
}

internal fun ByteArray.putUIntLE(offset: Int, value: UInt) {
    val v = value.toInt()
    for (i in 0..3) {
        this[offset + i] = (v shr (i * 8)).toByte()
    }
}

internal fun ByteArray.getUIntLE(offset: Int): UInt {
    var result = 0
    for (i in 0..3) {
        result = result or ((this[offset + i].toInt() and 0xFF) shl (i * 8))
    }
    return result.toUInt()
}
