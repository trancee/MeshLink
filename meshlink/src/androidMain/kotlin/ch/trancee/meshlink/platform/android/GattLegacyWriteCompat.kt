package ch.trancee.meshlink.platform.android

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor

@Suppress("DEPRECATION")
internal fun writeGattDescriptorLegacy(
    gatt: BluetoothGatt,
    descriptor: BluetoothGattDescriptor,
    value: ByteArray,
): Boolean {
    descriptor.value = value.copyOf()
    return gatt.writeDescriptor(descriptor)
}

@Suppress("DEPRECATION")
internal fun writeGattCharacteristicLegacy(
    gatt: BluetoothGatt,
    characteristic: BluetoothGattCharacteristic,
    value: ByteArray,
    writeType: Int,
): Boolean {
    characteristic.writeType = writeType
    characteristic.value = value.copyOf()
    return gatt.writeCharacteristic(characteristic)
}
