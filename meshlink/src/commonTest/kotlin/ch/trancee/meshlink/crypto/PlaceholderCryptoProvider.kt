package ch.trancee.meshlink.crypto

import ch.trancee.meshlink.api.MeshLinkException

/**
 * Deterministic, non-cryptographic [CryptoProvider] stand-in used only by tests and benchmarks that
 * need fast, reproducible "crypto" without exercising a real JCA/fallback implementation.
 *
 * This type lives in `commonTest`, not `commonMain`, on purpose: per issue #118, it was previously
 * reachable from production `commonMain` defaults ([ch.trancee.meshlink.identity.LocalIdentity]'s
 * `fromAppId`/`fromPeerId` test-identity factories, and transitively `MeshEngine.create`'s default
 * `localIdentity` parameter) with no runtime or compile-time guard against a future integration
 * path silently ending up on fake crypto. Moving it here makes that impossible by construction --
 * `commonMain` production code cannot depend on `commonTest` types at all, so this object is now
 * unreachable from any shipped code path rather than merely unused by convention. See
 * `ch.trancee.meshlink.identity.LocalIdentityTestFactories` (same source set) for the
 * `fromAppId`/`fromPeerId` factories that were moved alongside it for the same reason.
 */
internal object PlaceholderCryptoProvider : CryptoProvider {
    private var nonceSeed: Int = INITIAL_NONCE_SEED

    override fun randomBytes(size: Int): ByteArray {
        val start = nonceSeed
        nonceSeed += size + 1
        return ByteArray(size) { index ->
            (((start + index) * NONCE_MULTIPLIER) and BYTE_MASK).toByte()
        }
    }

    override fun sha256(input: ByteArray): ByteArray {
        return pseudoHash(input, HASH_SIZE_BYTES)
    }

    override fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        return pseudoHash(
            byteArrayOf(HMAC_INNER_PAD) + key + byteArrayOf(HMAC_OUTER_PAD) + data,
            HASH_SIZE_BYTES,
        )
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
}

private fun pseudoHash(input: ByteArray, outputSize: Int): ByteArray {
    var state = PSEUDO_HASH_INITIAL_STATE.toInt()
    val safeInput = if (input.isNotEmpty()) input else byteArrayOf(ZERO_BYTE)
    // Fold every byte of the input into state before generating any output. Without this, an
    // outputSize smaller than input.size (e.g. hashing two concatenated 32-byte keys down to a
    // 32-byte digest, as x25519() does) would only ever read a bounded prefix window of input
    // (index % safeInput.size stays within the first outputSize bytes), so the digest could
    // ignore large parts of the input entirely -- e.g. two different Noise ephemeral keys paired
    // with the same peer key could derive the same "shared secret" purely because their
    // differing bytes were never sampled, breaking the distinctness this fake crypto must provide
    // for tests that rely on mismatched keys failing to decrypt each other's messages.
    for (index in safeInput.indices) {
        val mixed = (safeInput[index].toInt() and BYTE_MASK) + index
        state = (state xor mixed) * PSEUDO_HASH_PRIME
    }
    val output =
        ByteArray(outputSize) { index ->
            val mixed = (safeInput[index % safeInput.size].toInt() and BYTE_MASK) + index
            state = (state xor mixed) * PSEUDO_HASH_PRIME
            (state ushr ((index and BYTE_INDEX_MASK) * BITS_PER_BYTE)).toByte()
        }
    if (output.all { it == ZERO_BYTE }) {
        output[0] = NON_ZERO_SENTINEL_BYTE
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
        val leftValue = left[index].toInt() and BYTE_MASK
        val rightValue = right[index].toInt() and BYTE_MASK
        if (leftValue != rightValue) {
            return leftValue - rightValue
        }
    }
    return left.size - right.size
}

private const val INITIAL_NONCE_SEED: Int = 1
private const val NONCE_MULTIPLIER: Int = 31
private const val HASH_SIZE_BYTES: Int = 32
private const val KEY_SIZE_BYTES: Int = 32
private const val SIGNATURE_SIZE_BYTES: Int = 64
private const val TAG_SIZE_BYTES: Int = 16
private const val BYTE_MASK: Int = 0xFF
private const val PSEUDO_HASH_INITIAL_STATE: UInt = 0x811C9DC5u
private const val PSEUDO_HASH_PRIME: Int = 16777619
private const val BYTE_INDEX_MASK: Int = 3
private const val BITS_PER_BYTE: Int = 8
private const val ZERO_BYTE: Byte = 0
private const val NON_ZERO_SENTINEL_BYTE: Byte = 1
private const val HMAC_INNER_PAD: Byte = 0x36
private const val HMAC_OUTER_PAD: Byte = 0x5C
