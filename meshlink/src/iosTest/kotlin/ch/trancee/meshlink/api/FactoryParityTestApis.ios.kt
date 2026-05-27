package ch.trancee.meshlink.api

import ch.trancee.meshlink.config.MeshLinkConfig

internal actual fun installFactoryTestBridges(): Unit {
    var counter = 1

    fun nextBytes(size: Int): ByteArray {
        return ByteArray(size) { index -> ((counter + index) and 0xFF).toByte() }
            .also { counter += 1 }
    }

    IosCryptoBridge.install(
        randomBytes = { size -> nextBytes(size) },
        sha256 = { input -> ByteArray(32) { index -> input.getOrElse(index) { index.toByte() } } },
        hmacSha256 = { _, data ->
            ByteArray(32) { index -> data.getOrElse(index) { index.toByte() } }
        },
        generateX25519KeyPair = {
            IosCryptoRawKeyPair(privateKey = nextBytes(32), publicKey = nextBytes(32))
        },
        generateEd25519KeyPair = {
            IosCryptoRawKeyPair(privateKey = nextBytes(32), publicKey = nextBytes(32))
        },
        x25519 = { _, publicKey -> publicKey.copyOf() },
        ed25519Sign = { _, message ->
            ByteArray(64) { index -> message.getOrElse(index % maxOf(1, message.size)) { 0 } }
        },
        ed25519Verify = { _, _, _ -> true },
        chacha20Poly1305Seal = { _, _, _, plaintext -> plaintext.copyOf() },
        chacha20Poly1305Open = { _, _, _, ciphertext -> ciphertext.copyOf() },
    )
}

internal actual fun createAndroidFactoryParityApi(config: MeshLinkConfig): MeshLink {
    return meshLink(config = config, bootstrap = AndroidFactoryTestMeshLinkBootstrap)
}

internal actual fun createIosFactoryParityApi(config: MeshLinkConfig): MeshLink {
    return meshLink(config = config)
}
