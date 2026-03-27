package io.meshlink.sample

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.meshlink.MeshLink
import io.meshlink.config.MeshLinkConfig
import io.meshlink.diagnostics.MeshHealthSnapshot
import io.meshlink.model.PeerEvent
import io.meshlink.transport.AdvertisementEvent
import io.meshlink.transport.BleTransport
import io.meshlink.transport.IncomingData
import io.meshlink.transport.PeerLostEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalUuidApi::class)
class MeshLinkViewModel : ViewModel() {

    private val transport = DemoTransport()
    private val config = MeshLinkConfig.chatOptimized()
    private val meshLink = MeshLink(transport, config)

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _health = MutableStateFlow(meshLink.meshHealth())
    val health: StateFlow<MeshHealthSnapshot> = _health.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    init {
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
                    is PeerEvent.Discovered -> log("🔵 Peer discovered: ${event.peerId.toHex()}")
                    is PeerEvent.Lost -> log("🔴 Peer lost: ${event.peerId.toHex()}")
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
                log("🟢 Mesh started")
            }
            .onFailure { e ->
                log("❌ Start failed: ${e.message}")
            }
    }

    fun stopMesh() {
        meshLink.stop()
        _isRunning.value = false
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

/**
 * No-op BLE transport for demonstration purposes.
 * In a real app, use AndroidBleTransport with proper runtime permissions.
 */
private class DemoTransport : BleTransport {
    override val localPeerId: ByteArray = ByteArray(16) { it.toByte() }
    override suspend fun startAdvertisingAndScanning() {}
    override suspend fun stopAll() {}
    override val advertisementEvents: Flow<AdvertisementEvent> = emptyFlow()
    override val peerLostEvents: Flow<PeerLostEvent> = emptyFlow()
    override suspend fun sendToPeer(peerId: ByteArray, data: ByteArray) {}
    override val incomingData: Flow<IncomingData> = emptyFlow()
}
