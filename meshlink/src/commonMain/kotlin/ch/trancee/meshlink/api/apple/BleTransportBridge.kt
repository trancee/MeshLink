package ch.trancee.meshlink.api.apple

import ch.trancee.meshlink.api.MeshLinkException

/**
 * Registers iOS-native CoreBluetooth callbacks for future MeshLink transport experiments.
 *
 * Install these callbacks during iOS application startup when the host app wants to enable
 * iPhone-hosted transport bearers that still need Apple-native bridging from KMP. The current
 * release keeps this bridge optional; when it is not installed, MeshLink continues on the existing
 * transport path.
 *
 * `gattNotifySend` receives opaque platform handles for the active `CBPeripheralManager`, the
 * `CBMutableCharacteristic` used for notifications, the subscribed `CBCentral`, and the raw frame
 * bytes to send. It must return whether Core Bluetooth accepted the notification immediately.
 */
public object BleTransportBridge {
    public fun install(gattNotifySend: (Any, Any, Any, ByteArray) -> Boolean): Unit {
        BleTransportBridgeRegistry.install(BleTransportCallbacks(gattNotifySend = gattNotifySend))
    }

    /**
     * Installs a more efficient iOS-native bridge variant that receives the GATT notification
     * payload as a platform data object instead of a Kotlin [ByteArray].
     *
     * Host apps may cast `payloadData` to `platform.Foundation.NSData` / Swift `Data` and hand it
     * directly to `CBPeripheralManager.updateValue(...)` to avoid per-byte bridge iteration.
     */
    public fun installData(gattNotifySendData: (Any, Any, Any, Any) -> Boolean): Unit {
        BleTransportBridgeRegistry.install(
            BleTransportCallbacks(
                gattNotifySend = { _, _, _, _ -> false },
                gattNotifySendData = gattNotifySendData,
            )
        )
    }
}

internal class BleTransportCallbacks
internal constructor(
    internal val gattNotifySend: (Any, Any, Any, ByteArray) -> Boolean,
    internal val gattNotifySendData: ((Any, Any, Any, Any) -> Boolean)? = null,
)

internal object BleTransportBridgeRegistry {
    private var callbacks: BleTransportCallbacks? = null

    internal fun install(callbacks: BleTransportCallbacks): Unit {
        this.callbacks = callbacks
    }

    internal fun clear(): Unit {
        callbacks = null
    }

    internal fun currentCallbacksOrNull(): BleTransportCallbacks? {
        return callbacks
    }

    internal fun requireCallbacks(): BleTransportCallbacks {
        return callbacks
            ?: throw MeshLinkException.PlatformFailure(
                message =
                    "iOS BLE transport bridge is not installed. " +
                        "Call BleTransportBridge.install(...) during app startup."
            )
    }
}
