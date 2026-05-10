package ch.trancee.meshlink.platform

import ch.trancee.meshlink.api.MeshLinkApi
import ch.trancee.meshlink.config.MeshLinkConfig

internal expect fun createAndroidMeshLink(config: MeshLinkConfig, context: Any): MeshLinkApi

internal expect fun createIosMeshLink(config: MeshLinkConfig): MeshLinkApi
