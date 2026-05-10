package ch.trancee.meshlink.crypto

internal object MessageSealer {
    internal fun seal(plaintext: ByteArray, identityFingerprint: String): ByteArray {
        return xor(plaintext, identityFingerprint.encodeToByteArray())
    }

    internal fun open(ciphertext: ByteArray, identityFingerprint: String): ByteArray {
        return xor(ciphertext, identityFingerprint.encodeToByteArray())
    }

    private fun xor(input: ByteArray, key: ByteArray): ByteArray {
        if (key.isEmpty()) {
            return input.copyOf()
        }
        return ByteArray(input.size) { index ->
            val value = input[index].toInt() xor key[index % key.size].toInt()
            value.toByte()
        }
    }
}
