package ch.trancee.meshlink.platform

import ch.trancee.meshlink.api.MeshLinkApi
import ch.trancee.meshlink.config.MeshLinkConfig
import ch.trancee.meshlink.engine.MeshEngine
import ch.trancee.meshlink.identity.LocalIdentityStore
import ch.trancee.meshlink.platform.ios.IosBleTransport
import ch.trancee.meshlink.platform.ios.IosCryptoProvider
import ch.trancee.meshlink.platform.ios.IosSecureStorage
import ch.trancee.meshlink.storage.InMemorySecureStorage
import kotlinx.coroutines.runBlocking

internal actual fun createAndroidMeshLink(config: MeshLinkConfig, context: Any): MeshLinkApi {
    val secureStorage = InMemorySecureStorage()
    val cryptoProvider = IosCryptoProvider()
    val localIdentity = runBlocking {
        LocalIdentityStore.loadOrCreate(
            appId = config.appId,
            secureStorage = secureStorage,
            provider = cryptoProvider,
        )
    }
    return MeshEngine.create(
        config = config,
        platformContext = context,
        localIdentity = localIdentity,
        secureStorage = secureStorage,
    )
}

internal actual fun createIosMeshLink(config: MeshLinkConfig): MeshLinkApi {
    val secureStorage = IosSecureStorage(config.appId)
    val cryptoProvider = IosCryptoProvider()
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
        bleTransport =
            IosBleTransport(
                appId = config.appId,
                advertisementKeyHash = localIdentity.advertisementKeyHash,
            ),
    )
}
