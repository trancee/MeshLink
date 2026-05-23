package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.MeshLinkApi
import ch.trancee.meshlink.config.MeshLinkConfig
import ch.trancee.meshlink.diagnostics.DiagnosticSink
import ch.trancee.meshlink.identity.LocalIdentity
import ch.trancee.meshlink.storage.InMemorySecureStorage
import ch.trancee.meshlink.storage.SecureStorage
import ch.trancee.meshlink.transport.BleTransport

internal object MeshEngine {
    @Suppress("LongParameterList", "UnusedParameter")
    internal fun create(
        config: MeshLinkConfig,
        platformContext: Any? = null,
        localIdentity: LocalIdentity = LocalIdentity.fromAppId(config.appId),
        secureStorage: SecureStorage = InMemorySecureStorage(),
        bleTransport: BleTransport? = null,
        diagnosticSink: DiagnosticSink? = null,
    ): MeshLinkApi {
        return MeshEngineRuntime(
            config = config,
            localIdentity = localIdentity,
            secureStorage = secureStorage,
            bleTransport = bleTransport,
            diagnosticSink = diagnosticSink,
        )
    }
}
