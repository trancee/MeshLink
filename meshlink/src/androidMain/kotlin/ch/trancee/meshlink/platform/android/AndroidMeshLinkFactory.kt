package ch.trancee.meshlink.platform

import android.content.Context
import ch.trancee.meshlink.api.MeshLinkApi
import ch.trancee.meshlink.api.MeshLinkException
import ch.trancee.meshlink.config.MeshLinkConfig
import ch.trancee.meshlink.engine.MeshEngine
import ch.trancee.meshlink.identity.LocalIdentityStore
import ch.trancee.meshlink.platform.android.AndroidBleTransport
import ch.trancee.meshlink.platform.android.AndroidCryptoProviderFactory
import ch.trancee.meshlink.platform.android.AndroidSecureStorage
import ch.trancee.meshlink.storage.InMemorySecureStorage
import kotlinx.coroutines.runBlocking

private const val ANDROID_CONTEXT_REQUIRED_MESSAGE =
    "Android context is required. Call MeshLink.create(config = ..., context = ...)."

internal actual fun createMeshLink(config: MeshLinkConfig): MeshLinkApi {
    throw MeshLinkException.InvalidConfiguration(ANDROID_CONTEXT_REQUIRED_MESSAGE)
}

internal actual fun createAndroidMeshLink(config: MeshLinkConfig, context: Any): MeshLinkApi {
    if (context === AndroidFactoryTestContext) {
        return createAndroidMeshLinkForFactoryTests(config = config, context = context)
    }

    val androidContext =
        context as? Context
            ?: throw MeshLinkException.InvalidConfiguration(ANDROID_CONTEXT_REQUIRED_MESSAGE)
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
        bleTransport =
            AndroidBleTransport(
                context = androidContext,
                appId = config.appId,
                advertisementKeyHash = localIdentity.advertisementKeyHash,
            ),
    )
}

private fun createAndroidMeshLinkForFactoryTests(
    config: MeshLinkConfig,
    context: Any,
): MeshLinkApi {
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
        platformContext = context,
        localIdentity = localIdentity,
        secureStorage = secureStorage,
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
