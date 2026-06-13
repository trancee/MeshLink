package ch.trancee.meshlink.crypto

import ch.trancee.meshlink.api.MeshLinkException

internal fun requireValidX25519SharedSecret(sharedSecret: ByteArray): ByteArray {
    var anyNonZeroByte = 0
    for (byte in sharedSecret) {
        anyNonZeroByte = anyNonZeroByte or byte.toInt()
    }
    if (anyNonZeroByte == 0) {
        throw MeshLinkException.CryptoFailure(
            "X25519 produced an all-zero shared secret; peer public key is low-order or invalid"
        )
    }
    return sharedSecret
}
