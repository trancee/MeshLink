package ch.trancee.meshlink.platform

import android.content.Context
import ch.trancee.meshlink.api.MeshLinkApi
import ch.trancee.meshlink.config.MeshLinkConfig
import ch.trancee.meshlink.engine.MeshEngine
import ch.trancee.meshlink.identity.LocalIdentity
import ch.trancee.meshlink.platform.android.AndroidBleTransport
import ch.trancee.meshlink.platform.android.AndroidSecureStorage

internal actual fun createAndroidMeshLink(config: MeshLinkConfig, context: Any): MeshLinkApi {
    val androidContext = context as? Context ?: error("Android context is required")
    return MeshEngine.create(
        config = config,
        platformContext = androidContext,
        localIdentity = LocalIdentity.fromAppId(config.appId),
        secureStorage = AndroidSecureStorage(androidContext, config.appId),
        bleTransport = AndroidBleTransport(androidContext),
    )
}

internal actual fun createIosMeshLink(config: MeshLinkConfig): MeshLinkApi {
    return MeshEngine.create(
        config = config,
        localIdentity = LocalIdentity.fromAppId(config.appId),
        secureStorage = ch.trancee.meshlink.storage.InMemorySecureStorage(),
    )
}
