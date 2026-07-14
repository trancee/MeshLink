package ch.trancee.meshlink.platform.android.gatt

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.os.Build

internal fun createGattNotifyCallback(
    sdkInt: Int,
    relay: GattNotifyCallbackRelay,
): BluetoothGattCallback {
    return selectGattNotifyCallback(
        sdkInt = sdkInt,
        modernFactory = { ModernGattNotifyCallback(relay) },
        legacyFactory = { LegacyGattNotifyCallback(relay) },
    )
}

internal fun <T> selectGattNotifyCallback(
    sdkInt: Int,
    modernFactory: () -> T,
    legacyFactory: () -> T,
): T {
    return if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
        modernFactory()
    } else {
        legacyFactory()
    }
}

private abstract class BaseGattNotifyCallback(private val relay: GattNotifyCallbackRelay) :
    BluetoothGattCallback() {
    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        relay.onConnectionStateChange(
            address = gatt.device.address,
            status = status,
            newState = newState,
        )
    }

    override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
        relay.onMtuChanged(mtu = mtu, status = status)
    }

    override fun onPhyUpdate(gatt: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
        relay.onPhyUpdate(txPhy = txPhy, rxPhy = rxPhy, status = status)
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        relay.onServicesDiscovered(status = status)
    }

    override fun onDescriptorWrite(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        status: Int,
    ) {
        relay.onDescriptorWrite(descriptorUuid = descriptor.uuid.toString(), status = status)
    }

    override fun onCharacteristicWrite(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int,
    ) {
        relay.onCharacteristicWrite(
            characteristicUuid = characteristic.uuid.toString(),
            status = status,
        )
    }

    protected fun relayCharacteristicValue(characteristicUuid: String, value: ByteArray?): Unit {
        relay.onCharacteristicChanged(characteristicUuid = characteristicUuid, value = value)
    }
}

private class ModernGattNotifyCallback(relay: GattNotifyCallbackRelay) :
    BaseGattNotifyCallback(relay) {
    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ) {
        relayCharacteristicValue(characteristicUuid = characteristic.uuid.toString(), value = value)
    }
}

private class LegacyGattNotifyCallback(relay: GattNotifyCallbackRelay) :
    BaseGattNotifyCallback(relay) {
    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
    ) {
        relayCharacteristicValue(
            characteristicUuid = characteristic.uuid.toString(),
            value = characteristic.value,
        )
    }
}
