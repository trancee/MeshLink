package ch.trancee.meshlink.platform.android

import ch.trancee.meshlink.api.MeshLinkException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CryptoProviderFactoryTest {
    @Test
    fun `create fails when the runtime lacks required key agreement support`() {
        // Arrange
        val capabilityReport =
            JcaCapabilityReport(
                supportsX25519 = false,
                supportsEd25519 = true,
                supportsChaCha20Poly1305 = true,
            )

        // Act
        val failure =
            assertFailsWith<MeshLinkException.CryptoFailure> {
                JcaCryptoProviderFactory.create(capabilityReport = capabilityReport)
            }

        // Assert
        assertTrue(failure.message.orEmpty().contains("required platform primitives"))
        assertTrue(failure.message.orEmpty().contains("X25519/XDH and ChaCha20-Poly1305"))
    }

    @Test
    fun `create selects the fallback provider when Ed25519 support is unavailable`() {
        // Arrange
        val capabilityReport =
            JcaCapabilityReport(
                supportsX25519 = true,
                supportsEd25519 = false,
                supportsChaCha20Poly1305 = true,
            )

        // Act
        val provider = JcaCryptoProviderFactory.create(capabilityReport = capabilityReport)

        // Assert
        assertIs<Ed25519FallbackCryptoProvider>(provider)
    }

    @Test
    fun `create returns the direct JCA provider when all capabilities are available`() {
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
