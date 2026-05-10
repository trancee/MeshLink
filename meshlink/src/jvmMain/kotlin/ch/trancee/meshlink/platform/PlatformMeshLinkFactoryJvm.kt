package ch.trancee.meshlink.platform

import ch.trancee.meshlink.api.MeshLinkApi
import ch.trancee.meshlink.config.MeshLinkConfig
import ch.trancee.meshlink.engine.MeshEngine
import ch.trancee.meshlink.identity.LocalIdentity
import ch.trancee.meshlink.storage.InMemorySecureStorage

internal actual fun createAndroidMeshLink(config: MeshLinkConfig, context: Any): MeshLinkApi {
    return MeshEngine.create(
        config = config,
        platformContext = context,
        localIdentity = LocalIdentity.fromAppId(config.appId),
        secureStorage = InMemorySecureStorage(),
    )
}

internal actual fun createIosMeshLink(config: MeshLinkConfig): MeshLinkApi {
    return MeshEngine.create(
        config = config,
        localIdentity = LocalIdentity.fromAppId(config.appId),
        secureStorage = InMemorySecureStorage(),
    )
}
