package ch.trancee.meshlink.engine.assembly

import ch.trancee.meshlink.api.MeshLink
import ch.trancee.meshlink.config.MeshLinkConfig
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
        // No production-safe default is available at this layer: per issue #118, the previous
        // default silently built a LocalIdentity via PlaceholderCryptoProvider (a deterministic,
        // non-cryptographic stand-in) -- now moved to commonTest, so it is not even reachable from
        // this commonMain default expression anymore. Every real platform factory
        // (Android/iOS/JVM) already constructs and passes its own LocalIdentity explicitly via
        // loadOrCreateLocalIdentityBlocking with a real CryptoProvider, so requiring this parameter
        // here is a source-compatible no-op for all of them; only a caller that was silently
        // relying on the removed placeholder default (which no shipping factory did) is affected.
        localIdentity: LocalIdentity,
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
