package ch.trancee.meshlink.tui.mesh

import ch.trancee.meshlink.api.*
import ch.trancee.meshlink.testing.VirtualMeshTransport
import ch.trancee.meshlink.testing.createForTest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Represents a single virtual mesh node in the TUI.
 */
@OptIn(ExperimentalMeshLinkApi::class)
data class MeshNode(
    val name: String,
    val meshLink: MeshLink,
    val transport: VirtualMeshTransport,
) {
    val peerId: ByteArray get() = transport.peerId
    val peerIdHex: String get() = peerId.toHexString()
}

/**
 * Manages a virtual mesh network with N nodes linked in a topology for local testing.
 *
 * Default: 3 nodes (Alice, Bob, Charlie) in a line: A↔B↔C
 * This means Alice can reach Charlie only via relay through Bob.
 */
@OptIn(ExperimentalMeshLinkApi::class)
class VirtualMeshNetwork(private val scope: CoroutineScope) {

    private val _nodes = mutableListOf<MeshNode>()
    val nodes: List<MeshNode> get() = _nodes

    private val _log = MutableSharedFlow<LogEntry>(replay = 100, extraBufferCapacity = 256)
    val log: SharedFlow<LogEntry> = _log

    /**
     * Creates the default 3-node line topology and starts all nodes.
     */
    suspend fun setup() {
        val config = meshLinkConfig("ch.trancee.meshlink.tui")

        // Create 3 transports with random peerIds — createForTest generates identities
        // internally and uses identity.keyHash as the real node ID. The transport peerId
        // is only used for BLE-level frame routing between linked transports.
        val transportA = VirtualMeshTransport(peerId = randomPeerId())
        val transportB = VirtualMeshTransport(peerId = randomPeerId())
        val transportC = VirtualMeshTransport(peerId = randomPeerId())

        // Link: A↔B and B↔C (line topology — A cannot reach C directly)
        transportA.linkTo(transportB)
        transportB.linkTo(transportC)

        val meshA = MeshLink.createForTest(config, transportA, scope)
        val meshB = MeshLink.createForTest(config, transportB, scope)
        val meshC = MeshLink.createForTest(config, transportC, scope)

        _nodes.add(MeshNode("Alice", meshA, transportA))
        _nodes.add(MeshNode("Bob", meshB, transportB))
        _nodes.add(MeshNode("Charlie", meshC, transportC))

        // Start all nodes
        for (node in _nodes) {
            node.meshLink.start()
            emitLog("${node.name} started (id: ${node.peerIdHex.take(8)}...)")
        }

        // Subscribe to events for logging
        for (node in _nodes) {
            subscribeEvents(node)
        }

        // Wait for engine coroutines to start their flow collectors.
        delay(200)

        // Trigger discovery ONE DIRECTION AT A TIME.
        // Both sides discovering simultaneously causes a race in NoiseHandshakeManager
        // (not thread-safe). Stagger so each link's 3-step handshake completes cleanly.
        
        // Link 1: A discovers B → A initiates handshake → B responds
        transportA.simulateDiscovery(transportB.peerId, ByteArray(16), rssi = -45)
        delay(500)
        
        // Link 2: B discovers C → B initiates handshake → C responds
        transportB.simulateDiscovery(transportC.peerId, ByteArray(16), rssi = -50)
        delay(500)

        // Now the reverse direction — keys are already pinned, so these are reconnect shortcuts
        transportB.simulateDiscovery(transportA.peerId, ByteArray(16), rssi = -45)
        transportC.simulateDiscovery(transportB.peerId, ByteArray(16), rssi = -50)
        delay(500)

        emitLog("Discovery simulated. Waiting for handshakes...")

        // Wait for peers to reach CONNECTED state
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val allConnected = _nodes.all { node ->
                node.meshLink.allPeerDetails().any { it.state == PeerState.CONNECTED }
            }
            if (allConnected) break
            delay(100)
        }

        val peerCounts = _nodes.map { it.name to it.meshLink.allPeerDetails().size }
        emitLog("Network ready. Peers: ${peerCounts.joinToString { "${it.first}=${it.second}" }}")
    }

    /**
     * Sends a message from one node to another.
     */
    suspend fun sendMessage(fromIndex: Int, toIndex: Int, payload: String) {
        val from = _nodes.getOrNull(fromIndex) ?: return
        val to = _nodes.getOrNull(toIndex) ?: return
        try {
            from.meshLink.send(to.peerId, payload.encodeToByteArray())
            emitLog("${from.name} → ${to.name}: \"$payload\"")
        } catch (e: Exception) {
            emitLog("SEND FAILED ${from.name} → ${to.name}: ${e.message}")
        }
    }

    /**
     * Broadcasts a message from a node.
     */
    suspend fun broadcastMessage(fromIndex: Int, payload: String, maxHops: Int = 3) {
        val from = _nodes.getOrNull(fromIndex) ?: return
        try {
            from.meshLink.broadcast(payload.encodeToByteArray(), maxHops)
            emitLog("${from.name} broadcast: \"$payload\" (hops=$maxHops)")
        } catch (e: Exception) {
            emitLog("BROADCAST FAILED ${from.name}: ${e.message}")
        }
    }

    /**
     * Stops all nodes.
     */
    suspend fun shutdown() {
        for (node in _nodes) {
            try {
                node.meshLink.stop()
            } catch (_: Exception) {}
        }
        emitLog("All nodes stopped.")
    }

    private fun subscribeEvents(node: MeshNode) {
        scope.launch {
            node.meshLink.peers.collect { event ->
                when (event) {
                    is PeerEvent.Found -> emitLog("${node.name}: peer found (${event.id.toHexString().take(8)}...)")
                    is PeerEvent.Lost -> emitLog("${node.name}: peer lost (${event.id.toHexString().take(8)}...)")
                    is PeerEvent.StateChanged -> emitLog("${node.name}: peer ${event.id.toHexString().take(8)}... → ${event.state}")
                }
            }
        }
        scope.launch {
            node.meshLink.messages.collect { msg ->
                val text = msg.payload.decodeToString()
                val sender = msg.senderId.toHexString().take(8)
                emitLog("${node.name} received from $sender...: \"$text\"")
            }
        }
    }

    private suspend fun emitLog(message: String) {
        _log.emit(LogEntry(System.currentTimeMillis(), message))
    }

    private fun randomPeerId(): ByteArray {
        val bytes = ByteArray(12)
        for (i in bytes.indices) bytes[i] = (kotlin.random.Random.nextInt(256) and 0xFF).toByte()
        return bytes
    }
}

data class LogEntry(val timestampMillis: Long, val message: String)

private fun ByteArray.toHexString(): String =
    joinToString("") { it.toInt().and(0xFF).toString(16).padStart(2, '0') }
