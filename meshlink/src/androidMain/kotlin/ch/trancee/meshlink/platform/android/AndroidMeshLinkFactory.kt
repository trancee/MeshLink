package ch.trancee.meshlink.platform

import android.content.Context
import ch.trancee.meshlink.api.MeshLinkApi
import ch.trancee.meshlink.config.MeshLinkConfig
import ch.trancee.meshlink.engine.MeshEngine
import ch.trancee.meshlink.identity.LocalIdentityStore
import ch.trancee.meshlink.platform.android.AndroidBleTransport
import ch.trancee.meshlink.platform.android.AndroidCryptoProviderFactory
import ch.trancee.meshlink.platform.android.AndroidSecureStorage
import ch.trancee.meshlink.storage.InMemorySecureStorage
import kotlinx.coroutines.runBlocking

internal actual fun createAndroidMeshLink(config: MeshLinkConfig, context: Any): MeshLinkApi {
    val androidContext = context as? Context ?: error("Android context is required")
    val secureStorage = AndroidSecureStorage(androidContext, config.appId)
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
        platformContext = androidContext,
        localIdentity = localIdentity,
        secureStorage = secureStorage,
        bleTransport = AndroidBleTransport(
            context = androidContext,
            appId = config.appId,
            advertisementKeyHash = localIdentity.advertisementKeyHash,
        ),
    )
}

internal actual fun createIosMeshLink(config: MeshLinkConfig): MeshLinkApi {
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

