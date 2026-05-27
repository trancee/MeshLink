package ch.trancee.meshlink.api

import ch.trancee.meshlink.config.MeshLinkConfig

internal expect fun installFactoryTestBridges(): Unit

internal expect fun createAndroidFactoryParityApi(config: MeshLinkConfig): MeshLink

internal expect fun createIosFactoryParityApi(config: MeshLinkConfig): MeshLink
