package ch.trancee.lab.host

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import android.os.PowerManager
import android.os.SystemClock
import bakeoff.BakeoffUuids
import bakeoff.Json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import kotlin.random.Random

/**
 * PROTOTYPE discovery measurements (ticket 13): advertiser and scanner modes, run inside a connectedDevice
 * foreground service so they keep running with the screen off. Launch arguments are `disc=adv|scan|caps,...`.
 */
class DiscoveryService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        val args = intent?.getStringExtra("args") ?: return START_NOT_STICKY
        job?.cancel()
        job = scope.launch {
            Discovery(this@DiscoveryService, args).run()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Discovery prototype", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("MeshLink discovery prototype")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(1, notification)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL = "discovery"
    }
}

/** Buffered JSONL writer; the scanner flushes once a second so the host can pull a consistent prefix. */
class JsonlFile(file: File) {
    private val writer = file.bufferedWriter(Charsets.UTF_8, 1 shl 16).also { file.parentFile?.mkdirs() }

    @Synchronized
    fun emit(fields: Map<String, Any?>) {
        writer.write(Json.encode(fields))
        writer.newLine()
    }

    @Synchronized
    fun flush() = writer.flush()

    @Synchronized
    fun close() = runCatching { writer.close() }
}

fun wallMs(): Long = System.currentTimeMillis()

fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

/** 16-byte placeholder payload (the real layout comes from separate desk research): "ML", step, cycle, tag[6], run nonce[6]. */
fun placeholderPayload(tag: String, step: Int, cycle: Int, nonce: ByteArray): ByteArray {
    val out = ByteArray(16)
    out[0] = 'M'.code.toByte()
    out[1] = 'L'.code.toByte()
    out[2] = step.toByte()
    out[3] = cycle.toByte()
    tag.encodeToByteArray().copyInto(out, 4, 0, minOf(6, tag.length))
    nonce.copyInto(out, 10, 0, 6)
    return out
}

const val TEST_COMPANY_ID = 0xFFFF
val MESH_TAG = byteArrayOf(0x4d, 0x4c, 0x30, 0x31) // placeholder 4 B mesh tag "ML01"
const val APPLE_COMPANY_ID = 0x004C

@SuppressLint("MissingPermission")
class Discovery(private val context: Context, argText: String) {
    private val raw: Map<String, String> = argText.split(',')
        .mapNotNull { token -> token.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }
        .toMap()
    private val runId = raw["run"] ?: "adhoc"
    private val tag = raw["tag"] ?: Build.MODEL.take(6)
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter = manager.adapter
    private val out = JsonlFile(File(File(context.filesDir, "results"), "$runId.jsonl"))
    private val nonce = Random.nextBytes(6)
    private val serviceUuid = ParcelUuid(UUID.fromString(BakeoffUuids.SERVICE))

    private fun record(test: String, extra: Map<String, Any?> = emptyMap()) {
        out.emit(linkedMapOf<String, Any?>("t" to wallMs(), "test" to test).apply { putAll(extra) })
    }

    private fun capabilities(): Map<String, Any?> = linkedMapOf(
        "le_2m" to adapter.isLe2MPhySupported,
        "le_coded" to adapter.isLeCodedPhySupported,
        "le_extended_adv" to adapter.isLeExtendedAdvertisingSupported,
        "le_periodic_adv" to adapter.isLePeriodicAdvertisingSupported,
        "le_max_adv_data_len" to adapter.leMaximumAdvertisingDataLength,
        "multiple_adv" to adapter.isMultipleAdvertisementSupported,
        "offloaded_filtering" to adapter.isOffloadedFilteringSupported,
        "offloaded_batching" to adapter.isOffloadedScanBatchingSupported,
    )

    suspend fun run() {
        val screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                record("system_event", mapOf("event" to intent.action?.substringAfterLast('.')))
                out.flush()
            }
        }
        context.registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        })
        val power = context.getSystemService(PowerManager::class.java)
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        record(
            "disc_start",
            linkedMapOf(
                "run_id" to runId, "commit" to raw["commit"], "tag" to tag, "mode" to raw["disc"],
                "reporter" to mapOf("model" to "${Build.MANUFACTURER} ${Build.MODEL}", "os" to "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})", "platform" to "android"),
                "fingerprint" to Build.FINGERPRINT, "args" to raw, "caps" to capabilities(),
                "screen_interactive" to power.isInteractive, "plugged" to (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1),
                "elapsed_ms" to SystemClock.elapsedRealtime(),
            ),
        )
        out.flush()
        val outcome = runCatching {
            when (raw["disc"]) {
                "adv" -> Advertiser().run()
                "scan" -> Scanner().run()
                "caps" -> Unit
                else -> throw IllegalArgumentException("unknown disc=${raw["disc"]}")
            }
        }
        record("done", mapOf("outcome" to if (outcome.isSuccess) "ok" else "fail", "error" to outcome.exceptionOrNull()?.toString()))
        out.flush()
        runCatching { context.unregisterReceiver(screenReceiver) }
        out.close()
    }

    // ------------------------------------------------------------------ advertiser

    private inner class Advertiser {
        private val advertiser: BluetoothLeAdvertiser = adapter.bluetoothLeAdvertiser ?: throw IllegalStateException("no LE advertiser")
        private val duration = (raw["duration"]?.toLong() ?: 600) * 1000
        private val steps = (raw["adv"] ?: "legacy").split('+')
        private val stepMs = raw["step_s"]?.toLong()?.times(1000)
        private val gapMs = (raw["gap_s"]?.toLong() ?: 3) * 1000
        private val reps = raw["reps"]?.toInt() ?: 1
        private val restartEvery = raw["restart_every"]?.toLong()?.times(1000)
        private val restartOff = (raw["restart_off"]?.toDouble() ?: 2.0).times(1000).toLong()
        private val restartKind = raw["restart_kind"] ?: "stop"
        private val cycles = raw["cycles"]?.toInt() ?: Int.MAX_VALUE
        private val pad = raw["pad"]?.toInt() ?: 0
        private val ownAddress = raw["own_addr"] == "1"
        private val ownAddressEveryMs = (raw["own_addr_every"]?.toLong() ?: 60) * 1000
        private val connectable = raw["conn"] != "0"

        private var legacyCallback: AdvertiseCallback? = null
        private var set: AdvertisingSet? = null
        private var setCallback: AdvertisingSetCallback? = null

        suspend fun run() {
            val started = SystemClock.elapsedRealtime()
            repeat(reps) { rep ->
                steps.forEachIndexed { index, step ->
                    val remaining = duration - (SystemClock.elapsedRealtime() - started)
                    if (remaining <= 0) return
                    val length = minOf(stepMs ?: remaining, remaining)
                    runStep(rep, index, step, length)
                    if (index < steps.size - 1 || rep < reps - 1) delay(gapMs)
                }
            }
        }

        private suspend fun runStep(rep: Int, index: Int, step: String, lengthMs: Long) {
            val unsupported = when (step) {
                "legacy", "set" -> null
                "ext1m" -> if (!adapter.isLeExtendedAdvertisingSupported) "extended advertising unsupported" else null
                "ext2m" -> if (!adapter.isLeExtendedAdvertisingSupported) "extended advertising unsupported" else if (!adapter.isLe2MPhySupported) "2M unsupported" else null
                "coded" -> if (!adapter.isLeExtendedAdvertisingSupported) "extended advertising unsupported" else if (!adapter.isLeCodedPhySupported) "coded unsupported" else null
                else -> throw IllegalArgumentException("unknown adv step $step")
            }
            if (unsupported != null) {
                record("adv_step", mapOf("rep" to rep, "step" to index, "kind" to step, "outcome" to "unsupported", "error" to unsupported))
                out.flush()
                delay(lengthMs)
                return
            }
            val stepStart = SystemClock.elapsedRealtime()
            var cycle = 0
            val firstOk = start(rep, index, step, cycle)
            record("adv_step", mapOf("rep" to rep, "step" to index, "kind" to step, "outcome" to if (firstOk) "ok" else "fail"))
            out.flush()
            var nextOwn = SystemClock.elapsedRealtime()
            var nextRestart = if (restartEvery != null) SystemClock.elapsedRealtime() + restartEvery else Long.MAX_VALUE
            try {
                while (SystemClock.elapsedRealtime() - stepStart < lengthMs) {
                    val now = SystemClock.elapsedRealtime()
                    if (ownAddress && set != null && now >= nextOwn) {
                        readOwnAddress()
                        nextOwn = now + ownAddressEveryMs
                    }
                    if (now >= nextRestart && cycle < cycles) {
                        cycle++
                        restart(rep, index, step, cycle)
                        nextRestart = SystemClock.elapsedRealtime() + restartEvery!!
                    }
                    delay(250)
                }
            } finally {
                stop(step, "step_end")
            }
        }

        private var adId = ByteArray(8)

        /**
         * Layout A (desk research 2026-11-02 §4): ADV = flags + mesh UUID; scan response = 13 B service data under the
         * mesh UUID (8 B random advertisement ID, 4 B mesh tag, 1 B flags). Extended steps carry the same service data
         * (plus `pad` bytes) in the ADV. `layout=P` keeps the older 16 B manufacturer-data placeholder.
         */
        private fun data(step: String, index: Int, cycle: Int): Pair<AdvertiseData, AdvertiseData?> {
            adId = Random.nextBytes(8)
            if (raw["layout"] != "P") {
                val hint = adId + MESH_TAG + byteArrayOf(((index and 0x0f) shl 4 or (cycle and 0x0f)).toByte())
                return if (step == "legacy" || step == "set") {
                    AdvertiseData.Builder().addServiceUuid(serviceUuid).setIncludeDeviceName(false).build() to
                        AdvertiseData.Builder().addServiceData(serviceUuid, hint).setIncludeDeviceName(false).build()
                } else {
                    AdvertiseData.Builder().addServiceUuid(serviceUuid).addServiceData(serviceUuid, hint + ByteArray(pad) { it.toByte() })
                        .setIncludeDeviceName(false).build() to null
                }
            }
            val payload = placeholderPayload(tag, index, cycle, nonce)
            return if (step == "legacy" || step == "set") {
                AdvertiseData.Builder().addServiceUuid(serviceUuid).setIncludeDeviceName(false).build() to
                    AdvertiseData.Builder().addManufacturerData(TEST_COMPANY_ID, payload).setIncludeDeviceName(false).build()
            } else {
                AdvertiseData.Builder().addServiceUuid(serviceUuid).addManufacturerData(TEST_COMPANY_ID, payload + ByteArray(pad) { it.toByte() })
                    .setIncludeDeviceName(false).build() to null
            }
        }

        private suspend fun start(rep: Int, index: Int, step: String, cycle: Int): Boolean {
            val (advData, scanResponse) = data(step, index, cycle)
            val t0 = wallMs()
            val result = if (step == "legacy") startLegacy(advData, scanResponse) else startSet(step, advData, scanResponse)
            record(
                "adv_start",
                mapOf("rep" to rep, "step" to index, "kind" to step, "cycle" to cycle, "ad_id" to adId.hex(), "t_call" to t0, "status" to result.first, "detail" to result.second),
            )
            out.flush()
            if (ownAddress && set != null) readOwnAddress()
            return result.first == 0
        }

        private suspend fun startLegacy(advData: AdvertiseData, scanResponse: AdvertiseData?): Pair<Int, String?> {
            val done = CompletableDeferred<Pair<Int, String?>>()
            val callback = object : AdvertiseCallback() {
                override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                    done.complete(0 to settingsInEffect.toString())
                }

                override fun onStartFailure(errorCode: Int) {
                    done.complete(errorCode to "onStartFailure")
                }
            }
            legacyCallback = callback
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(connectable)
                .build()
            advertiser.startAdvertising(settings, advData, scanResponse, callback)
            return withTimeoutOrNull(5_000) { done.await() } ?: (-1 to "timeout")
        }

        private suspend fun startSet(step: String, advData: AdvertiseData, scanResponse: AdvertiseData?): Pair<Int, String?> {
            val legacy = step == "set"
            val builder = AdvertisingSetParameters.Builder()
                .setLegacyMode(legacy)
                .setConnectable(connectable)
                .setScannable(legacy)
                .setInterval(AdvertisingSetParameters.INTERVAL_LOW)
                .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
            if (!legacy) {
                val (primary, secondary) = when (step) {
                    "ext1m" -> BluetoothDevice.PHY_LE_1M to BluetoothDevice.PHY_LE_1M
                    "ext2m" -> BluetoothDevice.PHY_LE_1M to BluetoothDevice.PHY_LE_2M
                    else -> BluetoothDevice.PHY_LE_CODED to BluetoothDevice.PHY_LE_CODED
                }
                builder.setPrimaryPhy(primary).setSecondaryPhy(secondary)
            }
            raw["own_type"]?.let { applyOwnAddressType(builder, it) }
            val started = CompletableDeferred<Pair<Int, String?>>()
            val callback = object : AdvertisingSetCallback() {
                override fun onAdvertisingSetStarted(advertisingSet: AdvertisingSet?, txPower: Int, status: Int) {
                    if (advertisingSet != null) set = advertisingSet
                    started.complete(status to "tx_power=$txPower")
                }

                override fun onAdvertisingSetStopped(advertisingSet: AdvertisingSet?) {
                    record("adv_set_stopped")
                }

                override fun onAdvertisingEnabled(advertisingSet: AdvertisingSet?, enable: Boolean, status: Int) {
                    record("adv_enabled_cb", mapOf("enable" to enable, "status" to status))
                }

                /** Hidden (system API) callback; declared so the framework can dispatch to it if the read is allowed. */
                @Suppress("unused")
                fun onOwnAddressRead(advertisingSet: AdvertisingSet?, addressType: Int, address: String?) {
                    record("own_address", mapOf("address_type" to addressType, "address" to address))
                    out.flush()
                }
            }
            setCallback = callback
            try {
                advertiser.startAdvertisingSet(builder.build(), advData, scanResponse, null, null, callback)
            } catch (failure: Exception) {
                return -2 to failure.toString()
            }
            return withTimeoutOrNull(5_000) { started.await() } ?: (-1 to "timeout")
        }

        private fun applyOwnAddressType(builder: AdvertisingSetParameters.Builder, type: String) {
            val value = when (type) {
                "public" -> 0
                "random" -> 1
                "nrpa" -> 2
                else -> -1
            }
            val outcome = runCatching {
                builder.javaClass.getMethod("setOwnAddressType", Int::class.javaPrimitiveType).invoke(builder, value)
            }
            record("own_type_set", mapOf("type" to type, "value" to value, "outcome" to if (outcome.isSuccess) "ok" else "fail", "error" to outcome.exceptionOrNull()?.let { describe(it) }))
        }

        private fun readOwnAddress() {
            val current = set ?: return
            val outcome = runCatching { current.javaClass.getMethod("getOwnAddress").invoke(current) }
            record("own_address_call", mapOf("outcome" to if (outcome.isSuccess) "ok" else "fail", "error" to outcome.exceptionOrNull()?.let { describe(it) }))
            out.flush()
        }

        private fun describe(failure: Throwable): String {
            val root = (failure as? java.lang.reflect.InvocationTargetException)?.targetException ?: failure
            return "${root.javaClass.name}: ${root.message}"
        }

        private suspend fun restart(rep: Int, index: Int, step: String, cycle: Int) {
            when {
                restartKind == "data" && step != "legacy" && set != null -> {
                    // T5(iii): new data on the live set, no disable; the address should not change.
                    val current = set!!
                    val (advData, scanResponse) = data(step, index, cycle)
                    current.setAdvertisingData(advData)
                    scanResponse?.let { current.setScanResponseData(it) }
                    record("adv_start", mapOf("rep" to rep, "step" to index, "kind" to step, "cycle" to cycle, "ad_id" to adId.hex(), "how" to "set_data", "status" to 0))
                    out.flush()
                }
                restartKind == "enable" && step != "legacy" && set != null -> {
                    val current = set!!
                    current.enableAdvertising(false, 0, 0)
                    record("adv_stop", mapOf("cycle" to cycle - 1, "how" to "enable_false"))
                    out.flush()
                    delay(restartOff)
                    // New payload on the same set, so the scanner can tell cycles apart.
                    val (advData, scanResponse) = data(step, index, cycle)
                    current.setAdvertisingData(advData)
                    scanResponse?.let { current.setScanResponseData(it) }
                    current.enableAdvertising(true, 0, 0)
                    record("adv_start", mapOf("rep" to rep, "step" to index, "kind" to step, "cycle" to cycle, "ad_id" to adId.hex(), "how" to "enable_true", "status" to 0))
                    out.flush()
                    if (ownAddress) readOwnAddress()
                }
                else -> {
                    stop(step, "restart")
                    delay(restartOff)
                    start(rep, index, step, cycle)
                }
            }
        }

        private fun stop(step: String, why: String) {
            if (step == "legacy") {
                legacyCallback?.let { runCatching { advertiser.stopAdvertising(it) } }
                legacyCallback = null
            } else {
                setCallback?.let { runCatching { advertiser.stopAdvertisingSet(it) } }
                setCallback = null
                set = null
            }
            record("adv_stop", mapOf("why" to why))
            out.flush()
        }
    }

    // ------------------------------------------------------------------ scanner

    private inner class Bucket(val firstMs: Long, var count: Int, var rssiMax: Int, val id: String?)

    private inner class Scanner {
        private val duration = (raw["duration"]?.toLong() ?: 600) * 1000
        private val rescanMs = (raw["rescan_s"]?.toLong() ?: 1500) * 1000
        private val logAll = raw["log"] == "all"
        private val buckets = HashMap<Pair<String, Long>, Bucket>()
        private val lastAdv = HashMap<String, String>()
        private val allAddresses = HashSet<String>()
        private var results = 0L

        fun filters(): List<ScanFilter> = (raw["filter"] ?: "none").split('+').flatMap { kind ->
            when (kind) {
                "none" -> emptyList()
                "service" -> listOf(ScanFilter.Builder().setServiceUuid(serviceUuid).build())
                "ml" -> listOf(ScanFilter.Builder().setManufacturerData(TEST_COMPANY_ID, byteArrayOf('M'.code.toByte(), 'L'.code.toByte()), byteArrayOf(-1, -1)).build())
                "apple" -> listOf(ScanFilter.Builder().setManufacturerData(APPLE_COMPANY_ID, byteArrayOf(1), byteArrayOf(-1)).build())
                else -> {
                    // apple:<hex data>:<hex mask> — manufacturer 0x004C filter, e.g. on the overflow-area bitmap.
                    val parts = kind.split(':')
                    if (parts[0] != "apple" || parts.size != 3) throw IllegalArgumentException("unknown filter $kind")
                    listOf(ScanFilter.Builder().setManufacturerData(APPLE_COMPANY_ID, parts[1].unhex(), parts[2].unhex()).build())
                }
            }
        }

        private fun String.unhex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        fun settings(): ScanSettings {
            val mode = when (raw["scan_mode"] ?: "low_latency") {
                "low_power" -> ScanSettings.SCAN_MODE_LOW_POWER
                "balanced" -> ScanSettings.SCAN_MODE_BALANCED
                else -> ScanSettings.SCAN_MODE_LOW_LATENCY
            }
            val builder = ScanSettings.Builder().setScanMode(mode)
            if (raw["scan_type"] == "passive") {
                if (Build.VERSION.SDK_INT < 36) throw IllegalStateException("passive scan needs API 36")
                builder.setScanType(ScanSettings.SCAN_TYPE_PASSIVE)
            }
            if (adapter.isLeExtendedAdvertisingSupported && raw["legacy_only"] != "1") {
                builder.setLegacy(false).setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
            }
            return builder.build()
        }

        private fun classify(result: ScanResult): Pair<String?, Map<String, Any?>> {
            val record = result.scanRecord ?: return null to emptyMap()
            record.getServiceData(serviceUuid)?.let { data ->
                if (data.size >= 13) {
                    return "a:${data.copyOfRange(0, 8).hex()}" to mapOf("mesh_tag" to data.copyOfRange(8, 12).hex(), "flags" to (data[12].toInt() and 0xff), "sd_len" to data.size)
                }
            }
            record.getManufacturerSpecificData(TEST_COMPANY_ID)?.let { data ->
                if (data.size >= 16 && data[0] == 'M'.code.toByte() && data[1] == 'L'.code.toByte()) {
                    val deviceTag = data.copyOfRange(4, 10).decodeToString().trimEnd('\u0000')
                    return "ml:$deviceTag" to mapOf("step" to data[2].toInt(), "cycle" to (data[3].toInt() and 0xff), "len" to data.size)
                }
            }
            record.deviceName?.let { if (it.startsWith("ML-")) return "name:$it" to emptyMap() }
            if (record.serviceUuids?.contains(serviceUuid) == true) return "svc" to emptyMap()
            record.getManufacturerSpecificData(APPLE_COMPANY_ID)?.let { if (it.isNotEmpty() && it[0].toInt() == 1) return "apple-ovf" to emptyMap() }
            return null to emptyMap()
        }

        private val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)

            override fun onBatchScanResults(batch: MutableList<ScanResult>) = batch.forEach(::handle)

            override fun onScanFailed(errorCode: Int) {
                record("scan_failed", mapOf("error" to errorCode))
                failed.complete(errorCode)
            }
        }
        private var failed = CompletableDeferred<Int>()

        private fun handle(result: ScanResult) {
            val (id, info) = classify(result)
            val address = result.device.address
            val nowWall = wallMs()
            val seenWall = nowWall - (SystemClock.elapsedRealtimeNanos() - result.timestampNanos) / 1_000_000
            synchronized(buckets) {
                results++
                allAddresses += address
                if (id == null && !logAll) return
                val bytes = result.scanRecord?.bytes ?: ByteArray(0)
                val rawHex = bytes.copyOf(bytes.indexOfLast { it.toInt() != 0 } + 1).hex()
                val legacy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) result.isLegacy else true
                val signature = "$rawHex|$legacy|${result.isConnectable}"
                if (lastAdv[address] != signature) {
                    lastAdv[address] = signature
                    out.emit(linkedMapOf<String, Any?>(
                        "t" to seenWall, "test" to "adv", "a" to address, "id" to id, "raw" to rawHex, "rssi" to result.rssi,
                        "legacy" to legacy, "conn" to result.isConnectable,
                        "pphy" to result.primaryPhy, "sphy" to result.secondaryPhy, "sid" to result.advertisingSid,
                        "txp" to result.txPower, "ds" to result.dataStatus, "name" to result.scanRecord?.deviceName,
                    ).apply { putAll(info) })
                }
                val key = address to seenWall / 1000
                val bucket = buckets.getOrPut(key) { Bucket(seenWall, 0, result.rssi, id) }
                bucket.count++
                if (result.rssi > bucket.rssiMax) bucket.rssiMax = result.rssi
            }
        }

        private fun flush(all: Boolean) {
            val cutoff = wallMs() / 1000 - 2
            synchronized(buckets) {
                val ready = buckets.entries.filter { all || it.key.second < cutoff }.sortedBy { it.key.second }
                for ((key, bucket) in ready) {
                    out.emit(linkedMapOf("t" to bucket.firstMs, "test" to "s", "a" to key.first, "id" to bucket.id, "n" to bucket.count, "rssi" to bucket.rssiMax))
                    buckets.remove(key)
                }
            }
            out.flush()
        }

        suspend fun run() {
            val scanner = adapter.bluetoothLeScanner ?: throw IllegalStateException("no LE scanner")
            val started = SystemClock.elapsedRealtime()
            var lastStats = started
            try {
                while (SystemClock.elapsedRealtime() - started < duration) {
                    failed = CompletableDeferred()
                    val settings = settings()
                    val filters = filters()
                    // Paired start/stop (library rule R10); long scans are restarted before AOSP's 30 min downgrade.
                    try {
                        scanner.startScan(filters, settings, callback)
                        record("scan_start", mapOf("filters" to filters.size, "settings" to settings.toString()))
                        val segmentEnd = minOf(started + duration, SystemClock.elapsedRealtime() + rescanMs)
                        while (SystemClock.elapsedRealtime() < segmentEnd && !failed.isCompleted) {
                            delay(1000)
                            flush(false)
                            if (SystemClock.elapsedRealtime() - lastStats >= 60_000) {
                                lastStats = SystemClock.elapsedRealtime()
                                synchronized(buckets) { record("scan_stats", mapOf("results" to results, "distinct_addresses" to allAddresses.size)) }
                            }
                        }
                    } finally {
                        runCatching { scanner.stopScan(callback) }
                        record("scan_stop")
                    }
                    if (failed.isCompleted) throw IllegalStateException("scan failed ${failed.getCompleted()}")
                    delay(7_000)
                }
            } finally {
                flush(true)
                synchronized(buckets) { record("scan_stats", mapOf("results" to results, "distinct_addresses" to allAddresses.size)) }
            }
        }
    }
}
