package ch.trancee.meshlink.crypto

import kotlin.test.Test

/**
 * Runs the full `policy.json`-classified Wycheproof corpus against [JvmCryptoProvider], the
 * provider MeshLink ships for the JVM target.
 *
 * See `docs/rfcs/crypto/vector-policy.md` for the policy-verdict contract this enforces.
 */
class JvmCryptoPolicyConformanceTest {
    @Test
    fun `JvmCryptoProvider honors the ed25519 and x25519 policy manifest`() {
        // Arrange
        val provider = JvmCryptoProvider()

        // Act / Assert
        assertCryptoProviderMatchesPolicy(provider)
    }
}
