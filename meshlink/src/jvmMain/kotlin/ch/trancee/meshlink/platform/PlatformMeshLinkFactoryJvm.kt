package ch.trancee.meshlink.platform

import ch.trancee.meshlink.api.MeshLink
import ch.trancee.meshlink.api.MeshLinkBootstrap
import ch.trancee.meshlink.config.MeshLinkConfig
import ch.trancee.meshlink.crypto.JvmCryptoProvider
import ch.trancee.meshlink.engine.MeshEngine
import ch.trancee.meshlink.storage.InMemorySecureStorage

internal actual fun createMeshLink(config: MeshLinkConfig): MeshLink {
    val secureStorage = InMemorySecureStorage()
    val localIdentity =
        loadOrCreateLocalIdentityBlocking(
            appId = config.appId,
            secureStorage = secureStorage,
            provider = JvmCryptoProvider(),
        )
    return MeshEngine.create(
        config = config,
        localIdentity = localIdentity,
        secureStorage = secureStorage,
    )
}

internal actual fun createMeshLink(config: MeshLinkConfig, bootstrap: MeshLinkBootstrap): MeshLink {
    val secureStorage = InMemorySecureStorage()
    val localIdentity =
        loadOrCreateLocalIdentityBlocking(
            appId = config.appId,
            secureStorage = secureStorage,
            provider = JvmCryptoProvider(),
        )
    return MeshEngine.create(
        config = config,
        platformContext = bootstrap,
        localIdentity = localIdentity,
        secureStorage = secureStorage,
    )
}
