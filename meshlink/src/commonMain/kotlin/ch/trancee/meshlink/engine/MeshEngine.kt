package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.MeshLinkApi
import ch.trancee.meshlink.config.MeshLinkConfig
import ch.trancee.meshlink.diagnostics.DiagnosticSink
import ch.trancee.meshlink.identity.LocalIdentity
import ch.trancee.meshlink.storage.InMemorySecureStorage
import ch.trancee.meshlink.storage.SecureStorage
import ch.trancee.meshlink.transport.BleTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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
        val assembly =
            assembleMeshEngineRuntime(
                config = config,
                localIdentity = localIdentity,
                secureStorage = secureStorage,
                bleTransport = bleTransport,
                diagnosticSink = diagnosticSink,
                coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            )
        return MeshEngineRuntime(
            publishedSurface = assembly.publishedSurface,
            graph = assembly.graph,
        )
    }
}
