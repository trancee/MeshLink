package ch.trancee.meshlink.platform.android

import ch.trancee.meshlink.platform.android.crypto.AndroidFallbackCryptoProvider
import ch.trancee.meshlink.platform.android.crypto.JcaCapabilityReport
import ch.trancee.meshlink.platform.android.crypto.JcaCryptoProviderFactory
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs

class CryptoRuntimeCapabilityTest {
    @Test
    fun `supportsMeshLinkRuntime requires x25519 and ChaCha20-Poly1305`() {
        // Arrange
        val capabilityReport =
            JcaCapabilityReport(
                supportsX25519 = true,
                supportsEd25519 = true,
                supportsChaCha20Poly1305 = false,
            )

        // Act
        val actual = capabilityReport.supportsMeshLinkRuntime

        // Assert
        assertFalse(actual)
    }

    @Test
    fun `create returns fallback provider when the runtime lacks ChaCha20-Poly1305 support`() {
        // Arrange
        val capabilityReport =
            JcaCapabilityReport(
                supportsX25519 = true,
                supportsEd25519 = true,
                supportsChaCha20Poly1305 = false,
            )

        // Act
        val provider = JcaCryptoProviderFactory.create(capabilityReport = capabilityReport)

        // Assert
        assertIs<AndroidFallbackCryptoProvider>(provider)
    }
}
