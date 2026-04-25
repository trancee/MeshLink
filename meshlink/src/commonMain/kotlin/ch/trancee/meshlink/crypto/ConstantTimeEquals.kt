package ch.trancee.meshlink.crypto

/**
 * Compares two byte arrays in constant time to prevent timing side-channel attacks.
 *
 * Returns `false` immediately if the arrays have different lengths — length is not secret for
 * fixed-size cryptographic identifiers (Key Hash 12 bytes, public keys 32/64 bytes).
 *
 * The comparison uses an XOR-and-OR accumulator so runtime does not depend on the position of the
 * first differing byte.
 *
 * @param a First byte array.
 * @param b Second byte array.
 * @return `true` if both arrays have equal length and identical contents, `false` otherwise.
 */
internal fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) return false
    var result = 0
    for (i in a.indices) {
        result = result or (a[i].toInt() xor b[i].toInt())
    }
    return result == 0
}
