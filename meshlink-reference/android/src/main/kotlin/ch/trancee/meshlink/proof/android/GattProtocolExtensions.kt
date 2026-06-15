@file:Suppress("ReturnCount", "MagicNumber", "TooManyFunctions")

package ch.trancee.meshlink.proof.android

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings

internal fun ByteArray.toHexToken(): String {
    return joinToString(separator = "") { byte ->
        (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
    }
}

internal fun String.decodeHexTokenBytes(): ByteArray? {
    if (length != 16 || length % 2 != 0) {
        return null
    }
    val bytes = ByteArray(length / 2)
    for (index in bytes.indices) {
        val charIndex = index * 2
        val hi = Character.digit(this[charIndex], 16)
        val lo = Character.digit(this[charIndex + 1], 16)
        if (hi < 0 || lo < 0) {
            return null
        }
        bytes[index] = ((hi shl 4) or lo).toByte()
    }
    return bytes
}

internal fun ByteArray.chunkedBytes(chunkSize: Int): List<ByteArray> {
    if (isEmpty()) {
        return listOf(ByteArray(0))
    }
    val chunks = mutableListOf<ByteArray>()
    var offset = 0
    while (offset < size) {
        val end = minOf(offset + chunkSize, size)
        chunks += copyOfRange(offset, end)
        offset = end
    }
    return chunks
}

internal fun benchmarkAckMatches(
    ack: ProofGattBenchmarkProtocol.AckFrame?,
    tokenHex: String?,
    expectedBytes: Int,
): Boolean {
    return ack != null && tokenHex != null && ack.tokenHex == tokenHex && ack.totalBytes == expectedBytes
}

internal fun BluetoothGatt.safeDiscoverServices(logger: (String) -> Unit): Boolean {
    return try {
        discoverServices()
        true
    } catch (securityException: SecurityException) {
        logger("GATT service discovery rejected: ${securityException.message}")
        false
    }
}

internal fun BluetoothLeScanner?.safeStartScan(
    filters: List<ScanFilter>,
    settings: ScanSettings,
    callback: ScanCallback,
    logger: (String) -> Unit,
): Boolean {
    return try {
        if (this == null) {
            logger("GATT scan rejected: BluetoothLeScanner is unavailable")
            false
        } else {
            startScan(filters, settings, callback)
            true
        }
    } catch (securityException: SecurityException) {
        logger("GATT scan rejected: ${securityException.message}")
        false
    }
}

internal fun BluetoothLeScanner?.safeStopScan(logger: (String) -> Unit, callback: ScanCallback) {
    try {
        this?.stopScan(callback)
    } catch (securityException: SecurityException) {
        logger("GATT scan stop rejected: ${securityException.message}")
    }
}

internal fun BluetoothDevice.safeConnectGatt(
    context: android.content.Context,
    autoConnect: Boolean,
    callback: BluetoothGattCallback,
    logger: (String) -> Unit,
): BluetoothGatt? {
    return try {
        connectGatt(context, autoConnect, callback)
    } catch (securityException: SecurityException) {
        logger("GATT connection rejected: ${securityException.message}")
        null
    }
}

internal fun BluetoothGatt.safeSetCharacteristicNotification(
    characteristic: BluetoothGattCharacteristic,
    enabled: Boolean,
    logger: (String) -> Unit,
): Boolean {
    return try {
        setCharacteristicNotification(characteristic, enabled)
        true
    } catch (securityException: SecurityException) {
        logger("GATT notification toggle rejected: ${securityException.message}")
        false
    }
}

@Suppress("DEPRECATION")
internal fun BluetoothGatt.safeWriteDescriptor(
    descriptor: BluetoothGattDescriptor,
    logger: (String) -> Unit,
): Boolean {
    return try {
        writeDescriptor(descriptor)
        true
    } catch (securityException: SecurityException) {
        logger("GATT descriptor write rejected: ${securityException.message}")
        false
    }
}

@Suppress("DEPRECATION")
internal fun BluetoothGatt.safeWriteCharacteristic(
    characteristic: BluetoothGattCharacteristic,
    logger: (String) -> Unit,
): Boolean {
    return try {
        writeCharacteristic(characteristic)
    } catch (securityException: SecurityException) {
        logger("GATT characteristic write rejected: ${securityException.message}")
        false
    }
}

internal fun BluetoothGatt?.safeClose(logger: (String) -> Unit) {
    try {
        this?.close()
    } catch (securityException: SecurityException) {
        logger("GATT close rejected: ${securityException.message}")
    }
}
