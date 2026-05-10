package ch.trancee.meshlink.api

import ch.trancee.meshlink.config.MeshLinkConfig
import ch.trancee.meshlink.engine.MeshEngine

public object MeshLink {
    public fun createAndroid(context: Any, config: MeshLinkConfig): MeshLinkApi {
        return MeshEngine.create(config = config, platformContext = context)
    }

    public fun createIos(config: MeshLinkConfig): MeshLinkApi {
        return MeshEngine.create(config = config)
    }
}
