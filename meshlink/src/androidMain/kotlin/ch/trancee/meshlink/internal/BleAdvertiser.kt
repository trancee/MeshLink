package ch.trancee.meshlink.internal

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.BluetoothLeAdvertiser
import android.os.ParcelUuid

/**
 * Advertises the MESH service as ADR 0010 decision 1 specifies: legacy, connectable and scannable
 * on 1M, with exactly the 32-bit MESH UUID and the payload as a 128-bit service UUID, no name, no
 * TX power and no scan response. Each payload is a new advertising set, never a data update, so the
 * address and payload change together (ADR 0010 decision 3).
 */
internal class BleAdvertiser(
    private val adapter: BluetoothAdapter,
    private val emit: (RadioEvent) -> Unit,
) {
    @Volatile private var current: Callback? = null

    /** Advertises [payload] instead of any earlier payload. */
    fun start(payload: ByteArray): RadioResult<Unit> {
        if (payload.size != RadioPort.ADVERTISEMENT_LENGTH) return RadioResult.Failed(RadioFailure.INVALID_ARGUMENT)
        stop()
        val started = adapter.bluetoothLeAdvertiser?.let { advertise(it, payload) } != null
        return if (started) RadioResult.Ok(Unit) else RadioResult.Failed(RadioFailure.RADIO_ERROR)
    }

    private fun advertise(
        leAdvertiser: BluetoothLeAdvertiser,
        payload: ByteArray,
    ) {
        val data =
            AdvertiseData
                .Builder()
                .addServiceUuid(MeshGatt.MESH_PARCEL)
                .addServiceUuid(ParcelUuid(MeshGatt.uuidOf(payload)))
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .build()
        val callback = Callback(leAdvertiser)
        current = callback
        leAdvertiser.startAdvertisingSet(PARAMETERS, data, null, null, null, callback)
    }

    /** Stops advertising; does nothing if it is not advertising. */
    fun stop() {
        current?.let { it.advertiser.stopAdvertisingSet(it) }
        current = null
    }

    private inner class Callback(
        val advertiser: BluetoothLeAdvertiser,
    ) : AdvertisingSetCallback() {
        override fun onAdvertisingSetStarted(
            advertisingSet: AdvertisingSet?,
            txPower: Int,
            status: Int,
        ) {
            if (status != ADVERTISE_SUCCESS && current === this) emit(RadioEvent.Failed(RadioFailure.RADIO_ERROR))
        }
    }
}

private val PARAMETERS: AdvertisingSetParameters =
    AdvertisingSetParameters
        .Builder()
        .setLegacyMode(true)
        .setConnectable(true)
        .setScannable(true)
        .setPrimaryPhy(BluetoothDevice.PHY_LE_1M)
        .setSecondaryPhy(BluetoothDevice.PHY_LE_1M)
        .build()
