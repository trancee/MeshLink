package bakeoff.ios

import bakeoff.BakeoffUuids
import bakeoff.Json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import platform.CoreBluetooth.CBAdvertisementDataIsConnectable
import platform.CoreBluetooth.CBAdvertisementDataLocalNameKey
import platform.CoreBluetooth.CBAdvertisementDataManufacturerDataKey
import platform.CoreBluetooth.CBAdvertisementDataOverflowServiceUUIDsKey
import platform.CoreBluetooth.CBAdvertisementDataServiceDataKey
import platform.CoreBluetooth.CBAdvertisementDataServiceUUIDsKey
import platform.CoreBluetooth.CBAdvertisementDataTxPowerLevelKey
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCentralManagerFeatureExtendedScanAndConnect
import platform.CoreBluetooth.CBCentralManagerScanOptionAllowDuplicatesKey
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralManager
import platform.CoreBluetooth.CBPeripheralManagerDelegateProtocol
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSSelectorFromString
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationState
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import platform.UIKit.UIApplicationWillResignActiveNotification
import platform.UIKit.UIBackgroundTaskInvalid
import platform.UIKit.UIControl
import platform.UIKit.UIDevice
import platform.darwin.NSObject

/**
 * PROTOTYPE discovery measurements (ticket 13) on iOS. Arguments: `disc=adv|scan|caps run=... tag=... duration=...`;
 * `bg=1` sends the app to the background (private suspend selector, lab only) before advertising or right after
 * starting the scan.
 */
class IosDiscovery(private val raw: Map<String, String>, private val sink: FileLogSink, private val onStatus: (String) -> Unit) {
    private val tag = raw["tag"] ?: "ios"
    private val duration = (raw["duration"]?.toLong() ?: 600) * 1000

    private fun now(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

    fun record(test: String, extra: Map<String, Any?> = emptyMap()) {
        sink.emit(Json.encode(linkedMapOf<String, Any?>("t" to now(), "test" to test).apply { putAll(extra) }))
    }

    private fun appState(): String = when (UIApplication.sharedApplication.applicationState) {
        UIApplicationState.UIApplicationStateActive -> "active"
        UIApplicationState.UIApplicationStateBackground -> "background"
        else -> "inactive"
    }

    /** Called by the Swift shell for Darwin notifications (lock state, screen blanking). */
    fun systemEvent(name: String, state: Long) {
        record("system_event", mapOf("event" to name, "state" to state, "app" to appState()))
    }

    private val observers = mutableListOf<Any>()

    private fun observeLifecycle(onBackground: () -> Unit) {
        val center = NSNotificationCenter.defaultCenter
        listOf(
            UIApplicationWillResignActiveNotification to "will_resign_active",
            UIApplicationDidEnterBackgroundNotification to "did_enter_background",
            UIApplicationWillEnterForegroundNotification to "will_enter_foreground",
            UIApplicationDidBecomeActiveNotification to "did_become_active",
        ).forEach { (name, label) ->
            observers += center.addObserverForName(name, null, null) { _ ->
                record("system_event", mapOf("event" to label, "app" to appState()))
                if (label == "did_enter_background") onBackground()
            }
        }
    }

    private var backgroundTask = UIBackgroundTaskInvalid

    private fun beginBackgroundTask() {
        if (backgroundTask != UIBackgroundTaskInvalid) return
        backgroundTask = UIApplication.sharedApplication.beginBackgroundTaskWithExpirationHandler {
            record("system_event", mapOf("event" to "background_task_expired"))
            UIApplication.sharedApplication.endBackgroundTask(backgroundTask)
            backgroundTask = UIBackgroundTaskInvalid
        }
    }

    /** Lab-only: asks UIKit to suspend (same as pressing Home). Not for library code. */
    private fun suspendApp() {
        record("suspend_request", mapOf("app" to appState()))
        UIControl().sendAction(NSSelectorFromString("suspend"), to = UIApplication.sharedApplication, forEvent = null)
    }

    private suspend fun waitBackground(): Boolean {
        repeat(100) {
            if (UIApplication.sharedApplication.applicationState == UIApplicationState.UIApplicationStateBackground) return true
            delay(100)
        }
        return false
    }

    fun start(scope: CoroutineScope) {
        observeLifecycle { scanner?.flushAll() }
        record(
            "disc_start",
            linkedMapOf(
                "run_id" to raw["run"], "commit" to raw["commit"], "tag" to tag, "mode" to raw["disc"], "args" to raw,
                "reporter" to mapOf("model" to raw["model"], "os" to "iOS ${UIDevice.currentDevice.systemVersion}", "platform" to "ios"),
                "caps" to mapOf("extended_scan_and_connect" to CBCentralManager.supportsFeatures(CBCentralManagerFeatureExtendedScanAndConnect)),
                "app" to appState(),
            ),
        )
        scope.launch {
            val outcome = runCatching {
                when (raw["disc"]) {
                    "adv" -> advertise()
                    "scan" -> scan()
                    "caps" -> Unit
                    else -> throw IllegalArgumentException("unknown disc=${raw["disc"]}")
                }
            }
            scanner?.flushAll()
            record("done", mapOf("outcome" to if (outcome.isSuccess) "ok" else "fail", "error" to outcome.exceptionOrNull()?.toString()))
            onStatus("discovery done ${outcome.exceptionOrNull() ?: ""}")
        }
    }

    // ------------------------------------------------------------------ advertiser

    private var peripheralOn = CompletableDeferred<Unit>()
    private var advStarted = CompletableDeferred<String?>()

    private val peripheralDelegate = object : NSObject(), CBPeripheralManagerDelegateProtocol {
        override fun peripheralManagerDidUpdateState(peripheral: CBPeripheralManager) {
            record("peripheral_state", mapOf("state" to peripheral.state))
            if (peripheral.state == CBManagerStatePoweredOn) peripheralOn.complete(Unit)
        }

        override fun peripheralManagerDidStartAdvertising(peripheral: CBPeripheralManager, error: NSError?) {
            advStarted.complete(error?.describe())
        }
    }

    private var peripheralManager: CBPeripheralManager? = null

    private fun advertisement(cycle: Int): Map<Any?, Any?> {
        // Layout A: UUID only (name=none, the default). name=tag gives "ML-<tag>" for attribution; any other value is literal.
        val kind = raw["restart_kind"] ?: "stop"
        val nameArg = raw["name"] ?: "none"
        val base = if (nameArg == "tag") "ML-$tag" else nameArg
        val name = when {
            kind == "name" -> "ML-$tag${cycle % 10}"
            nameArg == "none" -> null
            else -> base
        }
        val uuids = mutableListOf(cbuuid(BakeoffUuids.SERVICE))
        if (raw["uuids"] == "2" || (kind == "uuids" && cycle % 2 == 1)) uuids += cbuuid("6e4b00ff-8f3c-4d2a-9b61-2c7a5d3e9f10")
        val data = mutableMapOf<Any?, Any?>(CBAdvertisementDataServiceUUIDsKey to uuids)
        if (name != null) data[CBAdvertisementDataLocalNameKey] = name
        return data
    }

    private suspend fun startAdvertising(cycle: Int) {
        val manager = peripheralManager!!
        advStarted = CompletableDeferred()
        val data = advertisement(cycle)
        val tCall = now()
        manager.startAdvertising(data)
        val error = withTimeoutOrNull(10_000) { advStarted.await() }
        record(
            "adv_start",
            mapOf("cycle" to cycle, "t_call" to tCall, "error" to error, "name" to data[CBAdvertisementDataLocalNameKey], "uuids" to (data[CBAdvertisementDataServiceUUIDsKey] as List<*>).size, "app" to appState()),
        )
    }

    private suspend fun advertise() {
        val manager = CBPeripheralManager(delegate = peripheralDelegate, queue = null)
        peripheralManager = manager
        withTimeoutOrNull(30_000) { peripheralOn.await() } ?: throw IllegalStateException("peripheral not powered on")
        if (raw["bg"] == "1") {
            beginBackgroundTask()
            suspendApp()
            record("background", mapOf("reached" to waitBackground(), "app" to appState()))
        }
        val restartEvery = raw["restart_every"]?.toLong()?.times(1000)
        val restartOff = ((raw["restart_off"]?.toDouble() ?: 2.0) * 1000).toLong()
        val kind = raw["restart_kind"] ?: "stop"
        val cycles = raw["cycles"]?.toInt() ?: Int.MAX_VALUE
        onStatus("advertising ML-$tag ${raw["run"]}")
        val started = now()
        var cycle = 0
        startAdvertising(cycle)
        try {
            while (now() - started < duration) {
                if (restartEvery != null && cycle < cycles) {
                    delay(restartEvery)
                    cycle++
                    manager.stopAdvertising()
                    record("adv_stop", mapOf("cycle" to cycle - 1, "kind" to kind))
                    if (kind == "stop") delay(restartOff)
                    startAdvertising(cycle)
                } else {
                    delay(1000)
                }
            }
        } finally {
            manager.stopAdvertising()
            record("adv_stop", mapOf("why" to "end"))
        }
    }

    // ------------------------------------------------------------------ scanner

    private var scanner: Scanner? = null

    private inner class Scanner {
        val buckets = linkedMapOf<Pair<String, Long>, MutableMap<String, Any?>>()
        val lastAdv = HashMap<String, String>()
        var results = 0L

        fun flushOld() {
            val cutoff = now() / 1000 - 1
            val ready = buckets.keys.filter { it.second < cutoff }
            ready.forEach { key -> sink.emit(Json.encode(buckets.remove(key))) }
        }

        fun flushAll() {
            buckets.values.forEach { sink.emit(Json.encode(it)) }
            buckets.clear()
        }

        fun handle(peripheral: CBPeripheral, data: Map<Any?, *>, rssi: NSNumber) {
            results++
            val t = now()
            val identifier = peripheral.identifier.UUIDString
            val name = data[CBAdvertisementDataLocalNameKey] as? String
            fun uuids(key: Any?) = (data[key] as? List<*>)?.mapNotNull { (it as? CBUUID)?.UUIDString }
            val services = uuids(CBAdvertisementDataServiceUUIDsKey)
            val overflow = uuids(CBAdvertisementDataOverflowServiceUUIDsKey)
            val manufacturer = (data[CBAdvertisementDataManufacturerDataKey] as? NSData)?.toByteArray()
            val serviceData = (data[CBAdvertisementDataServiceDataKey] as? Map<*, *>)?.entries?.associate {
                (it.key as? CBUUID)?.UUIDString.toString() to ((it.value as? NSData)?.toByteArray()?.hex() ?: "")
            }
            val ours = (services.orEmpty() + overflow.orEmpty()).any { it.equals(BakeoffUuids.SERVICE, ignoreCase = true) }
            val hint = serviceData?.entries?.firstOrNull { it.key.equals(BakeoffUuids.SERVICE, ignoreCase = true) }?.value
            val id = when {
                hint != null && hint.length >= 26 -> "a:${hint.substring(0, 16)}"
                manufacturer != null && manufacturer.size >= 18 && manufacturer[0] == 0xff.toByte() && manufacturer[1] == 0xff.toByte() &&
                    manufacturer[2] == 'M'.code.toByte() && manufacturer[3] == 'L'.code.toByte() ->
                    "ml:" + manufacturer.copyOfRange(6, 12).decodeToString().trimEnd('\u0000')
                name?.startsWith("ML-") == true -> "name:$name"
                ours -> "svc"
                else -> null
            }
            if (id == null && raw["log"] != "all") return
            val connectable = (data[CBAdvertisementDataIsConnectable] as? NSNumber)?.boolValue
            val signature = "$name|$services|$overflow|${manufacturer?.hex()}|$serviceData|$connectable"
            if (lastAdv[identifier] != signature) {
                lastAdv[identifier] = signature
                record(
                    "adv",
                    linkedMapOf(
                        "a" to identifier, "id" to id, "rssi" to rssi.intValue, "name" to name, "svc" to services, "ovf" to overflow,
                        "mfg" to manufacturer?.hex(), "sd" to serviceData, "conn" to connectable,
                        "txp" to (data[CBAdvertisementDataTxPowerLevelKey] as? NSNumber)?.intValue, "app" to appState(),
                        "step" to manufacturer?.takeIf { id?.startsWith("ml:") == true }?.get(4)?.toInt(),
                        "cycle" to manufacturer?.takeIf { id?.startsWith("ml:") == true }?.get(5)?.toInt()?.and(0xff),
                    ),
                )
            }
            val key = identifier to t / 1000
            val bucket = buckets.getOrPut(key) { linkedMapOf("t" to t, "test" to "s", "a" to identifier, "id" to id, "n" to 0, "rssi" to rssi.intValue) }
            bucket["n"] = (bucket["n"] as Int) + 1
            if (rssi.intValue > bucket["rssi"] as Int) bucket["rssi"] = rssi.intValue
            if (UIApplication.sharedApplication.applicationState == UIApplicationState.UIApplicationStateActive) flushOld() else flushAll()
        }
    }

    private var centralOn = CompletableDeferred<Unit>()

    private val centralDelegate = object : NSObject(), CBCentralManagerDelegateProtocol {
        override fun centralManagerDidUpdateState(central: CBCentralManager) {
            record("central_state", mapOf("state" to central.state))
            if (central.state == CBManagerStatePoweredOn) centralOn.complete(Unit)
        }

        override fun centralManager(central: CBCentralManager, didDiscoverPeripheral: CBPeripheral, advertisementData: Map<Any?, *>, RSSI: NSNumber) {
            scanner?.handle(didDiscoverPeripheral, advertisementData, RSSI)
            maybeRestart(raw["rescan_s"]?.toLong()?.times(1000))
        }
    }

    private var restartScan: (() -> Unit)? = null
    private var lastRestart = 0L

    /** T11: restarts the scan every rescan_s (timer in the foreground; from discovery callbacks once suspended). */
    private fun maybeRestart(rescanMs: Long?) {
        if (rescanMs == null || now() - lastRestart < rescanMs) return
        lastRestart = now()
        restartScan?.invoke()
    }

    private suspend fun scan() {
        val manager = CBCentralManager(delegate = centralDelegate, queue = null)
        withTimeoutOrNull(30_000) { centralOn.await() } ?: throw IllegalStateException("central not powered on")
        val current = Scanner()
        scanner = current
        val filtered = (raw["filter"] ?: "service") == "service"
        val duplicates = raw["dups"] != "0"
        val rescanMs = raw["rescan_s"]?.toLong()?.times(1000)
        var restarts = 0
        restartScan = {
            manager.stopScan()
            manager.scanForPeripheralsWithServices(
                if (filtered) listOf(cbuuid(BakeoffUuids.SERVICE)) else null,
                options = mapOf<Any?, Any?>(CBCentralManagerScanOptionAllowDuplicatesKey to duplicates),
            )
            record(if (restarts == 0) "scan_start" else "scan_restart", mapOf("n" to restarts, "filtered" to filtered, "dups" to duplicates, "app" to appState()))
            restarts++
        }
        lastRestart = now()
        restartScan!!.invoke()
        onStatus("scanning ${raw["run"]} filtered=$filtered dups=$duplicates")
        if (raw["bg"] == "1") {
            beginBackgroundTask()
            delay(500)
            suspendApp()
            record("background", mapOf("reached" to waitBackground(), "app" to appState()))
        }
        val started = now()
        var lastStats = started
        try {
            while (now() - started < duration) {
                delay(1000)
                current.flushOld()
                maybeRestart(rescanMs)
                if (now() - lastStats >= 60_000) {
                    lastStats = now()
                    record("scan_stats", mapOf("results" to current.results, "app" to appState()))
                }
            }
        } finally {
            manager.stopScan()
            current.flushAll()
            record("scan_stats", mapOf("results" to current.results, "app" to appState()))
            record("scan_stop")
        }
    }
}

fun ByteArray.hex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
