package ch.trancee.meshlink.api

import ch.trancee.meshlink.config.MeshLinkConfig
import ch.trancee.meshlink.platform.createMeshLink

/**
 * Creates a MeshLink runtime when the current platform does not require extra bootstrap input.
 *
 * This top-level helper gives Swift and Kotlin callers a stable factory entry point without needing
 * to reference a generated singleton object directly.
 *
 * @throws MeshLinkException.InvalidConfiguration on Android when no platform bootstrap is supplied.
 */
public fun meshLink(config: MeshLinkConfig): MeshLinkApi {
    return createMeshLink(config = config)
}

/**
 * Creates a MeshLink runtime with typed platform bootstrap input, such as an Android application
 * context wrapped by `androidMeshLinkBootstrap(...)`.
 *
 * On platforms that do not require extra bootstrap input, prefer [meshLink].
 *
 * @throws MeshLinkException.InvalidConfiguration when [bootstrap] is missing or invalid for the
 *   current platform.
 */
public fun meshLink(config: MeshLinkConfig, bootstrap: MeshLinkBootstrap): MeshLinkApi {
    return createMeshLink(config = config, bootstrap = bootstrap)
}
