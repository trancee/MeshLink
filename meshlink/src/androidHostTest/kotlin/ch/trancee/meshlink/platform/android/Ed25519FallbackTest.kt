package ch.trancee.meshlink.platform.android

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Ed25519FallbackTest {
    private val fallback = Ed25519Fallback(randomBytesProvider = { size -> ByteArray(size) })

    @Test
    fun `fallback matches RFC 8032 test vector 1`() {
        // Arrange
        val privateKey = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        val message = byteArrayOf()
        val expectedPublicKey =
            hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
        val expectedSignature =
            hex(
                "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"
            )

        // Act
        val keyPair = fallback.deriveKeyPair(privateKey)
        val signature = fallback.sign(privateKey, message)
        val isValid = fallback.verify(keyPair.publicKey, message, signature)

        // Assert
        assertContentEquals(expectedPublicKey, keyPair.publicKey)
        assertContentEquals(expectedSignature, signature)
        assertTrue(
            isValid,
            "The fallback should verify the RFC 8032 reference signature it produced",
        )
    }

    @Test
    fun `fallback signatures verify with JCA Ed25519`() {
        // Arrange
        val privateKey = hex("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb")
        val message = "meshlink-fallback-signature".encodeToByteArray()
        val keyPair = fallback.deriveKeyPair(privateKey)

        // Act
        val signature = fallback.sign(privateKey, message)
        val isValid = verifyWithJca(keyPair.publicKey, message, signature)

        // Assert
        assertTrue(
            isValid,
            "Fallback signatures must remain interoperable with JCA Ed25519 verifiers",
        )
    }

    @Test
    fun `fallback verifies JCA Ed25519 signatures`() {
        // Arrange
        val privateKey = hex("c5aa8df43f9f837bedb7442f31dcb7b166d38535076f094b85ce3a2e0b4458f7")
        val message = "meshlink-fallback-verify".encodeToByteArray()
        val keyPair = fallback.deriveKeyPair(privateKey)
        val signature = signWithJca(privateKey, message)

        // Act
        val isValid = fallback.verify(keyPair.publicKey, message, signature)

        // Assert
        assertTrue(
            isValid,
            "The fallback verifier must accept interoperable JCA Ed25519 signatures",
        )
    }

    @Test
    fun `fallback provider is selected when JCA Ed25519 is missing`() {
        // Arrange
        val provider =
            JcaCryptoProviderFactory.create(
                capabilityReport =
                    JcaCapabilityReport(
                        supportsX25519 = true,
                        supportsEd25519 = false,
                        supportsChaCha20Poly1305 = true,
                    )
            )
        val message = "meshlink-provider-selection".encodeToByteArray()

        // Act
        val keyPair = provider.generateEd25519KeyPair()
        val signature = provider.ed25519Sign(keyPair.privateKey, message)
        val isValid = provider.ed25519Verify(keyPair.publicKey, message, signature)

        // Assert
        assertTrue(provider is Ed25519FallbackCryptoProvider)
        assertTrue(
            isValid,
            "Fallback-backed providers must expose fully working Ed25519 operations",
        )
        assertTrue(
            verifyWithJca(keyPair.publicKey, message, signature),
            "Fallback-backed provider signatures must remain JCA-interoperable",
        )
    }

    @Test
    fun `fallback rejects signatures with non canonical S component`() {
        // Arrange
        val privateKey = hex("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb")
        val message = byteArrayOf(0x72)
        val keyPair = fallback.deriveKeyPair(privateKey)
        val signature =
            fallback.sign(privateKey, message).also { tampered ->
                GROUP_ORDER.copyInto(tampered, destinationOffset = 32)
            }

        // Act
        val isValid = fallback.verify(keyPair.publicKey, message, signature)

        // Assert
        assertFalse(
            isValid,
            "The fallback must reject malleable signatures where S is not canonical",
        )
    }

    private fun signWithJca(privateKey: ByteArray, message: ByteArray): ByteArray {
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(
            KeyFactory.getInstance("Ed25519")
                .generatePrivate(PKCS8EncodedKeySpec(ED25519_PKCS8_PREAMBLE + privateKey))
        )
        signer.update(message)
        return signer.sign()
    }

    private fun verifyWithJca(
        publicKey: ByteArray,
        message: ByteArray,
        signature: ByteArray,
    ): Boolean {
        val verifier = Signature.getInstance("Ed25519")
        verifier.initVerify(
            KeyFactory.getInstance("Ed25519")
                .generatePublic(X509EncodedKeySpec(ED25519_X509_PREAMBLE + publicKey))
        )
        verifier.update(message)
        return verifier.verify(signature)
    }

    private fun hex(value: String): ByteArray {
        return value.chunked(2) { chunk -> chunk.toString().toInt(16).toByte() }.toByteArray()
    }

    private companion object {
        private val ED25519_PKCS8_PREAMBLE =
            byteArrayOf(
                0x30,
                0x2e,
                0x02,
                0x01,
                0x00,
                0x30,
                0x05,
                0x06,
                0x03,
                0x2b,
                0x65,
                0x70,
                0x04,
                0x22,
                0x04,
                0x20,
            )

        private val ED25519_X509_PREAMBLE =
            byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)

        private val GROUP_ORDER =
            byteArrayOf(
                0xed.toByte(),
                0xd3.toByte(),
                0xf5.toByte(),
                0x5c.toByte(),
                0x1a.toByte(),
                0x63.toByte(),
                0x12.toByte(),
                0x58.toByte(),
                0xd6.toByte(),
                0x9c.toByte(),
                0xf7.toByte(),
                0xa2.toByte(),
                0xde.toByte(),
                0xf9.toByte(),
                0xde.toByte(),
                0x14.toByte(),
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
                0x10,
            )
    }
}
