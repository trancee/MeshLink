package ch.trancee.meshlink.crypto

import ch.trancee.meshlink.api.MeshLinkException
import ch.trancee.meshlink.platform.android.JcaCapabilityProbe
import ch.trancee.meshlink.platform.android.JcaCryptoProviderFactory
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Proves the fail-closed Noise XX handshake contract (see
 * [CryptoProviderRuntimeContractTest]/`docs/rfcs/crypto/vector-policy.md`) against whichever
 * provider [JcaCryptoProviderFactory] actually selects on this JVM -- `JcaCryptoProvider`,
 * `Ed25519FallbackCryptoProvider`, or `AndroidFallbackCryptoProvider` -- rather than a single
 * hand-picked provider. This is the Android-runtime-specific evidence: capability probing means
 * production devices can land on any of those three at connect time, so the fail-closed contract
 * must hold for whichever one `detect()` picks, not only for the one the host JVM happens to favor.
 */
class MeshRuntimeAndroidCryptoTest {
    @Test
    fun `runtime-selected android provider rejects a low-order ephemeral key before deriving keys`() {
        // Arrange
        val provider = JcaCryptoProviderFactory.create(JcaCapabilityProbe.detect())
        val vectors = CryptoPolicyCorpus.x25519PolicyVectorsOrNull() ?: return
        val lowOrderPublicKey =
            vectors.firstOrNull { it.tcId == LOW_ORDER_ZERO_SHARED_SECRET_TC_ID }?.publicKey
                ?: error("Expected tcId=$LOW_ORDER_ZERO_SHARED_SECRET_TC_ID in the x25519 corpus")
        val responderIdentity = NoiseIdentity.generate(provider)
        val manager = NoiseXXHandshakeManager(provider)

        // Act / Assert
        assertFailsWith<MeshLinkException.CryptoFailure> {
            manager.processMessage1AndCreateMessage2(responderIdentity, lowOrderPublicKey)
        }
    }

    private companion object {
        // Same tracked vector as CryptoProviderRuntimeContractTest: x25519_test.json tcId=66
        // (non-canonical, top-bit-set low-order public key -- see that test's KDoc).
        private const val LOW_ORDER_ZERO_SHARED_SECRET_TC_ID = 66
    }
}
