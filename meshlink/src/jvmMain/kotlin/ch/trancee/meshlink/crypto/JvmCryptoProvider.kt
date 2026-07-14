package ch.trancee.meshlink.crypto

import ch.trancee.meshlink.api.MeshLinkException
import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PublicKey
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
import javax.crypto.KeyAgreement

internal class JvmCryptoProvider : CryptoProvider {
    private val symmetricPrimitives = JcaSymmetricPrimitives()
    private val x25519KeyPairGenerators = threadLocal {
        KeyPairGenerator.getInstance("X25519").apply { initialize(NamedParameterSpec.X25519) }
    }
    private val ed25519KeyPairGenerators = threadLocal { KeyPairGenerator.getInstance("Ed25519") }
    private val x25519KeyAgreements = threadLocal { KeyAgreement.getInstance("X25519") }
    private val x25519KeyFactories = threadLocal { KeyFactory.getInstance("X25519") }
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
        try {
            val keyAgreement = x25519KeyAgreements.value()
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
        x25519KeyFactories
            .value()
            .generatePrivate(XECPrivateKeySpec(NamedParameterSpec.X25519, bytes))

    /**
     * RFC 7748 SS5's `decodeUCoordinate` requires masking the most significant bit of the final
     * byte before use for X25519 (the wire encoding only carries 255 significant bits). Without
     * this, [XECPublicKeySpec]'s `u` value differs from the field element every other RFC 7748
     * implementation derives for a non-canonical encoding (u >= 2^255 - 19), so this provider would
     * silently disagree with a spec-conformant peer -- and, for public keys of small order, would
     * fail to surface the `InvalidKeyException` ("Point has small order") that lets [x25519] fail
     * closed instead of returning a shared secret Wycheproof's `x25519_test.json` disagrees with
     * (see `AndroidCryptoPolicyConformanceTest`/`JvmCryptoPolicyConformanceTest`).
     */
    private fun x25519PublicKey(bytes: ByteArray): PublicKey {
        val uCoordinateBytes = bytes.copyOf()
        uCoordinateBytes[uCoordinateBytes.size - 1] =
            (uCoordinateBytes[uCoordinateBytes.size - 1].toInt() and X25519_U_COORDINATE_MASK)
                .toByte()
        return x25519KeyFactories
            .value()
            .generatePublic(
                XECPublicKeySpec(
                    NamedParameterSpec.X25519,
                    littleEndianToBigInteger(uCoordinateBytes),
                )
            )
    }

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

    private companion object {
        // RFC 7748 SS5 decodeUCoordinate: only the low 255 bits of the u-coordinate are
        // significant, so the top bit of the final byte must be masked off before use.
        private const val X25519_U_COORDINATE_MASK: Int = 0x7f
    }
}
