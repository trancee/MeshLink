package ch.trancee.meshlink.api

import ch.trancee.meshlink.config.MeshLinkConfig
import ch.trancee.meshlink.engine.MeshEngine
import ch.trancee.meshlink.identity.LocalIdentity
import ch.trancee.meshlink.storage.InMemorySecureStorage

public object MeshLink {
    public fun createAndroid(context: Any, config: MeshLinkConfig): MeshLinkApi {
        return MeshEngine.create(
            config = config,
            platformContext = context,
            localIdentity = LocalIdentity.fromAppId(config.appId),
            secureStorage = InMemorySecureStorage(),
        )
    }

    public fun createIos(config: MeshLinkConfig): MeshLinkApi {
        return MeshEngine.create(
            config = config,
            localIdentity = LocalIdentity.fromAppId(config.appId),
            secureStorage = InMemorySecureStorage(),
        )
    }
}
