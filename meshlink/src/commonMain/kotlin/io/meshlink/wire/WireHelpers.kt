package io.meshlink.wire

/** Byte-order helpers shared across wire codecs. */

// ── Little-endian ───────────────────────────────────────────

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

// ── Big-endian ──────────────────────────────────────────────

internal fun ByteArray.putUShortBE(offset: Int, value: UShort) {
    val v = value.toInt()
    this[offset] = (v ushr 8).toByte()
    this[offset + 1] = (v and 0xFF).toByte()
}

internal fun ByteArray.getUShortBE(offset: Int): UShort =
    (
        ((this[offset].toInt() and 0xFF) shl 8) or
            (this[offset + 1].toInt() and 0xFF)
        ).toUShort()

internal fun ByteArray.putULongBE(offset: Int, value: ULong) {
    val v = value.toLong()
    for (i in 0..7) {
        this[offset + i] = (v shr ((7 - i) * 8)).toByte()
    }
}

internal fun ByteArray.getULongBE(offset: Int): ULong {
    var result = 0L
    for (i in 0..7) {
        result = result or ((this[offset + i].toLong() and 0xFF) shl ((7 - i) * 8))
    }
    return result.toULong()
}
