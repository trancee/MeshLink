package ch.trancee.meshlink.api

import ch.trancee.meshlink.config.MeshLinkConfig
import ch.trancee.meshlink.platform.createMeshLink

/**
 * Creates a MeshLink runtime when the current platform does not require extra bootstrap input.
 *
 * This top-level helper gives Swift and Kotlin callers a stable factory entry point without needing
 * to reference a generated singleton object directly.
 *
 * @throws MeshLinkException.InvalidConfiguration on Android when no platform context is supplied.
 */
public fun createMeshLinkRuntime(config: MeshLinkConfig): MeshLinkApi {
    return createMeshLink(config = config)
}

/**
 * Creates a MeshLink runtime on platforms that require extra bootstrap input, such as Android.
 *
 * The supplied value must be an Android `Context` on Android targets. On platforms that do not
 * require extra bootstrap input, prefer [createMeshLinkRuntime].
 *
 * @throws MeshLinkException.InvalidConfiguration when [context] is missing or invalid.
 */
public fun createMeshLinkRuntime(config: MeshLinkConfig, context: Any): MeshLinkApi {
    return createMeshLink(config = config, context = context)
}
