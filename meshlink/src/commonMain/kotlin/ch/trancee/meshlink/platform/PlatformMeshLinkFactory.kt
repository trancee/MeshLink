package ch.trancee.meshlink.platform

import ch.trancee.meshlink.api.MeshLinkApi
import ch.trancee.meshlink.api.MeshLinkBootstrap
import ch.trancee.meshlink.config.MeshLinkConfig

internal expect fun createMeshLink(config: MeshLinkConfig): MeshLinkApi

internal expect fun createMeshLink(
    config: MeshLinkConfig,
    bootstrap: MeshLinkBootstrap,
): MeshLinkApi
