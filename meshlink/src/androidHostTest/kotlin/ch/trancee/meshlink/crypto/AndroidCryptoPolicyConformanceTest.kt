package ch.trancee.meshlink.crypto

import ch.trancee.meshlink.platform.android.crypto.JcaCryptoProvider
import kotlin.test.Test

/**
 * Runs the full `policy.json`-classified Wycheproof corpus against [JcaCryptoProvider] on host
 * execution -- the JCA-backed provider Android uses whenever the device's platform crypto provider
 * actually supports X25519/XDH and Ed25519 (see `JcaCryptoProviderFactory`).
 *
 * The pure-Kotlin fallback used on devices without that platform support is covered separately by
 * [WycheproofRegressionTest] against `AndroidFallbackCryptoProvider`; see that file's KDoc for why
 * it is held to the same bar. See `docs/rfcs/crypto/vector-policy.md` for the policy-verdict
 * contract this enforces.
 */
class AndroidCryptoPolicyConformanceTest {
    @Test
    fun `JcaCryptoProvider honors the ed25519 and x25519 policy manifest`() {
        // Arrange
        val provider = JcaCryptoProvider()

        // Act / Assert
        assertCryptoProviderMatchesPolicy(provider)
    }
}
