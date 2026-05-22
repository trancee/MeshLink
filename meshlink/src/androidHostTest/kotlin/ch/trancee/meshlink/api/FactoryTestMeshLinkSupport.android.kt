package ch.trancee.meshlink.api

import ch.trancee.meshlink.config.MeshLinkConfig
import ch.trancee.meshlink.engine.MeshEngine
import ch.trancee.meshlink.identity.LocalIdentityStore
import ch.trancee.meshlink.platform.AndroidFactoryTestContext
import ch.trancee.meshlink.platform.android.AndroidCryptoProviderFactory
import ch.trancee.meshlink.storage.InMemorySecureStorage
import kotlinx.coroutines.runBlocking

internal actual fun installFactoryTestBridges(): Unit = Unit

internal actual fun createAndroidFactoryParityApi(config: MeshLinkConfig): MeshLinkApi {
    return MeshLink.create(config = config, context = AndroidFactoryTestContext)
}

internal actual fun createIosFactoryParityApi(config: MeshLinkConfig): MeshLinkApi {
    val secureStorage = InMemorySecureStorage()
    val cryptoProvider = AndroidCryptoProviderFactory.create()
    val localIdentity = runBlocking {
        LocalIdentityStore.loadOrCreate(
            appId = config.appId,
            secureStorage = secureStorage,
            provider = cryptoProvider,
        )
    }
    return MeshEngine.create(
        config = config,
        localIdentity = localIdentity,
        secureStorage = secureStorage,
    )
}
