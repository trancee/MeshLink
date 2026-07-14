package ch.trancee.meshlink.identity

import ch.trancee.meshlink.crypto.Ed25519KeyPair
import ch.trancee.meshlink.crypto.NoiseIdentity
import ch.trancee.meshlink.crypto.PlaceholderCryptoProvider
import ch.trancee.meshlink.crypto.X25519KeyPair

/**
 * Deterministic-seed [LocalIdentity] factories used only by tests and benchmarks, backed by
 * [PlaceholderCryptoProvider]'s fake (non-cryptographic) primitives.
 *
 * Per issue #118, these live in `commonTest` rather than `commonMain` -- unlike
 * [LocalIdentity.fromNoiseIdentity]/[LocalIdentity.computeMeshDomainHash], which take a real
 * [ch.trancee.meshlink.crypto.CryptoProvider] explicitly and stay in `commonMain`, `fromAppId`/
 * `fromPeerId` hardcoded [PlaceholderCryptoProvider] internally with no way for a caller to supply
 * a real provider instead. No shipping production code path called either function (every real
 * platform factory already constructs its `LocalIdentity` via `loadOrCreateLocalIdentityBlocking`
 * with a real provider), so moving them here makes that unreachability a compile-time guarantee
 * rather than a fact a reader has to independently verify by tracing call sites.
 */
internal fun LocalIdentity.Companion.fromAppId(
    appId: String,
    meshDomainHash: ByteArray = DEFAULT_MESH_DOMAIN_HASH_FOR_TESTS,
): LocalIdentity {
    return fromPeerId(
        peerId = ch.trancee.meshlink.api.PeerId(appId),
        identitySeed = appId,
        meshDomainHash = meshDomainHash,
    )
}

internal fun LocalIdentity.Companion.fromPeerId(
    peerId: ch.trancee.meshlink.api.PeerId,
    identitySeed: String,
    meshDomainHash: ByteArray = DEFAULT_MESH_DOMAIN_HASH_FOR_TESTS,
): LocalIdentity {
    val noiseIdentity =
        NoiseIdentity(
            ed25519KeyPair =
                Ed25519KeyPair(
                    privateKey = deterministicBytes("$identitySeed|ed25519", size = KEY_SIZE_BYTES),
                    publicKey = deterministicBytes("$identitySeed|ed25519", size = KEY_SIZE_BYTES),
                ),
            x25519KeyPair =
                X25519KeyPair(
                    privateKey = deterministicBytes("$identitySeed|x25519", size = KEY_SIZE_BYTES),
                    publicKey = deterministicBytes("$identitySeed|x25519", size = KEY_SIZE_BYTES),
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
        advertisementKeyHash = publicKeyHash.copyOfRange(0, ADVERTISEMENT_KEY_HASH_SIZE_BYTES),
        meshDomainHash = meshDomainHash,
    )
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

private val DEFAULT_MESH_DOMAIN_HASH_FOR_TESTS: ByteArray = ByteArray(0)
private const val ADVERTISEMENT_KEY_HASH_SIZE_BYTES: Int = 12
private const val KEY_SIZE_BYTES: Int = 32
private const val BYTE_MASK: Int = 0xFF
private const val BITS_PER_BYTE: Int = 8
private const val BYTE_INDEX_MASK: Int = 3
private const val FNV_OFFSET_BASIS: UInt = 0x811C9DC5u
private const val FNV_PRIME: Int = 16777619
private const val NON_ZERO_SENTINEL_BYTE: Byte = 1
private const val ZERO_BYTE: Byte = 0
