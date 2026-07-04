package ch.trancee.meshlink.platform

import ch.trancee.meshlink.api.MeshLink
import ch.trancee.meshlink.api.MeshLinkBootstrap
import ch.trancee.meshlink.config.MeshLinkConfig
import ch.trancee.meshlink.engine.MeshEngine
import ch.trancee.meshlink.platform.ios.BleTransportAdapter
import ch.trancee.meshlink.platform.ios.BridgeCryptoProvider
import ch.trancee.meshlink.platform.ios.DefaultsSecureStorage
import ch.trancee.meshlink.platform.ios.IosBatteryMonitor
import ch.trancee.meshlink.storage.InMemorySecureStorage

internal actual fun createMeshLink(config: MeshLinkConfig): MeshLink {
    val secureStorage = DefaultsSecureStorage(config.appId)
    val cryptoProvider = BridgeCryptoProvider()
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
            BleTransportAdapter(
                appId = config.appId,
                advertisementKeyHash = localIdentity.advertisementKeyHash,
            ),
        batteryMonitor = IosBatteryMonitor(),
    )
}

internal actual fun createMeshLink(config: MeshLinkConfig, bootstrap: MeshLinkBootstrap): MeshLink {
    val secureStorage = InMemorySecureStorage()
    val cryptoProvider = BridgeCryptoProvider()
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
        batteryMonitor = IosBatteryMonitor(),
    )
}
