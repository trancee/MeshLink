package ch.trancee.meshlink.internal

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings

/**
 * Scans for the MESH service with a hardware filter and reports each advertisement that carries a
 * payload UUID as a [RadioEvent.Sighted]. Each start is paired with one stop (library rule R10).
 * Scan windows and restarts, screen-off filters and the 5-starts-per-30-s budget (rule R11) belong
 * to ticket 32; until then a scan runs from [start] to [stop].
 */
internal class BleScanner(
    private val adapter: BluetoothAdapter,
    private val emit: (RadioEvent) -> Unit,
) {
    @Volatile private var current: Callback? = null

    /** Starts scanning; does nothing if it is scanning. */
    fun start(): RadioResult<Unit> {
        val scanning = current != null || adapter.bluetoothLeScanner?.let { scan(it) } != null
        return if (scanning) RadioResult.Ok(Unit) else RadioResult.Failed(RadioFailure.RADIO_ERROR)
    }

    private fun scan(leScanner: BluetoothLeScanner) {
        val callback = Callback(leScanner)
        current = callback
        leScanner.startScan(FILTERS, SETTINGS, callback)
    }

    /** Stops scanning; does nothing if it is not scanning. */
    fun stop() {
        current?.let { it.scanner.stopScan(it) }
        current = null
    }

    private inner class Callback(
        val scanner: BluetoothLeScanner,
    ) : ScanCallback() {
        override fun onScanResult(
            callbackType: Int,
            result: ScanResult,
        ) {
            val uuids = result.scanRecord?.serviceUuids.orEmpty()
            val payload = uuids.firstOrNull { it != MeshGatt.MESH_PARCEL }
            if (current === this && payload != null && MeshGatt.MESH_PARCEL in uuids) {
                val sighting = Sighting(MeshGatt.payloadOf(payload.uuid), AndroidPeer(result.device), result.rssi)
                emit(RadioEvent.Sighted(sighting))
            }
        }

        override fun onScanFailed(errorCode: Int) {
            if (current === this) emit(RadioEvent.Failed(RadioFailure.RADIO_ERROR))
        }
    }
}

private val FILTERS: List<ScanFilter> = listOf(ScanFilter.Builder().setServiceUuid(MeshGatt.MESH_PARCEL).build())

/** Balanced until ticket 32 sets the per-class scan schedule (ADR 0008 decision 3). */
private val SETTINGS: ScanSettings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build()
