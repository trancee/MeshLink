package ch.trancee.meshlink.api

import ch.trancee.meshlink.config.MeshLinkConfig
import ch.trancee.meshlink.platform.createAndroidMeshLink
import ch.trancee.meshlink.platform.createIosMeshLink
import ch.trancee.meshlink.platform.createMeshLink

public object MeshLink {
    /**
     * Creates a MeshLink runtime when the current platform does not require extra bootstrap input.
     *
     * iOS consumers call this overload directly. Android consumers must call the overload that also
     * accepts a platform context.
     *
     * @throws MeshLinkException.InvalidConfiguration on Android when no platform context is
     *   supplied.
     */
    public fun create(config: MeshLinkConfig): MeshLinkApi {
        return createMeshLink(config = config)
    }

    /**
     * Creates a MeshLink runtime on Android using [context] for platform services.
     *
     * The supplied value must be an Android [android.content.Context] on Android targets.
     *
     * @throws MeshLinkException.InvalidConfiguration when [context] is missing or invalid.
     */
    public fun create(config: MeshLinkConfig, context: Any): MeshLinkApi {
        return createAndroidMeshLink(config = config, context = context)
    }

    @Deprecated(
        message = "Use create(config, context) instead.",
        replaceWith =
            ReplaceWith(
                expression = "MeshLink.create(config = config, context = context)",
                imports = ["ch.trancee.meshlink.api.MeshLink"],
            ),
    )
    public fun createAndroid(context: Any, config: MeshLinkConfig): MeshLinkApi {
        return create(config = config, context = context)
    }

    @Deprecated(
        message = "Use create(config) instead.",
        replaceWith =
            ReplaceWith(
                expression = "MeshLink.create(config)",
                imports = ["ch.trancee.meshlink.api.MeshLink"],
            ),
    )
    public fun createIos(config: MeshLinkConfig): MeshLinkApi {
        return createIosMeshLink(config = config)
    }
}
