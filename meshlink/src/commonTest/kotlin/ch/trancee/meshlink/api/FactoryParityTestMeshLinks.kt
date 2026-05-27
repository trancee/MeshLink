package ch.trancee.meshlink.api

import ch.trancee.meshlink.config.MeshLinkConfig

internal expect fun installFactoryTestBridges(): Unit

internal expect fun createAndroidFactoryParityMeshLink(config: MeshLinkConfig): MeshLink

internal expect fun createIosFactoryParityMeshLink(config: MeshLinkConfig): MeshLink
