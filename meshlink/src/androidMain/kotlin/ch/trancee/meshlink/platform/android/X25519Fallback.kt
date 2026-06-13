package ch.trancee.meshlink.platform.android

import ch.trancee.meshlink.crypto.PureX25519

internal object X25519Fallback {
    fun publicKeyFromPrivate(privateKey: ByteArray): ByteArray {
        return PureX25519.publicKeyFromPrivate(privateKey)
    }

    fun sharedSecret(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        return PureX25519.sharedSecret(privateKey, publicKey)
    }
}
