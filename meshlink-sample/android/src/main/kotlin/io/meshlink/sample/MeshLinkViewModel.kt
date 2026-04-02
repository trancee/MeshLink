package io.meshlink.sample

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.meshlink.MeshLink
import io.meshlink.config.MeshLinkConfig
import io.meshlink.crypto.CryptoProvider
import io.meshlink.diagnostics.DiagnosticEvent
import io.meshlink.diagnostics.MeshHealthSnapshot
import io.meshlink.diagnostics.Severity
import io.meshlink.model.PeerDetail
import io.meshlink.model.PeerEvent
import io.meshlink.transport.AndroidBleTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Lightweight descriptor of a discovered peer for the mesh visualizer. */
data class PeerInfo(
    val id: String,
    val rssi: Int,
    val lastSeen: Long,
    val firstSeen: Long,
)

class MeshLinkViewModel(application: Application) : AndroidViewModel(application) {

    private val transport = AndroidBleTransport(application)

    private var _currentConfig = MutableStateFlow(MeshLinkConfig.smallPayloadLowLatency { diagnosticsEnabled = true })
    /** Current [MeshLinkConfig] — observable for the Settings screen. */
    val currentConfig: StateFlow<MeshLinkConfig> = _currentConfig.asStateFlow()

    private val _currentPreset = MutableStateFlow(ConfigPreset.CHAT)
    /** Which config preset is active. */
    val currentPreset: StateFlow<ConfigPreset> = _currentPreset.asStateFlow()

    private val crypto = CryptoProvider()

    private var meshLink = MeshLink(transport, _currentConfig.value, crypto = crypto)

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _health = MutableStateFlow(meshLink.meshHealth())
    val health: StateFlow<MeshHealthSnapshot> = _health.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _startTime = MutableStateFlow(0L)
    /** Epoch millis when the mesh was last started (0 = never). */
    val startTime: StateFlow<Long> = _startTime.asStateFlow()

    private val _discoveredPeers = MutableStateFlow<List<PeerInfo>>(emptyList())
    /** Live list of discovered peers for the mesh visualizer. */
    val discoveredPeers: StateFlow<List<PeerInfo>> = _discoveredPeers.asStateFlow()

    private val _diagnosticEvents = MutableStateFlow<List<DiagnosticEvent>>(emptyList())
    /** Rolling list of diagnostic events for the diagnostics screen. */
    val diagnosticEvents: StateFlow<List<DiagnosticEvent>> = _diagnosticEvents.asStateFlow()

    private val _severityFilter = MutableStateFlow<Severity?>(null)
    /** Current severity filter (null = show all). */
    val severityFilter: StateFlow<Severity?> = _severityFilter.asStateFlow()

    private val _selectedPeerId = MutableStateFlow<String?>(null)
    /** Currently selected peer ID in the mesh visualizer (null = none). */
    val selectedPeerId: StateFlow<String?> = _selectedPeerId.asStateFlow()

    /** Returns core library detail for the given peer, or null if unknown. */
    fun peerDetail(peerIdHex: String): PeerDetail? = meshLink.peerDetail(peerIdHex)

    fun selectPeer(peerId: String?) {
        _selectedPeerId.value = peerId
    }

    init {
        // Enable BLE debug logging for debug builds
        AndroidBleTransport.debugLogging = true

        log("🔧 Transport: AndroidBleTransport (peer: ${transport.localPeerId.toHex()})")
        collectFlows()
    }

    private fun collectFlows() {
        viewModelScope.launch {
            meshLink.messages.collect { message ->
                log("📨 Message from ${message.senderId.toHex()}: ${message.payload.decodeToString()}")
            }
        }
        viewModelScope.launch {
            meshLink.peers.collect { event ->
                when (event) {
                    is PeerEvent.Found -> {
                        val hexId = event.peerId.toHex()
                        log("🔵 Peer discovered: $hexId")
                        _discoveredPeers.update { peers ->
                            // Simulated RSSI — a real transport would supply the actual value.
                            val rssi = (-90..-40).random()
                            val now = System.currentTimeMillis()
                            val existing = peers.find { it.id == hexId }
                            peers.filterNot { it.id == hexId } + PeerInfo(
                                hexId,
                                rssi,
                                lastSeen = now,
                                firstSeen = existing?.firstSeen ?: now,
                            )
                        }
                    }
                    is PeerEvent.Lost -> {
                        val hexId = event.peerId.toHex()
                        log("🔴 Peer lost: $hexId")
                        _discoveredPeers.update { peers -> peers.filterNot { it.id == hexId } }
                    }
                }
            }
        }
        viewModelScope.launch {
            meshLink.meshHealthFlow.collect { snapshot ->
                _health.value = snapshot
                log("💓 Health update: peers=${snapshot.connectedPeers}, mode=${snapshot.powerMode.name}")
            }
        }
        viewModelScope.launch {
            meshLink.deliveryConfirmations.collect { uuid ->
                log("✅ Delivered: $uuid")
            }
        }
        viewModelScope.launch {
            meshLink.transferFailures.collect { failure ->
                log("❌ Transfer failed: ${failure.messageId}")
            }
        }
        viewModelScope.launch {
            meshLink.diagnosticEvents.collect { event ->
                _diagnosticEvents.update { events ->
                    (events + event).takeLast(500)
                }
            }
        }
    }

    fun startMesh() {
        meshLink.start()
            .onSuccess {
                _isRunning.value = true
                _startTime.value = System.currentTimeMillis()
                log("🟢 Mesh started")
            }
            .onFailure { e ->
                log("❌ Start failed: ${e.message}")
            }
    }

    fun stopMesh() {
        meshLink.stop()
        _isRunning.value = false
        _startTime.value = 0L
        _discoveredPeers.value = emptyList()
        _health.value = meshLink.meshHealth()
        log("🔴 Mesh stopped")
    }

    fun sendMessage(recipientId: String, message: String) {
        val recipientBytes = recipientId.hexToByteArray()
        val payloadBytes = message.encodeToByteArray()
        meshLink.send(recipientBytes, payloadBytes)
            .onSuccess { uuid ->
                log("📤 Sent (id=$uuid) to ${recipientId.take(12)}…")
            }
            .onFailure { e ->
                log("❌ Send failed: ${e.message}")
            }
    }

    fun broadcastMessage(message: String) {
        val payloadBytes = message.encodeToByteArray()
        meshLink.broadcast(payloadBytes, maxHops = _currentConfig.value.broadcastTTL)
            .onSuccess { uuid ->
                log("📡 Broadcast (id=$uuid): $message")
            }
            .onFailure { e ->
                log("❌ Broadcast failed: ${e.message}")
            }
    }

    /** Switch to a different config preset. Restarts the mesh if it was running. */
    fun applyPreset(preset: String) {
        val wasRunning = _isRunning.value
        if (wasRunning) meshLink.stop()

        val newConfig = when (preset) {
            "largePayloadHighThroughput" -> {
                _currentPreset.value = ConfigPreset.FILE_TRANSFER
                MeshLinkConfig.largePayloadHighThroughput { diagnosticsEnabled = true }
            }
            "minimalResourceUsage" -> {
                _currentPreset.value = ConfigPreset.POWER
                MeshLinkConfig.minimalResourceUsage { diagnosticsEnabled = true }
            }
            "minimalOverhead" -> {
                _currentPreset.value = ConfigPreset.SENSOR
                MeshLinkConfig.minimalOverhead { diagnosticsEnabled = true }
            }
            else -> {
                _currentPreset.value = ConfigPreset.CHAT
                MeshLinkConfig.smallPayloadLowLatency { diagnosticsEnabled = true }
            }
        }
        _currentConfig.value = newConfig
        meshLink = MeshLink(transport, newConfig, crypto = crypto)
        collectFlows()
        log("⚙️ Applied preset: $preset")

        if (wasRunning) startMesh()
    }

    /** Update the MTU in the current config. Restarts the mesh if it was running. */
    fun updateMtu(mtu: Int) {
        val wasRunning = _isRunning.value
        if (wasRunning) meshLink.stop()

        val newConfig = _currentConfig.value.copy(mtu = mtu)
        _currentConfig.value = newConfig
        meshLink = MeshLink(transport, newConfig, crypto = crypto)
        collectFlows()

        if (wasRunning) startMesh()
    }

    fun setSeverityFilter(severity: Severity?) {
        _severityFilter.value = severity
    }

    fun clearDiagnostics() {
        _diagnosticEvents.value = emptyList()
    }

    private fun log(entry: String) {
        _logs.update { it + entry }
    }

    override fun onCleared() {
        super.onCleared()
        meshLink.stop()
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

private fun String.hexToByteArray(): ByteArray {
    check(length % 2 == 0) { "Hex string must have even length" }
    return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
