@file:JvmName("AndroidCryptoProvider")

package io.meshlink.crypto

import android.os.Build

/**
 * Selects the optimal crypto provider for the current Android API level.
 * - API 33+: JCA-backed provider (hardware-accelerated Ed25519/X25519)
 * - API 26–32: Pure Kotlin provider (JCA lacks Ed25519/X25519 before API 33)
 */
actual fun createCryptoProvider(): CryptoProvider =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        try {
            AndroidJcaCryptoProvider()
        } catch (_: Exception) {
            PureKotlinCryptoProvider()
        }
    } else {
        PureKotlinCryptoProvider()
    }

/**
 * JCA-backed crypto provider for Android API 33+.
 * Uses platform KeyPairGenerator for Ed25519/X25519 and Cipher for ChaCha20-Poly1305.
 */
private class AndroidJcaCryptoProvider : CryptoProvider {

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
        val kpg = java.security.KeyPairGenerator.getInstance("Ed25519", "AndroidOpenSSL")
        val javaKeyPair = kpg.generateKeyPair()
        val rawPublic = javaKeyPair.public.encoded.copyOfRange(
            ed25519X509Prefix.size,
            javaKeyPair.public.encoded.size,
        )
        val rawPrivate = javaKeyPair.private.encoded.copyOfRange(
            ed25519Pkcs8Prefix.size,
            javaKeyPair.private.encoded.size,
        )
        return CryptoKeyPair(rawPublic, rawPrivate)
    }

    override fun sign(privateKey: ByteArray, data: ByteArray): ByteArray {
        val keyFactory = java.security.KeyFactory.getInstance("Ed25519", "AndroidOpenSSL")
        val pkcs8Key = keyFactory.generatePrivate(
            java.security.spec.PKCS8EncodedKeySpec(ed25519Pkcs8Prefix + privateKey),
        )
        val sig = java.security.Signature.getInstance("Ed25519", "AndroidOpenSSL")
        sig.initSign(pkcs8Key)
        sig.update(data)
        return sig.sign()
    }

    override fun verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean {
        return try {
            val keyFactory = java.security.KeyFactory.getInstance("Ed25519", "AndroidOpenSSL")
            val x509Key = keyFactory.generatePublic(
                java.security.spec.X509EncodedKeySpec(ed25519X509Prefix + publicKey),
            )
            val sig = java.security.Signature.getInstance("Ed25519", "AndroidOpenSSL")
            sig.initVerify(x509Key)
            sig.update(data)
            sig.verify(signature)
        } catch (_: Exception) {
            false
        }
    }

    override fun generateX25519KeyPair(): CryptoKeyPair {
        val kpg = java.security.KeyPairGenerator.getInstance("X25519", "AndroidOpenSSL")
        val javaKeyPair = kpg.generateKeyPair()
        val rawPublic = javaKeyPair.public.encoded.copyOfRange(
            x25519X509Prefix.size,
            javaKeyPair.public.encoded.size,
        )
        val rawPrivate = javaKeyPair.private.encoded.copyOfRange(
            x25519Pkcs8Prefix.size,
            javaKeyPair.private.encoded.size,
        )
        return CryptoKeyPair(rawPublic, rawPrivate)
    }

    override fun x25519SharedSecret(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        val keyFactory = java.security.KeyFactory.getInstance("X25519", "AndroidOpenSSL")
        val privKey = keyFactory.generatePrivate(java.security.spec.PKCS8EncodedKeySpec(x25519Pkcs8Prefix + privateKey))
        val pubKey = keyFactory.generatePublic(java.security.spec.X509EncodedKeySpec(x25519X509Prefix + publicKey))
        val ka = javax.crypto.KeyAgreement.getInstance("X25519", "AndroidOpenSSL")
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
        val hashLen = 32
        require(length in 1..(255 * hashLen)) { "HKDF output length must be 1..${255 * hashLen}" }
        val effectiveSalt = if (salt.isEmpty()) ByteArray(hashLen) else salt
        val prk = hmacSha256(effectiveSalt, ikm)
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
