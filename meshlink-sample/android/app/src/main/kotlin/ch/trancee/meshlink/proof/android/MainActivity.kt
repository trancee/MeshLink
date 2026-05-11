package ch.trancee.meshlink.proof.android

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ch.trancee.meshlink.api.InboundMessage
import ch.trancee.meshlink.api.MeshLink
import ch.trancee.meshlink.api.MeshLinkApi
import ch.trancee.meshlink.api.MeshLinkState
import ch.trancee.meshlink.api.PeerEvent
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.api.SendResult
import ch.trancee.meshlink.config.PowerMode
import ch.trancee.meshlink.config.RegulatoryRegion
import ch.trancee.meshlink.config.meshLinkConfig
import ch.trancee.meshlink.diagnostics.DiagnosticEvent
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : Activity() {
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var stateLabel: TextView
    private lateinit var peersLabel: TextView
    private lateinit var logLabel: TextView
    private lateinit var startStopButton: Button
    private lateinit var sendHelloButton: Button

    override fun onCreate(savedInstanceState: Bundle?): Unit {
        super.onCreate(savedInstanceState)

        setContentView(buildContentView())
        MeshLinkProofRuntime.initialize(
            context = applicationContext,
            launchConfig = launchConfigFromIntent(),
        )
        renderSnapshot()

        activityScope.launch {
            MeshLinkProofRuntime.updates.collectLatest {
                renderSnapshot()
            }
        }

        ensurePermissionsAndStart()
    }

    override fun onDestroy(): Unit {
        activityScope.cancel()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ): Unit {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS_CODE) {
            return
        }
        val granted =
            grantResults.isNotEmpty() &&
                grantResults.all { result -> result == PackageManager.PERMISSION_GRANTED }
        if (granted) {
            MeshLinkProofRuntime.appendLog("Bluetooth permissions granted")
            MeshLinkProofRuntime.start()
        } else {
            MeshLinkProofRuntime.appendLog(
                "Bluetooth permissions denied; MeshLink transport will stay idle"
            )
        }
    }

    private fun buildContentView(): ScrollView {
        stateLabel = TextView(this).apply { textSize = 18f }
        peersLabel = TextView(this)
        startStopButton = Button(this).apply {
            setOnClickListener {
                if (MeshLinkProofRuntime.isRunning) {
                    MeshLinkProofRuntime.stop()
                } else {
                    ensurePermissionsAndStart()
                }
            }
        }
        sendHelloButton = Button(this).apply {
            text = "Send Hello"
            setOnClickListener {
                MeshLinkProofRuntime.sendHelloToFirstPeer()
            }
        }
        logLabel = TextView(this).apply { setTextIsSelectable(true) }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            setPadding(32, 32, 32, 32)
            addView(stateLabel)
            addView(peersLabel)
            addView(startStopButton)
            addView(sendHelloButton)
            addView(logLabel)
        }
        return ScrollView(this).apply { addView(container) }
    }

    private fun renderSnapshot(): Unit {
        val snapshot = MeshLinkProofRuntime.snapshot
        runOnUiThread {
            stateLabel.text = "State: ${snapshot.state}"
            peersLabel.text =
                if (snapshot.peers.isEmpty()) {
                    "Peers: none"
                } else {
                    buildString {
                            appendLine("Peers:")
                            snapshot.peers.forEach { peer -> appendLine("- $peer") }
                        }
                        .trimEnd()
                }
            startStopButton.text = if (snapshot.running) "Stop MeshLink" else "Start MeshLink"
            sendHelloButton.isEnabled = snapshot.running && snapshot.peers.isNotEmpty()
            logLabel.text =
                if (snapshot.logs.isEmpty()) {
                    "Logs will appear here"
                } else {
                    snapshot.logs.joinToString(separator = "\n")
                }
        }
    }

    private fun ensurePermissionsAndStart(): Unit {
        val missingPermissions = requiredPermissions().filterNot(::hasPermission)
        if (missingPermissions.isEmpty()) {
            MeshLinkProofRuntime.start()
        } else {
            requestPermissions(missingPermissions.toTypedArray(), REQUEST_PERMISSIONS_CODE)
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun requiredPermissions(): List<String> {
        return if (Build.VERSION.SDK_INT >= 31) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
            )
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun launchConfigFromIntent(): ProofLaunchConfig {
        val currentIntent = intent
        return ProofLaunchConfig(
            appId = currentIntent?.getStringExtra(EXTRA_APP_ID) ?: DEFAULT_APP_ID,
            powerMode = parsePowerMode(currentIntent?.getStringExtra(EXTRA_POWER_MODE)),
            benchmarkPayloadBytes =
                currentIntent?.getIntExtra(EXTRA_BENCHMARK_PAYLOAD_BYTES, 0)?.takeIf { it > 0 },
            benchmarkBatteryLevel =
                currentIntent
                    ?.takeIf { it.hasExtra(EXTRA_BENCHMARK_BATTERY_LEVEL) }
                    ?.getFloatExtra(EXTRA_BENCHMARK_BATTERY_LEVEL, 0f),
            benchmarkIsCharging =
                currentIntent
                    ?.takeIf { it.hasExtra(EXTRA_BENCHMARK_IS_CHARGING) }
                    ?.getBooleanExtra(EXTRA_BENCHMARK_IS_CHARGING, false),
            benchmarkColdStart =
                currentIntent?.getBooleanExtra(EXTRA_BENCHMARK_COLD_START, false) ?: false,
            disableAutoSend =
                currentIntent?.getBooleanExtra(EXTRA_DISABLE_AUTO_SEND, false) ?: false,
        )
    }

    private fun parsePowerMode(rawValue: String?): PowerMode {
        return when (rawValue?.lowercase(Locale.US)) {
            "performance" -> PowerMode.Performance
            "balanced" -> PowerMode.Balanced
            "powersaver" -> PowerMode.PowerSaver
            else -> PowerMode.Automatic
        }
    }

    private companion object {
        private const val REQUEST_PERMISSIONS_CODE: Int = 1001
        private const val DEFAULT_APP_ID: String = "demo.meshlink"
        private const val EXTRA_APP_ID: String = "meshlink.appId"
        private const val EXTRA_POWER_MODE: String = "meshlink.powerMode"
        private const val EXTRA_BENCHMARK_PAYLOAD_BYTES: String = "meshlink.benchmarkPayloadBytes"
        private const val EXTRA_BENCHMARK_BATTERY_LEVEL: String = "meshlink.benchmarkBatteryLevel"
        private const val EXTRA_BENCHMARK_IS_CHARGING: String = "meshlink.benchmarkIsCharging"
        private const val EXTRA_BENCHMARK_COLD_START: String = "meshlink.benchmarkColdStart"
        private const val EXTRA_DISABLE_AUTO_SEND: String = "meshlink.disableAutoSend"
    }
}

private data class ProofLaunchConfig(
    val appId: String,
    val powerMode: PowerMode = PowerMode.Automatic,
    val benchmarkPayloadBytes: Int? = null,
    val benchmarkBatteryLevel: Float? = null,
    val benchmarkIsCharging: Boolean? = null,
    val benchmarkColdStart: Boolean = false,
    val disableAutoSend: Boolean = false,
)

private fun PowerMode.logLabel(): String {
    return when (this) {
        PowerMode.Automatic -> "Automatic"
        PowerMode.Performance -> "Performance"
        PowerMode.Balanced -> "Balanced"
        PowerMode.PowerSaver -> "PowerSaver"
    }
}

private object MeshLinkProofRuntime {
    private const val PROOF_LOG_FILE_NAME: String = "proof.log"
    private const val BENCHMARK_WARMUP_PAYLOAD: String = "benchmark warmup"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val updatesFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 32)
    private val knownPeers: LinkedHashMap<String, PeerId> = linkedMapOf()
    private val autoSendJobs: LinkedHashMap<String, Job> = linkedMapOf()
    private val logLines: ArrayDeque<String> = ArrayDeque()

    private var launchConfig: ProofLaunchConfig = ProofLaunchConfig(appId = "demo.meshlink")
    private var meshLink: MeshLinkApi? = null
    private var currentLaunchConfig: ProofLaunchConfig? = null
    private var localAdvertisementKeyHashHex: String? = null
    private var collectorsStarted: Boolean = false
    private var running: Boolean = false
    private var appContext: Context? = null

    val updates: Flow<Unit> = updatesFlow.asSharedFlow()

    val isRunning: Boolean
        get() = running

    val snapshot: ProofSnapshot
        get() {
            val peers = synchronized(knownPeers) { knownPeers.values.map { peerId -> peerId.value } }
            val logs = synchronized(logLines) { logLines.toList() }
            return ProofSnapshot(
                state = meshLink?.state?.value?.toString() ?: MeshLinkState.Uninitialized.toString(),
                peers = peers,
                logs = logs,
                running = running,
            )
        }

    fun initialize(context: Context, launchConfig: ProofLaunchConfig): Unit {
        synchronized(this) {
            if (appContext == null) {
                appContext = context.applicationContext
            }
            val resolvedLaunchConfig = launchConfig.copy(appId = launchConfig.appId.ifBlank { "demo.meshlink" })
            if (meshLink == null || currentLaunchConfig != resolvedLaunchConfig) {
                this.launchConfig = resolvedLaunchConfig
                meshLink =
                    MeshLink.createAndroid(
                        context = appContext!!,
                        config = meshLinkConfig {
                            appId = resolvedLaunchConfig.appId
                            regulatoryRegion = RegulatoryRegion.DEFAULT
                            powerMode = resolvedLaunchConfig.powerMode
                        },
                    )
                currentLaunchConfig = resolvedLaunchConfig
                collectorsStarted = false
                running = false
                synchronized(knownPeers) { knownPeers.clear() }
                synchronized(autoSendJobs) {
                    autoSendJobs.values.forEach(Job::cancel)
                    autoSendJobs.clear()
                }
                synchronized(logLines) { logLines.clear() }
                localAdvertisementKeyHashHex =
                    computeLocalAdvertisementKeyHashHex(
                        context = appContext!!,
                        appId = resolvedLaunchConfig.appId,
                    )
                clearPersistedLogs()
                appendLog(
                    "MeshLink proof app ready on ${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT}) appId=${resolvedLaunchConfig.appId} powerMode=${resolvedLaunchConfig.powerMode.logLabel()} keyHash=${localAdvertisementKeyHashHex.orEmpty()}",
                )
            }
        }
    }

    fun start(): Job {
        ensureCollectors()
        return scope.launch {
            val startedAtNanos = SystemClock.elapsedRealtimeNanos()
            val result = runCatching { requireMeshLink().start() }
            result.onSuccess { startResult ->
                appendLog("mesh.start() -> $startResult")
                if (launchConfig.benchmarkColdStart) {
                    appendLog(
                        "BENCHMARK coldStart elapsedMs=${elapsedMillisSince(startedAtNanos)} result=$startResult",
                    )
                }
                applyBenchmarkPowerSnapshot()
            }.onFailure { error ->
                appendLog(
                    "mesh.start() failed: ${error.javaClass.simpleName}: ${error.message.orEmpty()}"
                )
                if (launchConfig.benchmarkColdStart) {
                    appendLog(
                        "BENCHMARK coldStart failed=${error.javaClass.simpleName}: ${error.message.orEmpty()}"
                    )
                }
            }
        }
    }

    fun stop(): Job {
        return scope.launch {
            val result = runCatching { requireMeshLink().stop() }
            result.onSuccess { stopResult ->
                appendLog("mesh.stop() -> $stopResult")
            }.onFailure { error ->
                appendLog(
                    "mesh.stop() failed: ${error.javaClass.simpleName}: ${error.message.orEmpty()}"
                )
            }
        }
    }

    fun sendHelloToFirstPeer(): Job {
        val peerId = synchronized(knownPeers) { knownPeers.values.firstOrNull() }
        if (peerId == null) {
            appendLog("No discovered peer is available yet")
            return Job()
        }
        return scope.launch {
            val result = runCatching {
                requireMeshLink().send(peerId, "hello mesh from ${Build.MODEL}".encodeToByteArray())
            }
            result.onSuccess { sendResult ->
                appendLog("mesh.send(${peerId.value.takeLast(6)}) -> $sendResult")
            }.onFailure { error ->
                appendLog(
                    "mesh.send() failed: ${error.javaClass.simpleName}: ${error.message.orEmpty()}"
                )
            }
        }
    }

    fun appendLog(message: String): Unit {
        Log.i(TAG, message)
        val persistedLogs = synchronized(logLines) {
            logLines += message
            while (logLines.size > MAX_LOG_LINES) {
                logLines.removeFirst()
            }
            logLines.joinToString(separator = "\n") + "\n"
        }
        persistLogs(persistedLogs)
        updatesFlow.tryEmit(Unit)
    }

    private fun clearPersistedLogs(): Unit {
        val context = appContext ?: return
        context.deleteFile(PROOF_LOG_FILE_NAME)
    }

    private fun persistLogs(contents: String): Unit {
        val context = appContext ?: return
        runCatching {
            context
                .openFileOutput(PROOF_LOG_FILE_NAME, Context.MODE_PRIVATE)
                .bufferedWriter()
                .use { writer ->
                    writer.write(contents)
                }
        }
    }

    private fun ensureCollectors(): Unit {
        synchronized(this) {
            if (collectorsStarted) {
                return
            }
            collectorsStarted = true
        }
        val mesh = requireMeshLink()

        scope.launch {
            mesh.state.collectLatest { state ->
                running = state == MeshLinkState.Running
                updatesFlow.tryEmit(Unit)
            }
        }
        scope.launch {
            mesh.peerEvents.collectLatest(::handlePeerEvent) }
        scope.launch { mesh.diagnosticEvents.collectLatest(::handleDiagnosticEvent) }
        scope.launch { mesh.messages.collectLatest(::handleInboundMessage) }
    }

    private fun handlePeerEvent(event: PeerEvent): Unit {
        when (event) {
            is PeerEvent.Found -> {
                synchronized(knownPeers) {
                    knownPeers[event.peerId.value] = event.peerId
                }
                appendLog("Peer found: ${event.peerId.value} (${event.state})")
                scheduleAutoHello(event.peerId)
            }
            is PeerEvent.Lost -> {
                synchronized(knownPeers) {
                    knownPeers.remove(event.peerId.value)
                }
                synchronized(autoSendJobs) {
                    autoSendJobs.remove(event.peerId.value)?.cancel()
                }
                appendLog("Peer lost: ${event.peerId.value}")
            }
            is PeerEvent.StateChanged -> {
                appendLog("Peer state changed: ${event.peerId.value} -> ${event.state}")
            }
        }
        updatesFlow.tryEmit(Unit)
    }

    private fun handleDiagnosticEvent(event: DiagnosticEvent): Unit {
        val metadataSuffix =
            if (event.metadata.isEmpty()) {
                ""
            } else {
                event.metadata.entries
                    .sortedBy { (key, _) -> key }
                    .joinToString(prefix = " metadata={", postfix = "}") { (key, value) ->
                        "$key=$value"
                    }
            }
        appendLog("DIAG ${event.code} stage=${event.stage} reason=${event.reason}$metadataSuffix")
    }

    private fun handleInboundMessage(message: InboundMessage): Unit {
        appendLog(
            "MSG from ${message.originPeerId.value} bytes=${message.payload.size} text=${message.payload.decodeToString()}",
        )
    }

    private fun scheduleAutoHello(peerId: PeerId): Unit {
        synchronized(autoSendJobs) {
            if (autoSendJobs.containsKey(peerId.value)) {
                return
            }
            if (launchConfig.disableAutoSend) {
                appendLog(
                    "auto-send skipped for ${peerId.value.takeLast(6)} because passive benchmark mode is enabled"
                )
                return
            }
            if (launchConfig.benchmarkPayloadBytes == null && !shouldInitiateHello(peerId)) {
                appendLog(
                    "auto-send skipped for ${peerId.value.takeLast(6)} because this device is not the initiator"
                )
                return
            }
            autoSendJobs[peerId.value] =
                scope.launch {
                    if (launchConfig.benchmarkPayloadBytes != null) {
                        val warmupResult =
                            runCatching {
                                requireMeshLink().send(
                                    peerId,
                                    BENCHMARK_WARMUP_PAYLOAD.encodeToByteArray(),
                                )
                            }
                        warmupResult
                            .onSuccess { sendResult ->
                                appendLog("BENCHMARK transport warmup=$sendResult")
                            }
                            .onFailure { error ->
                                appendLog(
                                    "BENCHMARK transport warmupFailed=${error.javaClass.simpleName}: ${error.message.orEmpty()}"
                                )
                            }
                        delay(BENCHMARK_WARMUP_DELAY_MS)
                    }
                    repeat(AUTO_SEND_ATTEMPTS) { attemptIndex ->
                        delay(AUTO_SEND_RETRY_DELAY_MS)
                        val peerStillKnown =
                            synchronized(knownPeers) { knownPeers.containsKey(peerId.value) }
                        if (!peerStillKnown) {
                            return@launch
                        }
                        val payload = buildAutoSendPayload()
                        val startedAtNanos = SystemClock.elapsedRealtimeNanos()
                        val result = runCatching { requireMeshLink().send(peerId, payload) }
                        result
                            .onSuccess { sendResult ->
                                appendLog(
                                    "auto-send attempt ${attemptIndex + 1} -> $sendResult for ${peerId.value.takeLast(6)}"
                                )
                                if (launchConfig.benchmarkPayloadBytes != null) {
                                    val elapsedMs = elapsedMillisSince(startedAtNanos)
                                    appendLog(
                                        "BENCHMARK transport bytes=${payload.size} elapsedMs=$elapsedMs throughputKBps=${formatThroughputKilobytesPerSecond(payload.size, elapsedMs)} result=$sendResult"
                                    )
                                }
                                if (sendResult is SendResult.Sent) {
                                    return@launch
                                }
                            }
                            .onFailure { error ->
                                appendLog(
                                    "auto-send attempt ${attemptIndex + 1} failed: ${error.javaClass.simpleName}: ${error.message.orEmpty()}"
                                )
                            }
                    }
                }
        }
    }

    private fun requireMeshLink(): MeshLinkApi {
        return meshLink ?: error("MeshLinkProofRuntime.initialize must be called first")
    }

    private fun applyBenchmarkPowerSnapshot(): Unit {
        val level = launchConfig.benchmarkBatteryLevel ?: return
        val isCharging = launchConfig.benchmarkIsCharging ?: return
        requireMeshLink().updateBattery(level = level, isCharging = isCharging)
        appendLog(
            "BENCHMARK power batteryLevel=$level isCharging=$isCharging powerMode=${launchConfig.powerMode.logLabel()}"
        )
    }

    private fun buildAutoSendPayload(): ByteArray {
        val benchmarkBytes = launchConfig.benchmarkPayloadBytes
        if (benchmarkBytes != null) {
            return ByteArray(benchmarkBytes) { index -> ((index * 31) and 0xFF).toByte() }
        }
        return "hello mesh from ${Build.MODEL}".encodeToByteArray()
    }

    private fun shouldInitiateHello(peerId: PeerId): Boolean {
        val localKeyHash = localAdvertisementKeyHashHex ?: return false
        return localKeyHash < peerId.value
    }

    private fun computeLocalAdvertisementKeyHashHex(context: Context, appId: String): String {
        val preferences = context.getSharedPreferences("meshlink-$appId", Context.MODE_PRIVATE)
        val ed25519 =
            preferences
                .getString("identity:$appId:ed25519-public", null)
                ?.let { encoded -> Base64.decode(encoded, Base64.NO_WRAP) } ?: return ""
        val x25519 =
            preferences
                .getString("identity:$appId:x25519-public", null)
                ?.let { encoded -> Base64.decode(encoded, Base64.NO_WRAP) } ?: return ""
        val hash = MessageDigest.getInstance("SHA-256").digest(ed25519 + x25519)
        return hash.copyOfRange(0, 12).joinToString(separator = "") { byte ->
            (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
    }

    private fun elapsedMillisSince(startedAtNanos: Long): Long {
        return (SystemClock.elapsedRealtimeNanos() - startedAtNanos) / 1_000_000L
    }

    private fun formatThroughputKilobytesPerSecond(bytes: Int, elapsedMs: Long): String {
        if (elapsedMs <= 0L) {
            return "0.00"
        }
        val kibPerSecond = (bytes.toDouble() / 1024.0) / (elapsedMs.toDouble() / 1000.0)
        return String.format(Locale.US, "%.2f", kibPerSecond)
    }

    private const val AUTO_SEND_ATTEMPTS: Int = 6
    private const val AUTO_SEND_RETRY_DELAY_MS: Long = 2_000
    private const val BENCHMARK_WARMUP_DELAY_MS: Long = 500L
    private const val MAX_LOG_LINES: Int = 64
    private const val TAG = "MeshLinkProof"
}

private class ProofSnapshot(
    val state: String,
    val peers: List<String>,
    val logs: List<String>,
    val running: Boolean,
)
