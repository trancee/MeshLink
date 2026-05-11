package ch.trancee.meshlink.proof.android

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import kotlinx.coroutines.CoroutineScope
import java.security.MessageDigest
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
        MeshLinkProofRuntime.initialize(applicationContext)
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
        val granted = grantResults.isNotEmpty() && grantResults.all { result -> result == PackageManager.PERMISSION_GRANTED }
        if (granted) {
            MeshLinkProofRuntime.appendLog("Bluetooth permissions granted")
            MeshLinkProofRuntime.start()
        } else {
            MeshLinkProofRuntime.appendLog("Bluetooth permissions denied; MeshLink transport will stay idle")
        }
    }

    private fun buildContentView(): ScrollView {
        stateLabel = TextView(this).apply {
            textSize = 18f
        }
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
        logLabel = TextView(this).apply {
            setTextIsSelectable(true)
        }

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
        return ScrollView(this).apply {
            addView(container)
        }
    }

    private fun renderSnapshot(): Unit {
        val snapshot = MeshLinkProofRuntime.snapshot
        runOnUiThread {
            stateLabel.text = "State: ${snapshot.state}"
            peersLabel.text = if (snapshot.peers.isEmpty()) {
                "Peers: none"
            } else {
                buildString {
                    appendLine("Peers:")
                    snapshot.peers.forEach { peer -> appendLine("- $peer") }
                }.trimEnd()
            }
            startStopButton.text = if (snapshot.running) "Stop MeshLink" else "Start MeshLink"
            sendHelloButton.isEnabled = snapshot.running && snapshot.peers.isNotEmpty()
            logLabel.text = if (snapshot.logs.isEmpty()) "Logs will appear here" else snapshot.logs.joinToString(separator = "\n")
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

    private companion object {
        private const val REQUEST_PERMISSIONS_CODE: Int = 1001
    }
}

private object MeshLinkProofRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val updatesFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 32)
    private val knownPeers: LinkedHashMap<String, PeerId> = linkedMapOf()
    private val autoSendJobs: LinkedHashMap<String, Job> = linkedMapOf()
    private val logLines: ArrayDeque<String> = ArrayDeque()

    private var meshLink: MeshLinkApi? = null
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

    fun initialize(context: Context): Unit {
        synchronized(this) {
            if (appContext == null) {
                appContext = context.applicationContext
            }
            if (meshLink == null) {
                meshLink = MeshLink.createAndroid(
                    context = appContext!!,
                    config = meshLinkConfig {
                        appId = "demo.meshlink"
                        regulatoryRegion = RegulatoryRegion.DEFAULT
                        powerMode = PowerMode.Automatic
                    },
                )
                localAdvertisementKeyHashHex = computeLocalAdvertisementKeyHashHex(appContext!!)
                appendLog(
                    "MeshLink proof app ready on ${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT}) keyHash=${localAdvertisementKeyHashHex.orEmpty()}",
                )
            }
        }
    }

    fun start(): Job {
        ensureCollectors()
        return scope.launch {
            val result = runCatching { requireMeshLink().start() }
            result.onSuccess { startResult ->
                appendLog("mesh.start() -> $startResult")
            }.onFailure { error ->
                appendLog("mesh.start() failed: ${error.javaClass.simpleName}: ${error.message.orEmpty()}")
            }
        }
    }

    fun stop(): Job {
        return scope.launch {
            val result = runCatching { requireMeshLink().stop() }
            result.onSuccess { stopResult ->
                appendLog("mesh.stop() -> $stopResult")
            }.onFailure { error ->
                appendLog("mesh.stop() failed: ${error.javaClass.simpleName}: ${error.message.orEmpty()}")
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
                appendLog("mesh.send() failed: ${error.javaClass.simpleName}: ${error.message.orEmpty()}")
            }
        }
    }

    fun appendLog(message: String): Unit {
        Log.i(TAG, message)
        synchronized(logLines) {
            logLines += message
            while (logLines.size > MAX_LOG_LINES) {
                logLines.removeFirst()
            }
        }
        updatesFlow.tryEmit(Unit)
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
            mesh.peerEvents.collectLatest(::handlePeerEvent)
        }
        scope.launch {
            mesh.diagnosticEvents.collectLatest(::handleDiagnosticEvent)
        }
        scope.launch {
            mesh.messages.collectLatest(::handleInboundMessage)
        }
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
        appendLog("DIAG ${event.code} stage=${event.stage} reason=${event.reason}")
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
            if (!shouldInitiateHello(peerId)) {
                appendLog("auto-send skipped for ${peerId.value.takeLast(6)} because this device is not the initiator")
                return
            }
            autoSendJobs[peerId.value] = scope.launch {
                repeat(AUTO_SEND_ATTEMPTS) { attemptIndex ->
                    delay(AUTO_SEND_RETRY_DELAY_MS)
                    val peerStillKnown = synchronized(knownPeers) { knownPeers.containsKey(peerId.value) }
                    if (!peerStillKnown) {
                        return@launch
                    }
                    val result = runCatching {
                        requireMeshLink().send(
                            peerId,
                            "hello mesh from ${Build.MODEL}".encodeToByteArray(),
                        )
                    }
                    result.onSuccess { sendResult ->
                        appendLog("auto-send attempt ${attemptIndex + 1} -> $sendResult for ${peerId.value.takeLast(6)}")
                        if (sendResult is SendResult.Sent) {
                            return@launch
                        }
                    }.onFailure { error ->
                        appendLog(
                            "auto-send attempt ${attemptIndex + 1} failed: ${error.javaClass.simpleName}: ${error.message.orEmpty()}",
                        )
                    }
                }
            }
        }
    }

    private fun requireMeshLink(): MeshLinkApi {
        return meshLink ?: error("MeshLinkProofRuntime.initialize must be called first")
    }

    private fun shouldInitiateHello(peerId: PeerId): Boolean {
        val localKeyHash = localAdvertisementKeyHashHex ?: return false
        return localKeyHash < peerId.value
    }

    private fun computeLocalAdvertisementKeyHashHex(context: Context): String {
        val preferences = context.getSharedPreferences("meshlink-demo.meshlink", Context.MODE_PRIVATE)
        val ed25519 = preferences.getString("identity:demo.meshlink:ed25519-public", null)
            ?.let { encoded -> Base64.decode(encoded, Base64.NO_WRAP) }
            ?: return ""
        val x25519 = preferences.getString("identity:demo.meshlink:x25519-public", null)
            ?.let { encoded -> Base64.decode(encoded, Base64.NO_WRAP) }
            ?: return ""
        val hash = MessageDigest.getInstance("SHA-256").digest(ed25519 + x25519)
        return hash.copyOfRange(0, 12).joinToString(separator = "") { byte ->
            (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
    }

    private const val AUTO_SEND_ATTEMPTS: Int = 6
    private const val AUTO_SEND_RETRY_DELAY_MS: Long = 2_000
    private const val MAX_LOG_LINES: Int = 64
    private const val TAG = "MeshLinkProof"
}

private class ProofSnapshot(
    val state: String,
    val peers: List<String>,
    val logs: List<String>,
    val running: Boolean,
)
