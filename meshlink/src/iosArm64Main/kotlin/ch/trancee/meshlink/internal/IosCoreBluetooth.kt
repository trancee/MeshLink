@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package ch.trancee.meshlink.internal

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreBluetooth.CBATTErrorInvalidOffset
import platform.CoreBluetooth.CBATTErrorSuccess
import platform.CoreBluetooth.CBATTRequest
import platform.CoreBluetooth.CBAdvertisementDataServiceUUIDsKey
import platform.CoreBluetooth.CBAttributePermissionsReadable
import platform.CoreBluetooth.CBAttributePermissionsWriteable
import platform.CoreBluetooth.CBCentral
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCentralManagerOptionShowPowerAlertKey
import platform.CoreBluetooth.CBCentralManagerScanOptionAllowDuplicatesKey
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBCharacteristicPropertyNotify
import platform.CoreBluetooth.CBCharacteristicPropertyRead
import platform.CoreBluetooth.CBCharacteristicPropertyWriteWithoutResponse
import platform.CoreBluetooth.CBCharacteristicWriteWithoutResponse
import platform.CoreBluetooth.CBManager
import platform.CoreBluetooth.CBMutableCharacteristic
import platform.CoreBluetooth.CBMutableService
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralDelegateProtocol
import platform.CoreBluetooth.CBPeripheralManager
import platform.CoreBluetooth.CBPeripheralManagerDelegateProtocol
import platform.CoreBluetooth.CBPeripheralManagerOptionShowPowerAlertKey
import platform.CoreBluetooth.CBService
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.Foundation.create
import platform.darwin.NSObject
import platform.darwin.dispatch_queue_t
import platform.posix.memcpy

/**
 * The thin layer over CoreBluetooth (Constitution T3, `config/thin-adapters.txt`): each call calls
 * one CoreBluetooth method, and each delegate callback passes its arguments on to the
 * [CoreBluetoothDelegates]. Both managers run on [queue] and never show the power alert; there is
 * no state restoration (ticket 32). Device runs prove it (ticket 17).
 */
internal class IosCoreBluetooth private constructor(
    private val queue: dispatch_queue_t,
    private val managers: Managers,
) : CoreBluetooth,
    CentralManagerCalls by IosCentralManagerCalls(managers),
    PeripheralCalls by IosPeripheralCalls(managers),
    PeripheralManagerCalls by IosPeripheralManagerCalls(managers) {
    constructor(queue: dispatch_queue_t) : this(queue, Managers())

    override val authorization: Long get() = CBManager.authorization

    override fun open(delegates: CoreBluetoothDelegates) {
        val central = CentralDelegate(delegates.central)
        val peripheralManager = PeripheralManagerDelegate(delegates.peripheralManager)
        managers.delegates = listOf(central, peripheralManager)
        managers.events = delegates
        managers.peripheralDelegate = PeripheralDelegate(delegates.peripheral)
        managers.central =
            CBCentralManager(central, queue, mapOf<Any?, Any>(CBCentralManagerOptionShowPowerAlertKey to false))
        managers.peripheralManager =
            CBPeripheralManager(
                peripheralManager,
                queue,
                mapOf<Any?, Any>(CBPeripheralManagerOptionShowPowerAlertKey to false),
            )
    }
}

/**
 * The managers, and the delegates they and the peripherals hold weakly, which this keeps alive;
 * [IosCoreBluetooth.open] sets them.
 */
private class Managers {
    lateinit var central: CBCentralManager
    lateinit var peripheralManager: CBPeripheralManager
    lateinit var events: CoreBluetoothDelegates
    lateinit var peripheralDelegate: PeripheralDelegate
    lateinit var delegates: List<NSObject>

    /** The published peripheral-to-central characteristic, once [IosPeripheralManagerCalls.addService] built it. */
    var toCentral: CBMutableCharacteristic? = null
}

private class IosCentralManagerCalls(
    private val managers: Managers,
) : CentralManagerCalls {
    override fun scan(service: ByteArray) =
        managers.central.scanForPeripheralsWithServices(
            listOf(service.uuid()),
            mapOf<Any?, Any>(CBCentralManagerScanOptionAllowDuplicatesKey to true),
        )

    override fun stopScan() = managers.central.stopScan()

    override fun connect(peripheral: CbPeripheral) {
        val remote = peripheral.remote
        remote.delegate = managers.peripheralDelegate
        managers.central.connectPeripheral(remote, null)
    }

    override fun cancel(peripheral: CbPeripheral) = managers.central.cancelPeripheralConnection(peripheral.remote)
}

private class IosPeripheralCalls(
    private val managers: Managers,
) : PeripheralCalls {
    override fun discoverServices(
        peripheral: CbPeripheral,
        service: ByteArray,
    ) = peripheral.remote.discoverServices(listOf(service.uuid()))

    override fun discoverCharacteristics(
        peripheral: CbPeripheral,
        service: ByteArray,
        characteristics: List<ByteArray>,
    ) {
        val remote = peripheral.remote
        val found = remote.services?.firstOrNull { (it as CBService).UUID == service.uuid() } as CBService?
        if (found != null) {
            remote.discoverCharacteristics(characteristics.map { it.uuid() }, found)
        } else {
            managers.events.peripheral.characteristicsDiscovered(peripheral, null)
        }
    }

    override fun read(
        peripheral: CbPeripheral,
        characteristic: ByteArray,
    ) {
        val found = (peripheral as IosPeripheral).characteristic(characteristic)
        if (found != null) {
            peripheral.peripheral.readValueForCharacteristic(found)
        } else {
            managers.events.peripheral.valueUpdated(peripheral, characteristic, null)
        }
    }

    override fun subscribe(
        peripheral: CbPeripheral,
        characteristic: ByteArray,
    ) {
        val found = (peripheral as IosPeripheral).characteristic(characteristic)
        if (found != null) {
            peripheral.peripheral.setNotifyValue(true, found)
        } else {
            managers.events.peripheral.subscribeAnswered(peripheral, false)
        }
    }

    override fun write(
        peripheral: CbPeripheral,
        characteristic: ByteArray,
        value: ByteArray,
    ) {
        val remote = peripheral as IosPeripheral
        remote.characteristic(characteristic)?.let {
            remote.peripheral.writeValue(value.data(), it, CBCharacteristicWriteWithoutResponse)
        }
    }

    override fun canWrite(peripheral: CbPeripheral): Boolean = peripheral.remote.canSendWriteWithoutResponse

    override fun maximumWriteLength(peripheral: CbPeripheral): Int =
        (peripheral as IosPeripheral)
            .peripheral
            .maximumWriteValueLengthForType(CBCharacteristicWriteWithoutResponse)
            .toInt()
}

private class IosPeripheralManagerCalls(
    private val managers: Managers,
) : PeripheralManagerCalls {
    override fun advertise(services: List<ByteArray>) =
        managers.peripheralManager.startAdvertising(
            mapOf<Any?, Any>(CBAdvertisementDataServiceUUIDsKey to services.map { it.uuid() }),
        )

    override fun stopAdvertising() = managers.peripheralManager.stopAdvertising()

    override fun addService(service: GattService) {
        val notify =
            CBMutableCharacteristic(
                service.toCentral.uuid(),
                CBCharacteristicPropertyNotify,
                null,
                CBAttributePermissionsReadable,
            )
        managers.toCentral = notify
        val published = CBMutableService(service.uuid.uuid(), true)
        published.setCharacteristics(
            listOf(
                CBMutableCharacteristic(
                    service.capability.uuid(),
                    CBCharacteristicPropertyRead,
                    null,
                    CBAttributePermissionsReadable,
                ),
                CBMutableCharacteristic(
                    service.toPeripheral.uuid(),
                    CBCharacteristicPropertyWriteWithoutResponse,
                    null,
                    CBAttributePermissionsWriteable,
                ),
                notify,
            ),
        )
        managers.peripheralManager.addService(published)
    }

    override fun respond(
        request: CbReadRequest,
        value: ByteArray?,
    ) {
        val att = (request as IosReadRequest).request
        att.setValue(value?.data())
        managers.peripheralManager.respondToRequest(
            att,
            if (value != null) CBATTErrorSuccess else CBATTErrorInvalidOffset,
        )
    }

    override fun notify(
        central: CbCentral,
        value: ByteArray,
    ): Boolean =
        managers.toCentral?.let {
            managers.peripheralManager.updateValue(value.data(), it, listOf(central.remote))
        } ?: false

    override fun maximumNotifyLength(central: CbCentral): Int = central.remote.maximumUpdateValueLength.toInt()
}

private class CentralDelegate(
    private val events: CentralManagerEvents,
) : NSObject(),
    CBCentralManagerDelegateProtocol {
    override fun centralManagerDidUpdateState(central: CBCentralManager) = events.centralState(central.state)

    override fun centralManager(
        central: CBCentralManager,
        didDiscoverPeripheral: CBPeripheral,
        advertisementData: Map<Any?, *>,
        RSSI: NSNumber,
    ) {
        val services = (advertisementData[CBAdvertisementDataServiceUUIDsKey] as List<*>?).orEmpty()
        events.discovered(
            IosPeripheral(didDiscoverPeripheral),
            services.map { (it as CBUUID).toByteArray() },
            RSSI.intValue,
        )
    }

    override fun centralManager(
        central: CBCentralManager,
        didConnectPeripheral: CBPeripheral,
    ) = events.connected(IosPeripheral(didConnectPeripheral))

    @ObjCSignatureOverride
    override fun centralManager(
        central: CBCentralManager,
        didFailToConnectPeripheral: CBPeripheral,
        error: NSError?,
    ) = events.connectFailed(IosPeripheral(didFailToConnectPeripheral))

    @ObjCSignatureOverride
    override fun centralManager(
        central: CBCentralManager,
        didDisconnectPeripheral: CBPeripheral,
        error: NSError?,
    ) = events.disconnected(IosPeripheral(didDisconnectPeripheral))
}

private class PeripheralDelegate(
    private val events: PeripheralEvents,
) : NSObject(),
    CBPeripheralDelegateProtocol {
    override fun peripheral(
        peripheral: CBPeripheral,
        didDiscoverServices: NSError?,
    ) = events.servicesDiscovered(
        IosPeripheral(peripheral),
        if (didDiscoverServices == null) {
            peripheral.services.orEmpty().map { (it as CBService).UUID.toByteArray() }
        } else {
            null
        },
    )

    @ObjCSignatureOverride
    override fun peripheral(
        peripheral: CBPeripheral,
        didDiscoverCharacteristicsForService: CBService,
        error: NSError?,
    ) = events.characteristicsDiscovered(
        IosPeripheral(peripheral),
        if (error == null) {
            didDiscoverCharacteristicsForService.characteristics.orEmpty().map {
                (it as CBCharacteristic).UUID.toByteArray()
            }
        } else {
            null
        },
    )

    @ObjCSignatureOverride
    override fun peripheral(
        peripheral: CBPeripheral,
        didUpdateValueForCharacteristic: CBCharacteristic,
        error: NSError?,
    ) = events.valueUpdated(
        IosPeripheral(peripheral),
        didUpdateValueForCharacteristic.UUID.toByteArray(),
        if (error == null) didUpdateValueForCharacteristic.value?.toByteArray() ?: ByteArray(0) else null,
    )

    @ObjCSignatureOverride
    override fun peripheral(
        peripheral: CBPeripheral,
        didUpdateNotificationStateForCharacteristic: CBCharacteristic,
        error: NSError?,
    ) = events.subscribeAnswered(IosPeripheral(peripheral), error == null)

    override fun peripheralIsReadyToSendWriteWithoutResponse(peripheral: CBPeripheral) {
        events.readyToWrite(IosPeripheral(peripheral))
    }

    override fun peripheral(
        peripheral: CBPeripheral,
        didModifyServices: List<*>,
    ) = events.servicesInvalidated(
        IosPeripheral(peripheral),
        didModifyServices.map { (it as CBService).UUID.toByteArray() },
    )
}

private class PeripheralManagerDelegate(
    private val events: PeripheralManagerEvents,
) : NSObject(),
    CBPeripheralManagerDelegateProtocol {
    override fun peripheralManagerDidUpdateState(peripheral: CBPeripheralManager) {
        events.peripheralState(peripheral.state)
    }

    override fun peripheralManagerDidStartAdvertising(
        peripheral: CBPeripheralManager,
        error: NSError?,
    ) = events.advertisingStarted(error == null)

    override fun peripheralManager(
        peripheral: CBPeripheralManager,
        didAddService: CBService,
        error: NSError?,
    ) = events.serviceAdded(error == null)

    override fun peripheralManager(
        peripheral: CBPeripheralManager,
        didReceiveReadRequest: CBATTRequest,
    ) = events.readRequested(IosReadRequest(didReceiveReadRequest))

    override fun peripheralManager(
        peripheral: CBPeripheralManager,
        didReceiveWriteRequests: List<*>,
    ) = didReceiveWriteRequests.forEach {
        val request = it as CBATTRequest
        events.written(
            IosCentral(request.central),
            request.characteristic.UUID.toByteArray(),
            request.value?.toByteArray() ?: ByteArray(0),
        )
    }

    @ObjCSignatureOverride
    override fun peripheralManager(
        peripheral: CBPeripheralManager,
        central: CBCentral,
        didSubscribeToCharacteristic: CBCharacteristic,
    ) = events.subscribed(IosCentral(central), didSubscribeToCharacteristic.UUID.toByteArray())

    @ObjCSignatureOverride
    override fun peripheralManager(
        peripheral: CBPeripheralManager,
        central: CBCentral,
        didUnsubscribeFromCharacteristic: CBCharacteristic,
    ) = events.unsubscribed(IosCentral(central), didUnsubscribeFromCharacteristic.UUID.toByteArray())

    override fun peripheralManagerIsReadyToUpdateSubscribers(peripheral: CBPeripheralManager) = events.readyToNotify()
}

private class IosPeripheral(
    val peripheral: CBPeripheral,
) : CbPeripheral {
    override val id: String = peripheral.identifier.UUIDString

    /** The discovered characteristic [uuid] of the peripheral's services. */
    fun characteristic(uuid: ByteArray): CBCharacteristic? {
        val wanted = uuid.uuid()
        return peripheral.services.orEmpty().flatMap { (it as CBService).characteristics.orEmpty() }.firstOrNull {
            (it as CBCharacteristic).UUID == wanted
        } as CBCharacteristic?
    }
}

private class IosCentral(
    val central: CBCentral,
) : CbCentral {
    override val id: String = central.identifier.UUIDString
}

private class IosReadRequest(
    val request: CBATTRequest,
) : CbReadRequest {
    override val central: CbCentral = IosCentral(request.central)
    override val characteristic: ByteArray = request.characteristic.UUID.toByteArray()
    override val offset: Int = request.offset.toInt()
}

private val CbPeripheral.remote: CBPeripheral get() = (this as IosPeripheral).peripheral

private val CbCentral.remote: CBCentral get() = (this as IosCentral).central

private fun ByteArray.uuid(): CBUUID = CBUUID.UUIDWithData(data())

private fun CBUUID.toByteArray(): ByteArray = data.toByteArray()

private fun ByteArray.data(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }

private fun NSData.toByteArray(): ByteArray =
    ByteArray(length.toInt()).also { copy ->
        if (copy.isNotEmpty()) copy.usePinned { memcpy(it.addressOf(0), bytes, length) }
    }
