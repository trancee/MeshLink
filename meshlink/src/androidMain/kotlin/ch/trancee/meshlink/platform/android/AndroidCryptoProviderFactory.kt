package ch.trancee.meshlink.platform.android

import ch.trancee.meshlink.api.MeshLinkException
import ch.trancee.meshlink.crypto.CryptoProvider
import ch.trancee.meshlink.crypto.Ed25519KeyPair
import ch.trancee.meshlink.crypto.X25519KeyPair

internal object AndroidCryptoProviderFactory {
    internal fun create(
        capabilityReport: AndroidJcaCapabilityReport = AndroidJcaCapabilityProbe.detect(),
    ): CryptoProvider {
        if (!capabilityReport.supportsMeshLinkRuntime) {
            throw MeshLinkException.CryptoFailure(
                message = "Android device does not expose the required platform primitives for MeshLink runtime (X25519/XDH and ChaCha20-Poly1305). Ed25519 can fall back to the in-repo software implementation, but key agreement and AEAD still require platform support.",
            )
        }

        val jcaProvider = AndroidCryptoProvider()
        return if (capabilityReport.supportsEd25519) {
            jcaProvider
        } else {
            AndroidEd25519FallbackCryptoProvider(
                jcaDelegate = jcaProvider,
                ed25519Fallback = AndroidEd25519Fallback(
                    randomBytesProvider = jcaProvider::randomBytes,
                ),
            )
        }
    }
}

internal class AndroidEd25519FallbackCryptoProvider internal constructor(
    private val jcaDelegate: AndroidCryptoProvider,
    private val ed25519Fallback: AndroidEd25519Fallback,
) : CryptoProvider {
    override fun randomBytes(size: Int): ByteArray {
        return jcaDelegate.randomBytes(size)
    }

    override fun sha256(input: ByteArray): ByteArray {
        return jcaDelegate.sha256(input)
    }

    override fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        return jcaDelegate.hmacSha256(key, data)
    }

    override fun generateX25519KeyPair(): X25519KeyPair {
        return jcaDelegate.generateX25519KeyPair()
    }

    override fun generateEd25519KeyPair(): Ed25519KeyPair {
        return ed25519Fallback.generateKeyPair()
    }

    override fun x25519(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        return jcaDelegate.x25519(privateKey, publicKey)
    }

    override fun ed25519Sign(privateKey: ByteArray, message: ByteArray): ByteArray {
        return ed25519Fallback.sign(privateKey, message)
    }

    override fun ed25519Verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        return ed25519Fallback.verify(publicKey, message, signature)
    }

    override fun chacha20Poly1305Seal(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        plaintext: ByteArray,
    ): ByteArray {
        return jcaDelegate.chacha20Poly1305Seal(
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
        return jcaDelegate.chacha20Poly1305Open(
            key = key,
            nonce = nonce,
            aad = aad,
            ciphertext = ciphertext,
        )
    }
}
