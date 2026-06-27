package ch.trancee.meshlink.api.android

import android.content.Context
import ch.trancee.meshlink.api.MeshLink
import ch.trancee.meshlink.api.MeshLinkBootstrap
import ch.trancee.meshlink.config.MeshLinkConfig
import ch.trancee.meshlink.engine.MeshEngine
import ch.trancee.meshlink.platform.android.BleTransportAdapter
import ch.trancee.meshlink.platform.android.JcaCryptoProviderFactory
import ch.trancee.meshlink.platform.android.PreferencesSecureStorage
import ch.trancee.meshlink.platform.loadOrCreateLocalIdentityBlocking

/** Returns an Android bootstrap handle backed by the application context. */
public fun meshLinkBootstrap(context: Context): MeshLinkBootstrap {
    return AndroidMeshLinkBootstrap(context.applicationContext)
}

/** Creates a MeshLink runtime directly from an Android application context. */
public fun meshLink(config: MeshLinkConfig, context: Context): MeshLink {
    val appContext = context.applicationContext
    val secureStorage = PreferencesSecureStorage(appContext, config.appId)
    val cryptoProvider = JcaCryptoProviderFactory.create()
    val localIdentity =
        loadOrCreateLocalIdentityBlocking(
            appId = config.appId,
            secureStorage = secureStorage,
            provider = cryptoProvider,
        )
    return MeshEngine.create(
        config = config,
        platformContext = appContext,
        localIdentity = localIdentity,
        secureStorage = secureStorage,
        bleTransport =
            BleTransportAdapter(
                context = appContext,
                appId = config.appId,
                advertisementKeyHash = localIdentity.advertisementKeyHash,
            ),
    )
}
