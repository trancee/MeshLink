package ch.trancee.meshlink.platform

import ch.trancee.meshlink.api.FactoryTestBootstrap
import ch.trancee.meshlink.api.MeshLink
import ch.trancee.meshlink.api.MeshLinkBootstrap
import ch.trancee.meshlink.api.MeshLinkException
import ch.trancee.meshlink.api.android.AndroidBootstrapContextCarrier
import ch.trancee.meshlink.api.android.AndroidMeshLinkBootstrap
import ch.trancee.meshlink.config.MeshLinkConfig
import ch.trancee.meshlink.engine.MeshEngine
import ch.trancee.meshlink.platform.android.AndroidBatteryMonitor
import ch.trancee.meshlink.platform.android.BleTransportAdapter
import ch.trancee.meshlink.platform.android.JcaCryptoProviderFactory
import ch.trancee.meshlink.platform.android.PreferencesSecureStorage
import ch.trancee.meshlink.storage.InMemorySecureStorage

private const val ANDROID_BOOTSTRAP_REQUIRED_MESSAGE =
    "Android bootstrap is required. Call meshLink(config = ..., bootstrap = meshLinkBootstrap(context))."

internal actual fun createMeshLink(config: MeshLinkConfig): MeshLink {
    throw MeshLinkException.InvalidConfiguration(ANDROID_BOOTSTRAP_REQUIRED_MESSAGE)
}

internal actual fun createMeshLink(config: MeshLinkConfig, bootstrap: MeshLinkBootstrap): MeshLink {
    if (bootstrap === FactoryTestBootstrap) {
        return createFactoryTestMeshLink(config = config)
    }

    val androidContext =
        when (bootstrap) {
            is AndroidMeshLinkBootstrap -> bootstrap.context
            is AndroidBootstrapContextCarrier -> bootstrap.context
            else -> throw MeshLinkException.InvalidConfiguration(ANDROID_BOOTSTRAP_REQUIRED_MESSAGE)
        }
    val secureStorage = PreferencesSecureStorage(androidContext, config.appId)
    val cryptoProvider = JcaCryptoProviderFactory.create()
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
            BleTransportAdapter(
                context = androidContext,
                appId = config.appId,
                advertisementKeyHash = localIdentity.advertisementKeyHash,
            ),
        batteryMonitor = AndroidBatteryMonitor(androidContext),
    )
}

private fun createFactoryTestMeshLink(config: MeshLinkConfig): MeshLink {
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
