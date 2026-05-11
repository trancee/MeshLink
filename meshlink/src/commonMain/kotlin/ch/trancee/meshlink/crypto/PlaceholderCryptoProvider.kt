package ch.trancee.meshlink.crypto

import ch.trancee.meshlink.api.MeshLinkException

internal object PlaceholderCryptoProvider : CryptoProvider {
    private var nonceSeed: Int = 1

    override fun randomBytes(size: Int): ByteArray {
        val start = nonceSeed
        nonceSeed += size + 1
        return ByteArray(size) { index -> (((start + index) * 31) and 0xFF).toByte() }
    }

    override fun sha256(input: ByteArray): ByteArray {
        return pseudoHash(input, HASH_SIZE_BYTES)
    }

    override fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        return pseudoHash(byteArrayOf(0x36) + key + byteArrayOf(0x5C) + data, HASH_SIZE_BYTES)
    }

    override fun generateX25519KeyPair(): X25519KeyPair {
        val rawKey = randomBytes(KEY_SIZE_BYTES)
        return X25519KeyPair(privateKey = rawKey.copyOf(), publicKey = rawKey.copyOf())
    }

    override fun generateEd25519KeyPair(): Ed25519KeyPair {
        val rawKey = randomBytes(KEY_SIZE_BYTES)
        return Ed25519KeyPair(privateKey = rawKey.copyOf(), publicKey = rawKey.copyOf())
    }

    override fun x25519(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        val left = privateKey.copyOf()
        val right = publicKey.copyOf()
        val combined =
            if (lexicographicallyCompare(left, right) <= 0) left + right else right + left
        return pseudoHash(combined, HASH_SIZE_BYTES)
    }

    override fun ed25519Sign(privateKey: ByteArray, message: ByteArray): ByteArray {
        return expandDigest(pseudoHash(privateKey + message, HASH_SIZE_BYTES), SIGNATURE_SIZE_BYTES)
    }

    override fun ed25519Verify(
        publicKey: ByteArray,
        message: ByteArray,
        signature: ByteArray,
    ): Boolean {
        return signature.contentEquals(ed25519Sign(publicKey, message))
    }

    override fun chacha20Poly1305Seal(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        plaintext: ByteArray,
    ): ByteArray {
        val keystream = expandDigest(pseudoHash(key + nonce + aad, HASH_SIZE_BYTES), plaintext.size)
        val ciphertext =
            ByteArray(plaintext.size) { index ->
                (plaintext[index].toInt() xor keystream[index].toInt()).toByte()
            }
        val tag = pseudoHash(key + nonce + aad + ciphertext, HASH_SIZE_BYTES).copyOf(TAG_SIZE_BYTES)
        return ciphertext + tag
    }

    override fun chacha20Poly1305Open(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray {
        if (ciphertext.size < TAG_SIZE_BYTES) {
            throw MeshLinkException.CryptoFailure(
                "Ciphertext is too short to contain an authentication tag"
            )
        }
        val cipherBytes = ciphertext.copyOfRange(0, ciphertext.size - TAG_SIZE_BYTES)
        val actualTag = ciphertext.copyOfRange(ciphertext.size - TAG_SIZE_BYTES, ciphertext.size)
        val expectedTag =
            pseudoHash(key + nonce + aad + cipherBytes, HASH_SIZE_BYTES).copyOf(TAG_SIZE_BYTES)
        if (!actualTag.contentEquals(expectedTag)) {
            throw MeshLinkException.CryptoFailure(
                "Ciphertext authentication tag verification failed"
            )
        }
        val keystream =
            expandDigest(pseudoHash(key + nonce + aad, HASH_SIZE_BYTES), cipherBytes.size)
        return ByteArray(cipherBytes.size) { index ->
            (cipherBytes[index].toInt() xor keystream[index].toInt()).toByte()
        }
    }

    private fun pseudoHash(input: ByteArray, outputSize: Int): ByteArray {
        var state = 0x811C9DC5.toInt()
        val safeInput = if (input.isNotEmpty()) input else byteArrayOf(0)
        val output =
            ByteArray(outputSize) { index ->
                val mixed = (safeInput[index % safeInput.size].toInt() and 0xFF) + index
                state = (state xor mixed) * 16777619
                (state ushr ((index and 3) * 8)).toByte()
            }
        if (output.all { it == 0.toByte() }) {
            output[0] = 1
        }
        return output
    }

    private fun expandDigest(seed: ByteArray, size: Int): ByteArray {
        val output = ByteArray(size)
        var offset = 0
        var counter = 0
        while (offset < size) {
            val block = pseudoHash(seed + counter.toByte(), HASH_SIZE_BYTES)
            val copySize = minOf(block.size, size - offset)
            block.copyInto(output, destinationOffset = offset, endIndex = copySize)
            offset += copySize
            counter += 1
        }
        return output
    }

    private fun lexicographicallyCompare(left: ByteArray, right: ByteArray): Int {
        val size = minOf(left.size, right.size)
        for (index in 0 until size) {
            val leftValue = left[index].toInt() and 0xFF
            val rightValue = right[index].toInt() and 0xFF
            if (leftValue != rightValue) {
                return leftValue - rightValue
            }
        }
        return left.size - right.size
    }

    private const val HASH_SIZE_BYTES: Int = 32
    private const val KEY_SIZE_BYTES: Int = 32
    private const val SIGNATURE_SIZE_BYTES: Int = 64
    private const val TAG_SIZE_BYTES: Int = 16
}
