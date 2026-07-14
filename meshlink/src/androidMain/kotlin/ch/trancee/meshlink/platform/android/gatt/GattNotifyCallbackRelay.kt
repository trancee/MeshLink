package ch.trancee.meshlink.platform.android.gatt

internal class GattNotifyCallbackRelay(private val listener: GattNotifySessionListener) {
    fun onConnectionStateChange(address: String, status: Int, newState: Int): Unit {
        listener.onConnectionStateChange(address = address, status = status, newState = newState)
    }

    fun onMtuChanged(mtu: Int, status: Int): Unit {
        listener.onMtuChanged(mtu = mtu, status = status)
    }

    fun onPhyUpdate(txPhy: Int, rxPhy: Int, status: Int): Unit {
        listener.onPhyUpdate(txPhy = txPhy, rxPhy = rxPhy, status = status)
    }

    fun onServicesDiscovered(status: Int): Unit {
        listener.onServicesDiscovered(status = status)
    }

    fun onDescriptorWrite(descriptorUuid: String, status: Int): Unit {
        listener.onDescriptorWrite(descriptorUuid = descriptorUuid, status = status)
    }

    fun onCharacteristicChanged(characteristicUuid: String, value: ByteArray?): Unit {
        if (value == null) {
            return
        }
        listener.onCharacteristicChanged(
            characteristicUuid = characteristicUuid,
            value = value.copyOf(),
        )
    }

    fun onCharacteristicWrite(characteristicUuid: String, status: Int): Unit {
        listener.onCharacteristicWrite(characteristicUuid = characteristicUuid, status = status)
    }
}
