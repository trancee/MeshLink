package ch.trancee.meshlink.platform.android.gatt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.os.Build
import java.util.UUID

// One function per discrete BluetoothGatt client operation this adapter wraps for testability
// (see GattNotifyClientTest/BluetoothGattNotifySessionTest for the fakes this seam enables);
// disconnect() was added alongside close() specifically so callers can request a graceful
// disconnect before releasing the client interface, matching documented Android BLE guidance.
@Suppress("TooManyFunctions")
internal interface GattConnectionAdapter {
    val address: String

    fun requestConnectionPriority(priority: Int): Unit

    fun requestFastPhy(): Unit

    fun requestMtu(mtu: Int): Boolean

    fun discoverServices(): Unit

    /**
     * Clears the platform's cached GATT service table for this connection, forcing the next
     * [discoverServices] call to query the remote device directly rather than returning a stale
     * cached result. Returns false if the refresh could not be requested (e.g. the hidden
     * `BluetoothGatt.refresh()` API is unavailable on this platform/OEM build).
     */
    fun refreshServiceCache(): Boolean

    fun findService(uuid: String): GattServiceAdapter?

    fun setCharacteristicNotification(
        characteristic: GattCharacteristicAdapter,
        enabled: Boolean,
    ): Unit

    fun writeDescriptor(descriptor: GattDescriptorAdapter, value: ByteArray): Boolean

    fun writeCharacteristic(
        characteristic: GattCharacteristicAdapter,
        value: ByteArray,
        writeType: Int,
    ): Boolean

    /**
     * Requests a graceful disconnect of this GATT client connection. Android's BluetoothGatt API
     * documents that [close] should always be preceded by [disconnect] when the connection may
     * still be active -- closing directly abruptly releases the client interface slot without
     * giving the underlying controller/HAL a chance to tear down the link-layer connection first.
     * On some OEM Bluetooth stacks (observed on older Samsung/MediaTek-based devices in this
     * project's device fleet), skipping this step has been linked to a subsequent connectGatt() to
     * the same remote device either failing immediately with status=133 (GATT_ERROR) or producing
     * duplicate/conflicting connection-state callbacks instead of ever reaching a clean ready
     * state.
     */
    fun disconnect(): Unit

    fun close(): Unit
}

internal interface GattServiceAdapter {
    fun findCharacteristic(uuid: String): GattCharacteristicAdapter?
}

internal interface GattCharacteristicAdapter {
    val uuid: String

    fun findDescriptor(uuid: String): GattDescriptorAdapter?
}

internal interface GattDescriptorAdapter {
    val uuid: String
}

@SuppressLint("MissingPermission", "ObsoleteSdkInt")
@Suppress("TooManyFunctions")
internal class PlatformGattConnectionAdapter(
    private val gatt: BluetoothGatt,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : GattConnectionAdapter {
    override val address: String
        get() = gatt.device.address

    override fun requestConnectionPriority(priority: Int): Unit {
        gatt.requestConnectionPriority(priority)
    }

    override fun requestFastPhy(): Unit {
        gatt.setPreferredPhy(
            BluetoothDevice.PHY_LE_2M_MASK,
            BluetoothDevice.PHY_LE_2M_MASK,
            BluetoothDevice.PHY_OPTION_NO_PREFERRED,
        )
    }

    override fun requestMtu(mtu: Int): Boolean {
        return gatt.requestMtu(mtu)
    }

    override fun discoverServices(): Unit {
        gatt.discoverServices()
    }

    override fun refreshServiceCache(): Boolean {
        return refreshBluetoothGattServiceCache(gatt)
    }

    override fun findService(uuid: String): GattServiceAdapter? {
        return gatt.getService(UUID.fromString(uuid))?.let(::PlatformGattServiceAdapter)
    }

    override fun setCharacteristicNotification(
        characteristic: GattCharacteristicAdapter,
        enabled: Boolean,
    ): Unit {
        val platformCharacteristic = (characteristic as PlatformGattCharacteristicAdapter).delegate
        gatt.setCharacteristicNotification(platformCharacteristic, enabled)
    }

    override fun writeDescriptor(descriptor: GattDescriptorAdapter, value: ByteArray): Boolean {
        val platformDescriptor = (descriptor as PlatformGattDescriptorAdapter).delegate
        return writeGattDescriptor(
            sdkInt = sdkInt,
            api33Write = { gatt.writeDescriptor(platformDescriptor, value.copyOf()) },
            legacyWrite = { writeGattDescriptorLegacy(gatt, platformDescriptor, value) },
        )
    }

    override fun writeCharacteristic(
        characteristic: GattCharacteristicAdapter,
        value: ByteArray,
        writeType: Int,
    ): Boolean {
        val platformCharacteristic = (characteristic as PlatformGattCharacteristicAdapter).delegate
        return writeGattCharacteristic(
            sdkInt = sdkInt,
            api33Write = {
                gatt.writeCharacteristic(platformCharacteristic, value.copyOf(), writeType)
            },
            legacyWrite = {
                writeGattCharacteristicLegacy(gatt, platformCharacteristic, value, writeType)
            },
        )
    }

    override fun close(): Unit {
        gatt.close()
    }

    override fun disconnect(): Unit {
        gatt.disconnect()
    }
}

// BluetoothGatt.refresh() clears the platform's per-device GATT service cache. It has been a
// hidden/@UnsupportedAppUsage API on every Android version to date (there is no public
// alternative), so it must be invoked via reflection. Without this, a device that reconnects to a
// peer whose GATT service table changed since the cache was populated (e.g. the remote app
// restarted between test runs, changing its dynamically-registered service) can have
// discoverServices() return a stale, incomplete table indefinitely - observed in the field as a
// persistent "missing service" loop that never self-heals across reconnect attempts, even though
// the remote peripheral is correctly advertising the expected service the whole time. See
// docs/explanation/reference-app-physical-integration-findings.md for the investigation that
// uncovered this.
internal fun refreshBluetoothGattServiceCache(gatt: BluetoothGatt): Boolean {
    return runCatching {
            val refresh = gatt.javaClass.getMethod("refresh")
            refresh.invoke(gatt) as? Boolean ?: false
        }
        .getOrDefault(false)
}

internal class PlatformGattServiceAdapter(val delegate: BluetoothGattService) : GattServiceAdapter {
    override fun findCharacteristic(uuid: String): GattCharacteristicAdapter? {
        return delegate
            .getCharacteristic(UUID.fromString(uuid))
            ?.let(::PlatformGattCharacteristicAdapter)
    }
}

internal class PlatformGattCharacteristicAdapter(val delegate: BluetoothGattCharacteristic) :
    GattCharacteristicAdapter {
    override val uuid: String
        get() = delegate.uuid.toString()

    override fun findDescriptor(uuid: String): GattDescriptorAdapter? {
        return delegate.getDescriptor(UUID.fromString(uuid))?.let(::PlatformGattDescriptorAdapter)
    }
}

internal class PlatformGattDescriptorAdapter(val delegate: BluetoothGattDescriptor) :
    GattDescriptorAdapter {
    override val uuid: String
        get() = delegate.uuid.toString()
}
