package ch.trancee.meshlink.platform.ios

import ch.trancee.meshlink.api.IosCryptoBridgeRegistry
import ch.trancee.meshlink.crypto.CryptoProvider
import ch.trancee.meshlink.crypto.Ed25519KeyPair
import ch.trancee.meshlink.crypto.X25519KeyPair

internal class IosCryptoProvider : CryptoProvider {
    override fun randomBytes(size: Int): ByteArray {
        return callbacks().randomBytes(size).copyOf()
    }

    override fun sha256(input: ByteArray): ByteArray {
        return callbacks().sha256(input.copyOf()).copyOf()
    }

    override fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        return callbacks().hmacSha256(key.copyOf(), data.copyOf()).copyOf()
    }

    override fun generateX25519KeyPair(): X25519KeyPair {
        val keyPair = callbacks().generateX25519KeyPair()
        return X25519KeyPair(
            privateKey = keyPair.privateKey.copyOf(),
            publicKey = keyPair.publicKey.copyOf(),
        )
    }

    override fun generateEd25519KeyPair(): Ed25519KeyPair {
        val keyPair = callbacks().generateEd25519KeyPair()
        return Ed25519KeyPair(
            privateKey = keyPair.privateKey.copyOf(),
            publicKey = keyPair.publicKey.copyOf(),
        )
    }

    override fun x25519(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        return callbacks().x25519(privateKey.copyOf(), publicKey.copyOf()).copyOf()
    }

    override fun ed25519Sign(privateKey: ByteArray, message: ByteArray): ByteArray {
        return callbacks().ed25519Sign(privateKey.copyOf(), message.copyOf()).copyOf()
    }

    override fun ed25519Verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        return callbacks().ed25519Verify(publicKey.copyOf(), message.copyOf(), signature.copyOf())
    }

    override fun chacha20Poly1305Seal(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        plaintext: ByteArray,
    ): ByteArray {
        return callbacks().chacha20Poly1305Seal(
            key.copyOf(),
            nonce.copyOf(),
            aad.copyOf(),
            plaintext.copyOf(),
        ).copyOf()
    }

    override fun chacha20Poly1305Open(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray {
        return callbacks().chacha20Poly1305Open(
            key.copyOf(),
            nonce.copyOf(),
            aad.copyOf(),
            ciphertext.copyOf(),
        ).copyOf()
    }

    private fun callbacks() = IosCryptoBridgeRegistry.requireCallbacks()
}
