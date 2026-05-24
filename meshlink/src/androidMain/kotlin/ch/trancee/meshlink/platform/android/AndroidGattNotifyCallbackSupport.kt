package ch.trancee.meshlink.platform.android

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.os.Build

internal fun createAndroidGattNotifyCallback(
    sdkInt: Int,
    relay: AndroidGattNotifyCallbackRelay,
): BluetoothGattCallback {
    return selectAndroidGattNotifyCallback(
        sdkInt = sdkInt,
        modernFactory = { AndroidModernGattNotifyCallback(relay) },
        legacyFactory = { AndroidLegacyGattNotifyCallback(relay) },
    )
}

internal fun <T> selectAndroidGattNotifyCallback(
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

private abstract class AndroidBaseGattNotifyCallback(
    private val relay: AndroidGattNotifyCallbackRelay
) : BluetoothGattCallback() {
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

private class AndroidModernGattNotifyCallback(relay: AndroidGattNotifyCallbackRelay) :
    AndroidBaseGattNotifyCallback(relay) {
    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ) {
        relayCharacteristicValue(characteristicUuid = characteristic.uuid.toString(), value = value)
    }
}

private class AndroidLegacyGattNotifyCallback(relay: AndroidGattNotifyCallbackRelay) :
    AndroidBaseGattNotifyCallback(relay) {
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
