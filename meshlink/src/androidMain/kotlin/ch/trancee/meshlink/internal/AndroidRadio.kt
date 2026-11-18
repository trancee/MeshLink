package ch.trancee.meshlink.internal

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * The Android Bluetooth LE radio (ADR 0010): advertises and scans with [BleAdvertiser] and
 * [BleScanner], serves the MeshLink GATT service with [GattServerHost] and connects to peripherals
 * as a [GattCentralLink]. On Android 10 and later a link may switch to an L2CAP CoC channel:
 * [CocListener] accepts them as peripheral, [CocChannels] opens them as central, and blocking socket
 * calls run on [io]. Platform callbacks arrive on binder threads; [events] carries them to the one
 * collector in order.
 */
internal class AndroidRadio(
    context: Context,
    manager: BluetoothManager,
    adapter: BluetoothAdapter,
    scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : RadioPort {
    private val app = context.applicationContext
    private val inbox = Channel<RadioEvent>(Channel.UNLIMITED)
    private val advertiser = BleAdvertiser(adapter, ::emit)
    private val scanner = BleScanner(adapter, ::emit)
    private val server = GattServerHost(app, manager, scope, ::emit)
    private val listener = CocListener(adapter, scope, io, ::emit)

    // Ticket 31 maps the platform's cap, if any; none until then.
    override val peripheralLinkCap: Int? = null

    override val events: Flow<RadioEvent> = inbox.receiveAsFlow()

    override fun startAdvertising(payload: ByteArray): RadioResult<Unit> = advertiser.start(payload)

    override fun stopAdvertising() = advertiser.stop()

    override fun startScanning(): RadioResult<Unit> = scanner.start()

    override fun stopScanning() = scanner.stop()

    override fun serveCapability(record: ByteArray): RadioResult<Unit> = server.serve(record)

    override suspend fun listenForChannels(): RadioResult<Int> = listener.listen()

    override fun stopListening() = listener.stop()

    override suspend fun connect(peer: PeerHandle): RadioResult<CentralLink> =
        when (peer) {
            is AndroidPeer -> GattCentralLink(app, peer.device, io, ::emit).open()
            else -> RadioResult.Failed(RadioFailure.INVALID_ARGUMENT)
        }

    /**
     * Bluetooth turned off: the server, the channel listener and their links are gone;
     * [serveCapability] and [listenForChannels] open new ones.
     */
    fun bluetoothOff() {
        server.close()
        listener.close()
    }

    private fun emit(event: RadioEvent) {
        inbox.trySend(event)
    }
}
