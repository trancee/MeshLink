package ch.trancee.meshlink.api

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
public object IosBleTransportBridge {
    public fun install(gattNotifySend: (Any, Any, Any, ByteArray) -> Boolean): Unit {
        IosBleTransportBridgeRegistry.install(
            IosBleTransportCallbacks(
                gattNotifySend = gattNotifySend,
            )
        )
    }
}

internal class IosBleTransportCallbacks
internal constructor(
    internal val gattNotifySend: (Any, Any, Any, ByteArray) -> Boolean,
)

internal object IosBleTransportBridgeRegistry {
    private var callbacks: IosBleTransportCallbacks? = null

    internal fun install(callbacks: IosBleTransportCallbacks): Unit {
        this.callbacks = callbacks
    }

    internal fun clear(): Unit {
        callbacks = null
    }

    internal fun currentCallbacksOrNull(): IosBleTransportCallbacks? {
        return callbacks
    }

    internal fun requireCallbacks(): IosBleTransportCallbacks {
        return callbacks
            ?: throw MeshLinkException.PlatformFailure(
                message =
                    "iOS BLE transport bridge is not installed. Call IosBleTransportBridge.install(...) during app startup."
            )
    }
}
