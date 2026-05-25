package ch.trancee.meshlink.api

import ch.trancee.meshlink.config.MeshLinkConfig
import ch.trancee.meshlink.platform.AndroidFactoryTestContext

internal actual fun installFactoryTestBridges(): Unit = Unit

internal actual fun createAndroidFactoryParityApi(config: MeshLinkConfig): MeshLinkApi {
    return createMeshLinkRuntime(config = config, context = AndroidFactoryTestContext)
}

internal actual fun createIosFactoryParityApi(config: MeshLinkConfig): MeshLinkApi {
    return createMeshLinkRuntime(config = config)
}
