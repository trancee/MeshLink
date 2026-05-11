package ch.trancee.meshlink.platform.android

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal class AndroidJcaCapabilityReport internal constructor(
    internal val supportsX25519: Boolean,
    internal val supportsEd25519: Boolean,
    internal val supportsChaCha20Poly1305: Boolean,
) {
    internal val supportsNoisePrimitives: Boolean
        get() = supportsX25519 && supportsEd25519 && supportsChaCha20Poly1305
}

internal object AndroidJcaCapabilityProbe {
    internal fun detect(): AndroidJcaCapabilityReport {
        return AndroidJcaCapabilityReport(
            supportsX25519 = canRun(::probeX25519),
            supportsEd25519 = canRun(::probeEd25519),
            supportsChaCha20Poly1305 = canRun(::probeChaCha20Poly1305),
        )
    }

    private fun canRun(block: () -> Unit): Boolean {
        return runCatching(block).isSuccess
    }

    private fun probeX25519(): Unit {
        val keyPairGenerator = xdhKeyPairGenerator()
        val alice = keyPairGenerator.generateKeyPair()
        val bob = keyPairGenerator.generateKeyPair()

        val aliceAgreement = xdhKeyAgreement()
        aliceAgreement.init(alice.private)
        aliceAgreement.doPhase(bob.public, true)
        val aliceSecret = aliceAgreement.generateSecret()

        val bobAgreement = xdhKeyAgreement()
        bobAgreement.init(bob.private)
        bobAgreement.doPhase(alice.public, true)
        val bobSecret = bobAgreement.generateSecret()

        check(aliceSecret.contentEquals(bobSecret)) { "X25519 agreement produced mismatched shared secrets" }
    }

    private fun probeEd25519(): Unit {
        val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val message = byteArrayOf(0x01, 0x23, 0x45)

        val signer = Signature.getInstance("Ed25519")
        signer.initSign(keyPair.private)
        signer.update(message)
        val signature = signer.sign()

        val verifier = Signature.getInstance("Ed25519")
        verifier.initVerify(keyPair.public)
        verifier.update(message)
        check(verifier.verify(signature)) { "Ed25519 verification failed during capability probe" }
    }

    private fun probeChaCha20Poly1305(): Unit {
        val key = ByteArray(32) { index -> (index + 1).toByte() }
        val nonce = ByteArray(12) { index -> (index + 2).toByte() }
        val aad = byteArrayOf(0x09, 0x08)
        val plaintext = byteArrayOf(0x01, 0x02, 0x03)

        val encryptCipher = Cipher.getInstance("ChaCha20-Poly1305")
        encryptCipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
        encryptCipher.updateAAD(aad)
        val ciphertext = encryptCipher.doFinal(plaintext)

        val decryptCipher = Cipher.getInstance("ChaCha20-Poly1305")
        decryptCipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
        decryptCipher.updateAAD(aad)
        val decrypted = decryptCipher.doFinal(ciphertext)

        check(decrypted.contentEquals(plaintext)) { "ChaCha20-Poly1305 round-trip failed during capability probe" }
    }
}

internal fun xdhKeyPairGenerator(): KeyPairGenerator {
    return firstAvailable("XDH", "X25519") { algorithm ->
        KeyPairGenerator.getInstance(algorithm)
    }
}

internal fun xdhKeyAgreement(): KeyAgreement {
    return firstAvailable("XDH", "X25519") { algorithm ->
        KeyAgreement.getInstance(algorithm)
    }
}

internal fun xdhKeyFactory(): KeyFactory {
    return firstAvailable("XDH", "X25519") { algorithm ->
        KeyFactory.getInstance(algorithm)
    }
}

private inline fun <T> firstAvailable(
    primaryAlgorithm: String,
    fallbackAlgorithm: String,
    create: (String) -> T,
): T {
    return runCatching { create(primaryAlgorithm) }
        .recoverCatching { create(fallbackAlgorithm) }
        .getOrThrow()
}
