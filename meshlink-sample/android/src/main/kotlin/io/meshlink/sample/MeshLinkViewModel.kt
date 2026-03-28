package io.meshlink.sample

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.meshlink.MeshLink
import io.meshlink.config.MeshLinkConfig
import io.meshlink.diagnostics.MeshHealthSnapshot
import io.meshlink.model.PeerEvent
import io.meshlink.transport.AndroidBleTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi

/** Lightweight descriptor of a discovered peer for the mesh visualizer. */
data class PeerInfo(
    val id: String,
    val rssi: Int,
    val lastSeen: Long,
)

@OptIn(ExperimentalUuidApi::class)
class MeshLinkViewModel(application: Application) : AndroidViewModel(application) {

    private val transport = AndroidBleTransport(application)

    private var _currentConfig = MutableStateFlow(MeshLinkConfig.chatOptimized())
    /** Current [MeshLinkConfig] — observable for the Settings screen. */
    val currentConfig: StateFlow<MeshLinkConfig> = _currentConfig.asStateFlow()

    private val _currentPreset = MutableStateFlow(ConfigPreset.CHAT)
    /** Which config preset is active. */
    val currentPreset: StateFlow<ConfigPreset> = _currentPreset.asStateFlow()

    private var meshLink = MeshLink(transport, _currentConfig.value)

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

    init {
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
                    is PeerEvent.Discovered -> {
                        val hexId = event.peerId.toHex()
                        log("🔵 Peer discovered: $hexId")
                        _discoveredPeers.update { peers ->
                            // Simulated RSSI — a real transport would supply the actual value.
                            val rssi = (-40..-90).random()
                            peers.filterNot { it.id == hexId } + PeerInfo(hexId, rssi, System.currentTimeMillis())
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
                log("💓 Health update: peers=${snapshot.connectedPeers}, mode=${snapshot.powerMode}")
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
        log("🔴 Mesh stopped")
    }

    fun sendMessage(recipientId: String, message: String) {
        val recipientBytes = recipientId.hexToByteArray()
        val payloadBytes = message.encodeToByteArray()
        meshLink.send(recipientBytes, payloadBytes)
            .onSuccess { uuid ->
                log("📤 Sent (id=$uuid) to $recipientId")
            }
            .onFailure { e ->
                log("❌ Send failed: ${e.message}")
            }
    }

    /** Switch to a different config preset. Restarts the mesh if it was running. */
    fun applyPreset(preset: String) {
        val wasRunning = _isRunning.value
        if (wasRunning) meshLink.stop()

        val newConfig = when (preset) {
            "fileTransferOptimized" -> {
                _currentPreset.value = ConfigPreset.FILE_TRANSFER
                MeshLinkConfig.fileTransferOptimized()
            }
            "powerOptimized" -> {
                _currentPreset.value = ConfigPreset.POWER
                MeshLinkConfig.powerOptimized()
            }
            "sensorOptimized" -> {
                _currentPreset.value = ConfigPreset.SENSOR
                MeshLinkConfig.sensorOptimized()
            }
            else -> {
                _currentPreset.value = ConfigPreset.CHAT
                MeshLinkConfig.chatOptimized()
            }
        }
        _currentConfig.value = newConfig
        meshLink = MeshLink(transport, newConfig)
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
        meshLink = MeshLink(transport, newConfig)
        collectFlows()

        if (wasRunning) startMesh()
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
