package io.meshlink.util

private val HEX_CHARS = charArrayOf(
    '0', '1', '2', '3', '4', '5', '6', '7',
    '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
)

internal fun ByteArray.toHex(): String {
    val chars = CharArray(size * 2)
    for (i in indices) {
        val v = this[i].toInt() and 0xFF
        chars[i * 2] = HEX_CHARS[v ushr 4]
        chars[i * 2 + 1] = HEX_CHARS[v and 0x0F]
    }
    return chars.concatToString()
}

internal fun hexToBytes(hex: String): ByteArray {
    val len = hex.length
    val result = ByteArray(len / 2)
    var i = 0
    while (i < len) {
        val hi = hexCharToInt(hex[i])
        val lo = hexCharToInt(hex[i + 1])
        result[i / 2] = ((hi shl 4) or lo).toByte()
        i += 2
    }
    return result
}

private fun hexCharToInt(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    in 'A'..'F' -> c - 'A' + 10
    else -> throw IllegalArgumentException("Invalid hex character: $c")
}
