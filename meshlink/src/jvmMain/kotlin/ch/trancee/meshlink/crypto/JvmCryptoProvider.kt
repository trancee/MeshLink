package ch.trancee.meshlink.crypto

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
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
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal class JvmCryptoProvider : CryptoProvider {
    private val secureRandom: SecureRandom = SecureRandom()
    private val sha256Digests = threadLocal { MessageDigest.getInstance("SHA-256") }
    private val hmacSha256Macs = threadLocal { Mac.getInstance("HmacSHA256") }
    private val x25519KeyPairGenerators = threadLocal {
        KeyPairGenerator.getInstance("X25519").apply { initialize(NamedParameterSpec.X25519) }
    }
    private val ed25519KeyPairGenerators = threadLocal { KeyPairGenerator.getInstance("Ed25519") }
    private val x25519KeyAgreements = threadLocal { KeyAgreement.getInstance("X25519") }
    private val x25519KeyFactories = threadLocal { KeyFactory.getInstance("X25519") }
    private val ed25519KeyFactories = threadLocal { KeyFactory.getInstance("Ed25519") }
    private val ed25519Signatures = threadLocal { Signature.getInstance("Ed25519") }
    private val ed25519Verifiers = threadLocal { Signature.getInstance("Ed25519") }
    private val chacha20Poly1305EncryptCiphers = threadLocal {
        Cipher.getInstance("ChaCha20-Poly1305")
    }
    private val chacha20Poly1305DecryptCiphers = threadLocal {
        Cipher.getInstance("ChaCha20-Poly1305")
    }

    override fun randomBytes(size: Int): ByteArray {
        return ByteArray(size).also(secureRandom::nextBytes)
    }

    override fun sha256(input: ByteArray): ByteArray {
        return sha256Digests.value().digest(input)
    }

    override fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = hmacSha256Macs.value()
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    override fun generateX25519KeyPair(): X25519KeyPair {
        val keyPair = x25519KeyPairGenerators.value().generateKeyPair()
        val privateKey = (keyPair.private as XECPrivateKey).scalar.orElseThrow()
        val publicKey = bigIntegerToLittleEndian((keyPair.public as XECPublicKey).u, 32)
        return X25519KeyPair(privateKey = privateKey, publicKey = publicKey)
    }

    override fun generateEd25519KeyPair(): Ed25519KeyPair {
        val keyPair = ed25519KeyPairGenerators.value().generateKeyPair()
        val privateKey = (keyPair.private as EdECPrivateKey).bytes.orElseThrow()
        val publicKey = encodeEd25519PublicKey(keyPair.public as EdECPublicKey)
        return Ed25519KeyPair(privateKey = privateKey, publicKey = publicKey)
    }

    override fun x25519(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        val keyAgreement = x25519KeyAgreements.value()
        keyAgreement.init(x25519PrivateKey(privateKey))
        keyAgreement.doPhase(x25519PublicKey(publicKey), true)
        return keyAgreement.generateSecret()
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
        val cipher = chacha20Poly1305EncryptCiphers.value()
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
        val cipher = chacha20Poly1305DecryptCiphers.value()
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    private fun x25519PrivateKey(bytes: ByteArray) =
        x25519KeyFactories
            .value()
            .generatePrivate(XECPrivateKeySpec(NamedParameterSpec.X25519, bytes))

    private fun x25519PublicKey(bytes: ByteArray) =
        x25519KeyFactories
            .value()
            .generatePublic(
                XECPublicKeySpec(NamedParameterSpec.X25519, littleEndianToBigInteger(bytes))
            )

    private fun ed25519PrivateKey(bytes: ByteArray) =
        ed25519KeyFactories
            .value()
            .generatePrivate(EdECPrivateKeySpec(NamedParameterSpec.ED25519, bytes))

    private fun ed25519PublicKey(bytes: ByteArray) =
        ed25519KeyFactories
            .value()
            .generatePublic(
                EdECPublicKeySpec(NamedParameterSpec.ED25519, decodeEd25519Point(bytes))
            )

    private fun encodeEd25519PublicKey(publicKey: EdECPublicKey): ByteArray {
        val point = publicKey.point
        val yBytes = bigIntegerToLittleEndian(point.y, 32)
        if (point.isXOdd) {
            yBytes[31] = (yBytes[31].toInt() or 0x80).toByte()
        }
        return yBytes
    }

    private fun decodeEd25519Point(bytes: ByteArray): EdECPoint {
        require(bytes.size == 32) { "Ed25519 public key must be 32 bytes" }
        val yBytes = bytes.copyOf()
        val xOdd = (yBytes[31].toInt() and 0x80) != 0
        yBytes[31] = (yBytes[31].toInt() and 0x7F).toByte()
        return EdECPoint(xOdd, littleEndianToBigInteger(yBytes))
    }

    private fun littleEndianToBigInteger(bytes: ByteArray): BigInteger {
        return BigInteger(1, bytes.reversedArray())
    }

    private fun bigIntegerToLittleEndian(value: BigInteger, size: Int): ByteArray {
        val bigEndian =
            value.toByteArray().let {
                if (it.size > size) it.copyOfRange(it.size - size, it.size) else it
            }
        val padded = ByteArray(size)
        bigEndian.copyInto(padded, destinationOffset = size - bigEndian.size)
        return padded.reversedArray()
    }

    private fun <T> threadLocal(create: () -> T): ThreadLocal<T> {
        return object : ThreadLocal<T>() {
            override fun initialValue(): T {
                return create()
            }
        }
    }

    private fun <T> ThreadLocal<T>.value(): T {
        return checkNotNull(get())
    }
}
