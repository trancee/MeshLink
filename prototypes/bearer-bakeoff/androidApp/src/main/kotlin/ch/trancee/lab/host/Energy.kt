package ch.trancee.lab.host

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import bakeoff.ByteLink
import bakeoff.ByteReader
import bakeoff.ByteWriter
import bakeoff.CentralConnection
import bakeoff.ConnectRequest
import bakeoff.MessagePipe
import bakeoff.MessageType
import bakeoff.PeerIdentity
import bakeoff.PhaseClock
import bakeoff.Phy
import bakeoff.ResultSink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * PROTOTYPE energy windows (ticket 23). Started from the shell with
 * `am start-foreground-service -n ch.trancee.lab.host/.EnergyService --es args energy=<mode>,run=<id>,...`.
 * Logs `window_start` / `window_end` with elapsedRealtimeNanos (Perfetto boottime) so the host can slice rails.
 */
class EnergyService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("energy", "Energy prototype", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, "energy")
            .setContentTitle("MeshLink energy prototype")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .build()
        startForeground(2, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        val args = intent?.getStringExtra("args") ?: return START_NOT_STICKY
        job?.cancel()
        job = scope.launch {
            Energy(this@EnergyService, args).run()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

@SuppressLint("MissingPermission")
class Energy(private val context: Context, argText: String) {
    private val raw: Map<String, String> = argText.split(',')
        .mapNotNull { token -> token.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }
        .toMap()
    private val runId = raw["run"] ?: "adhoc"
    private val mode = raw["energy"] ?: "idle"
    private val windowMs = (raw["window_s"]?.toLong() ?: 600) * 1000
    private val settleMs = (raw["settle_s"]?.toLong() ?: 20) * 1000
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter = manager.adapter
    private val power = context.getSystemService(PowerManager::class.java)
    private val out = JsonlFile(File(File(context.filesDir, "results"), "$runId.jsonl"))
    private val scanResults = AtomicLong()
    private val connections = mutableListOf<CentralConnection>()
    private val linkPeers = mutableListOf<String>()
    private val lost = mutableListOf<String>()

    private fun record(test: String, extra: Map<String, Any?> = emptyMap()) {
        out.emit(linkedMapOf<String, Any?>("t" to wallMs(), "boot_ns" to SystemClock.elapsedRealtimeNanos(), "test" to test, "run_id" to runId).apply { putAll(extra) })
        out.flush()
    }

    private fun state(): Map<String, Any?> {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val wifi = context.getSystemService(WifiManager::class.java)
        return linkedMapOf(
            "screen_interactive" to power.isInteractive,
            "device_idle" to power.isDeviceIdleMode,
            "light_idle" to runCatching { power.isDeviceLightIdleMode }.getOrNull(),
            "wifi_enabled" to wifi?.isWifiEnabled,
            "bt_enabled" to adapter.isEnabled,
            "plugged" to battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1),
            "battery_status" to battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1),
            "level" to battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
            "temp_dc" to battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1),
            "links_alive" to connections.count { it.isConnected },
            "scan_results" to scanResults.get(),
        )
    }

    suspend fun run() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                record("system_event", mapOf("event" to intent.action?.substringAfterLast('.'), "device_idle" to power.isDeviceIdleMode))
            }
        }
        context.registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            addAction("android.os.action.LIGHT_DEVICE_IDLE_MODE_CHANGED")
        })
        record(
            "energy_start",
            linkedMapOf(
                "mode" to mode, "args" to raw, "model" to "${Build.MANUFACTURER} ${Build.MODEL}",
                "os" to "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})", "fingerprint" to Build.FINGERPRINT,
                "le_2m" to adapter.isLe2MPhySupported, "le_extended_adv" to adapter.isLeExtendedAdvertisingSupported,
                "offloaded_filtering" to adapter.isOffloadedFilteringSupported,
            ) + state(),
        )
        // Screen off, the Pixel suspends and coroutine delays stop counting, so duty schedules and the window
        // stretch (trial: 60 s window -> 403 s, 4 s scan gaps -> 11-121 s). Measured windows hold a partial
        // wake lock (same in the baseline); the overnight run passes wakelock=0 to see the unassisted behavior.
        val wakeLock = if (raw["wakelock"] != "0") power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bakeoff:energy").apply { acquire(24 * 3600_000L) } else null
        record("wakelock", mapOf("held" to (wakeLock != null)))
        val outcome = runCatching { coroutineScope { body() } }
        wakeLock?.let { if (it.isHeld) it.release() }
        record("done", mapOf("outcome" to if (outcome.isSuccess) "ok" else "fail", "error" to outcome.exceptionOrNull()?.toString()?.take(400)) + state())
        runCatching { context.unregisterReceiver(receiver) }
        out.close()
    }

    private suspend fun CoroutineScope.body() {
        val background = mutableListOf<Job>()
        try {
            val parts = mode.split('+')
            if ("adv" in parts) background += launch { Advertiser(raw["adv"] ?: "fast").run() }
            if ("scan" in parts) background += launch { DutyScanner().run() }
            val traffic: (suspend () -> Map<String, Any?>)? = when {
                "links" in parts -> {
                    setupLinks()
                    // Idle links carry no traffic; a reader per link only notices a closed GATT/CoC stream.
                    pipes.forEachIndexed { i, pipe ->
                        background += launch {
                            runCatching { pipe.link.read() }.exceptionOrNull()?.let {
                                if (it !is kotlinx.coroutines.CancellationException) {
                                    lost += linkPeers[i]
                                    record("link_lost", mapOf("model" to linkPeers[i], "error" to it.toString().take(200)))
                                }
                            }
                        }
                    }
                    null
                }
                "bulk" in parts -> { setupLinks(); { bulk() } }
                "relay" in parts -> { setupLinks(); relayTraffic() }
                else -> null
            }
            delay(settleMs)
            record("window_start", state() + mapOf("links" to linkPeers))
            val ticker = launch {
                while (isActive) {
                    delay(60_000)
                    record("tick", state())
                }
            }
            val start = SystemClock.elapsedRealtime()
            val result = if (traffic != null) traffic() else {
                // Wall (boottime) deadline: without a wake lock, delay() only advances while the CPU is awake.
                val end = SystemClock.elapsedRealtime() + windowMs
                while (SystemClock.elapsedRealtime() < end) delay(minOf(60_000L, end - SystemClock.elapsedRealtime()))
                emptyMap()
            }
            ticker.cancel()
            record("window_end", state() + mapOf("window_ms" to SystemClock.elapsedRealtime() - start, "lost" to lost.toList()) + result)
            // Traffic acks (bulk/relay) happen after window_end so the final round trip is not in the window.
            pendingAck?.let { record("ack", it()) }
        } finally {
            background.forEach { it.cancelAndJoin() }
            connections.forEach { runCatching { it.disconnect() } }
        }
    }

    private var pendingAck: (suspend () -> Map<String, Any?>)? = null

    // ------------------------------------------------------------------ links

    private val bearer get() = raw["bearer"] ?: "gatt"
    private val phy get() = Phy.entries.first { it.label == (raw["phy"] ?: "2m") }
    private val pipes = mutableListOf<MessagePipe>()

    private suspend fun setupLinks() {
        val want = (raw["want"] ?: "").split('+').filter { it.isNotBlank() }
        val n = raw["n"]?.toInt() ?: want.size.coerceAtLeast(1)
        val tag = raw["tag"]?.toInt()
        val central = AndroidCentral(context, adapter, NullSink, CentralTuning.from(raw + mapOf("connect_retries" to (raw["connect_retries"] ?: "3"))))
        val excluded = mutableSetOf<String>()
        val remaining = want.toMutableList()
        var attempts = 0
        val deadline = SystemClock.elapsedRealtime() + (raw["setup_s"]?.toLong() ?: 240) * 1000
        while (connections.size < n && SystemClock.elapsedRealtime() < deadline) {
            attempts++
            val clock = PhaseClock()
            var connection: CentralConnection? = null
            try {
                connection = withTimeout(60_000) { central.connect(ConnectRequest(phy, excluded + connections.map { it.address }, 20_000), clock) }
                val control = PeerIdentity.decodeControl(withTimeout(10_000) { connection.readControl() })
                val model = control.info.substringBefore('|')
                val match = if (want.isEmpty()) "" else remaining.firstOrNull { model.contains(it, ignoreCase = true) }
                if ((tag != null && control.runTag != tag) || match == null) {
                    excluded += connection.address
                    record("link_skip", mapOf("model" to model, "tag" to control.runTag))
                    connection.disconnect()
                    continue
                }
                if (want.isNotEmpty()) remaining.remove(match)
                // Keep `want` order: relay uses index 0 as source, 1 as sink.
                val pipe = when (bearer) {
                    "coc" -> {
                        val psm = withTimeout(10_000) { connection.readPsm() }
                        MessagePipe(connection.openL2cap(psm, secure = false))
                    }
                    else -> MessagePipe(connection.gattLink)
                }
                clock.mark("bearer")
                connections += connection
                pipes += pipe
                linkPeers += model
                record("link_up", mapOf("model" to model, "bearer" to bearer, "mtu" to connection.mtu, "phy" to connection.phyReport(), "timings" to clock.marks, "attempt" to attempts))
            } catch (failure: Throwable) {
                if (failure is kotlinx.coroutines.CancellationException && failure !is kotlinx.coroutines.TimeoutCancellationException) throw failure
                record("link_fail", mapOf("error" to failure.toString().take(300), "timings" to clock.marks, "attempt" to attempts))
                connection?.let { c -> if (c !in connections) runCatching { c.disconnect() } }
            }
        }
        if (connections.size < n) throw IllegalStateException("only ${connections.size}/$n links: ${linkPeers}")
        if (want.size >= 2) {
            // Reorder pipes to the `want` order (source first, sink second).
            val order = want.map { w -> linkPeers.indexOfFirst { it.contains(w, ignoreCase = true) } }
            val sortedPipes = order.map { pipes[it] }
            pipes.clear()
            pipes += sortedPipes
            val sortedPeers = order.map { linkPeers[it] }
            linkPeers.clear()
            linkPeers += sortedPeers
        }
        // Link-loss watcher: polls connection state (cheap; no radio traffic).
        CoroutineScope(Dispatchers.Default).launch {
            val reported = mutableSetOf<String>()
            while (isActive && connections.isNotEmpty()) {
                delay(1000)
                connections.forEachIndexed { i, c ->
                    if (!c.isConnected && c.address !in reported) {
                        reported += c.address
                        lost += linkPeers.getOrElse(i) { c.address }
                        record("link_lost", mapOf("model" to linkPeers.getOrNull(i)))
                    }
                }
            }
        }
    }

    private suspend fun bulk(): Map<String, Any?> {
        val pipe = pipes.first()
        val size = raw["msg"]?.toInt() ?: 4096
        val block = Random.nextBytes(size)
        val start = SystemClock.elapsedRealtime()
        var sent = 0L
        var messages = 0
        while (SystemClock.elapsedRealtime() - start < windowMs) {
            pipe.send(MessageType.BULK, block)
            sent += size
            messages++
        }
        val elapsed = SystemClock.elapsedRealtime() - start
        pendingAck = {
            val ack = withTimeoutOrNull(60_000) {
                pipe.send(MessageType.BULK_END, ByteWriter().int(0).toByteArray())
                var message = pipe.receive()
                while (message.type != MessageType.BULK_ACK) message = pipe.receive()
                ByteReader(message.payload).let { it.long() to it.long() }
            }
            mapOf("acked_bytes" to ack?.first, "peer_span_us" to ack?.second, "sent_bytes" to sent)
        }
        return mapOf("sent_bytes" to sent, "messages" to messages, "msg_size" to size, "send_ms" to elapsed, "kbps_sent" to sent * 1000.0 / elapsed / 1024)
    }

    private fun CoroutineScope.relayTraffic(): suspend () -> Map<String, Any?> {
        val source = pipes[0]
        val sink = pipes[1]
        val rate = raw["rate"]?.toInt() ?: 1
        val size = raw["size"]?.toInt() ?: 200
        val forwarded = AtomicLong()
        val forwardedBytes = AtomicLong()
        var counting = false
        var inWindow = 0L
        val total = (rate * (windowMs + settleMs + 120_000) / 1000).toInt()
        val forwarder = launch {
            source.send(MessageType.RATE, ByteWriter().int(total).int(size).int(1000 / rate).toByteArray())
            while (isActive) {
                val message = source.receive()
                if (message.type != MessageType.BULK) continue
                sink.send(MessageType.BULK, message.payload)
                forwarded.incrementAndGet()
                forwardedBytes.addAndGet(message.payload.size.toLong())
                if (counting) inWindow++
            }
        }
        return {
            counting = true
            val before = forwarded.get()
            delay(windowMs)
            counting = false
            val count = forwarded.get() - before
            pendingAck = {
                forwarder.cancel()
                val ack = withTimeoutOrNull(30_000) {
                    sink.send(MessageType.BULK_END, ByteWriter().int(0).toByteArray())
                    var message = sink.receive()
                    while (message.type != MessageType.BULK_ACK) message = sink.receive()
                    ByteReader(message.payload).long()
                }
                mapOf("sink_acked_bytes" to ack, "forwarded_total" to forwarded.get(), "forwarded_bytes_total" to forwardedBytes.get())
            }
            mapOf("forwarded_in_window" to count, "rate" to rate, "size" to size, "expected" to rate * windowMs / 1000)
        }
    }

    // ------------------------------------------------------------------ advertising (ADR 0010 layout E)

    private inner class Advertiser(private val speed: String) {
        private val advertiser = adapter.bluetoothLeAdvertiser ?: throw IllegalStateException("no LE advertiser")
        private val rotateMs = (raw["rotate_s"]?.toLong() ?: 300) * 1000
        private var callback: AdvertisingSetCallback? = null

        suspend fun run() {
            var cycle = 0
            try {
                while (true) {
                    start(cycle++)
                    delay(rotateMs)
                    stop()
                }
            } finally {
                stop()
            }
        }

        private suspend fun start(cycle: Int) {
            val meta = Random.nextBytes(8) + MESH_TAG + byteArrayOf(cycle.toByte()) + Random.nextBytes(3)
            val data = AdvertiseData.Builder().addServiceUuid(MESH32_UUID).addServiceUuid(metaUuid(meta)).setIncludeDeviceName(false).build()
            val params = AdvertisingSetParameters.Builder()
                .setLegacyMode(true)
                .setConnectable(true)
                .setScannable(true)
                .setInterval(if (speed == "slow") AdvertisingSetParameters.INTERVAL_HIGH else AdvertisingSetParameters.INTERVAL_LOW)
                .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
                .build()
            val started = CompletableDeferred<Pair<Int, Int>>()
            val cb = object : AdvertisingSetCallback() {
                override fun onAdvertisingSetStarted(advertisingSet: AdvertisingSet?, txPower: Int, status: Int) {
                    started.complete(status to txPower)
                }
            }
            callback = cb
            advertiser.startAdvertisingSet(params, data, null, null, null, cb)
            val result = withTimeoutOrNull(5_000) { started.await() }
            record("adv_start", mapOf("cycle" to cycle, "speed" to speed, "status" to result?.first, "tx_power" to result?.second, "ad_id" to meta.copyOfRange(0, 8).hex()))
        }

        private fun stop() {
            callback?.let { runCatching { advertiser.stopAdvertisingSet(it) } }
            callback = null
        }
    }

    // ------------------------------------------------------------------ scanning (ADR 0008 duty, ADR 0010 filters)

    private inner class DutyScanner {
        private val scanner = adapter.bluetoothLeScanner ?: throw IllegalStateException("no LE scanner")
        private val onMs = raw["scan_on_s"]?.toLong()?.times(1000)
        private val offMs = raw["scan_off_s"]?.toLong()?.times(1000)
        private var bursts = 0
        private var emptyBursts = 0

        private fun settings(): ScanSettings {
            val modeValue = when (raw["scan_mode"] ?: "low_latency") {
                "low_power" -> ScanSettings.SCAN_MODE_LOW_POWER
                "balanced" -> ScanSettings.SCAN_MODE_BALANCED
                else -> ScanSettings.SCAN_MODE_LOW_LATENCY
            }
            val builder = ScanSettings.Builder().setScanMode(modeValue)
            if (adapter.isLeExtendedAdvertisingSupported) builder.setLegacy(false).setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
            return builder.build()
        }

        private val filters = listOf(
            ScanFilter.Builder().setServiceUuid(MESH32_UUID).build(),
            ScanFilter.Builder().setManufacturerData(APPLE_COMPANY_ID, byteArrayOf(1), byteArrayOf(-1)).build(),
        )

        suspend fun run() {
            try {
                while (true) {
                    val before = scanResults.get()
                    val failed = CompletableDeferred<Int>()
                    val callback = object : ScanCallback() {
                        override fun onScanResult(callbackType: Int, result: ScanResult) {
                            scanResults.incrementAndGet()
                        }

                        override fun onBatchScanResults(results: MutableList<ScanResult>) {
                            scanResults.addAndGet(results.size.toLong())
                        }

                        override fun onScanFailed(errorCode: Int) {
                            failed.complete(errorCode)
                        }
                    }
                    val t0 = SystemClock.elapsedRealtime()
                    try {
                        scanner.startScan(filters, settings(), callback)
                        bursts++
                        if (onMs == null) {
                            // Continuous: restart every 4 min only if unfiltered (lessons); filtered scans run as one.
                            failed.await().let { record("scan_failed", mapOf("code" to it)) }
                            return
                        }
                        withTimeoutOrNull(onMs) { failed.await() }?.let { record("scan_failed", mapOf("code" to it)) }
                    } finally {
                        runCatching { scanner.stopScan(callback) }
                    }
                    val onActual = SystemClock.elapsedRealtime() - t0
                    val got = scanResults.get() - before
                    if (got == 0L) emptyBursts++
                    if (bursts <= 20 || bursts % 50 == 0) record("burst", mapOf("n" to bursts, "on_ms" to onActual, "results" to got, "empty_bursts" to emptyBursts))
                    val t1 = SystemClock.elapsedRealtime()
                    delay(offMs ?: 0)
                    if (bursts <= 20 || bursts % 50 == 0) record("gap", mapOf("n" to bursts, "off_ms" to SystemClock.elapsedRealtime() - t1))
                }
            } finally {
                record("scan_summary", mapOf("bursts" to bursts, "empty_bursts" to emptyBursts, "results" to scanResults.get()))
            }
        }
    }
}

private object NullSink : ResultSink {
    override fun emit(jsonLine: String) = Unit
    override fun log(message: String) = Unit
}
