package ch.trancee.meshlink.platform.android.crypto

import ch.trancee.meshlink.api.MeshLinkException
import ch.trancee.meshlink.crypto.CryptoProvider
import ch.trancee.meshlink.crypto.Ed25519KeyPair
import ch.trancee.meshlink.crypto.JcaSymmetricPrimitives
import ch.trancee.meshlink.crypto.X25519KeyPair
import ch.trancee.meshlink.crypto.requireValidX25519SharedSecret
import ch.trancee.meshlink.crypto.threadLocal
import ch.trancee.meshlink.crypto.value
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

internal class JcaCryptoProvider : CryptoProvider {
    private val symmetricPrimitives = JcaSymmetricPrimitives()
    private val xdhKeyPairGenerators = threadLocal(::xdhKeyPairGenerator)
    private val xdhKeyAgreements = threadLocal(::xdhKeyAgreement)
    private val xdhKeyFactories = threadLocal(::xdhKeyFactory)
    private val ed25519KeyPairGenerators = threadLocal { KeyPairGenerator.getInstance("Ed25519") }
    private val ed25519KeyFactories = threadLocal { KeyFactory.getInstance("Ed25519") }
    private val ed25519Signatures = threadLocal { Signature.getInstance("Ed25519") }
    private val ed25519Verifiers = threadLocal { Signature.getInstance("Ed25519") }

    override fun randomBytes(size: Int): ByteArray {
        return symmetricPrimitives.randomBytes(size)
    }

    override fun sha256(input: ByteArray): ByteArray {
        return symmetricPrimitives.sha256(input)
    }

    override fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        return symmetricPrimitives.hmacSha256(key, data)
    }

    override fun generateX25519KeyPair(): X25519KeyPair {
        val keyPair = xdhKeyPairGenerators.value().generateKeyPair()
        return X25519KeyPair(
            privateKey =
                decodePkcs8Raw(keyPair.private.encoded, X25519_PKCS8_PREAMBLE, "X25519 private"),
            publicKey = decodeX509Raw(keyPair.public.encoded, X25519_X509_PREAMBLE, "X25519 public"),
        )
    }

    override fun generateEd25519KeyPair(): Ed25519KeyPair {
        val keyPair = ed25519KeyPairGenerators.value().generateKeyPair()
        return Ed25519KeyPair(
            privateKey =
                decodePkcs8Raw(keyPair.private.encoded, ED25519_PKCS8_PREAMBLE, "Ed25519 private"),
            publicKey =
                decodeX509Raw(keyPair.public.encoded, ED25519_X509_PREAMBLE, "Ed25519 public"),
        )
    }

    override fun x25519(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        try {
            val keyAgreement = xdhKeyAgreements.value()
            keyAgreement.init(x25519PrivateKey(privateKey))
            keyAgreement.doPhase(x25519PublicKey(publicKey), true)
            return requireValidX25519SharedSecret(keyAgreement.generateSecret())
        } catch (error: GeneralSecurityException) {
            throw MeshLinkException.CryptoFailure("X25519 key agreement failed", error)
        }
    }

    override fun ed25519Sign(privateKey: ByteArray, message: ByteArray): ByteArray {
        val signature = ed25519Signatures.value()
        signature.initSign(ed25519PrivateKey(privateKey))
        signature.update(message)
        return signature.sign()
    }

    override fun ed25519Verify(
        publicKey: ByteArray,
        message: ByteArray,
        signature: ByteArray,
    ): Boolean {
        val verifier = ed25519Verifiers.value()
        verifier.initVerify(ed25519PublicKey(publicKey))
        verifier.update(message)
        return verifier.verify(signature)
    }

    override fun chacha20Poly1305Seal(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        plaintext: ByteArray,
    ): ByteArray {
        return symmetricPrimitives.chacha20Poly1305Seal(
            key = key,
            nonce = nonce,
            aad = aad,
            plaintext = plaintext,
        )
    }

    override fun chacha20Poly1305Open(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray {
        return symmetricPrimitives.chacha20Poly1305Open(
            key = key,
            nonce = nonce,
            aad = aad,
            ciphertext = ciphertext,
        )
    }

    private fun x25519PrivateKey(bytes: ByteArray) =
        xdhKeyFactories.value().generatePrivate(PKCS8EncodedKeySpec(X25519_PKCS8_PREAMBLE + bytes))

    private fun x25519PublicKey(bytes: ByteArray) =
        xdhKeyFactories.value().generatePublic(X509EncodedKeySpec(X25519_X509_PREAMBLE + bytes))

    private fun ed25519PrivateKey(bytes: ByteArray) =
        ed25519KeyFactories
            .value()
            .generatePrivate(PKCS8EncodedKeySpec(ED25519_PKCS8_PREAMBLE + bytes))

    private fun ed25519PublicKey(bytes: ByteArray) =
        ed25519KeyFactories
            .value()
            .generatePublic(X509EncodedKeySpec(ED25519_X509_PREAMBLE + bytes))

    private fun decodePkcs8Raw(encoded: ByteArray, preamble: ByteArray, label: String): ByteArray {
        if (!encoded.startsWith(preamble)) {
            throw MeshLinkException.CryptoFailure(
                "$label key does not use the expected PKCS#8 encoding"
            )
        }
        return encoded.copyOfRange(preamble.size, encoded.size)
    }

    private fun decodeX509Raw(encoded: ByteArray, preamble: ByteArray, label: String): ByteArray {
        if (!encoded.startsWith(preamble)) {
            throw MeshLinkException.CryptoFailure(
                "$label key does not use the expected X.509 encoding"
            )
        }
        return encoded.copyOfRange(preamble.size, encoded.size)
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        return size >= prefix.size && prefix.indices.all { index -> this[index] == prefix[index] }
    }

    private companion object {
        private val X25519_PKCS8_PREAMBLE =
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
                0x6e,
                0x04,
                0x22,
                0x04,
                0x20,
            )

        private val X25519_X509_PREAMBLE =
            byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x6e, 0x03, 0x21, 0x00)

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
    }
}
