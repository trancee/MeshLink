package ch.trancee.meshlink.internal

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattConnectionSettings
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.content.Context
import android.os.Build
import java.util.concurrent.Executor

/**
 * The GATT calls whose API changed after API 26, each choosing the current API where the device
 * has it and [LegacyGatt] below. Results use [GattExchange]'s codes.
 */
internal object AndroidGatt {
    private val direct = Executor(Runnable::run)

    /**
     * Connects to [device] directly (not auto-connect) over LE. API 26–36 initiate on the 1M and 2M
     * PHYs (library rule R4); API 37's connection settings have no PHY choice. The core retries a
     * connect that [failsTransiently]; PHY requests belong to ticket 32.
     */
    fun connect(
        context: Context,
        device: BluetoothDevice,
        callback: BluetoothGattCallback,
    ): BluetoothGatt? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
            val settings =
                BluetoothGattConnectionSettings
                    .Builder()
                    .setTransport(BluetoothDevice.TRANSPORT_LE)
                    .setAutoConnectEnabled(false)
                    .build()
            device.connectGatt(settings, direct, callback)
        } else {
            LegacyGatt.connect(context, device, callback)
        }

    /**
     * Whether a connection that ended with [status] before its link was up is worth retrying:
     * `GATT_ERROR` (133) and `GATT_CONNECTION_TIMEOUT` (147), which retries cleared in the lab
     * (library rule R7).
     */
    fun failsTransiently(status: Int): Boolean = status == GATT_ERROR || status == GATT_CONNECTION_TIMEOUT

    /**
     * Whether the stack closing an open link with [status] is a transient loss its central
     * reconnects after: 133, 147, or 22 (terminated by the local host), which the stack reports
     * after it dropped a link whose ATT request went unanswered for 30 s (library rule R8).
     */
    fun losesTransiently(status: Int): Boolean = failsTransiently(status) || status == TERMINATED_BY_LOCAL_HOST

    /** Writes [value] to [characteristic] without response. */
    fun write(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(characteristic, value, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        } else {
            GattExchange.legacy(LegacyGatt.write(gatt, characteristic, value))
        }

    /** Writes [value] to [descriptor]. */
    fun write(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray,
    ): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value)
        } else {
            GattExchange.legacy(LegacyGatt.write(gatt, descriptor, value))
        }

    /** Notifies [device] of [value] on [characteristic], without confirmation. */
    fun notify(
        server: BluetoothGattServer,
        device: BluetoothDevice,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            server.notifyCharacteristicChanged(device, characteristic, false, value)
        } else {
            GattExchange.legacy(LegacyGatt.notify(server, device, characteristic, value))
        }
}

// The stack's status codes for link failures; the SDK leaves them hidden or adds them late (147 in API 35).
private const val GATT_ERROR = 133
private const val GATT_CONNECTION_TIMEOUT = 147
private const val TERMINATED_BY_LOCAL_HOST = 22
