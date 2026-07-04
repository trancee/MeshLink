package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.MeshLink
import ch.trancee.meshlink.config.MeshLinkConfig
import ch.trancee.meshlink.crypto.PlaceholderCryptoProvider
import ch.trancee.meshlink.diagnostics.DiagnosticSink
import ch.trancee.meshlink.identity.LocalIdentity
import ch.trancee.meshlink.power.BatteryMonitor
import ch.trancee.meshlink.power.NoOpBatteryMonitor
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
        localIdentity: LocalIdentity =
            LocalIdentity.fromAppId(
                appId = config.appId,
                meshDomainHash =
                    LocalIdentity.computeMeshDomainHash(
                        appId = config.appId,
                        provider = PlaceholderCryptoProvider,
                    ),
            ),
        secureStorage: SecureStorage = InMemorySecureStorage(),
        bleTransport: BleTransport? = null,
        batteryMonitor: BatteryMonitor = NoOpBatteryMonitor,
        diagnosticSink: DiagnosticSink? = null,
        coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    ): MeshLink {
        return MeshEngineRuntime.assembleMeshEngineRuntime(
            config = config,
            localIdentity = localIdentity,
            secureStorage = secureStorage,
            bleTransport = bleTransport,
            batteryMonitor = batteryMonitor,
            diagnosticSink = diagnosticSink,
            coroutineScope = coroutineScope,
        )
    }
}
