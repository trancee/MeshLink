package ch.trancee.meshlink.api.apple

import kotlin.concurrent.Volatile

/**
 * Enables the optional iOS GATT-notify peripheral bearer.
 *
 * MeshLink can act as a Bluetooth LE GATT peripheral that notifies subscribed centrals directly, in
 * addition to the default L2CAP bearer. The CoreBluetooth plumbing for this bearer is fully
 * implemented inside MeshLink; host apps do not need to supply any native callbacks. Call
 * [enableGattNotifyBearer] once during app startup, before
 * [MeshLink][ch.trancee.meshlink.api.MeshLink] is started, to opt in.
 *
 * Leave the bearer disabled (the default) if the host app only needs the L2CAP bearer.
 */
public object BleTransportBridge {
    /** Opts in to the iOS GATT-notify peripheral bearer. */
    public fun enableGattNotifyBearer(): Unit {
        BleTransportBridgeRegistry.setGattNotifyBearerEnabled(true)
    }

    /** Opts back out of the iOS GATT-notify peripheral bearer. */
    public fun disableGattNotifyBearer(): Unit {
        BleTransportBridgeRegistry.setGattNotifyBearerEnabled(false)
    }
}

internal object BleTransportBridgeRegistry {
    @Volatile private var gattNotifyBearerEnabled: Boolean = false

    internal fun setGattNotifyBearerEnabled(enabled: Boolean): Unit {
        gattNotifyBearerEnabled = enabled
    }

    internal fun isGattNotifyBearerEnabled(): Boolean {
        return gattNotifyBearerEnabled
    }

    internal fun clear(): Unit {
        gattNotifyBearerEnabled = false
    }
}
