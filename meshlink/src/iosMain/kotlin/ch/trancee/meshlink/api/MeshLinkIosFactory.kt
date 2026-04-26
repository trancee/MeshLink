package ch.trancee.meshlink.api

import ch.trancee.meshlink.crypto.Identity
import ch.trancee.meshlink.crypto.IosCryptoProvider
import ch.trancee.meshlink.power.FixedBatteryMonitor
import ch.trancee.meshlink.power.PowerTier
import ch.trancee.meshlink.storage.IosSecureStorage
import ch.trancee.meshlink.transport.IosBleTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import platform.Foundation.NSDate

private const val TAG = "MeshLinkIosFactory"

/**
 * Creates a [MeshLink] instance backed by [IosBleTransport], [IosCryptoProvider], and
 * [IosSecureStorage].
 *
 * Identity is loaded from (or generated into) iOS Keychain via [IosSecureStorage]. [PowerTier]
 * starts at [PowerTier.BALANCED]; call [MeshLink.updateBattery] to enable automatic tier selection
 * based on battery level.
 *
 * The [restorationIdentifier] is forwarded to
 * [CBCentralManager][platform.CoreBluetooth.CBCentralManager] and
 * [CBPeripheralManager][platform.CoreBluetooth.CBPeripheralManager] for iOS State Preservation &
 * Restoration. It should be unique per application and match the value registered in the app's
 * `Info.plist` `UIBackgroundModes` entry.
 *
 * **Caller responsibility:** the host app's `Info.plist` must declare `bluetooth-central` and
 * `bluetooth-peripheral` in `UIBackgroundModes`, and the app must have requested
 * `NSBluetoothAlwaysUsageDescription` authorisation before calling [MeshLink.start].
 *
 * @param config MeshLink configuration (created via [meshLinkConfig] or a preset factory).
 * @param restorationIdentifier CoreBluetooth state restoration identifier. Defaults to
 *   `"ch.trancee.meshlink"`. Override to match your app bundle identifier for proper restoration.
 * @return A new [MeshLink] instance ready to [MeshLink.start].
 */
public fun MeshLink.Companion.createIos(
    config: MeshLinkConfig,
    restorationIdentifier: String = "ch.trancee.meshlink",
): MeshLink {
    println("[$TAG] createIos restorationIdentifier=$restorationIdentifier")

    val storage = IosSecureStorage()
    val isFirstLaunch = !storage.contains("meshlink.identity.ed25519.public")
    println("[$TAG] createIos isFirstLaunch=$isFirstLaunch")

    val crypto = IosCryptoProvider()
    val identity = Identity.loadOrGenerate(crypto, storage)
    if (isFirstLaunch) {
        println("[$TAG] Identity generated and persisted to Keychain")
    } else {
        println("[$TAG] Identity loaded from Keychain")
    }

    val bleConfig = config.toBleTransportConfig()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val powerTierFlow = MutableStateFlow(PowerTier.BALANCED)

    val transport =
        IosBleTransport(
            config = bleConfig,
            cryptoProvider = crypto,
            identity = identity,
            scope = scope,
            powerTierFlow = powerTierFlow,
            restorationIdentifier = restorationIdentifier,
        )
    println("[$TAG] IosBleTransport created localPeerId=${transport.localPeerId.size}b")

    val clock: () -> Long = { (NSDate().timeIntervalSince1970 * 1_000.0).toLong() }
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
