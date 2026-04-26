package ch.trancee.meshlink.crypto

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.interfaces.EdECPrivateKey
import java.security.interfaces.EdECPublicKey
import java.security.interfaces.XECPrivateKey
import java.security.interfaces.XECPublicKey
import java.security.spec.EdECPoint
import java.security.spec.EdECPrivateKeySpec
import java.security.spec.EdECPublicKeySpec
import java.security.spec.NamedParameterSpec
import java.security.spec.XECPrivateKeySpec
import java.security.spec.XECPublicKeySpec
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * JVM implementation of [CryptoProvider] backed by JDK 17+ built-in crypto APIs.
 *
 * This implementation runs on the JVM host target, enabling `commonTest` / `jvmTest` to exercise
 * real cryptographic operations without native libraries. It is not a shipping target.
 *
 * Key-encoding conventions (shared with AndroidCryptoProvider / libsodium):
 * - Ed25519 private key: 64 bytes = seed (32) || compressed public key (32).
 * - Ed25519 public key: 32 bytes, RFC 8032 compressed point (little-endian y + x-sign bit).
 * - X25519 private key: 32 bytes, raw scalar.
 * - X25519 public key: 32 bytes, little-endian u-coordinate (RFC 7748).
 */
internal class JvmCryptoProvider : CryptoProvider {

    // ── Ed25519 (RFC 8032) ────────────────────────────────────────────────────

    override fun generateEd25519KeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("Ed25519")
        val kp = kpg.generateKeyPair()
        val edPub = kp.public as EdECPublicKey
        val edPriv = kp.private as EdECPrivateKey

        val pubKeyBytes = encodeEd25519PublicKey(edPub)
        val seed =
            edPriv.bytes.orElseThrow { IllegalStateException("Ed25519 private key has no seed") }
        // 64-byte private key = seed (32) || public key (32) — matches libsodium convention.
        val privKeyBytes = seed + pubKeyBytes
        return KeyPair(publicKey = pubKeyBytes, privateKey = privKeyBytes)
    }

    override fun sign(privateKey: ByteArray, message: ByteArray): ByteArray {
        require(privateKey.size == 64) {
            "Ed25519 private key must be 64 bytes, got ${privateKey.size}"
        }
        // First 32 bytes are the seed.
        val seed = privateKey.copyOf(32)
        val keySpec = EdECPrivateKeySpec(NamedParameterSpec.ED25519, seed)
        val privKey = KeyFactory.getInstance("Ed25519").generatePrivate(keySpec)
        val sig = java.security.Signature.getInstance("Ed25519")
        sig.initSign(privKey)
        sig.update(message)
        return sig.sign()
    }

    override fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        require(publicKey.size == 32) {
            "Ed25519 public key must be 32 bytes, got ${publicKey.size}"
        }
        return try {
            val pubKey = decodeEd25519PublicKey(publicKey)
            val sig = java.security.Signature.getInstance("Ed25519")
            sig.initVerify(pubKey)
            sig.update(message)
            sig.verify(signature)
        } catch (_: Exception) {
            false
        }
    }

    // ── X25519 (RFC 7748) ─────────────────────────────────────────────────────

    override fun generateX25519KeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("X25519")
        val kp = kpg.generateKeyPair()
        val xecPriv = kp.private as XECPrivateKey
        val xecPub = kp.public as XECPublicKey

        val privKeyBytes =
            xecPriv.scalar.orElseThrow { IllegalStateException("X25519 private key has no scalar") }
        val pubKeyBytes = bigIntegerToLittleEndian32(xecPub.u)
        return KeyPair(publicKey = pubKeyBytes, privateKey = privKeyBytes)
    }

    override fun x25519SharedSecret(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        require(privateKey.size == 32) {
            "X25519 private key must be 32 bytes, got ${privateKey.size}"
        }
        require(publicKey.size == 32) {
            "X25519 public key must be 32 bytes, got ${publicKey.size}"
        }
        val kf = KeyFactory.getInstance("X25519")
        val privKey = kf.generatePrivate(XECPrivateKeySpec(NamedParameterSpec.X25519, privateKey))
        val pubKey =
            kf.generatePublic(
                XECPublicKeySpec(NamedParameterSpec.X25519, littleEndian32ToBigInteger(publicKey))
            )
        val ka = KeyAgreement.getInstance("X25519")
        ka.init(privKey)
        ka.doPhase(pubKey, true)
        return ka.generateSecret()
    }

    // ── ChaCha20-Poly1305 IETF AEAD (RFC 8439) ────────────────────────────────

    override fun aeadEncrypt(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        require(key.size == 32) { "AEAD key must be 32 bytes, got ${key.size}" }
        require(nonce.size == 12) { "AEAD nonce must be 12 bytes, got ${nonce.size}" }
        val cipher = Cipher.getInstance("ChaCha20-Poly1305")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    override fun aeadDecrypt(
        key: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        require(key.size == 32) { "AEAD key must be 32 bytes, got ${key.size}" }
        require(nonce.size == 12) { "AEAD nonce must be 12 bytes, got ${nonce.size}" }
        val cipher = Cipher.getInstance("ChaCha20-Poly1305")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return try {
            cipher.doFinal(ciphertext)
        } catch (e: AEADBadTagException) {
            throw IllegalStateException("AEAD authentication failed", e)
        } catch (e: BadPaddingException) {
            throw IllegalStateException("AEAD authentication failed", e)
        }
    }

    // ── SHA-256 (RFC 6234) ────────────────────────────────────────────────────

    override fun sha256(input: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(input)

    // ── HKDF-SHA-256 (RFC 5869) ───────────────────────────────────────────────

    override fun hkdfSha256(
        salt: ByteArray,
        ikm: ByteArray,
        info: ByteArray,
        length: Int,
    ): ByteArray {
        require(length in 1..(255 * 32)) { "HKDF output length out of bounds: $length" }

        // Extract: PRK = HMAC-SHA256(salt, IKM). Per RFC 5869 §2.2: if salt is empty, use zeros.
        val effectiveSalt = if (salt.isEmpty()) ByteArray(32) else salt
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(effectiveSalt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)

        // Expand: T(i) = HMAC-SHA256(PRK, T(i-1) || info || i), concatenate until length bytes.
        val result = ByteArray(length)
        var offset = 0
        var t = ByteArray(0)
        var counter = 1
        while (offset < length) {
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(t)
            mac.update(info)
            mac.update(counter.toByte())
            t = mac.doFinal()
            val toCopy = minOf(t.size, length - offset)
            t.copyInto(result, offset, 0, toCopy)
            offset += toCopy
            counter++
        }
        return result
    }

    // ── Key encoding helpers ──────────────────────────────────────────────────

    /**
     * Encodes an [EdECPublicKey] as a 32-byte RFC 8032 compressed point: little-endian y-coordinate
     * with the sign bit of x in the MSB of the last byte.
     */
    private fun encodeEd25519PublicKey(key: EdECPublicKey): ByteArray {
        val point = key.point
        val bytes = ByteArray(32)
        val yBytes = point.y.toByteArray() // big-endian, may have leading sign byte
        // Copy y into result as big-endian, then reverse to little-endian.
        val srcOffset = if (yBytes.size > 32) yBytes.size - 32 else 0
        val srcLen = minOf(32, yBytes.size - srcOffset)
        yBytes.copyInto(bytes, 32 - srcLen, srcOffset, srcOffset + srcLen)
        bytes.reverse() // now little-endian
        if (point.isXOdd) bytes[31] = (bytes[31].toInt() or 0x80).toByte()
        return bytes
    }

    /** Decodes a 32-byte RFC 8032 compressed Ed25519 point into a JDK [EdECPublicKey]. */
    private fun decodeEd25519PublicKey(rawPubKey: ByteArray): java.security.PublicKey {
        val bytes = rawPubKey.copyOf()
        val xOdd = (bytes[31].toInt() and 0x80) != 0
        bytes[31] = (bytes[31].toInt() and 0x7F).toByte()
        bytes.reverse() // big-endian for BigInteger
        val y = BigInteger(1, bytes)
        val keySpec = EdECPublicKeySpec(NamedParameterSpec.ED25519, EdECPoint(xOdd, y))
        return KeyFactory.getInstance("Ed25519").generatePublic(keySpec)
    }

    /**
     * Converts a [BigInteger] to a 32-byte little-endian unsigned representation (RFC 7748
     * u-coord).
     */
    private fun bigIntegerToLittleEndian32(value: BigInteger): ByteArray {
        val beBytes = value.toByteArray() // big-endian, possibly with leading 0x00 sign byte
        val result = ByteArray(32)
        val srcOffset = if (beBytes.size > 32) beBytes.size - 32 else 0
        val srcLen = minOf(32, beBytes.size - srcOffset)
        val dstOffset = 32 - srcLen
        beBytes.copyInto(result, dstOffset, srcOffset, srcOffset + srcLen)
        result.reverse() // to little-endian
        return result
    }

    /**
     * Converts a 32-byte little-endian unsigned value to [BigInteger] (RFC 7748 u-coord → X25519).
     */
    private fun littleEndian32ToBigInteger(bytes: ByteArray): BigInteger {
        val beBytes = bytes.copyOf().also { it.reverse() }
        return BigInteger(1, beBytes)
    }
}

internal actual fun createCryptoProvider(): CryptoProvider = JvmCryptoProvider()
