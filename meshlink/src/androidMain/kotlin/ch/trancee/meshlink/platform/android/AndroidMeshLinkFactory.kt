package ch.trancee.meshlink.platform

import ch.trancee.meshlink.api.AndroidContextMeshLinkBootstrap
import ch.trancee.meshlink.api.AndroidFactoryTestMeshLinkBootstrap
import ch.trancee.meshlink.api.MeshLinkApi
import ch.trancee.meshlink.api.MeshLinkBootstrap
import ch.trancee.meshlink.api.MeshLinkException
import ch.trancee.meshlink.config.MeshLinkConfig
import ch.trancee.meshlink.engine.MeshEngine
import ch.trancee.meshlink.platform.android.AndroidBleTransport
import ch.trancee.meshlink.platform.android.AndroidCryptoProviderFactory
import ch.trancee.meshlink.platform.android.AndroidSecureStorage
import ch.trancee.meshlink.storage.InMemorySecureStorage

private const val ANDROID_BOOTSTRAP_REQUIRED_MESSAGE =
    "Android bootstrap is required. Call meshLink(config = ..., bootstrap = androidMeshLinkBootstrap(context))."

internal actual fun createMeshLink(config: MeshLinkConfig): MeshLinkApi {
    throw MeshLinkException.InvalidConfiguration(ANDROID_BOOTSTRAP_REQUIRED_MESSAGE)
}

internal actual fun createMeshLink(
    config: MeshLinkConfig,
    bootstrap: MeshLinkBootstrap,
): MeshLinkApi {
    if (bootstrap === AndroidFactoryTestMeshLinkBootstrap) {
        return createFactoryTestMeshLink(config = config)
    }

    val androidContext =
        (bootstrap as? AndroidContextMeshLinkBootstrap)?.context
            ?: throw MeshLinkException.InvalidConfiguration(ANDROID_BOOTSTRAP_REQUIRED_MESSAGE)
    val secureStorage = AndroidSecureStorage(androidContext, config.appId)
    val cryptoProvider = AndroidCryptoProviderFactory.create()
    val localIdentity =
        loadOrCreateLocalIdentityBlocking(
            appId = config.appId,
            secureStorage = secureStorage,
            provider = cryptoProvider,
        )
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

private fun createFactoryTestMeshLink(config: MeshLinkConfig): MeshLinkApi {
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
