package ch.trancee.meshlink.platform

import ch.trancee.meshlink.api.MeshLinkApi
import ch.trancee.meshlink.config.MeshLinkConfig
import ch.trancee.meshlink.engine.MeshEngine
import ch.trancee.meshlink.identity.LocalIdentity
import ch.trancee.meshlink.platform.ios.IosBleTransport
import ch.trancee.meshlink.platform.ios.IosSecureStorage

internal actual fun createAndroidMeshLink(config: MeshLinkConfig, context: Any): MeshLinkApi {
    return MeshEngine.create(
        config = config,
        platformContext = context,
        localIdentity = LocalIdentity.fromAppId(config.appId),
        secureStorage = ch.trancee.meshlink.storage.InMemorySecureStorage(),
    )
}

internal actual fun createIosMeshLink(config: MeshLinkConfig): MeshLinkApi {
    return MeshEngine.create(
        config = config,
        localIdentity = LocalIdentity.fromAppId(config.appId),
        secureStorage = IosSecureStorage(config.appId),
        bleTransport = IosBleTransport(),
    )
}
