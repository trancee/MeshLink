package ch.trancee.meshlink.platform.android

import ch.trancee.meshlink.api.MeshLinkException
import ch.trancee.meshlink.crypto.CryptoProvider
import ch.trancee.meshlink.crypto.Ed25519KeyPair
import ch.trancee.meshlink.crypto.X25519KeyPair
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal class AndroidCryptoProvider : CryptoProvider {
    override fun randomBytes(size: Int): ByteArray {
        return ByteArray(size).also { java.security.SecureRandom().nextBytes(it) }
    }

    override fun sha256(input: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(input)
    }

    override fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    override fun generateX25519KeyPair(): X25519KeyPair {
        val keyPair = xdhKeyPairGenerator().generateKeyPair()
        return X25519KeyPair(
            privateKey = decodePkcs8Raw(keyPair.private.encoded, X25519_PKCS8_PREAMBLE, "X25519 private"),
            publicKey = decodeX509Raw(keyPair.public.encoded, X25519_X509_PREAMBLE, "X25519 public"),
        )
    }

    override fun generateEd25519KeyPair(): Ed25519KeyPair {
        val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        return Ed25519KeyPair(
            privateKey = decodePkcs8Raw(keyPair.private.encoded, ED25519_PKCS8_PREAMBLE, "Ed25519 private"),
            publicKey = decodeX509Raw(keyPair.public.encoded, ED25519_X509_PREAMBLE, "Ed25519 public"),
        )
    }

    override fun x25519(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        val keyAgreement = xdhKeyAgreement()
        keyAgreement.init(x25519PrivateKey(privateKey))
        keyAgreement.doPhase(x25519PublicKey(publicKey), true)
        return keyAgreement.generateSecret()
    }

    override fun ed25519Sign(privateKey: ByteArray, message: ByteArray): ByteArray {
        val signature = Signature.getInstance("Ed25519")
        signature.initSign(ed25519PrivateKey(privateKey))
        signature.update(message)
        return signature.sign()
    }

    override fun ed25519Verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        val verifier = Signature.getInstance("Ed25519")
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
        val cipher = Cipher.getInstance("ChaCha20-Poly1305")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    override fun chacha20Poly1305Open(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance("ChaCha20-Poly1305")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    private fun x25519PrivateKey(bytes: ByteArray) =
        xdhKeyFactory().generatePrivate(PKCS8EncodedKeySpec(X25519_PKCS8_PREAMBLE + bytes))

    private fun x25519PublicKey(bytes: ByteArray) =
        xdhKeyFactory().generatePublic(X509EncodedKeySpec(X25519_X509_PREAMBLE + bytes))

    private fun ed25519PrivateKey(bytes: ByteArray) =
        KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(ED25519_PKCS8_PREAMBLE + bytes))

    private fun ed25519PublicKey(bytes: ByteArray) =
        KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(ED25519_X509_PREAMBLE + bytes))

    private fun decodePkcs8Raw(encoded: ByteArray, preamble: ByteArray, label: String): ByteArray {
        if (!encoded.startsWith(preamble)) {
            throw MeshLinkException.CryptoFailure("$label key does not use the expected PKCS#8 encoding")
        }
        return encoded.copyOfRange(preamble.size, encoded.size)
    }

    private fun decodeX509Raw(encoded: ByteArray, preamble: ByteArray, label: String): ByteArray {
        if (!encoded.startsWith(preamble)) {
            throw MeshLinkException.CryptoFailure("$label key does not use the expected X.509 encoding")
        }
        return encoded.copyOfRange(preamble.size, encoded.size)
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        return size >= prefix.size && prefix.indices.all { index -> this[index] == prefix[index] }
    }

    private companion object {
        private val X25519_PKCS8_PREAMBLE = byteArrayOf(
            0x30, 0x2e,
            0x02, 0x01, 0x00,
            0x30, 0x05,
            0x06, 0x03, 0x2b, 0x65, 0x6e,
            0x04, 0x22, 0x04, 0x20,
        )

        private val X25519_X509_PREAMBLE = byteArrayOf(
            0x30, 0x2a,
            0x30, 0x05,
            0x06, 0x03, 0x2b, 0x65, 0x6e,
            0x03, 0x21, 0x00,
        )

        private val ED25519_PKCS8_PREAMBLE = byteArrayOf(
            0x30, 0x2e,
            0x02, 0x01, 0x00,
            0x30, 0x05,
            0x06, 0x03, 0x2b, 0x65, 0x70,
            0x04, 0x22, 0x04, 0x20,
        )

        private val ED25519_X509_PREAMBLE = byteArrayOf(
            0x30, 0x2a,
            0x30, 0x05,
            0x06, 0x03, 0x2b, 0x65, 0x70,
            0x03, 0x21, 0x00,
        )
    }
}
