package ch.trancee.meshlink.api

import ch.trancee.meshlink.config.MeshLinkConfig
import ch.trancee.meshlink.engine.MeshEngine
import ch.trancee.meshlink.platform.android.JcaCryptoProviderFactory
import ch.trancee.meshlink.platform.loadOrCreateLocalIdentityBlocking
import ch.trancee.meshlink.storage.InMemorySecureStorage

internal actual fun installFactoryTestBridges(): Unit = Unit

internal actual fun createAndroidFactoryParityMeshLink(config: MeshLinkConfig): MeshLink {
    return meshLink(config = config, bootstrap = FactoryTestBootstrap)
}

internal actual fun createIosFactoryParityMeshLink(config: MeshLinkConfig): MeshLink {
    val secureStorage = InMemorySecureStorage()
    val cryptoProvider = JcaCryptoProviderFactory.create()
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
