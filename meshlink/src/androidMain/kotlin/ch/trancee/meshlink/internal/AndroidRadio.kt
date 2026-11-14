package ch.trancee.meshlink.internal

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * The Android Bluetooth LE radio (ADR 0010): advertises and scans with [BleAdvertiser] and
 * [BleScanner], serves the MeshLink GATT service with [GattServerHost] and connects to peripherals
 * as a [GattCentralLink]. Platform callbacks arrive on binder threads; [events] carries them to the
 * one collector in order. Links run over GATT only; L2CAP CoC belongs to ticket 31.
 */
internal class AndroidRadio(
    context: Context,
    manager: BluetoothManager,
    adapter: BluetoothAdapter,
    scope: CoroutineScope,
) : RadioPort {
    private val app = context.applicationContext
    private val inbox = Channel<RadioEvent>(Channel.UNLIMITED)
    private val advertiser = BleAdvertiser(adapter, ::emit)
    private val scanner = BleScanner(adapter, ::emit)
    private val server = GattServerHost(app, manager, scope, ::emit)

    override val events: Flow<RadioEvent> = inbox.receiveAsFlow()

    override fun startAdvertising(payload: ByteArray): RadioResult<Unit> = advertiser.start(payload)

    override fun stopAdvertising() = advertiser.stop()

    override fun startScanning(): RadioResult<Unit> = scanner.start()

    override fun stopScanning() = scanner.stop()

    override fun serveCapability(record: ByteArray): RadioResult<Unit> = server.serve(record)

    override suspend fun connect(peer: PeerHandle): RadioResult<CentralLink> =
        when (peer) {
            is AndroidPeer -> GattCentralLink(app, peer.device, ::emit).open()
            else -> RadioResult.Failed(RadioFailure.INVALID_ARGUMENT)
        }

    /** Bluetooth turned off: the server and its links are gone; [serveCapability] opens a new one. */
    fun bluetoothOff() = server.close()

    private fun emit(event: RadioEvent) {
        inbox.trySend(event)
    }
}
