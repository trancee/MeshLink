package ch.trancee.meshlink.internal

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Scans with two hardware filters, the MESH service and Apple's manufacturer data, so no scan is
 * unfiltered and screen-off scans need no other setup (ADR 0010 decisions 2, 5 and 6). Reports
 * an advertisement that carries a payload UUID as a [RadioEvent.Sighted], and one that carries only
 * the overflow MESH bit, as a backgrounded iPhone's does, as a sighting without payload.
 *
 * The core scans in windows; each [start] is one `startScan` and each [stop] its `stopScan`, also
 * after a scan failure, since the stack may still register a scan it reported failed (library rule
 * R10). Android throttles an app beyond 5 starts in 30 s, so a start beyond that budget (rule R11)
 * is deferred in [scope] until the oldest start is 30 s old, as measured by [time]; a [stop] before
 * then cancels it.
 */
internal class BleScanner(
    private val adapter: BluetoothAdapter,
    private val scope: CoroutineScope,
    private val time: TimeSource.WithComparableMarks,
    private val emit: (RadioEvent) -> Unit,
) {
    @Volatile private var current: Callback? = null

    private var deferred: Job? = null

    /** When the starts within the last [BUDGET_WINDOW] began, oldest first. */
    private val starts = ArrayDeque<ComparableTimeMark>()

    /** Starts scanning, or defers the start beyond the budget; does nothing if it is scanning or deferred. */
    @Synchronized
    fun start(): RadioResult<Unit> {
        val leScanner = adapter.bluetoothLeScanner
        return when {
            current != null || deferred != null -> RadioResult.Ok(Unit)
            leScanner == null -> RadioResult.Failed(RadioFailure.RADIO_ERROR)
            else -> RadioResult.Ok(startWithinBudget(leScanner))
        }
    }

    private fun startWithinBudget(leScanner: BluetoothLeScanner) {
        while (starts.firstOrNull()?.let { it.elapsedNow() >= BUDGET_WINDOW } == true) starts.removeFirst()
        if (starts.size < BUDGET) {
            scan(leScanner)
        } else {
            val wait = BUDGET_WINDOW - starts.first().elapsedNow()
            deferred =
                scope.launch {
                    delay(wait)
                    startDeferred(coroutineContext)
                }
        }
    }

    /** Stops scanning and cancels a deferred start; does nothing if neither. */
    @Synchronized
    fun stop() {
        deferred?.cancel()
        deferred = null
        current?.let { it.scanner.stopScan(it) }
        current = null
    }

    /** Starts the deferred scan unless [stop] cancelled it in [context] first; Bluetooth may be off by now. */
    @Synchronized
    private fun startDeferred(context: CoroutineContext) {
        context.ensureActive()
        deferred = null
        adapter.bluetoothLeScanner?.let(::scan) ?: emit(RadioEvent.ScanFailed(RadioFailure.RADIO_ERROR))
    }

    private fun scan(leScanner: BluetoothLeScanner) {
        val callback = Callback(leScanner)
        current = callback
        starts.addLast(time.markNow())
        leScanner.startScan(FILTERS, SETTINGS, callback)
    }

    private inner class Callback(
        val scanner: BluetoothLeScanner,
    ) : ScanCallback() {
        override fun onScanResult(
            callbackType: Int,
            result: ScanResult,
        ) {
            val record = result.scanRecord
            val uuids = record?.serviceUuids.orEmpty()
            val payload = uuids.firstOrNull { it != MeshGatt.MESH_PARCEL }?.takeIf { MeshGatt.MESH_PARCEL in uuids }
            val peer = AndroidPeer(result.device)
            val sighting =
                when {
                    payload != null -> Sighting(MeshGatt.payloadOf(payload.uuid), peer, result.rssi)
                    record != null && carriesOverflowMeshBit(record) -> Sighting(null, peer, result.rssi)
                    else -> null
                }
            if (current === this && sighting != null) emit(RadioEvent.Sighted(sighting))
        }

        override fun onScanFailed(errorCode: Int) {
            if (current === this) {
                val leaked = errorCode == SCAN_FAILED_APPLICATION_REGISTRATION_FAILED
                emit(RadioEvent.ScanFailed(RadioFailure.RADIO_ERROR, errorCode, registrationFailed = leaked))
            }
        }
    }
}

/** At most this many scan starts in any [BUDGET_WINDOW] (library rule R11; AOSP `ScanManager`). */
private const val BUDGET = 5
private val BUDGET_WINDOW = 30.seconds

/** Apple's Bluetooth SIG company identifier. */
private const val APPLE = 0x004C

/**
 * Apple's overflow area: within Apple manufacturer data (`FF 4C 00`), the sub-field type `0x01`
 * followed, with no length byte, by a 16-byte bitmap with one hash bit per overflowed service UUID.
 * Other Apple sub-fields are type, length, data. Undocumented by Apple; the format and hash are in
 * ADR 0010's 2026-11-23 amendment and `docs/explanation/background-iphone-discovery.md`.
 */
private const val MANUFACTURER_DATA = 0xFF
private const val OVERFLOW_TYPE = 0x01
private const val OVERFLOW_BITMAP_SIZE = 16
private const val BYTE_MASK = 0xFF
private const val BITS_PER_BYTE = 8

/** An Apple manufacturer-data AD structure's type and little-endian company identifier. */
private val APPLE_MANUFACTURER_DATA =
    byteArrayOf(MANUFACTURER_DATA.toByte(), APPLE.toByte(), (APPLE ushr BITS_PER_BYTE).toByte())

/** CRC-8 with polynomial `0x09` (x⁸ + x³ + 1), initial value 0, of which the overflow keeps 7 bits. */
private const val CRC_POLYNOMIAL = 0x09
private const val CRC_TOP_BIT = 0x80
private const val OVERFLOW_BIT_MASK = 0x7F
private const val UUID32_SIZE = Int.SIZE_BYTES

/** MESH's overflow bit, 123: the last bitmap byte's `0x08`. */
private val MESH_OVERFLOW_BIT = overflowBit32((MeshGatt.MESH.mostSignificantBits ushr Int.SIZE_BITS).toInt())

/**
 * The overflow bit of the 32-bit service UUID [uuid]: the CRC over the UUID zero-padded on the
 * right to 16 bytes. iOS zero-pads 32-bit UUIDs but expands 16-bit ones with the Base UUID
 * (ADR 0010, 2026-11-23 amendment).
 */
private fun overflowBit32(uuid: Int): Int {
    val padded = ByteArray(OVERFLOW_BITMAP_SIZE)
    for (i in 0 until UUID32_SIZE) padded[i] = (uuid ushr (BITS_PER_BYTE * (UUID32_SIZE - 1 - i))).toByte()
    var crc = 0
    for (byte in padded) {
        crc = crc xor (byte.toInt() and BYTE_MASK)
        repeat(BITS_PER_BYTE) {
            crc = ((crc shl 1) xor (if (crc and CRC_TOP_BIT != 0) CRC_POLYNOMIAL else 0)) and BYTE_MASK
        }
    }
    return crc and OVERFLOW_BIT_MASK
}

/**
 * Whether [record] holds an overflow area with the MESH bit in any of its AD structures. It reads
 * the raw bytes because Android 15 and later concatenate all of a company's manufacturer data, so a
 * background iPhone's Nearby Info would come first (AOSP `ScanRecord.parseFromBytes`); a zero length
 * ends the record, and a structure past its end is malformed.
 */
private fun carriesOverflowMeshBit(record: ScanRecord): Boolean {
    val bytes = record.bytes
    var at = 0
    var found = false
    while (!found && at < bytes.size) {
        val length = bytes[at].toInt() and BYTE_MASK
        if (length == 0 || at + length >= bytes.size) break
        val end = at + length + 1
        found =
            length >= APPLE_MANUFACTURER_DATA.size &&
            APPLE_MANUFACTURER_DATA.indices.all { bytes[at + 1 + it] == APPLE_MANUFACTURER_DATA[it] } &&
            appleDataHasMeshBit(bytes, at + 1 + APPLE_MANUFACTURER_DATA.size, end)
        at = end
    }
    return found
}

/**
 * Whether the Apple sub-fields in [bytes] from [start] to [end] hold an overflow bitmap with the
 * MESH bit. iOS has so far always put the overflow first; sub-fields before it are skipped anyway.
 */
private fun appleDataHasMeshBit(
    bytes: ByteArray,
    start: Int,
    end: Int,
): Boolean {
    var at = start
    while (at + 1 < end && bytes[at].toInt() != OVERFLOW_TYPE) at += 2 + (bytes[at + 1].toInt() and BYTE_MASK)
    val bitmap = at + 1
    val mask = 1 shl (MESH_OVERFLOW_BIT % BITS_PER_BYTE)
    return at < end &&
        bytes[at].toInt() == OVERFLOW_TYPE &&
        bitmap + OVERFLOW_BITMAP_SIZE <= end &&
        bytes[bitmap + MESH_OVERFLOW_BIT / BITS_PER_BYTE].toInt() and mask != 0
}

/**
 * Every Apple advertisement: a data mask cannot find the overflow area at a fixed offset once
 * Android 15 concatenates it after Nearby Info, so [carriesOverflowMeshBit] checks it instead. The
 * data is empty, not null: a null one makes `ScanFilter.matches` skip the manufacturer check.
 */
private val FILTERS: List<ScanFilter> =
    listOf(
        ScanFilter.Builder().setServiceUuid(MeshGatt.MESH_PARCEL).build(),
        ScanFilter.Builder().setManufacturerData(APPLE, ByteArray(0)).build(),
    )

/** Balanced: the core's windows set the duty cycle (ADR 0008 decision 3). */
private val SETTINGS: ScanSettings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build()
