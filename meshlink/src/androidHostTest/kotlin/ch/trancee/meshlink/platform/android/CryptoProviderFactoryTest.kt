package ch.trancee.meshlink.platform.android

import ch.trancee.meshlink.platform.android.crypto.AndroidFallbackCryptoProvider
import ch.trancee.meshlink.platform.android.crypto.JcaCapabilityReport
import ch.trancee.meshlink.platform.android.crypto.JcaCryptoProvider
import ch.trancee.meshlink.platform.android.crypto.JcaCryptoProviderFactory
import kotlin.test.Test
import kotlin.test.assertIs

class CryptoProviderFactoryTest {
    @Test
    fun `create returns fallback provider when runtime lacks key agreement support`() {
        // Arrange
        val capabilityReport =
            JcaCapabilityReport(
                supportsX25519 = false,
                supportsEd25519 = true,
                supportsChaCha20Poly1305 = true,
            )

        // Act
        val provider = JcaCryptoProviderFactory.create(capabilityReport = capabilityReport)

        // Assert
        assertIs<AndroidFallbackCryptoProvider>(provider)
    }

    @Test
    fun `create returns direct JCA provider when all capabilities are available`() {
        // Arrange
        val capabilityReport =
            JcaCapabilityReport(
                supportsX25519 = true,
                supportsEd25519 = true,
                supportsChaCha20Poly1305 = true,
            )

        // Act
        val provider = JcaCryptoProviderFactory.create(capabilityReport = capabilityReport)

        // Assert
        assertIs<JcaCryptoProvider>(provider)
    }
}
