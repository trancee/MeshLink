package ch.trancee.meshlink.crypto

import ch.trancee.meshlink.api.MeshLinkException
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Proves the "fail-closed runtime expectations" from `docs/rfcs/crypto/vector-policy.md`: a
 * rejected or all-zero X25519 shared secret must fail the Noise XX handshake before HKDF derivation
 * or transport dispatch, using a real Wycheproof low-order public key rather than a hand-rolled
 * one.
 *
 * Runs against whichever [CryptoProvider] the current test source set wires up via
 * [wycheproofProviderOrNull] (`JvmCryptoProvider` on `jvmTest`, `AndroidFallbackCryptoProvider` on
 * `androidHostTest`); silently does nothing where neither the provider nor the corpus resources are
 * available (`iosTest`), matching [WycheproofRegressionTest]'s existing convention.
 */
class CryptoProviderRuntimeContractTest {
    @Test
    fun `noise xx responder rejects a low-order ephemeral public key before deriving keys`() {
        // Arrange
        val provider = wycheproofProviderOrNull() ?: return
        val lowOrderPublicKey = lowOrderX25519PublicKeyOrNull() ?: return
        val responderIdentity = NoiseIdentity.generate(provider)
        val manager = NoiseXXHandshakeManager(provider)

        // Act / Assert: a genuine low-order message1 must never reach message2 construction --
        // the handshake must fail closed at the X25519/HKDF step, not proceed with a permissive
        // all-zero shared secret.
        assertFailsWith<MeshLinkException.CryptoFailure> {
            manager.processMessage1AndCreateMessage2(responderIdentity, lowOrderPublicKey)
        }
    }

    @Test
    fun `noise xx initiator rejects a low-order responder ephemeral key before deriving keys`() {
        // Arrange
        val provider = wycheproofProviderOrNull() ?: return
        val lowOrderPublicKey = lowOrderX25519PublicKeyOrNull() ?: return
        val manager = NoiseXXHandshakeManager(provider)
        manager.createMessage1()
        val initiatorIdentity = NoiseIdentity.generate(provider)
        val message2 = buildMessage2WithResponderEphemeral(lowOrderPublicKey)

        // Act / Assert: the crafted message2's ephemeral key alone forces an all-zero shared
        // secret at the first mixKey step, before any encrypted static payload is ever decrypted.
        assertFailsWith<MeshLinkException.CryptoFailure> {
            manager.processMessage2AndCreateMessage3(initiatorIdentity, message2)
        }
    }

    private fun lowOrderX25519PublicKeyOrNull(): ByteArray? {
        val vectors = CryptoPolicyCorpus.x25519PolicyVectorsOrNull() ?: return null
        val vector =
            vectors.firstOrNull { it.tcId == LOW_ORDER_ZERO_SHARED_SECRET_TC_ID }
                ?: error("Expected tcId=$LOW_ORDER_ZERO_SHARED_SECRET_TC_ID in the x25519 corpus")
        return vector.publicKey
    }

    private fun buildMessage2WithResponderEphemeral(
        responderEphemeralPublicKey: ByteArray
    ): ByteArray {
        return responderEphemeralPublicKey + ByteArray(MESSAGE2_REMAINDER_SIZE_BYTES)
    }

    private companion object {
        // x25519_test.json tcId=66: "public key with low order", flags LowOrderPublic +
        // NonCanonicalPublic + ZeroSharedSecret. Its encoding has the top (256th) bit set, so this
        // also regression-guards RFC 7748 SS5 u-coordinate masking: without masking that bit before
        // use, a provider derives the wrong (non-zero) shared secret instead of failing closed (see
        // JvmCryptoProvider.x25519PublicKey).
        private const val LOW_ORDER_ZERO_SHARED_SECRET_TC_ID = 66
        private const val MESSAGE2_REMAINDER_SIZE_BYTES = 96
    }
}
