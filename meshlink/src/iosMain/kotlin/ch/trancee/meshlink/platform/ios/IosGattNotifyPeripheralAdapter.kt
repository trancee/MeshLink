package ch.trancee.meshlink.platform.ios

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreBluetooth.CBCentral
import platform.CoreBluetooth.CBMutableCharacteristic
import platform.CoreBluetooth.CBPeripheralManager
import platform.Foundation.NSData
import platform.Foundation.NSMutableData
import platform.Foundation.create
import platform.posix.memcpy

internal interface GattNotifyPeripheralAdapter {
    fun requestLowConnectionLatency(): Unit

    fun updateValue(chunk: ByteArray): Boolean
}

internal class CoreBluetoothGattNotifyPeripheralAdapter(
    private val peripheralManager: CBPeripheralManager,
    private val notifyCharacteristic: CBMutableCharacteristic,
    private val central: CBCentral,
) : GattNotifyPeripheralAdapter {
    override fun requestLowConnectionLatency(): Unit {
        peripheralManager.setDesiredConnectionLatency(
            latency = platform.CoreBluetooth.CBPeripheralManagerConnectionLatencyLow,
            forCentral = central,
        )
    }

    override fun updateValue(chunk: ByteArray): Boolean {
        return peripheralManager.updateValue(
            chunk.toNSData(),
            forCharacteristic = notifyCharacteristic,
            onSubscribedCentrals = listOf(central),
        )
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData {
    val data = if (isEmpty()) null else NSMutableData.create(length = size.toULong())
    if (data != null) {
        usePinned { pinned -> memcpy(data.mutableBytes, pinned.addressOf(0), size.toULong()) }
    }
    return data ?: NSData()
}
