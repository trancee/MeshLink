package ch.trancee.meshlink.identity

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.crypto.CryptoProvider
import ch.trancee.meshlink.crypto.Ed25519KeyPair
import ch.trancee.meshlink.crypto.NoiseIdentity
import ch.trancee.meshlink.crypto.PlaceholderCryptoProvider
import ch.trancee.meshlink.crypto.X25519KeyPair

internal class LocalIdentity
internal constructor(
    internal val peerId: PeerId,
    identityFingerprint: String? = null,
    identityFingerprintBytes: ByteArray,
    internal val noiseIdentity: NoiseIdentity,
    internal val cryptoProvider: CryptoProvider,
    advertisementKeyHash: ByteArray,
) {
    private var identityFingerprintText: String? = identityFingerprint
    internal val identityFingerprintBytes: ByteArray = identityFingerprintBytes.copyOf()
    internal val identityFingerprint: String
        get() =
            identityFingerprintText
                ?: identityFingerprintBytes.toHexString().also { identityFingerprintText = it }

    internal val advertisementKeyHash: ByteArray = advertisementKeyHash.copyOf()
    internal val ed25519PublicKey: ByteArray = noiseIdentity.ed25519KeyPair.publicKey.copyOf()
    internal val x25519PublicKey: ByteArray = noiseIdentity.x25519KeyPair.publicKey.copyOf()

    internal companion object {
        internal fun fromAppId(appId: String): LocalIdentity {
            return fromPeerId(peerId = PeerId(appId), identitySeed = appId)
        }

        internal fun fromPeerId(peerId: PeerId, identitySeed: String): LocalIdentity {
            val noiseIdentity =
                NoiseIdentity(
                    ed25519KeyPair =
                        Ed25519KeyPair(
                            privateKey =
                                deterministicBytes("$identitySeed|ed25519", size = KEY_SIZE_BYTES),
                            publicKey =
                                deterministicBytes("$identitySeed|ed25519", size = KEY_SIZE_BYTES),
                        ),
                    x25519KeyPair =
                        X25519KeyPair(
                            privateKey =
                                deterministicBytes("$identitySeed|x25519", size = KEY_SIZE_BYTES),
                            publicKey =
                                deterministicBytes("$identitySeed|x25519", size = KEY_SIZE_BYTES),
                        ),
                )
            val publicKeyHash =
                PlaceholderCryptoProvider.sha256(
                    noiseIdentity.ed25519KeyPair.publicKey + noiseIdentity.x25519KeyPair.publicKey
                )
            return LocalIdentity(
                peerId = peerId,
                identityFingerprintBytes = publicKeyHash,
                noiseIdentity = noiseIdentity,
                cryptoProvider = PlaceholderCryptoProvider,
                advertisementKeyHash =
                    publicKeyHash.copyOfRange(0, ADVERTISEMENT_KEY_HASH_SIZE_BYTES),
            )
        }

        internal fun fromNoiseIdentity(
            noiseIdentity: NoiseIdentity,
            provider: CryptoProvider,
            peerId: PeerId? = null,
        ): LocalIdentity {
            val publicKeyHash =
                provider.sha256(
                    noiseIdentity.ed25519KeyPair.publicKey + noiseIdentity.x25519KeyPair.publicKey
                )
            val derivedPeerId =
                peerId ?: PeerId(publicKeyHash.copyOfRange(0, PEER_ID_SIZE_BYTES).toHexString())
            return LocalIdentity(
                peerId = derivedPeerId,
                identityFingerprintBytes = publicKeyHash,
                noiseIdentity = noiseIdentity,
                cryptoProvider = provider,
                advertisementKeyHash =
                    publicKeyHash.copyOfRange(0, ADVERTISEMENT_KEY_HASH_SIZE_BYTES),
            )
        }

        private const val ADVERTISEMENT_KEY_HASH_SIZE_BYTES: Int = 12
        private const val KEY_SIZE_BYTES: Int = 32
        private const val PEER_ID_SIZE_BYTES: Int = 20
    }
}

private fun deterministicBytes(seed: String, size: Int): ByteArray {
    val encodedSeed = seed.encodeToByteArray()
    val seedBytes = if (encodedSeed.isNotEmpty()) encodedSeed else byteArrayOf(ZERO_BYTE)
    var state = FNV_OFFSET_BASIS.toInt()
    val output =
        ByteArray(size) { index ->
            val mixedInput = (seedBytes[index % seedBytes.size].toInt() and BYTE_MASK) + index
            state = (state xor mixedInput) * FNV_PRIME
            (state ushr ((index and BYTE_INDEX_MASK) * BITS_PER_BYTE)).toByte()
        }
    if (output.all { byte -> byte == ZERO_BYTE }) {
        output[0] = NON_ZERO_SENTINEL_BYTE
    }
    return output
}

internal fun ByteArray.toHexString(): String {
    return joinToString(separator = "") { byte ->
        val value = byte.toInt() and BYTE_MASK
        value.toString(radix = HEX_RADIX).padStart(length = HEX_BYTE_LENGTH, padChar = '0')
    }
}

internal fun String.toBytes(): ByteArray? {
    val hasEvenLength = (length and 1) == 0
    if (!hasEvenLength) {
        return null
    }

    var invalidHexDetected = false
    val decodedBytes =
        ByteArray(length / HEX_BYTE_LENGTH) { index ->
            val decoded = decodeHexByte(charIndex = index * HEX_BYTE_LENGTH)
            if (decoded == null) {
                    invalidHexDetected = true
                    ZERO_BYTE.toInt()
                } else {
                    decoded
                }
                .toByte()
        }
    return decodedBytes.takeUnless { invalidHexDetected }
}

internal fun String.hexContentEquals(bytes: ByteArray): Boolean {
    return length == bytes.size * HEX_BYTE_LENGTH &&
        bytes.indices.all { index ->
            decodeHexByte(charIndex = index * HEX_BYTE_LENGTH) ==
                (bytes[index].toInt() and BYTE_MASK)
        }
}

internal fun String.hexStartsWith(bytes: ByteArray): Boolean {
    return length >= bytes.size * HEX_BYTE_LENGTH &&
        bytes.indices.all { index ->
            decodeHexByte(charIndex = index * HEX_BYTE_LENGTH) ==
                (bytes[index].toInt() and BYTE_MASK)
        }
}

private fun String.decodeHexByte(charIndex: Int): Int? {
    val hasCompleteByte = charIndex + 1 < length
    val highNibble = if (hasCompleteByte) decodeHexNibble(this[charIndex]) else null
    val lowNibble = if (hasCompleteByte) decodeHexNibble(this[charIndex + 1]) else null

    return if (highNibble != null && lowNibble != null) {
        (highNibble shl HIGH_NIBBLE_SHIFT) or lowNibble
    } else {
        null
    }
}

private fun decodeHexNibble(value: Char): Int? {
    return when (value) {
        in '0'..'9' -> value.code - '0'.code
        in 'a'..'f' -> value.code - 'a'.code + HEX_ALPHA_OFFSET
        in 'A'..'F' -> value.code - 'A'.code + HEX_ALPHA_OFFSET
        else -> null
    }
}

private fun decodeHexNibble(value: Int): Int? {
    return when (value) {
        in '0'.code..'9'.code -> value - '0'.code
        in 'a'.code..'f'.code -> value - 'a'.code + HEX_ALPHA_OFFSET
        in 'A'.code..'F'.code -> value - 'A'.code + HEX_ALPHA_OFFSET
        else -> null
    }
}

private const val BYTE_MASK: Int = 0xFF
private const val BITS_PER_BYTE: Int = 8
private const val BYTE_INDEX_MASK: Int = 3
private const val FNV_OFFSET_BASIS: UInt = 0x811C9DC5u
private const val FNV_PRIME: Int = 16777619
private const val HEX_ALPHA_OFFSET: Int = 10
private const val HEX_BYTE_LENGTH: Int = 2
private const val HEX_RADIX: Int = 16
private const val HIGH_NIBBLE_SHIFT: Int = 4
private const val NON_ZERO_SENTINEL_BYTE: Byte = 1
private const val ZERO_BYTE: Byte = 0
