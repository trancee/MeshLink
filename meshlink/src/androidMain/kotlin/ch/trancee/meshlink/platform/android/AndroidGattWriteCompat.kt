package ch.trancee.meshlink.platform.android

import android.bluetooth.BluetoothStatusCodes
import android.os.Build

internal fun writeAndroidGattDescriptor(
    sdkInt: Int,
    api33Write: () -> Int,
    legacyWrite: () -> Boolean,
): Boolean {
    return if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
        api33Write() == BluetoothStatusCodes.SUCCESS
    } else {
        legacyWrite()
    }
}

internal fun writeAndroidGattCharacteristic(
    sdkInt: Int,
    api33Write: () -> Int,
    legacyWrite: () -> Boolean,
): Boolean {
    return if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
        api33Write() == BluetoothStatusCodes.SUCCESS
    } else {
        legacyWrite()
    }
}
