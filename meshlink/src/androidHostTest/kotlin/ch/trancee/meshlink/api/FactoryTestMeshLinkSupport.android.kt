package ch.trancee.meshlink.api

import ch.trancee.meshlink.config.MeshLinkConfig
import ch.trancee.meshlink.engine.MeshEngine
import ch.trancee.meshlink.platform.android.AndroidCryptoProviderFactory
import ch.trancee.meshlink.platform.loadOrCreateLocalIdentityBlocking
import ch.trancee.meshlink.storage.InMemorySecureStorage

internal actual fun installFactoryTestBridges(): Unit = Unit

internal actual fun createAndroidFactoryParityApi(config: MeshLinkConfig): MeshLinkApi {
    return meshLink(config = config, bootstrap = AndroidFactoryTestMeshLinkBootstrap)
}

internal actual fun createIosFactoryParityApi(config: MeshLinkConfig): MeshLinkApi {
    val secureStorage = InMemorySecureStorage()
    val cryptoProvider = AndroidCryptoProviderFactory.create()
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
    )
}
