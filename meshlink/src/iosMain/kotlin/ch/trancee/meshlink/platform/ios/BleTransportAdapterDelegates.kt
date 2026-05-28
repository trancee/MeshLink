@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package ch.trancee.meshlink.platform.ios

import kotlinx.cinterop.ObjCSignatureOverride
import platform.CoreBluetooth.CBAdvertisementDataServiceUUIDsKey
import platform.CoreBluetooth.CBCentral
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBL2CAPChannel
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralDelegateProtocol
import platform.CoreBluetooth.CBPeripheralManager
import platform.CoreBluetooth.CBPeripheralManagerDelegateProtocol
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.darwin.NSObject

internal class CentralDelegate(private val owner: BleTransportAdapter) :
    NSObject(), CBCentralManagerDelegateProtocol {
    override fun centralManagerDidUpdateState(central: CBCentralManager) {
        owner.reportLog("centralManagerDidUpdateState state=${central.state}")
        owner.startScanIfReady(central)
    }

    override fun centralManager(
        central: CBCentralManager,
        didDiscoverPeripheral: CBPeripheral,
        advertisementData: Map<Any?, *>,
        RSSI: NSNumber,
    ) {
        val rawServiceUuids = advertisementData[CBAdvertisementDataServiceUUIDsKey] as? List<*>
        val serviceUuids = rawServiceUuids?.filterIsInstance<CBUUID>() ?: return
        if (serviceUuids.size != rawServiceUuids.size) {
            return
        }
        owner.handleDiscoveredPeripheral(didDiscoverPeripheral, serviceUuids)
    }

    override fun centralManager(central: CBCentralManager, didConnectPeripheral: CBPeripheral) {
        owner.handleConnectedPeripheral(didConnectPeripheral)
    }

    @ObjCSignatureOverride
    override fun centralManager(
        central: CBCentralManager,
        didFailToConnectPeripheral: CBPeripheral,
        error: NSError?,
    ) {
        owner.handleFailedConnection(didFailToConnectPeripheral, error)
    }

    @ObjCSignatureOverride
    override fun centralManager(
        central: CBCentralManager,
        didDisconnectPeripheral: CBPeripheral,
        error: NSError?,
    ) {
        owner.handleDisconnectedPeripheral(didDisconnectPeripheral)
    }
}

internal class PeripheralClientDelegate(private val owner: BleTransportAdapter) :
    NSObject(), CBPeripheralDelegateProtocol {
    override fun peripheral(
        peripheral: CBPeripheral,
        didOpenL2CAPChannel: CBL2CAPChannel?,
        error: NSError?,
    ) {
        owner.handleOpenedOutgoingChannel(peripheral, didOpenL2CAPChannel, error)
    }
}

internal class PeripheralManagerDelegate(private val owner: BleTransportAdapter) :
    NSObject(), CBPeripheralManagerDelegateProtocol {
    override fun peripheralManagerDidUpdateState(peripheral: CBPeripheralManager) {
        owner.reportLog("peripheralManagerDidUpdateState state=${peripheral.state}")
        owner.installGattNotifyServiceIfReady(peripheral)
        owner.publishL2capChannelIfReady(peripheral)
    }

    @ObjCSignatureOverride
    override fun peripheralManager(
        peripheral: CBPeripheralManager,
        central: CBCentral,
        didSubscribeToCharacteristic: CBCharacteristic,
    ) {
        owner.handleGattNotifySubscribed(central, didSubscribeToCharacteristic)
    }

    @ObjCSignatureOverride
    override fun peripheralManager(
        peripheral: CBPeripheralManager,
        central: CBCentral,
        didUnsubscribeFromCharacteristic: CBCharacteristic,
    ) {
        owner.handleGattNotifyUnsubscribed(central, didUnsubscribeFromCharacteristic)
    }

    override fun peripheralManagerIsReadyToUpdateSubscribers(peripheral: CBPeripheralManager) {
        owner.pumpGattNotifyLinks()
    }

    override fun peripheralManager(
        peripheral: CBPeripheralManager,
        didReceiveWriteRequests: List<*>,
    ) {
        owner.handleGattWriteRequests(didReceiveWriteRequests)
    }

    override fun peripheralManager(
        peripheral: CBPeripheralManager,
        didPublishL2CAPChannel: UShort,
        error: NSError?,
    ) {
        owner.handlePublishedL2capChannel(didPublishL2CAPChannel, error)
    }

    override fun peripheralManager(
        peripheral: CBPeripheralManager,
        didOpenL2CAPChannel: CBL2CAPChannel?,
        error: NSError?,
    ) {
        owner.handleOpenedIncomingChannel(didOpenL2CAPChannel, error)
    }
}
