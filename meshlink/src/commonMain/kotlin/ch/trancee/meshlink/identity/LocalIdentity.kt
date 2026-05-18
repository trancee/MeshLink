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
            identityFingerprintText ?: identityFingerprintBytes.toHexString().also {
                identityFingerprintText = it
            }
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
    val seedBytes = if (encodedSeed.isNotEmpty()) encodedSeed else byteArrayOf(0)
    var state = 0x811C9DC5.toInt()
    val output =
        ByteArray(size) { index ->
            val mixedInput = (seedBytes[index % seedBytes.size].toInt() and 0xFF) + index
            state = (state xor mixedInput) * 16777619
            (state ushr ((index and 3) * 8)).toByte()
        }
    if (output.all { byte -> byte == 0.toByte() }) {
        output[0] = 1
    }
    return output
}

internal fun ByteArray.toHexString(): String {
    return joinToString(separator = "") { byte ->
        val value = byte.toInt() and 0xFF
        value.toString(radix = 16).padStart(length = 2, padChar = '0')
    }
}

internal fun String.toBytes(): ByteArray? {
    if ((length and 1) != 0) {
        return null
    }
    return ByteArray(length / 2) { index ->
        (decodeHexByte(charIndex = index * 2) ?: return null).toByte()
    }
}

internal fun String.hexContentEquals(bytes: ByteArray): Boolean {
    if (length != bytes.size * 2) {
        return false
    }
    for (index in bytes.indices) {
        val decoded = decodeHexByte(charIndex = index * 2) ?: return false
        if (decoded != (bytes[index].toInt() and 0xFF)) {
            return false
        }
    }
    return true
}

internal fun String.hexStartsWith(bytes: ByteArray): Boolean {
    if (length < bytes.size * 2) {
        return false
    }
    for (index in bytes.indices) {
        val decoded = decodeHexByte(charIndex = index * 2) ?: return false
        if (decoded != (bytes[index].toInt() and 0xFF)) {
            return false
        }
    }
    return true
}

private fun String.decodeHexByte(charIndex: Int): Int? {
    if (charIndex + 1 >= length) {
        return null
    }
    val high = decodeHexNibble(this[charIndex]) ?: return null
    val low = decodeHexNibble(this[charIndex + 1]) ?: return null
    return (high shl 4) or low
}

private fun decodeHexNibble(value: Char): Int? {
    return when (value) {
        in '0'..'9' -> value.code - '0'.code
        in 'a'..'f' -> value.code - 'a'.code + 10
        in 'A'..'F' -> value.code - 'A'.code + 10
        else -> null
    }
}

private fun decodeHexNibble(value: Int): Int? {
    return when (value) {
        in '0'.code..'9'.code -> value - '0'.code
        in 'a'.code..'f'.code -> value - 'a'.code + 10
        in 'A'.code..'F'.code -> value - 'A'.code + 10
        else -> null
    }
}
