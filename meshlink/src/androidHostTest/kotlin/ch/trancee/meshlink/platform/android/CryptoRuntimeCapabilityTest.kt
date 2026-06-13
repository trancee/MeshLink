package ch.trancee.meshlink.platform.android

import ch.trancee.meshlink.api.MeshLinkException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
    fun `create fails when the runtime lacks ChaCha20-Poly1305 support`() {
        // Arrange
        val capabilityReport =
            JcaCapabilityReport(
                supportsX25519 = true,
                supportsEd25519 = true,
                supportsChaCha20Poly1305 = false,
            )

        // Act
        val failure =
            assertFailsWith<MeshLinkException.CryptoFailure> {
                JcaCryptoProviderFactory.create(capabilityReport = capabilityReport)
            }

        // Assert
        assertTrue(failure.message.orEmpty().contains("ChaCha20-Poly1305"))
    }
}
