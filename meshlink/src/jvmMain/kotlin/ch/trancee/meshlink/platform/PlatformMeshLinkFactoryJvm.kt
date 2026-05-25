package ch.trancee.meshlink.platform

import ch.trancee.meshlink.api.MeshLinkApi
import ch.trancee.meshlink.config.MeshLinkConfig
import ch.trancee.meshlink.crypto.JvmCryptoProvider
import ch.trancee.meshlink.engine.MeshEngine
import ch.trancee.meshlink.storage.InMemorySecureStorage

internal actual fun createMeshLink(config: MeshLinkConfig): MeshLinkApi {
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

internal actual fun createMeshLink(config: MeshLinkConfig, context: Any): MeshLinkApi {
    val secureStorage = InMemorySecureStorage()
    val localIdentity =
        loadOrCreateLocalIdentityBlocking(
            appId = config.appId,
            secureStorage = secureStorage,
            provider = JvmCryptoProvider(),
        )
    return MeshEngine.create(
        config = config,
        platformContext = context,
        localIdentity = localIdentity,
        secureStorage = secureStorage,
    )
}
