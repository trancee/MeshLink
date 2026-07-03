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
    meshDomainHash: ByteArray = DEFAULT_MESH_DOMAIN_HASH,
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

    /**
     * Cryptographically identifies the mesh this identity belongs to. Mixed into every Noise XX
     * handshake this identity performs (see [ch.trancee.meshlink.crypto.NoiseXXHandshakeManager])
     * so that handshakes can only complete between peers configured with the same mesh domain (i.e.
     * the same [ch.trancee.meshlink.config.MeshLinkConfig.appId]). Defaults to an empty prologue,
     * which reproduces the historical "any peer can complete the handshake" behavior for callers
     * (mostly tests) that construct identities without an explicit mesh domain.
     */
    internal val meshDomainHash: ByteArray = meshDomainHash.copyOf()

    internal companion object {
        internal fun fromAppId(
            appId: String,
            meshDomainHash: ByteArray = DEFAULT_MESH_DOMAIN_HASH,
        ): LocalIdentity {
            return fromPeerId(
                peerId = PeerId(appId),
                identitySeed = appId,
                meshDomainHash = meshDomainHash,
            )
        }

        internal fun fromPeerId(
            peerId: PeerId,
            identitySeed: String,
            meshDomainHash: ByteArray = DEFAULT_MESH_DOMAIN_HASH,
        ): LocalIdentity {
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
                meshDomainHash = meshDomainHash,
            )
        }

        internal fun fromNoiseIdentity(
            noiseIdentity: NoiseIdentity,
            provider: CryptoProvider,
            peerId: PeerId? = null,
            meshDomainHash: ByteArray = DEFAULT_MESH_DOMAIN_HASH,
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
                meshDomainHash = meshDomainHash,
            )
        }

        /**
         * Derives a stable mesh-domain-binding hash from [appId] using [provider]. Two identities
         * built with the same [appId] (and a provider whose [CryptoProvider.sha256] is
         * deterministic for identical input, which is true of every real implementation) always
         * derive the same value, so peers configured for the same mesh can complete Noise XX
         * handshakes with each other, while peers configured for different meshes cannot.
         */
        internal fun computeMeshDomainHash(appId: String, provider: CryptoProvider): ByteArray {
            return provider.sha256((MESH_DOMAIN_PROLOGUE_PREFIX + appId).encodeToByteArray())
        }

        private const val MESH_DOMAIN_PROLOGUE_PREFIX: String = "MeshLink-mesh-domain-v1:"
        private val DEFAULT_MESH_DOMAIN_HASH: ByteArray = ByteArray(0)
        private const val ADVERTISEMENT_KEY_HASH_SIZE_BYTES: Int = 12
        private const val KEY_SIZE_BYTES: Int = 32

        /**
         * Must match [ch.trancee.meshlink.transport.BleDiscoveryContract.KEY_HASH_SIZE_BYTES], the
         * canonical hash-prefix length used everywhere else a peer's identity-derived id is
         * computed (BLE discovery/advertisement and HOP-level trust pinning via
         * [canonicalPeerIdForTemporaryTransportPeer]). A prior mismatch here (20 vs. 12 bytes)
         * caused [ch.trancee.meshlink.api.PeerId] values derived by this function to never match
         * the canonical id a receiving peer pins trust under, so every direct message failed
         * `trust.verify.untrusted` despite a successful handshake.
         */
        private const val PEER_ID_SIZE_BYTES: Int = 12
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
