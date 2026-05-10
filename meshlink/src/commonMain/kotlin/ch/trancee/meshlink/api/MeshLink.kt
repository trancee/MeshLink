package ch.trancee.meshlink.api

import ch.trancee.meshlink.config.MeshLinkConfig
import ch.trancee.meshlink.platform.createAndroidMeshLink
import ch.trancee.meshlink.platform.createIosMeshLink

public object MeshLink {
    public fun createAndroid(context: Any, config: MeshLinkConfig): MeshLinkApi {
        return createAndroidMeshLink(config = config, context = context)
    }

    public fun createIos(config: MeshLinkConfig): MeshLinkApi {
        return createIosMeshLink(config = config)
    }
}
