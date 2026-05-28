package ch.trancee.meshlink.platform.ios

import platform.CoreBluetooth.CBCentral
import platform.CoreBluetooth.CBMutableCharacteristic
import platform.CoreBluetooth.CBPeripheralManager

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
