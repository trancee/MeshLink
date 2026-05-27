package ch.trancee.meshlink.platform

import ch.trancee.meshlink.api.MeshLink
import ch.trancee.meshlink.api.MeshLinkBootstrap
import ch.trancee.meshlink.config.MeshLinkConfig

internal expect fun createMeshLink(config: MeshLinkConfig): MeshLink

internal expect fun createMeshLink(config: MeshLinkConfig, bootstrap: MeshLinkBootstrap): MeshLink
