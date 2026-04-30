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
 * Manages a virtual mesh network with dynamically added/removed nodes for local testing.
 *
 * Default: 3 nodes (Alice, Bob, Charlie) in a line: A↔B↔C
 * Nodes and links can be added/removed at runtime to test different topologies and scenarios.
 */
@OptIn(ExperimentalMeshLinkApi::class)
class VirtualMeshNetwork(private val scope: CoroutineScope) {

    private val _nodes = mutableListOf<MeshNode>()
    val nodes: List<MeshNode> get() = _nodes

    private val _log = MutableSharedFlow<LogEntry>(replay = 100, extraBufferCapacity = 256)
    val log: SharedFlow<LogEntry> = _log

    /** Active link pairs for topology display (indices into _nodes). */
    private val _links = mutableSetOf<Pair<Int, Int>>()
    val links: Set<Pair<Int, Int>> get() = _links

    private val config = meshLinkConfig("ch.trancee.meshlink.tui")

    private val defaultNames = listOf(
        "Alice", "Bob", "Charlie", "Dave", "Eve", "Frank",
        "Grace", "Heidi", "Ivan", "Judy", "Karl", "Laura",
        "Mallory", "Niaj", "Oscar", "Peggy", "Quentin", "Rupert",
        "Sybil", "Trent", "Ursula", "Victor", "Wendy", "Xavier",
    )

    private var nameCounter = 0

    /**
     * Creates the default 3-node line topology and starts all nodes.
     */
    suspend fun setup() {
        // Create the initial 3 nodes
        addNode("Alice")
        addNode("Bob")
        addNode("Charlie")

        // Link: A↔B and B↔C (line topology)
        linkNodes(0, 1)
        linkNodes(1, 2)

        // Start all
        for (node in _nodes) {
            node.meshLink.start()
            emitLog("${node.name} started (id: ${node.peerIdHex.take(8)}...)")
        }

        // Subscribe to events
        for (node in _nodes) {
            subscribeEvents(node)
        }

        // Trigger handshakes
        delay(200)
        triggerDiscovery(0, 1)
        delay(500)
        triggerDiscovery(1, 2)
        delay(500)
        // Reverse direction (reconnect shortcuts — keys already pinned)
        triggerDiscovery(1, 0)
        triggerDiscovery(2, 1)
        delay(500)

        emitLog("Discovery simulated. Waiting for handshakes...")
        waitForConnections(timeoutMillis = 5_000)

        val peerCounts = _nodes.map { it.name to it.meshLink.allPeerDetails().size }
        emitLog("Network ready. Peers: ${peerCounts.joinToString { "${it.first}=${it.second}" }}")
        nameCounter = 3
    }

    // ── Dynamic node management ──────────────────────────────────────────────

    /**
     * Adds a new node to the network (not yet linked or started).
     * Returns the index of the new node.
     */
    suspend fun addNode(name: String? = null): Int {
        val nodeName = name ?: nextName()
        val transport = VirtualMeshTransport(peerId = randomPeerId())
        val meshLink = MeshLink.createForTest(config, transport, scope)
        val node = MeshNode(nodeName, meshLink, transport)
        _nodes.add(node)
        emitLog("+ Node '$nodeName' added (id: ${node.peerIdHex.take(8)}...)")
        return _nodes.size - 1
    }

    /**
     * Starts a node (if not already running) and optionally links + discovers with neighbours.
     */
    suspend fun startNode(index: Int) {
        val node = _nodes.getOrNull(index) ?: return
        if (node.meshLink.state.value == MeshLinkState.RUNNING) {
            emitLog("${node.name} is already running")
            return
        }
        node.meshLink.start()
        subscribeEvents(node)
        emitLog("${node.name} started")

        // Trigger discovery with any linked nodes
        delay(200)
        for ((a, b) in _links) {
            when (index) {
                a -> { triggerDiscovery(a, b); delay(300) }
                b -> { triggerDiscovery(b, a); delay(300) }
            }
        }
    }

    /**
     * Removes a node from the network. Stops the node and unlinks all connections.
     */
    suspend fun removeNode(index: Int) {
        val node = _nodes.getOrNull(index) ?: return
        // Unlink from all neighbours
        val toRemove = _links.filter { it.first == index || it.second == index }.toList()
        for ((a, b) in toRemove) {
            unlinkNodes(a, b)
        }
        // Stop the node
        try { node.meshLink.stop() } catch (_: Exception) {}
        _nodes.removeAt(index)
        // Reindex links
        val reindexed = _links.map { (a, b) ->
            val newA = if (a > index) a - 1 else a
            val newB = if (b > index) b - 1 else b
            newA to newB
        }.toSet()
        _links.clear()
        _links.addAll(reindexed)
        emitLog("- Node '${node.name}' removed")
    }

    /**
     * Links two nodes bidirectionally at the transport layer.
     */
    fun linkNodes(a: Int, b: Int) {
        val nodeA = _nodes.getOrNull(a) ?: return
        val nodeB = _nodes.getOrNull(b) ?: return
        nodeA.transport.linkTo(nodeB.transport)
        _links.add(if (a < b) a to b else b to a)
    }

    /**
     * Unlinks two nodes (frames can no longer be exchanged directly).
     */
    fun unlinkNodes(a: Int, b: Int) {
        val nodeA = _nodes.getOrNull(a) ?: return
        val nodeB = _nodes.getOrNull(b) ?: return
        nodeA.transport.unlink(nodeB.transport)
        _links.remove(if (a < b) a to b else b to a)
    }

    /**
     * Triggers BLE discovery between two linked nodes to initiate handshake.
     */
    suspend fun triggerDiscovery(fromIndex: Int, toIndex: Int) {
        val from = _nodes.getOrNull(fromIndex) ?: return
        val to = _nodes.getOrNull(toIndex) ?: return
        from.transport.simulateDiscovery(to.transport.peerId, ByteArray(16), rssi = -45)
    }

    /**
     * Simulates a connection loss for a node (sends peer-lost to all linked neighbours).
     */
    suspend fun simulateDisconnect(index: Int) {
        val node = _nodes.getOrNull(index) ?: return
        for ((a, b) in _links) {
            when (index) {
                a -> {
                    val other = _nodes[b]
                    node.transport.simulatePeerLost(other.transport.peerId)
                    other.transport.simulatePeerLost(node.transport.peerId)
                }
                b -> {
                    val other = _nodes[a]
                    node.transport.simulatePeerLost(other.transport.peerId)
                    other.transport.simulatePeerLost(node.transport.peerId)
                }
            }
        }
        emitLog("⚡ ${node.name} disconnected (simulated link loss)")
    }

    /**
     * Reconnects a node by re-triggering discovery with all linked neighbours.
     */
    suspend fun simulateReconnect(index: Int) {
        val node = _nodes.getOrNull(index) ?: return
        for ((a, b) in _links) {
            when (index) {
                a -> { triggerDiscovery(a, b); delay(200) }
                b -> { triggerDiscovery(b, a); delay(200) }
            }
        }
        emitLog("↻ ${node.name} reconnecting...")
    }

    /**
     * Pauses a node (stops advertising/scanning but keeps state).
     */
    suspend fun pauseNode(index: Int) {
        val node = _nodes.getOrNull(index) ?: return
        node.meshLink.pause()
        emitLog("⏸ ${node.name} paused")
    }

    /**
     * Resumes a paused node.
     */
    suspend fun resumeNode(index: Int) {
        val node = _nodes.getOrNull(index) ?: return
        node.meshLink.resume()
        emitLog("▶ ${node.name} resumed")
    }

    // ── Scenario presets ─────────────────────────────────────────────────────

    /**
     * Reconfigures the network into a star topology (first node is hub).
     */
    suspend fun scenarioStar() {
        clearAllLinks()
        if (_nodes.size < 2) return
        for (i in 1 until _nodes.size) {
            linkNodes(0, i)
        }
        emitLog("★ Topology: STAR (hub = ${_nodes[0].name})")
        rediscover()
    }

    /**
     * Reconfigures the network into a ring topology.
     */
    suspend fun scenarioRing() {
        clearAllLinks()
        if (_nodes.size < 2) return
        for (i in 0 until _nodes.size - 1) {
            linkNodes(i, i + 1)
        }
        linkNodes(_nodes.size - 1, 0)
        emitLog("○ Topology: RING")
        rediscover()
    }

    /**
     * Reconfigures the network into a line topology.
     */
    suspend fun scenarioLine() {
        clearAllLinks()
        if (_nodes.size < 2) return
        for (i in 0 until _nodes.size - 1) {
            linkNodes(i, i + 1)
        }
        emitLog("─ Topology: LINE")
        rediscover()
    }

    /**
     * Reconfigures the network into a full mesh (all nodes linked to all others).
     */
    suspend fun scenarioFullMesh() {
        clearAllLinks()
        for (i in _nodes.indices) {
            for (j in i + 1 until _nodes.size) {
                linkNodes(i, j)
            }
        }
        emitLog("◆ Topology: FULL MESH")
        rediscover()
    }

    /**
     * Partitions the network: splits into two halves with no links between them.
     */
    suspend fun scenarioPartition() {
        if (_nodes.size < 4) {
            emitLog("⚠ Need at least 4 nodes for partition scenario")
            return
        }
        val mid = _nodes.size / 2
        // Remove cross-partition links
        val crossLinks = _links.filter { (a, b) ->
            (a < mid && b >= mid) || (b < mid && a >= mid)
        }.toList()
        for ((a, b) in crossLinks) {
            unlinkNodes(a, b)
            // Simulate loss on both sides
            _nodes[a].transport.simulatePeerLost(_nodes[b].transport.peerId)
            _nodes[b].transport.simulatePeerLost(_nodes[a].transport.peerId)
        }
        emitLog("⚡ PARTITION: [${_nodes.take(mid).joinToString { it.name }}] | [${_nodes.drop(mid).joinToString { it.name }}]")
    }

    /**
     * Heals a partition: restores links between the two halves.
     */
    suspend fun scenarioHeal() {
        if (_nodes.size < 4) return
        val mid = _nodes.size / 2
        // Link the boundary nodes
        linkNodes(mid - 1, mid)
        triggerDiscovery(mid - 1, mid)
        delay(300)
        triggerDiscovery(mid, mid - 1)
        emitLog("✓ HEAL: ${_nodes[mid - 1].name} ↔ ${_nodes[mid].name} reconnected")
    }

    // ── Message operations ───────────────────────────────────────────────────

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
     * Sends a flood of N messages between two nodes (for stress testing).
     */
    suspend fun floodMessages(fromIndex: Int, toIndex: Int, count: Int) {
        val from = _nodes.getOrNull(fromIndex) ?: return
        val to = _nodes.getOrNull(toIndex) ?: return
        emitLog("⚡ FLOOD: ${from.name} → ${to.name} × $count messages")
        var sent = 0
        var failed = 0
        for (i in 1..count) {
            try {
                from.meshLink.send(to.peerId, "flood-$i".encodeToByteArray())
                sent++
            } catch (_: Exception) { failed++ }
            if (i % 10 == 0) delay(10) // Yield to avoid starving other coroutines
        }
        emitLog("⚡ FLOOD complete: $sent sent, $failed failed")
    }

    /**
     * Stops all nodes.
     */
    suspend fun shutdown() {
        for (node in _nodes) {
            try { node.meshLink.stop() } catch (_: Exception) {}
        }
        emitLog("All nodes stopped.")
    }

    // ── Internal helpers ─────────────────────────────────────────────────────

    private fun clearAllLinks() {
        for ((a, b) in _links.toList()) {
            val nodeA = _nodes.getOrNull(a)
            val nodeB = _nodes.getOrNull(b)
            if (nodeA != null && nodeB != null) {
                nodeA.transport.unlink(nodeB.transport)
            }
        }
        _links.clear()
    }

    private suspend fun rediscover() {
        delay(200)
        for ((a, b) in _links) {
            triggerDiscovery(a, b)
            delay(100)
        }
        delay(300)
        for ((a, b) in _links) {
            triggerDiscovery(b, a)
            delay(100)
        }
    }

    private suspend fun waitForConnections(timeoutMillis: Long) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val allConnected = _nodes.all { node ->
                node.meshLink.allPeerDetails().any { it.state == PeerState.CONNECTED }
            }
            if (allConnected) break
            delay(100)
        }
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

    private fun nextName(): String {
        val name = if (nameCounter < defaultNames.size) {
            defaultNames[nameCounter]
        } else {
            "Node-${nameCounter + 1}"
        }
        nameCounter++
        return name
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
