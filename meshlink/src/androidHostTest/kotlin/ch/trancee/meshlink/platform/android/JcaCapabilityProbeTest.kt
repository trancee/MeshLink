package ch.trancee.meshlink.platform.android

import ch.trancee.meshlink.platform.android.crypto.JcaCapabilityProbe
import ch.trancee.meshlink.platform.android.crypto.JcaCapabilityReport
import ch.trancee.meshlink.platform.android.crypto.xdhKeyAgreement
import ch.trancee.meshlink.platform.android.crypto.xdhKeyFactory
import ch.trancee.meshlink.platform.android.crypto.xdhKeyPairGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JcaCapabilityProbeTest {
    @Test
    fun `supportsMeshLinkRuntime requires both X25519 and ChaCha20 Poly1305 support`() {
        // Arrange
        val missingX25519 =
            JcaCapabilityReport(
                supportsX25519 = false,
                supportsEd25519 = true,
                supportsChaCha20Poly1305 = true,
            )
        val missingChaCha =
            JcaCapabilityReport(
                supportsX25519 = true,
                supportsEd25519 = true,
                supportsChaCha20Poly1305 = false,
            )
        val fullySupported =
            JcaCapabilityReport(
                supportsX25519 = true,
                supportsEd25519 = false,
                supportsChaCha20Poly1305 = true,
            )

        // Act / Assert
        assertEquals(false, missingX25519.supportsMeshLinkRuntime)
        assertEquals(false, missingChaCha.supportsMeshLinkRuntime)
        assertEquals(true, fullySupported.supportsMeshLinkRuntime)
    }

    @Test
    fun `detect produces a report whose aggregate runtime support matches its primitive flags`() {
        // Arrange / Act
        val report = JcaCapabilityProbe.detect()

        // Assert
        assertEquals(
            report.supportsX25519 && report.supportsChaCha20Poly1305,
            report.supportsMeshLinkRuntime,
        )
    }

    @Test
    fun `xdh helper factories provide working primitives when available`() {
        // Arrange
        val keyPairGenerator = xdhKeyPairGenerator()
        val keyFactory = xdhKeyFactory()
        val alice = keyPairGenerator.generateKeyPair()
        val bob = keyPairGenerator.generateKeyPair()

        // Act
        val aliceAgreement = xdhKeyAgreement()
        aliceAgreement.init(alice.private)
        aliceAgreement.doPhase(bob.public, true)
        val aliceSecret = aliceAgreement.generateSecret()

        val bobAgreement = xdhKeyAgreement()
        bobAgreement.init(bob.private)
        bobAgreement.doPhase(alice.public, true)
        val bobSecret = bobAgreement.generateSecret()

        // Assert
        assertTrue(aliceSecret.isNotEmpty())
        assertTrue(aliceSecret.contentEquals(bobSecret))
        assertTrue(keyFactory.algorithm == "XDH" || keyFactory.algorithm == "X25519")
    }
}
