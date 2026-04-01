@file:JvmName("CryptoProviderJvm")

package io.meshlink.crypto

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

actual fun CryptoProvider(): CryptoProvider = JvmCryptoProvider()

internal class JvmCryptoProvider : CryptoProvider {

    // ASN.1 prefixes for raw ↔ encoded key conversion
    private val ed25519Pkcs8Prefix = byteArrayOf(
        0x30, 0x2E, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06,
        0x03, 0x2B, 0x65, 0x70, 0x04, 0x22, 0x04, 0x20,
    )
    private val ed25519X509Prefix = byteArrayOf(
        0x30, 0x2A, 0x30, 0x05, 0x06, 0x03, 0x2B, 0x65,
        0x70, 0x03, 0x21, 0x00,
    )
    private val x25519Pkcs8Prefix = byteArrayOf(
        0x30, 0x2E, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06,
        0x03, 0x2B, 0x65, 0x6E, 0x04, 0x22, 0x04, 0x20,
    )
    private val x25519X509Prefix = byteArrayOf(
        0x30, 0x2A, 0x30, 0x05, 0x06, 0x03, 0x2B, 0x65,
        0x6E, 0x03, 0x21, 0x00,
    )

    override fun generateEd25519KeyPair(): CryptoKeyPair {
        val kpg = KeyPairGenerator.getInstance("Ed25519")
        val javaKeyPair = kpg.generateKeyPair()
        val rawPublic = javaKeyPair.public.encoded.copyOfRange(ed25519X509Prefix.size, javaKeyPair.public.encoded.size)
        val rawPrivate = javaKeyPair.private.encoded.copyOfRange(
            ed25519Pkcs8Prefix.size,
            javaKeyPair.private.encoded.size,
        )
        return CryptoKeyPair(rawPublic, rawPrivate)
    }

    override fun sign(privateKey: ByteArray, data: ByteArray): ByteArray {
        val keyFactory = KeyFactory.getInstance("Ed25519")
        val pkcs8Key = keyFactory.generatePrivate(PKCS8EncodedKeySpec(ed25519Pkcs8Prefix + privateKey))
        val sig = Signature.getInstance("Ed25519")
        sig.initSign(pkcs8Key)
        sig.update(data)
        return sig.sign()
    }

    override fun verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean {
        return try {
            val keyFactory = KeyFactory.getInstance("Ed25519")
            val x509Key = keyFactory.generatePublic(X509EncodedKeySpec(ed25519X509Prefix + publicKey))
            val sig = Signature.getInstance("Ed25519")
            sig.initVerify(x509Key)
            sig.update(data)
            sig.verify(signature)
        } catch (_: Exception) {
            false
        }
    }

    override fun generateX25519KeyPair(): CryptoKeyPair {
        val kpg = KeyPairGenerator.getInstance("X25519")
        val javaKeyPair = kpg.generateKeyPair()
        val rawPublic = javaKeyPair.public.encoded.copyOfRange(x25519X509Prefix.size, javaKeyPair.public.encoded.size)
        val rawPrivate = javaKeyPair.private.encoded.copyOfRange(
            x25519Pkcs8Prefix.size,
            javaKeyPair.private.encoded.size,
        )
        return CryptoKeyPair(rawPublic, rawPrivate)
    }

    override fun x25519SharedSecret(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        val keyFactory = KeyFactory.getInstance("X25519")
        val privKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(x25519Pkcs8Prefix + privateKey))
        val pubKey = keyFactory.generatePublic(X509EncodedKeySpec(x25519X509Prefix + publicKey))
        val ka = javax.crypto.KeyAgreement.getInstance("X25519")
        ka.init(privKey)
        ka.doPhase(pubKey, true)
        return ka.generateSecret()
    }

    override fun aeadEncrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        val cipher = javax.crypto.Cipher.getInstance("ChaCha20-Poly1305")
        val keySpec = javax.crypto.spec.SecretKeySpec(key, "ChaCha20")
        val ivSpec = javax.crypto.spec.IvParameterSpec(nonce)
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, keySpec, ivSpec)
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    override fun aeadDecrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray {
        val cipher = javax.crypto.Cipher.getInstance("ChaCha20-Poly1305")
        val keySpec = javax.crypto.spec.SecretKeySpec(key, "ChaCha20")
        val ivSpec = javax.crypto.spec.IvParameterSpec(nonce)
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keySpec, ivSpec)
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    override fun sha256(data: ByteArray): ByteArray {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(data)
    }

    override fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        // RFC 5869: HKDF-Extract then HKDF-Expand
        val hashLen = 32
        require(length in 1..(255 * hashLen)) { "HKDF output length must be 1..${255 * hashLen}" }

        // Extract: PRK = HMAC-SHA256(salt, IKM)
        val effectiveSalt = if (salt.isEmpty()) ByteArray(hashLen) else salt
        val prk = hmacSha256(effectiveSalt, ikm)

        // Expand: T(i) = HMAC-SHA256(PRK, T(i-1) || info || i)
        val n = (length + hashLen - 1) / hashLen
        val okm = ByteArray(n * hashLen)
        var prev = byteArrayOf()
        for (i in 1..n) {
            prev = hmacSha256(prk, prev + info + byteArrayOf(i.toByte()))
            prev.copyInto(okm, (i - 1) * hashLen)
        }
        return okm.copyOf(length)
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }
}
