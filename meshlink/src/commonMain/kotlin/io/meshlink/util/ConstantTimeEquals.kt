package io.meshlink.util

/**
 * Constant-time byte array comparison to prevent timing side-channel attacks.
 *
 * Unlike [ByteArray.contentEquals], this function always examines every byte,
 * preventing an attacker from learning how many leading bytes match.
 * Use for all security-sensitive comparisons (AEAD tags, signatures, keys).
 */
fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) return false
    var acc = 0
    for (i in a.indices) acc = acc or (a[i].toInt() xor b[i].toInt())
    return acc == 0
}
