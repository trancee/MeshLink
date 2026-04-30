package ch.trancee.meshlink.util

private val HEX_CHARS = "0123456789abcdef".toCharArray()

/**
 * Encodes a [ByteArray] to a lowercase hex string without any separator.
 *
 * Uses a pre-computed [HEX_CHARS] lookup — no per-byte `toString(16)`, no `padStart`, no
 * `joinToString` intermediate list.
 *
 * @param limit If > 0, encodes only the first [limit] bytes (for truncated debug output).
 */
internal fun ByteArray.toHex(limit: Int = 0): String {
    val count = if (limit > 0) minOf(limit, size) else size
    val chars = CharArray(count * 2)
    for (i in 0 until count) {
        val b = this[i].toInt() and 0xFF
        chars[i * 2] = HEX_CHARS[b ushr 4]
        chars[i * 2 + 1] = HEX_CHARS[b and 0x0F]
    }
    return chars.concatToString()
}
