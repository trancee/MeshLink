package ch.trancee.meshlink.api

import android.content.Context
import android.util.Log
import ch.trancee.meshlink.crypto.AndroidCryptoProvider
import ch.trancee.meshlink.crypto.Identity
import ch.trancee.meshlink.power.FixedBatteryMonitor
import ch.trancee.meshlink.power.PowerTier
import ch.trancee.meshlink.storage.AndroidSecureStorage
import ch.trancee.meshlink.transport.AndroidBleTransport
import ch.trancee.meshlink.transport.OemL2capProbeCache
import ch.trancee.meshlink.transport.OemSlotTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow

private const val TAG = "MeshLinkAndroidFactory"

/**
 * Creates a [MeshLink] instance backed by [AndroidBleTransport], [AndroidCryptoProvider], and
 * [AndroidSecureStorage].
 *
 * Identity is loaded from (or generated into) Android Keystore-backed
 * [EncryptedSharedPreferences][androidx.security.crypto.EncryptedSharedPreferences] via
 * [AndroidSecureStorage]. [PowerTier] starts at [PowerTier.BALANCED]; call [MeshLink.updateBattery]
 * to enable automatic tier selection based on battery level.
 *
 * The [OemL2capProbeCache] is restored from persistent storage so OEM L2CAP probe results survive
 * process restarts. The [OemSlotTracker] tracks per-OEM effective connection slots using the same
 * persistent storage.
 *
 * **Caller responsibility:** on Android 12+ the host app must hold a foreground service (or
 * equivalent) with the `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` permissions before calling
 * [MeshLink.start]. This factory does not start any Android Service.
 *
 * @param context Android [Context] used by [AndroidBleTransport] for [BluetoothManager] access and
 *   by [AndroidSecureStorage] for Keystore-backed persistent key storage.
 * @param config MeshLink configuration (created via [meshLinkConfig] or a preset factory).
 * @return A new [MeshLink] instance ready to [MeshLink.start].
 */
public fun MeshLink.Companion.createAndroid(context: Context, config: MeshLinkConfig): MeshLink {
    val storage = AndroidSecureStorage(context)
    val isFirstLaunch = !storage.contains("meshlink.identity.ed25519.public")
    Log.d(TAG, "createAndroid isFirstLaunch=$isFirstLaunch")

    val crypto = AndroidCryptoProvider()
    val identity = Identity.loadOrGenerate(crypto, storage)
    if (isFirstLaunch) {
        Log.d(TAG, "Identity generated and persisted to EncryptedSharedPreferences")
    } else {
        Log.d(TAG, "Identity loaded from EncryptedSharedPreferences")
    }

    val probeCache = OemL2capProbeCache()
    probeCache.restore(storage)
    Log.d(TAG, "OemL2capProbeCache restored entries=${probeCache.size}")

    val bleConfig = config.toBleTransportConfig()
    val oemSlotTracker =
        OemSlotTracker(storage, bleConfig.maxConnections) { System.currentTimeMillis() }
    Log.d(TAG, "OemSlotTracker constructed maxConnections=${bleConfig.maxConnections}")

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val powerTierFlow = MutableStateFlow(PowerTier.BALANCED)

    val transport =
        AndroidBleTransport(
            context = context,
            config = bleConfig,
            cryptoProvider = crypto,
            identity = identity,
            scope = scope,
            powerTierFlow = powerTierFlow,
            probeCache = probeCache,
            oemSlotTracker = oemSlotTracker,
            bootstrapMode = isFirstLaunch,
        )
    Log.d(TAG, "AndroidBleTransport created localPeerId=${transport.localPeerId.size}b")

    val clock: () -> Long = { System.currentTimeMillis() }
    return MeshLink.create(
        config = config,
        cryptoProvider = crypto,
        transport = transport,
        storage = storage,
        batteryMonitor = FixedBatteryMonitor(),
        parentScope = scope,
        clock = clock,
    )
}
