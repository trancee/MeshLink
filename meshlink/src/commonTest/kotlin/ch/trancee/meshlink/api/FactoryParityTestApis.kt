package ch.trancee.meshlink.api

import ch.trancee.meshlink.config.MeshLinkConfig

internal expect fun installFactoryTestBridges(): Unit

internal expect fun createAndroidFactoryParityApi(config: MeshLinkConfig): MeshLinkApi

internal expect fun createIosFactoryParityApi(config: MeshLinkConfig): MeshLinkApi
