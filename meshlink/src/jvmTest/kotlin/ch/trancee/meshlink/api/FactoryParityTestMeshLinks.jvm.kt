package ch.trancee.meshlink.api

import ch.trancee.meshlink.config.MeshLinkConfig

internal actual fun installFactoryTestBridges(): Unit = Unit

internal actual fun createAndroidFactoryParityMeshLink(config: MeshLinkConfig): MeshLink {
    return meshLink(config = config, bootstrap = AndroidFactoryTestMeshLinkBootstrap)
}

internal actual fun createIosFactoryParityMeshLink(config: MeshLinkConfig): MeshLink {
    return meshLink(config = config)
}
