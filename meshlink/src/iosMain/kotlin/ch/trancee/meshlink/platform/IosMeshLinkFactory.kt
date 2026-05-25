package ch.trancee.meshlink.platform

import ch.trancee.meshlink.api.MeshLinkApi
import ch.trancee.meshlink.api.MeshLinkBootstrap
import ch.trancee.meshlink.config.MeshLinkConfig
import ch.trancee.meshlink.engine.MeshEngine
import ch.trancee.meshlink.platform.ios.IosBleTransport
import ch.trancee.meshlink.platform.ios.IosCryptoProvider
import ch.trancee.meshlink.platform.ios.IosSecureStorage
import ch.trancee.meshlink.storage.InMemorySecureStorage

internal actual fun createMeshLink(config: MeshLinkConfig): MeshLinkApi {
    val secureStorage = IosSecureStorage(config.appId)
    val cryptoProvider = IosCryptoProvider()
    val localIdentity =
        loadOrCreateLocalIdentityBlocking(
            appId = config.appId,
            secureStorage = secureStorage,
            provider = cryptoProvider,
        )
    return MeshEngine.create(
        config = config,
        localIdentity = localIdentity,
        secureStorage = secureStorage,
        bleTransport =
            IosBleTransport(
                appId = config.appId,
                advertisementKeyHash = localIdentity.advertisementKeyHash,
            ),
    )
}

internal actual fun createMeshLink(
    config: MeshLinkConfig,
    bootstrap: MeshLinkBootstrap,
): MeshLinkApi {
    val secureStorage = InMemorySecureStorage()
    val cryptoProvider = IosCryptoProvider()
    val localIdentity =
        loadOrCreateLocalIdentityBlocking(
            appId = config.appId,
            secureStorage = secureStorage,
            provider = cryptoProvider,
        )
    return MeshEngine.create(
        config = config,
        platformContext = bootstrap,
        localIdentity = localIdentity,
        secureStorage = secureStorage,
    )
}
