package ch.trancee.meshlink.crypto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Test-only support for the tracked Wycheproof policy corpus described in
 * `docs/rfcs/crypto/vector-policy.md`.
 *
 * The corpus has two parts, both under `meshlink/src/commonTest/resources/wycheproof/`:
 * - `ed25519_test.json` / `x25519_test.json`: unmodified upstream C2SP Wycheproof test-vector files
 *   (`testGroups` -> `tests`, each with a `tcId` and Wycheproof's own `result` verdict).
 * - `policy.json`: MeshLink's own manifest classifying every tracked `tcId` into exactly one
 *   [CryptoPolicyVerdict] bucket. This is the file [CryptoPolicyCorpusTest] validates for
 *   completeness/staleness and the file [assertCryptoProviderMatchesPolicy] uses to decide, per
 *   vector, what a conforming [CryptoProvider] is allowed to do.
 */
private val corpusJson = Json { ignoreUnknownKeys = true }

@Serializable
internal data class PolicyManifest(val schemaVersion: Int, val buckets: List<PolicyBucket>)

@Serializable
internal data class PolicyBucket(
    val id: String,
    val algorithm: String,
    val wycheproofResult: String,
    val flags: List<String> = emptyList(),
    val policy: String,
    val tcIds: List<Int>,
)

@Serializable
internal data class Ed25519VectorFile(
    val numberOfTests: Int,
    val testGroups: List<Ed25519TestGroup>,
)

@Serializable
internal data class Ed25519TestGroup(
    val publicKey: Ed25519PublicKeyInfo,
    val tests: List<Ed25519TestCase>,
)

@Serializable internal data class Ed25519PublicKeyInfo(val pk: String)

@Serializable
internal data class Ed25519TestCase(
    val tcId: Int,
    val comment: String = "",
    val msg: String,
    val sig: String,
    val result: String,
)

@Serializable
internal data class X25519VectorFile(val numberOfTests: Int, val testGroups: List<X25519TestGroup>)

@Serializable
internal data class X25519TestGroup(val curve: String, val tests: List<X25519TestCase>)

@Serializable
internal data class X25519TestCase(
    val tcId: Int,
    val comment: String = "",
    val public: String,
    val private: String,
    val shared: String,
    val result: String,
)

/** MeshLink's classification of what a conforming [CryptoProvider] MUST do for a tracked vector. */
internal enum class CryptoPolicyVerdict {
    /** Provider MUST produce the vector's expected output. */
    ACCEPT,
    /** Provider MUST NOT accept the vector (return a negative result or throw). */
    REJECT,
    /** Provider MAY reject (throw) or accept, but an accepted value MUST match exactly. */
    FAIL_CLOSED_OR_MATCH,
}

internal fun String.toPolicyVerdictOrNull(): CryptoPolicyVerdict? =
    when (this) {
        "accept" -> CryptoPolicyVerdict.ACCEPT
        "reject" -> CryptoPolicyVerdict.REJECT
        "fail_closed_or_match" -> CryptoPolicyVerdict.FAIL_CLOSED_OR_MATCH
        else -> null
    }

internal class Ed25519PolicyVector
internal constructor(
    internal val tcId: Int,
    internal val comment: String,
    internal val policy: CryptoPolicyVerdict,
    internal val wycheproofResult: String,
    publicKey: ByteArray,
    message: ByteArray,
    signature: ByteArray,
) {
    internal val publicKey: ByteArray = publicKey.copyOf()
    internal val message: ByteArray = message.copyOf()
    internal val signature: ByteArray = signature.copyOf()
}

internal class X25519PolicyVector
internal constructor(
    internal val tcId: Int,
    internal val comment: String,
    internal val policy: CryptoPolicyVerdict,
    internal val wycheproofResult: String,
    privateKey: ByteArray,
    publicKey: ByteArray,
    sharedSecret: ByteArray,
) {
    internal val privateKey: ByteArray = privateKey.copyOf()
    internal val publicKey: ByteArray = publicKey.copyOf()
    internal val sharedSecret: ByteArray = sharedSecret.copyOf()
}

internal object CryptoPolicyCorpus {
    private const val ED25519 = "ed25519"
    private const val X25519 = "x25519"

    internal fun policyManifestOrNull(): PolicyManifest? =
        wycheproofResourceTextOrNull("policy.json")?.let(corpusJson::decodeFromString)

    internal fun ed25519VectorFileOrNull(): Ed25519VectorFile? =
        wycheproofResourceTextOrNull("ed25519_test.json")?.let(corpusJson::decodeFromString)

    internal fun x25519VectorFileOrNull(): X25519VectorFile? =
        wycheproofResourceTextOrNull("x25519_test.json")?.let(corpusJson::decodeFromString)

    /** Maps every policy-classified `tcId` for [algorithm] to its verdict and Wycheproof result. */
    internal fun bucketsByTcId(
        manifest: PolicyManifest,
        algorithm: String,
    ): Map<Int, PolicyBucket> {
        val result = linkedMapOf<Int, PolicyBucket>()
        manifest.buckets
            .filter { it.algorithm == algorithm }
            .forEach { bucket ->
                bucket.tcIds.forEach { tcId ->
                    check(result.put(tcId, bucket) == null) {
                        "Duplicate policy classification for $algorithm tcId=$tcId"
                    }
                }
            }
        return result
    }

    internal fun ed25519PolicyVectorsOrNull(): List<Ed25519PolicyVector>? {
        val manifest = policyManifestOrNull() ?: return null
        val vectors = ed25519VectorFileOrNull() ?: return null
        val buckets = bucketsByTcId(manifest, ED25519)
        return vectors.testGroups.flatMap { group ->
            group.tests.map { test ->
                val bucket =
                    buckets[test.tcId]
                        ?: error("ed25519 tcId=${test.tcId} is not classified in policy.json")
                Ed25519PolicyVector(
                    tcId = test.tcId,
                    comment = test.comment,
                    policy =
                        bucket.policy.toPolicyVerdictOrNull()
                            ?: error("Unknown policy '${bucket.policy}' for tcId=${test.tcId}"),
                    wycheproofResult = bucket.wycheproofResult,
                    publicKey = hexToBytes(group.publicKey.pk),
                    message = hexToBytes(test.msg),
                    signature = hexToBytes(test.sig),
                )
            }
        }
    }

    internal fun x25519PolicyVectorsOrNull(): List<X25519PolicyVector>? {
        val manifest = policyManifestOrNull() ?: return null
        val vectors = x25519VectorFileOrNull() ?: return null
        val buckets = bucketsByTcId(manifest, X25519)
        return vectors.testGroups.flatMap { group ->
            group.tests.map { test ->
                val bucket =
                    buckets[test.tcId]
                        ?: error("x25519 tcId=${test.tcId} is not classified in policy.json")
                X25519PolicyVector(
                    tcId = test.tcId,
                    comment = test.comment,
                    policy =
                        bucket.policy.toPolicyVerdictOrNull()
                            ?: error("Unknown policy '${bucket.policy}' for tcId=${test.tcId}"),
                    wycheproofResult = bucket.wycheproofResult,
                    privateKey = hexToBytes(test.private),
                    publicKey = hexToBytes(test.public),
                    sharedSecret = hexToBytes(test.shared),
                )
            }
        }
    }
}

internal fun hexToBytes(value: String): ByteArray {
    if (value.isEmpty()) return ByteArray(0)
    return value.chunked(2) { chunk -> chunk.toString().toInt(radix = 16).toByte() }.toByteArray()
}

/** Whole-file text for a resource under `meshlink/src/commonTest/resources/wycheproof/`. */
internal fun wycheproofResourceTextOrNull(fileName: String): String? =
    wycheproofResourceLinesOrNull(fileName)?.joinToString(separator = "\n")
