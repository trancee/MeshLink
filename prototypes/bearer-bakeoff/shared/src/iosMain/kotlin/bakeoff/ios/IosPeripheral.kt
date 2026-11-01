package bakeoff.ios

import bakeoff.BakeoffUuids
import bakeoff.GattChunkLink
import bakeoff.PeerIdentity
import bakeoff.PeripheralDriver
import bakeoff.PeripheralListener
import bakeoff.ResultSink
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import platform.CoreBluetooth.CBATTErrorInvalidOffset
import platform.CoreBluetooth.CBATTErrorSuccess
import platform.CoreBluetooth.CBATTRequest
import platform.CoreBluetooth.CBAdvertisementDataServiceUUIDsKey
import platform.CoreBluetooth.CBAttributePermissionsReadable
import platform.CoreBluetooth.CBAttributePermissionsWriteable
import platform.CoreBluetooth.CBCentral
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBCharacteristicPropertyNotify
import platform.CoreBluetooth.CBCharacteristicPropertyRead
import platform.CoreBluetooth.CBCharacteristicPropertyWriteWithoutResponse
import platform.CoreBluetooth.CBL2CAPChannel
import platform.CoreBluetooth.CBL2CAPPSM
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBMutableCharacteristic
import platform.CoreBluetooth.CBMutableService
import platform.CoreBluetooth.CBPeripheralManager
import platform.CoreBluetooth.CBPeripheralManagerDelegateProtocol
import platform.CoreBluetooth.CBService
import platform.Foundation.NSError
import platform.darwin.NSObject

/** CoreBluetooth peripheral: GATT service + published L2CAP channel, advertising only the 128-bit service UUID. */
class IosPeripheral(private val sink: ResultSink) : PeripheralDriver {
    private var poweredOn = CompletableDeferred<Unit>()
    private var serviceAdded = CompletableDeferred<Unit>()
    private var advertising = CompletableDeferred<Unit>()
    private var published = CompletableDeferred<Int>()
    private var readyToUpdate: CompletableDeferred<Unit>? = null
    private val notifyMutex = Mutex()
    private val links = mutableMapOf<String, ServerLink>()
    private val channels = mutableListOf<StreamByteLink>()
    private lateinit var listener: PeripheralListener
    private var control = ByteArray(0)
    private var psmValue = ByteArray(2)
    private var publishedPsm: Int? = null
    private var manager: CBPeripheralManager? = null

    private val notifyCharacteristic = CBMutableCharacteristic(
        type = cbuuid(BakeoffUuids.DATA_NOTIFY),
        properties = CBCharacteristicPropertyNotify,
        value = null,
        permissions = CBAttributePermissionsReadable,
    )

    private inner class ServerLink(val central: CBCentral) : GattChunkLink("gatt-peripheral:${central.identifier.UUIDString}") {
        override fun maxValueSize(): Int = minOf(central.maximumUpdateValueLength.toInt(), 512)
        override suspend fun sendValue(value: ByteArray) = notify(central, value)
        override fun close(reason: String) = closeInbound(reason)
    }

    private val delegate = object : NSObject(), CBPeripheralManagerDelegateProtocol {
        override fun peripheralManagerDidUpdateState(peripheral: CBPeripheralManager) {
            sink.log("peripheral state ${peripheral.state}")
            if (peripheral.state == CBManagerStatePoweredOn) poweredOn.complete(Unit)
        }

        override fun peripheralManager(peripheral: CBPeripheralManager, didAddService: CBService, error: NSError?) {
            if (error != null) serviceAdded.completeExceptionally(IllegalStateException(error.describe())) else serviceAdded.complete(Unit)
        }

        override fun peripheralManagerDidStartAdvertising(peripheral: CBPeripheralManager, error: NSError?) {
            if (error != null) advertising.completeExceptionally(IllegalStateException(error.describe())) else advertising.complete(Unit)
        }

        @ObjCSignatureOverride
        override fun peripheralManager(peripheral: CBPeripheralManager, didPublishL2CAPChannel: CBL2CAPPSM, error: NSError?) {
            if (error != null) published.completeExceptionally(IllegalStateException(error.describe())) else published.complete(didPublishL2CAPChannel.toInt())
        }

        override fun peripheralManager(peripheral: CBPeripheralManager, didOpenL2CAPChannel: CBL2CAPChannel?, error: NSError?) {
            if (didOpenL2CAPChannel == null || error != null) {
                listener.onEvent("l2cap_open_failed", null, error?.describe() ?: "no channel")
                return
            }
            val peer = didOpenL2CAPChannel.peer?.identifier?.UUIDString ?: "unknown"
            val link = StreamByteLink(didOpenL2CAPChannel, "l2cap-peripheral:$peer")
            channels += link
            listener.onL2capLink(peer, link)
        }

        @ObjCSignatureOverride
        override fun peripheralManager(peripheral: CBPeripheralManager, central: CBCentral, didSubscribeToCharacteristic: CBCharacteristic) {
            val address = central.identifier.UUIDString
            listener.onEvent("subscribed", address, "max_update=${central.maximumUpdateValueLength}")
            val link = ServerLink(central)
            links.put(address, link)?.close("replaced")
            listener.onGattLink(address, link)
        }

        @ObjCSignatureOverride
        override fun peripheralManager(peripheral: CBPeripheralManager, central: CBCentral, didUnsubscribeFromCharacteristic: CBCharacteristic) {
            val address = central.identifier.UUIDString
            listener.onEvent("unsubscribed", address, "")
            links.remove(address)?.close("unsubscribed")
        }

        override fun peripheralManager(peripheral: CBPeripheralManager, didReceiveReadRequest: CBATTRequest) {
            val request = didReceiveReadRequest
            val full = when {
                request.characteristic.UUID.matches(BakeoffUuids.CONTROL) -> control
                request.characteristic.UUID.matches(BakeoffUuids.PSM) -> psmValue
                else -> ByteArray(0)
            }
            val offset = request.offset.toInt()
            if (offset == 0 && request.characteristic.UUID.matches(BakeoffUuids.CONTROL)) reopenIfStale(request.central)
            if (offset > full.size) {
                peripheral.respondToRequest(request, withResult = CBATTErrorInvalidOffset)
                return
            }
            request.value = full.copyOfRange(offset, full.size).toNSData()
            peripheral.respondToRequest(request, withResult = CBATTErrorSuccess)
        }

        /**
         * iOS keeps the CCCD state of a reconnecting unbonded central until its old link times out, so no new
         * didSubscribe arrives; the control read that starts every connection reopens the GATT link instead.
         */
        private fun reopenIfStale(central: CBCentral) {
            val address = central.identifier.UUIDString
            val existing = links[address]
            if (existing != null && !existing.isClosed) return
            listener.onEvent("reopened_on_control_read", address, if (existing == null) "no_link" else "closed_link")
            val link = ServerLink(central)
            links[address] = link
            listener.onGattLink(address, link)
        }

        override fun peripheralManager(peripheral: CBPeripheralManager, didReceiveWriteRequests: List<*>) {
            for (request in didReceiveWriteRequests.filterIsInstance<CBATTRequest>()) {
                val address = request.central.identifier.UUIDString
                val link = links[address]
                if (link == null) {
                    listener.onEvent("write_without_link", address, "")
                    continue
                }
                link.onValueReceived(request.value?.toByteArray() ?: ByteArray(0))
            }
        }

        override fun peripheralManagerIsReadyToUpdateSubscribers(peripheral: CBPeripheralManager) {
            readyToUpdate?.complete(Unit)
        }
    }

    private suspend fun notify(central: CBCentral, value: ByteArray) = notifyMutex.withLock {
        val currentManager = manager ?: throw IllegalStateException("peripheral stopped")
        val data = value.toNSData()
        withTimeout(10_000) {
            while (!currentManager.updateValue(data, forCharacteristic = notifyCharacteristic, onSubscribedCentrals = listOf(central))) {
                val waiter = CompletableDeferred<Unit>()
                readyToUpdate = waiter
                withTimeoutOrNull(200) { waiter.await() }
                readyToUpdate = null
            }
        }
    }

    override suspend fun start(identity: PeerIdentity, secureL2cap: Boolean, listener: PeripheralListener): Int? {
        this.listener = listener
        control = identity.encodeControl()
        val currentManager = CBPeripheralManager(delegate = delegate, queue = null)
        manager = currentManager
        withTimeoutOrNull(60_000) { poweredOn.await() }
            ?: throw IllegalStateException("peripheral not powered on (state ${currentManager.state}; Bluetooth permission?)")
        currentManager.publishL2CAPChannelWithEncryption(secureL2cap)
        publishedPsm = runCatching { withTimeout(5_000) { published.await() } }
            .onFailure { listener.onEvent("l2cap_publish_failed", null, it.message ?: "") }
            .getOrNull()
        publishedPsm?.let { psmValue = byteArrayOf((it shr 8).toByte(), it.toByte()) }
        val service = CBMutableService(type = cbuuid(BakeoffUuids.SERVICE), primary = true)
        service.setCharacteristics(
            listOf(
                CBMutableCharacteristic(cbuuid(BakeoffUuids.CONTROL), CBCharacteristicPropertyRead, null, CBAttributePermissionsReadable),
                notifyCharacteristic,
                CBMutableCharacteristic(cbuuid(BakeoffUuids.DATA_WRITE), CBCharacteristicPropertyWriteWithoutResponse, null, CBAttributePermissionsWriteable),
                CBMutableCharacteristic(cbuuid(BakeoffUuids.PSM), CBCharacteristicPropertyRead, null, CBAttributePermissionsReadable),
            ),
        )
        currentManager.addService(service)
        withTimeout(10_000) { serviceAdded.await() }
        currentManager.startAdvertising(mapOf<Any?, Any?>(CBAdvertisementDataServiceUUIDsKey to listOf(cbuuid(BakeoffUuids.SERVICE))))
        withTimeout(10_000) { advertising.await() }
        return publishedPsm
    }

    override fun stop() {
        val currentManager = manager ?: return
        currentManager.stopAdvertising()
        publishedPsm?.let { currentManager.unpublishL2CAPChannel(it.toUShort()) }
        currentManager.removeAllServices()
        links.values.forEach { it.close("stopped") }
        links.clear()
        channels.forEach { it.close("stopped") }
        channels.clear()
        manager = null
    }
}
