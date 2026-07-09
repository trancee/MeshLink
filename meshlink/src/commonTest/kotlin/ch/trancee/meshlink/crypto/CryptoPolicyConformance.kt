package ch.trancee.meshlink.crypto

import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Runs the full policy-classified Wycheproof corpus (`ed25519_test.json` / `x25519_test.json`,
 * classified by `policy.json`) against [provider] and asserts it honors every tracked verdict.
 *
 * Shared between [ch.trancee.meshlink.crypto.CryptoPolicyCorpusTest] callers on each platform, so
 * "does provider X conform to the policy manifest" only needs to be implemented once. Silently does
 * nothing if the corpus resources are not available on the current platform (see
 * [wycheproofResourceLinesOrNull]).
 */
internal fun assertCryptoProviderMatchesPolicy(provider: CryptoProvider) {
    CryptoPolicyCorpus.ed25519PolicyVectorsOrNull()?.forEach { vector ->
        assertEd25519VectorMatchesPolicy(provider, vector)
    }
    CryptoPolicyCorpus.x25519PolicyVectorsOrNull()?.forEach { vector ->
        assertX25519VectorMatchesPolicy(provider, vector)
    }
}

private fun assertEd25519VectorMatchesPolicy(
    provider: CryptoProvider,
    vector: Ed25519PolicyVector,
) {
    when (vector.policy) {
        CryptoPolicyVerdict.ACCEPT -> {
            val verified =
                provider.ed25519Verify(
                    publicKey = vector.publicKey,
                    message = vector.message,
                    signature = vector.signature,
                )
            assertTrue(
                verified,
                "Expected ed25519 tcId=${vector.tcId} (${vector.comment}) to verify",
            )
        }

        CryptoPolicyVerdict.REJECT -> {
            val verified =
                runCatching {
                        provider.ed25519Verify(
                            publicKey = vector.publicKey,
                            message = vector.message,
                            signature = vector.signature,
                        )
                    }
                    .getOrDefault(false)
            assertFalse(
                verified,
                "Expected ed25519 tcId=${vector.tcId} (${vector.comment}) to be rejected",
            )
        }

        CryptoPolicyVerdict.FAIL_CLOSED_OR_MATCH -> {
            error(
                "policy.json has no ed25519 fail_closed_or_match buckets; " +
                    "tcId=${vector.tcId} needs a corpus-integrity fix instead"
            )
        }
    }
}

private fun assertX25519VectorMatchesPolicy(provider: CryptoProvider, vector: X25519PolicyVector) {
    when (vector.policy) {
        CryptoPolicyVerdict.ACCEPT -> {
            val sharedSecret = provider.x25519(vector.privateKey, vector.publicKey)
            assertContentEquals(
                vector.sharedSecret,
                sharedSecret,
                "Expected x25519 tcId=${vector.tcId} (${vector.comment}) to derive the " +
                    "documented shared secret",
            )
        }

        CryptoPolicyVerdict.REJECT -> {
            var didThrow = false
            runCatching { provider.x25519(vector.privateKey, vector.publicKey) }
                .onFailure { didThrow = true }
            assertTrue(
                didThrow,
                "Expected x25519 tcId=${vector.tcId} (${vector.comment}) to be rejected",
            )
        }

        CryptoPolicyVerdict.FAIL_CLOSED_OR_MATCH -> {
            val result = runCatching { provider.x25519(vector.privateKey, vector.publicKey) }
            result.onSuccess { sharedSecret ->
                assertContentEquals(
                    vector.sharedSecret,
                    sharedSecret,
                    "x25519 tcId=${vector.tcId} (${vector.comment}) was accepted with the " +
                        "wrong shared secret",
                )
            }
            // A thrown exception (rejection) is also policy-conforming for this verdict.
        }
    }
}
