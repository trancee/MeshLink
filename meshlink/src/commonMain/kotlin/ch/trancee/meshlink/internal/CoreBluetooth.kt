package ch.trancee.meshlink.internal

/**
 * The CoreBluetooth calls MeshLink makes on iOS, as a thin layer in `iosArm64Main` implements them:
 * each method calls one `CBCentralManager`, `CBPeripheral` or `CBPeripheralManager` method and does
 * nothing else (ADR 0017 decision 6; Constitution T3). UUIDs are their `CBUUID.data` bytes, and
 * values are copies of the `NSData` bytes. [AppleRadio] holds the sequencing and state, so tests
 * drive it with fakes on every target.
 *
 * Every call happens on the one serial queue that CoreBluetooth delivers the
 * [CoreBluetoothDelegates] callbacks on.
 */
internal interface CoreBluetooth :
    CentralManagerCalls,
    PeripheralCalls,
    PeripheralManagerCalls {
    /** `CBManager.authorization`, as its raw value; see [CbAuthorization]. */
    val authorization: Long

    /** Creates the central and peripheral managers with [delegates]. Called once. */
    fun open(delegates: CoreBluetoothDelegates)
}

/** The `CBCentralManager` calls. */
internal interface CentralManagerCalls {
    /** Scans for advertisements that carry [service], reporting each one, duplicates included. */
    fun scan(service: ByteArray)

    /** Stops scanning. */
    fun stopScan()

    /** Connects to [peripheral]. */
    fun connect(peripheral: CbPeripheral)

    /** Cancels the connection to [peripheral], or the attempt to make it. */
    fun cancel(peripheral: CbPeripheral)
}

/** The `CBPeripheral` calls, on a remote peripheral this node connects to. */
internal interface PeripheralCalls {
    /** Discovers [service] on [peripheral]. */
    fun discoverServices(
        peripheral: CbPeripheral,
        service: ByteArray,
    )

    /** Discovers [characteristics] of [service] on [peripheral]. */
    fun discoverCharacteristics(
        peripheral: CbPeripheral,
        service: ByteArray,
        characteristics: List<ByteArray>,
    )

    /** Reads the discovered [characteristic] of [peripheral]. */
    fun read(
        peripheral: CbPeripheral,
        characteristic: ByteArray,
    )

    /** Enables notifications on the discovered [characteristic] of [peripheral]. */
    fun subscribe(
        peripheral: CbPeripheral,
        characteristic: ByteArray,
    )

    /** Writes [value] without response to the discovered [characteristic] of [peripheral]. */
    fun write(
        peripheral: CbPeripheral,
        characteristic: ByteArray,
        value: ByteArray,
    )

    /** `CBPeripheral.canSendWriteWithoutResponse` of [peripheral]. */
    fun canWrite(peripheral: CbPeripheral): Boolean

    /** `CBPeripheral.maximumWriteValueLength(for: .withoutResponse)` of [peripheral]. */
    fun maximumWriteLength(peripheral: CbPeripheral): Int

    /** `CBPeripheral.readRSSI()` of the connected [peripheral]. */
    fun readRssi(peripheral: CbPeripheral)

    /** Opens an L2CAP channel to [psm] on [peripheral]. */
    fun openChannel(
        peripheral: CbPeripheral,
        psm: Int,
    )
}

/** The `CBPeripheralManager` calls. */
internal interface PeripheralManagerCalls {
    /** Advertises [services] as `CBAdvertisementDataServiceUUIDsKey` and nothing else. */
    fun advertise(services: List<ByteArray>)

    /** Stops advertising. */
    fun stopAdvertising()

    /** Publishes [service] as a primary service; see [GattService]. */
    fun addService(service: GattService)

    /** Answers [request] with [value], or with `CBATTError.invalidOffset` when [value] is null. */
    fun respond(
        request: CbReadRequest,
        value: ByteArray?,
    )

    /**
     * Notifies [value] on the published peripheral-to-central characteristic to [central]; false
     * when the queue is full.
     */
    fun notify(
        central: CbCentral,
        value: ByteArray,
    ): Boolean

    /** `CBCentral.maximumUpdateValueLength` of [central]. */
    fun maximumNotifyLength(central: CbCentral): Int

    /** Publishes an L2CAP channel without encryption (library rule R2). */
    fun publishChannel()

    /** Unpublishes the L2CAP channel on [psm]. */
    fun unpublishChannel(psm: Int)
}

/**
 * Where the thin layer passes CoreBluetooth's callbacks on, one sink per delegate, each callback as
 * it arrives. An `error` becomes `ok = false` or a null value.
 */
internal class CoreBluetoothDelegates(
    val central: CentralManagerEvents,
    val peripheral: PeripheralEvents,
    val peripheralManager: PeripheralManagerEvents,
    val channels: ChannelListenerEvents,
)

/** What the `CBCentralManagerDelegate` reports. */
internal interface CentralManagerEvents {
    /** `centralManagerDidUpdateState`, with the raw `CBManagerState`; see [CbState]. */
    fun centralState(state: Long)

    /** `didDiscover`: an advertisement with the `CBAdvertisementDataServiceUUIDsKey` [services]. */
    fun discovered(
        peripheral: CbPeripheral,
        services: List<ByteArray>,
        rssi: Int,
    )

    /** `didConnect`. */
    fun connected(peripheral: CbPeripheral)

    /** `didFailToConnect`. */
    fun connectFailed(peripheral: CbPeripheral)

    /** `didDisconnectPeripheral`. */
    fun disconnected(peripheral: CbPeripheral)
}

/** What the `CBPeripheralDelegate` of a remote peripheral reports. */
internal interface PeripheralEvents {
    /** `didDiscoverServices`: the UUIDs of `peripheral.services`, or null on an error. */
    fun servicesDiscovered(
        peripheral: CbPeripheral,
        services: List<ByteArray>?,
    )

    /** `didDiscoverCharacteristicsFor`: the UUIDs of the service's characteristics, or null on an error. */
    fun characteristicsDiscovered(
        peripheral: CbPeripheral,
        characteristics: List<ByteArray>?,
    )

    /** `didUpdateValueFor`: a read answer or a notification of [characteristic], or null on an error. */
    fun valueUpdated(
        peripheral: CbPeripheral,
        characteristic: ByteArray,
        value: ByteArray?,
    )

    /** `didUpdateNotificationStateFor`. */
    fun subscribeAnswered(
        peripheral: CbPeripheral,
        ok: Boolean,
    )

    /** `peripheralIsReady(toSendWriteWithoutResponse:)`. */
    fun readyToWrite(peripheral: CbPeripheral)

    /** `didReadRSSI`: the signal strength in dBm, or null on an error. */
    fun rssiRead(
        peripheral: CbPeripheral,
        rssi: Int?,
    )

    /** `didModifyServices`: the UUIDs of the invalidated services. */
    fun servicesInvalidated(
        peripheral: CbPeripheral,
        services: List<ByteArray>,
    )

    /** `didOpen` an L2CAP channel to [peripheral], or null on an error. */
    fun channelOpened(
        peripheral: CbPeripheral,
        channel: CbChannel?,
    )
}

/** What the `CBPeripheralManagerDelegate` reports. */
internal interface PeripheralManagerEvents {
    /** `peripheralManagerDidUpdateState`, with the raw `CBManagerState`; see [CbState]. */
    fun peripheralState(state: Long)

    /** `peripheralManagerDidStartAdvertising`. */
    fun advertisingStarted(ok: Boolean)

    /** `didAdd service`. */
    fun serviceAdded(ok: Boolean)

    /** `didReceiveRead`; answer it with [PeripheralManagerCalls.respond]. */
    fun readRequested(request: CbReadRequest)

    /** `didReceiveWrite`, once per request: [value] written to [characteristic] by [central]. */
    fun written(
        central: CbCentral,
        characteristic: ByteArray,
        value: ByteArray,
    )

    /** `didSubscribeTo`. */
    fun subscribed(
        central: CbCentral,
        characteristic: ByteArray,
    )

    /** `didUnsubscribeFrom`; CoreBluetooth reports it too when the central disconnects. */
    fun unsubscribed(
        central: CbCentral,
        characteristic: ByteArray,
    )

    /** `peripheralManagerIsReady(toUpdateSubscribers:)`. */
    fun readyToNotify()
}

/** What the `CBPeripheralManagerDelegate` reports about the published L2CAP channel. */
internal interface ChannelListenerEvents {
    /** `didPublishL2CAPChannel`: the PSM, or null on an error. */
    fun channelPublished(psm: Int?)

    /** `didOpen` an L2CAP channel from a central, or null on an error. */
    fun channelOpened(channel: CbChannel?)
}

/**
 * An open `CBL2CAPChannel`, which the thin layer keeps, with its input and output streams; [peerId]
 * is its peer's `identifier`. Closing it releases the channel, which closes it at both ends.
 */
internal interface CbChannel {
    val peerId: String

    /** `inputStream.hasBytesAvailable`. */
    val hasBytes: Boolean

    /** `outputStream.hasSpaceAvailable`. */
    val hasSpace: Boolean

    /** Opens both streams; their events reach [events] on the CoreBluetooth queue. */
    fun open(events: CbChannelEvents)

    /** `inputStream.read` into [buffer]: the count read, 0 at the end, or a negative count on an error. */
    fun read(buffer: ByteArray): Int

    /** `outputStream.write` of [bytes] from [offset]: the count written, or 0 or less if it failed. */
    fun write(
        bytes: ByteArray,
        offset: Int,
    ): Int

    /** Closes both streams and stops their events. */
    fun close()
}

/** The `NSStreamDelegate` events of a [CbChannel]'s streams. */
internal interface CbChannelEvents {
    /** `hasBytesAvailable` on the input stream. */
    fun readable()

    /** `hasSpaceAvailable` on the output stream. */
    fun writable()

    /** `endEncountered` or `errorOccurred` on either stream. */
    fun ended()
}

/** A remote peripheral, a `CBPeripheral`, which the thin layer keeps; [id] is its `identifier`. */
internal interface CbPeripheral {
    val id: String
}

/** A remote central, a `CBCentral`, which the thin layer keeps; [id] is its `identifier`. */
internal interface CbCentral {
    val id: String
}

/** A `CBATTRequest` to read [characteristic] at [offset] from [central]. */
internal interface CbReadRequest {
    val central: CbCentral
    val characteristic: ByteArray
    val offset: Int
}

/**
 * A primary service to publish: [capability] readable without encryption, [toPeripheral] written
 * without response, and [toCentral] notified (spec D-13).
 */
internal class GattService(
    val uuid: ByteArray,
    val capability: ByteArray,
    val toPeripheral: ByteArray,
    val toCentral: ByteArray,
)

/** The raw `CBManagerState` values (CoreBluetooth `CBManager.h`). */
internal object CbState {
    const val UNKNOWN: Long = 0
    const val RESETTING: Long = 1
    const val UNSUPPORTED: Long = 2
    const val UNAUTHORIZED: Long = 3
    const val POWERED_OFF: Long = 4
    const val POWERED_ON: Long = 5
}

/** The raw `CBManagerAuthorization` values (CoreBluetooth `CBManager.h`). */
internal object CbAuthorization {
    const val NOT_DETERMINED: Long = 0
    const val RESTRICTED: Long = 1
    const val DENIED: Long = 2
    const val ALLOWED_ALWAYS: Long = 3
}
