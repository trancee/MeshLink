package ch.trancee.meshlink.test

import ch.trancee.meshlink.api.MeshLinkApi
import ch.trancee.meshlink.api.MeshLink
import ch.trancee.meshlink.config.MeshLinkConfig
import ch.trancee.meshlink.config.meshLinkConfig

internal class MeshTestHarness {
    private val transports: MutableList<VirtualMeshTransport> = mutableListOf()

    internal fun createNode(config: MeshLinkConfig = defaultConfig()): MeshLinkApi {
        val transport = VirtualMeshTransport()
        transports += transport
        return MeshLink.createIos(config)
    }

    internal fun transportCount(): Int {
        return transports.size
    }

    private fun defaultConfig(): MeshLinkConfig {
        return meshLinkConfig {
            appId = "test.meshlink"
        }
    }
}
