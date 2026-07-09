package ch.trancee.meshlink.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Validates `meshlink/src/commonTest/resources/wycheproof/policy.json` against the upstream
 * Wycheproof corpus files it classifies, independent of any [CryptoProvider].
 *
 * This is the "corpus integrity" surface described in `docs/rfcs/crypto/vector-policy.md`: every
 * tracked `tcId` must be classified exactly once, no bucket may reference a `tcId` that does not
 * exist in the corresponding vector file (a stale `tcId`), and a bucket's declared
 * `wycheproofResult` must match the vector file's own `result` for every `tcId` it claims.
 */
class CryptoPolicyCorpusTest {
    @Test
    fun `policy manifest uses a supported schema version`() {
        // Arrange
        val manifest = CryptoPolicyCorpus.policyManifestOrNull() ?: return

        // Act / Assert
        assertEquals(1, manifest.schemaVersion)
    }

    @Test
    fun `every policy bucket uses a known policy and wycheproof result`() {
        // Arrange
        val manifest = CryptoPolicyCorpus.policyManifestOrNull() ?: return

        // Act / Assert
        manifest.buckets.forEach { bucket ->
            assertTrue(
                bucket.policy.toPolicyVerdictOrNull() != null,
                "Bucket ${bucket.id} has unknown policy '${bucket.policy}'",
            )
            assertTrue(
                bucket.wycheproofResult in KNOWN_WYCHEPROOF_RESULTS,
                "Bucket ${bucket.id} has unknown wycheproofResult '${bucket.wycheproofResult}'",
            )
        }
    }

    @Test
    fun `every policy bucket pairs its policy with the matching wycheproof result`() {
        // Arrange
        val manifest = CryptoPolicyCorpus.policyManifestOrNull() ?: return

        // Act / Assert
        manifest.buckets.forEach { bucket ->
            val expectedResult =
                when (bucket.policy.toPolicyVerdictOrNull()) {
                    CryptoPolicyVerdict.ACCEPT -> "valid"
                    CryptoPolicyVerdict.REJECT -> "invalid"
                    CryptoPolicyVerdict.FAIL_CLOSED_OR_MATCH -> "acceptable"
                    null -> error("Bucket ${bucket.id} has unknown policy '${bucket.policy}'")
                }
            assertEquals(
                expectedResult,
                bucket.wycheproofResult,
                "Bucket ${bucket.id} pairs policy '${bucket.policy}' with an unexpected " +
                    "wycheproofResult",
            )
        }
    }

    @Test
    fun `no tcId is classified twice within an algorithm`() {
        // Arrange
        val manifest = CryptoPolicyCorpus.policyManifestOrNull() ?: return

        // Act / Assert
        manifest.buckets
            .map { it.algorithm }
            .distinct()
            .forEach { algorithm ->
                // bucketsByTcId itself throws on a duplicate; a successful call is the assertion.
                CryptoPolicyCorpus.bucketsByTcId(manifest, algorithm)
            }
    }

    @Test
    fun `ed25519 policy manifest classifies exactly the tracked corpus with matching results`() {
        // Arrange
        val manifest = CryptoPolicyCorpus.policyManifestOrNull() ?: return
        val vectors = CryptoPolicyCorpus.ed25519VectorFileOrNull() ?: return
        val buckets = CryptoPolicyCorpus.bucketsByTcId(manifest, "ed25519")
        val corpusTcIds =
            vectors.testGroups.flatMap { group -> group.tests.map { it.tcId } }.toSet()

        // Act / Assert
        assertEquals(
            vectors.numberOfTests,
            corpusTcIds.size,
            "ed25519_test.json numberOfTests does not match its own distinct tcId count",
        )
        assertEquals(
            corpusTcIds,
            buckets.keys,
            "policy.json ed25519 coverage drifted from the corpus",
        )
        vectors.testGroups.forEach { group ->
            group.tests.forEach { test ->
                val bucket = buckets.getValue(test.tcId)
                assertEquals(
                    test.result,
                    bucket.wycheproofResult,
                    "policy.json bucket ${bucket.id} disagrees with ed25519_test.json result " +
                        "for tcId=${test.tcId}",
                )
            }
        }
    }

    @Test
    fun `x25519 policy manifest classifies exactly the tracked corpus with matching results`() {
        // Arrange
        val manifest = CryptoPolicyCorpus.policyManifestOrNull() ?: return
        val vectors = CryptoPolicyCorpus.x25519VectorFileOrNull() ?: return
        val buckets = CryptoPolicyCorpus.bucketsByTcId(manifest, "x25519")
        val corpusTcIds =
            vectors.testGroups.flatMap { group -> group.tests.map { it.tcId } }.toSet()

        // Act / Assert
        assertEquals(
            vectors.numberOfTests,
            corpusTcIds.size,
            "x25519_test.json numberOfTests does not match its own distinct tcId count",
        )
        assertEquals(
            corpusTcIds,
            buckets.keys,
            "policy.json x25519 coverage drifted from the corpus",
        )
        vectors.testGroups.forEach { group ->
            group.tests.forEach { test ->
                val bucket = buckets.getValue(test.tcId)
                assertEquals(
                    test.result,
                    bucket.wycheproofResult,
                    "policy.json bucket ${bucket.id} disagrees with x25519_test.json result " +
                        "for tcId=${test.tcId}",
                )
            }
        }
    }

    private companion object {
        private val KNOWN_WYCHEPROOF_RESULTS = setOf("valid", "invalid", "acceptable")
    }
}
