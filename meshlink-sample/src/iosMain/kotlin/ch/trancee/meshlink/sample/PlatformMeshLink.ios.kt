package ch.trancee.meshlink.sample

import ch.trancee.meshlink.api.MeshLink
import ch.trancee.meshlink.api.MeshLinkApi
import ch.trancee.meshlink.api.MeshLinkConfig
import ch.trancee.meshlink.api.createIos

/**
 * iOS actual for [createPlatformMeshLink]: creates a [MeshLink] backed by
 * [ch.trancee.meshlink.transport.IosBleTransport].
 *
 * The restoration identifier is set to the sample app's bundle identifier so CoreBluetooth
 * state restoration delivers pending connections to the correct process on wake.
 */
actual fun createPlatformMeshLink(config: MeshLinkConfig): MeshLinkApi =
    MeshLink.createIos(config, restorationIdentifier = "ch.trancee.meshlink.sample")
