package ch.trancee.meshlink.platform

import android.content.Context
import ch.trancee.meshlink.api.MeshLinkApi
import ch.trancee.meshlink.api.MeshLinkException
import ch.trancee.meshlink.config.MeshLinkConfig
import ch.trancee.meshlink.engine.MeshEngine
import ch.trancee.meshlink.identity.LocalIdentityStore
import ch.trancee.meshlink.platform.android.AndroidBleTransport
import ch.trancee.meshlink.platform.android.AndroidCryptoProvider
import ch.trancee.meshlink.platform.android.AndroidJcaCapabilityProbe
import ch.trancee.meshlink.platform.android.AndroidSecureStorage
import ch.trancee.meshlink.storage.InMemorySecureStorage
import kotlinx.coroutines.runBlocking

internal actual fun createAndroidMeshLink(config: MeshLinkConfig, context: Any): MeshLinkApi {
    val androidContext = context as? Context ?: error("Android context is required")
    val secureStorage = AndroidSecureStorage(androidContext, config.appId)
    val cryptoProvider = requireAndroidNoiseCryptoSupport()
    val localIdentity = runBlocking {
        LocalIdentityStore.loadOrCreate(
            appId = config.appId,
            secureStorage = secureStorage,
            provider = cryptoProvider,
        )
    }
    return MeshEngine.create(
        config = config,
        platformContext = androidContext,
        localIdentity = localIdentity,
        secureStorage = secureStorage,
        bleTransport = AndroidBleTransport(
            context = androidContext,
            appId = config.appId,
            advertisementKeyHash = localIdentity.advertisementKeyHash,
        ),
    )
}

internal actual fun createIosMeshLink(config: MeshLinkConfig): MeshLinkApi {
    val secureStorage = InMemorySecureStorage()
    val cryptoProvider = requireAndroidNoiseCryptoSupport()
    val localIdentity = runBlocking {
        LocalIdentityStore.loadOrCreate(
            appId = config.appId,
            secureStorage = secureStorage,
            provider = cryptoProvider,
        )
    }
    return MeshEngine.create(
        config = config,
        localIdentity = localIdentity,
        secureStorage = secureStorage,
    )
}

private fun requireAndroidNoiseCryptoSupport(): AndroidCryptoProvider {
    val capabilityReport = AndroidJcaCapabilityProbe.detect()
    if (!capabilityReport.supportsNoisePrimitives) {
        throw MeshLinkException.CryptoFailure(
            message = "Android device does not expose the required JCA primitives for Noise XX (X25519/XDH, Ed25519, ChaCha20-Poly1305). Pure-Kotlin fallback is not yet installed.",
        )
    }
    return AndroidCryptoProvider()
}
