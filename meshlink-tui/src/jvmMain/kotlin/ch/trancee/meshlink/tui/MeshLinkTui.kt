package ch.trancee.meshlink.tui

import ch.trancee.meshlink.tui.core.*
import ch.trancee.meshlink.tui.mesh.LogEntry
import ch.trancee.meshlink.tui.mesh.VirtualMeshNetwork
import ch.trancee.meshlink.tui.widgets.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * Tab identifiers for the TUI.
 */
enum class Tab(val label: String, val key: Char) {
    LOG("Log", '1'),
    PEERS("Peers", '2'),
    ROUTING("Routing", '3'),
    HEALTH("Health", '4'),
    NETWORK("Network", '5'),
    SEND("Send", '6'),
}

/**
 * Application state.
 */
class AppState {
    var activeTab: Tab = Tab.LOG
    var selectedNode: Int = 0
    var logOffset: Int = 0
    var inputBuffer: String = ""
    var inputMode: Boolean = false
    var commandMode: Boolean = false  // ':' command input
    var sendTarget: Int = 1
    var running: Boolean = true
    val logEntries: MutableList<LogEntry> = mutableListOf()
    var statusMessage: String = ""   // Transient feedback message
    var statusExpiry: Long = 0       // millis timestamp when status message expires
    var networkCursor: Int = 0       // Cursor in network tab
}

/**
 * Main TUI application for the MeshLink virtual mesh.
 */
class MeshLinkTui(
    private val network: VirtualMeshNetwork,
    private val scope: CoroutineScope,
) {
    private val state = AppState()
    private val backend = JvmTerminalBackend()
    private val renderer = TerminalRenderer(backend)
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS")

    suspend fun run() {
        renderer.init()

        // Collect logs into state
        scope.launch {
            network.log.collect { entry ->
                state.logEntries.add(entry)
                val (_, rows) = backend.size()
                val visibleLines = rows - 6
                if (state.logOffset >= state.logEntries.size - visibleLines - 2) {
                    state.logOffset = (state.logEntries.size - visibleLines).coerceAtLeast(0)
                }
            }
        }

        try {
            while (state.running) {
                render()
                handleInput()
                yield()
            }
        } finally {
            renderer.restore()
            backend.close()
        }
    }

    private fun render() {
        renderer.draw { buf, area ->
            val layout = Layout.vertical(
                Constraint.Length(1),  // Tab bar
                Constraint.Fill(),    // Main content
                Constraint.Length(1),  // Status bar
                Constraint.Length(1),  // Help / command line
            ).split(area)
            val (tabArea, contentArea, statusArea, helpArea) = layout

            renderTabBar(buf, tabArea)
            renderContent(buf, contentArea)
            renderStatusBar(buf, statusArea)
            renderHelpOrCommand(buf, helpArea)
        }
    }

    private fun renderTabBar(buf: Buffer, area: Rect) {
        val tabStyle = Style.DEFAULT.bg(Color.DarkGray).fg(Color.White)
        val activeStyle = Style.DEFAULT.bg(Color.Blue).fg(Color.White).bold()
        buf.fill(area, " ", tabStyle)

        var x = area.x + 1
        for (tab in Tab.entries) {
            val style = if (tab == state.activeTab) activeStyle else tabStyle
            val label = " ${tab.key}:${tab.label} "
            buf.setString(x, area.y, label, style)
            x += label.length + 1
        }

        // Node selector on right
        val nodes = network.nodes
        if (nodes.isNotEmpty()) {
            val nodeName = nodes.getOrNull(state.selectedNode)?.name ?: "?"
            val nodeLabel = "Node: $nodeName"
            buf.setString(area.right - nodeLabel.length - 2, area.y, nodeLabel, tabStyle.bold())
        }
    }

    private fun renderContent(buf: Buffer, area: Rect) {
        when (state.activeTab) {
            Tab.LOG -> renderLogTab(buf, area)
            Tab.PEERS -> renderPeersTab(buf, area)
            Tab.ROUTING -> renderRoutingTab(buf, area)
            Tab.HEALTH -> renderHealthTab(buf, area)
            Tab.NETWORK -> renderNetworkTab(buf, area)
            Tab.SEND -> renderSendTab(buf, area)
        }
    }

    private fun renderLogTab(buf: Buffer, area: Rect) {
        val block = Block(title = "Event Log", borderStyle = Style.DEFAULT.fg(Color.Cyan))
        block.render(buf, area)
        val inner = block.inner(area)

        val lines = state.logEntries.map { entry ->
            val time = dateFormat.format(Date(entry.timestampMillis))
            val style = when {
                "FAILED" in entry.message || "FLOOD" in entry.message && "failed" in entry.message -> Style.DEFAULT.fg(Color.Red)
                "received" in entry.message -> Style.DEFAULT.fg(Color.Green)
                "started" in entry.message || "ready" in entry.message -> Style.DEFAULT.fg(Color.LightBlue)
                "broadcast" in entry.message || "→" in entry.message -> Style.DEFAULT.fg(Color.Yellow)
                "PARTITION" in entry.message || "disconnected" in entry.message -> Style.DEFAULT.fg(Color.Red).bold()
                "HEAL" in entry.message || "reconnect" in entry.message -> Style.DEFAULT.fg(Color.Green).bold()
                "Topology" in entry.message -> Style.DEFAULT.fg(Color.Magenta).bold()
                "+" in entry.message.take(3) -> Style.DEFAULT.fg(Color.Green)
                "-" in entry.message.take(3) -> Style.DEFAULT.fg(Color.Red)
                else -> Style.DEFAULT.fg(Color.White)
            }
            "[$time] ${entry.message}" to style
        }
        renderLines(buf, inner, lines, state.logOffset)
    }

    private fun renderPeersTab(buf: Buffer, area: Rect) {
        val block = Block(title = "Peers", borderStyle = Style.DEFAULT.fg(Color.Green))
        block.render(buf, area)
        val inner = block.inner(area)

        val node = network.nodes.getOrNull(state.selectedNode) ?: return
        val peers = node.meshLink.allPeerDetails()

        val lines = mutableListOf<Pair<String, Style>>()
        lines.add("  Node: ${node.name} (${node.peerIdHex.take(8)}...)" to Style.DEFAULT.fg(Color.Cyan).bold())
        lines.add("" to Style.DEFAULT)
        lines.add("  ┌────────────────────────────────┬────────────┬───────────────┐" to Style.DEFAULT.fg(Color.DarkGray))
        lines.add("  │ Peer ID                        │ State      │ Trust         │" to Style.DEFAULT.fg(Color.DarkGray))
        lines.add("  ├────────────────────────────────┼────────────┼───────────────┤" to Style.DEFAULT.fg(Color.DarkGray))

        if (peers.isEmpty()) {
            lines.add("  │ (no peers discovered)          │            │               │" to Style.DEFAULT.dim())
        } else {
            for (peer in peers) {
                val id = peer.id.toHexString().take(24).padEnd(24)
                val stateStr = peer.state.name.padEnd(10)
                val trust = peer.trustMode.name.padEnd(13)
                val stateColor = when (peer.state) {
                    ch.trancee.meshlink.api.PeerState.CONNECTED -> Color.Green
                    ch.trancee.meshlink.api.PeerState.DISCONNECTED -> Color.Red
                }
                lines.add("  │ $id... │ $stateStr │ $trust │" to Style.DEFAULT.fg(stateColor))
            }
        }
        lines.add("  └────────────────────────────────┴────────────┴───────────────┘" to Style.DEFAULT.fg(Color.DarkGray))

        renderLines(buf, inner, lines)
    }

    private fun renderRoutingTab(buf: Buffer, area: Rect) {
        val block = Block(title = "Routing Table", borderStyle = Style.DEFAULT.fg(Color.Yellow))
        block.render(buf, area)
        val inner = block.inner(area)

        val node = network.nodes.getOrNull(state.selectedNode) ?: return
        val snapshot = node.meshLink.routingSnapshot()

        val lines = mutableListOf<Pair<String, Style>>()
        lines.add("  Node: ${node.name}" to Style.DEFAULT.fg(Color.Cyan).bold())
        lines.add("  Routes: ${snapshot.routes.size}" to Style.DEFAULT)
        lines.add("" to Style.DEFAULT)

        if (snapshot.routes.isEmpty()) {
            lines.add("  (no routes — peers need to complete Noise XX handshake)" to Style.DEFAULT.dim())
        } else {
            lines.add("  Destination            Next Hop                 Cost  SeqNo  Age" to Style.DEFAULT.bold())
            lines.add("  ${"─".repeat(72)}" to Style.DEFAULT.fg(Color.DarkGray))
            for (route in snapshot.routes) {
                val dest = route.destination.toHexString().take(16).padEnd(16)
                val hop = route.nextHop.toHexString().take(16).padEnd(16)
                val cost = route.cost.toString().padStart(4)
                val seq = route.seqNo.toString().padStart(5)
                val age = "${route.ageMillis}ms".padStart(8)
                lines.add("  $dest...    $hop...    $cost  $seq  $age" to Style.DEFAULT)
            }
        }
        renderLines(buf, inner, lines)
    }

    private fun renderHealthTab(buf: Buffer, area: Rect) {
        val block = Block(title = "Mesh Health", borderStyle = Style.DEFAULT.fg(Color.Magenta))
        block.render(buf, area)
        val inner = block.inner(area)

        val lines = mutableListOf<Pair<String, Style>>()

        for (node in network.nodes) {
            val health = node.meshLink.meshHealth()
            val meshState = node.meshLink.state.value

            lines.add("  ╭─ ${node.name} (${node.peerIdHex.take(8)}...) ─────────────────" to Style.DEFAULT.fg(Color.Cyan))
            lines.add("  │  State:            ${meshState.name}" to stateStyle(meshState))
            lines.add("  │  Connected Peers:  ${health.connectedPeers}" to Style.DEFAULT)
            lines.add("  │  Routing Table:    ${health.routingTableSize} routes" to Style.DEFAULT)
            lines.add("  │  Buffer Usage:     ${health.bufferUsageBytes} bytes (${health.bufferUtilizationPercent}%)" to Style.DEFAULT)
            lines.add("  │  Active Transfers: ${health.activeTransfers}" to Style.DEFAULT)
            lines.add("  │  Power Mode:       ${health.powerMode}" to Style.DEFAULT)
            lines.add("  │  Avg Route Cost:   ${String.format("%.2f", health.avgRouteCost)}" to Style.DEFAULT)
            lines.add("  │  Relay Queue:      ${health.relayQueueSize}" to Style.DEFAULT)
            lines.add("  ╰──────────────────────────────────────" to Style.DEFAULT.fg(Color.DarkGray))
            lines.add("" to Style.DEFAULT)
        }
        renderLines(buf, inner, lines)
    }

    private fun renderNetworkTab(buf: Buffer, area: Rect) {
        val block = Block(title = "Network Topology", borderStyle = Style.DEFAULT.fg(Color.Magenta))
        block.render(buf, area)
        val inner = block.inner(area)

        val lines = mutableListOf<Pair<String, Style>>()
        lines.add("" to Style.DEFAULT)

        // Topology visualization
        lines.add("  Nodes (${network.nodes.size}):" to Style.DEFAULT.bold())
        for ((i, node) in network.nodes.withIndex()) {
            val meshState = node.meshLink.state.value
            val prefix = if (i == state.networkCursor) " ▶" else "  "
            val stateIcon = when (meshState) {
                ch.trancee.meshlink.api.MeshLinkState.RUNNING -> "●"
                ch.trancee.meshlink.api.MeshLinkState.PAUSED -> "◐"
                ch.trancee.meshlink.api.MeshLinkState.STOPPED -> "○"
                else -> "?"
            }
            val stateColor = when (meshState) {
                ch.trancee.meshlink.api.MeshLinkState.RUNNING -> Color.Green
                ch.trancee.meshlink.api.MeshLinkState.PAUSED -> Color.Yellow
                ch.trancee.meshlink.api.MeshLinkState.STOPPED -> Color.Red
                else -> Color.White
            }
            val connectedPeers = node.meshLink.allPeerDetails().count {
                it.state == ch.trancee.meshlink.api.PeerState.CONNECTED
            }
            val linkCount = network.links.count { it.first == i || it.second == i }
            lines.add(
                "$prefix $stateIcon ${node.name.padEnd(10)} │ links=$linkCount peers=$connectedPeers │ ${node.peerIdHex.take(8)}..."
                    to Style.DEFAULT.fg(stateColor)
            )
        }

        lines.add("" to Style.DEFAULT)
        lines.add("  Links:" to Style.DEFAULT.bold())
        if (network.links.isEmpty()) {
            lines.add("    (no links)" to Style.DEFAULT.dim())
        } else {
            for ((a, b) in network.links) {
                val nameA = network.nodes.getOrNull(a)?.name ?: "?"
                val nameB = network.nodes.getOrNull(b)?.name ?: "?"
                lines.add("    $nameA ↔ $nameB" to Style.DEFAULT.fg(Color.Cyan))
            }
        }

        lines.add("" to Style.DEFAULT)
        lines.add("  ─── Actions ──────────────────────────────────────────" to Style.DEFAULT.fg(Color.DarkGray))
        lines.add("  [a] Add node   [d] Remove selected   [p] Pause/Resume" to Style.DEFAULT.dim())
        lines.add("  [l] Link sel → next   [u] Unlink sel → next" to Style.DEFAULT.dim())
        lines.add("  [x] Disconnect selected   [r] Reconnect selected" to Style.DEFAULT.dim())
        lines.add("  [↑/↓] Move cursor" to Style.DEFAULT.dim())
        lines.add("" to Style.DEFAULT)
        lines.add("  ─── Scenarios ────────────────────────────────────────" to Style.DEFAULT.fg(Color.DarkGray))
        lines.add("  [:] Command mode — type scenarios below:" to Style.DEFAULT.dim())
        lines.add("    :star  :ring  :line  :mesh  :partition  :heal" to Style.DEFAULT.fg(Color.Yellow))
        lines.add("    :add <name>   :flood <from> <to> <count>" to Style.DEFAULT.fg(Color.Yellow))

        renderLines(buf, inner, lines)
    }

    private fun renderSendTab(buf: Buffer, area: Rect) {
        val block = Block(title = "Send Message", borderStyle = Style.DEFAULT.fg(Color.Yellow))
        block.render(buf, area)
        val inner = block.inner(area)

        val from = network.nodes.getOrNull(state.selectedNode) ?: return
        val to = network.nodes.getOrNull(state.sendTarget) ?: return

        val lines = mutableListOf<Pair<String, Style>>()
        lines.add("" to Style.DEFAULT)
        lines.add("  From:    ${from.name} (${from.peerIdHex.take(8)}...)" to Style.DEFAULT.fg(Color.Cyan))
        lines.add("  To:      ${to.name} (${to.peerIdHex.take(8)}...)  [Tab to change]" to Style.DEFAULT.fg(Color.Green))
        lines.add("" to Style.DEFAULT)
        lines.add("  ┌─ Message ${"─".repeat((inner.width - 14).coerceAtLeast(1))}┐" to Style.DEFAULT.fg(Color.DarkGray))

        val cursor = if (state.inputMode) "▌" else ""
        val inputDisplay = "  │ ${state.inputBuffer}$cursor"
        lines.add(inputDisplay to Style.DEFAULT.fg(if (state.inputMode) Color.White else Color.DarkGray))
        lines.add("  └${"─".repeat((inner.width - 4).coerceAtLeast(1))}┘" to Style.DEFAULT.fg(Color.DarkGray))
        lines.add("" to Style.DEFAULT)

        if (state.inputMode) {
            lines.add("  [Enter] Send  [Esc] Cancel" to Style.DEFAULT.dim())
        } else {
            lines.add("  [i] Type message  [Tab] Switch target  [b] Broadcast" to Style.DEFAULT.dim())
        }
        renderLines(buf, inner, lines)
    }

    private fun renderStatusBar(buf: Buffer, area: Rect) {
        val statusStyle = Style.DEFAULT.bg(Color.Blue).fg(Color.White)
        val nodes = network.nodes
        val nodeCount = nodes.size
        val linkCount = network.links.size

        // Show transient status message if active
        val now = System.currentTimeMillis()
        val extra = if (state.statusMessage.isNotEmpty() && now < state.statusExpiry) {
            " │ ${state.statusMessage}"
        } else {
            state.statusMessage = ""
            ""
        }

        val status = " MeshLink TUI │ Nodes: $nodeCount │ Links: $linkCount │ Tab: ${state.activeTab.label}$extra"
        ch.trancee.meshlink.tui.widgets.renderStatusBar(buf, area, status, statusStyle)
    }

    private fun renderHelpOrCommand(buf: Buffer, area: Rect) {
        if (state.commandMode) {
            val cmdStyle = Style.DEFAULT.fg(Color.White).bg(Color.Black)
            buf.fill(area, " ", cmdStyle)
            buf.setString(area.x, area.y, ":${state.inputBuffer}▌", cmdStyle)
        } else {
            val helpStyle = Style.DEFAULT.fg(Color.DarkGray)
            val help = " [1-6] Tabs │ [←/→] Node │ [:] Command │ [q] Quit │ [↑/↓] Scroll"
            buf.setString(area.x, area.y, help.take(area.width), helpStyle)
        }
    }

    private suspend fun handleInput() {
        val event = renderer.pollEvent(50) ?: return

        if (state.commandMode) {
            handleCommandMode(event)
            return
        }
        if (state.inputMode) {
            handleInputMode(event)
            return
        }

        when (event.code) {
            is KeyCode.Char -> when (event.code.c) {
                'q' -> state.running = false
                '1' -> state.activeTab = Tab.LOG
                '2' -> state.activeTab = Tab.PEERS
                '3' -> state.activeTab = Tab.ROUTING
                '4' -> state.activeTab = Tab.HEALTH
                '5' -> state.activeTab = Tab.NETWORK
                '6' -> state.activeTab = Tab.SEND
                ':' -> { state.commandMode = true; state.inputBuffer = "" }
                'i' -> if (state.activeTab == Tab.SEND) { state.inputMode = true }
                'b' -> if (state.activeTab == Tab.SEND) {
                    scope.launch { network.broadcastMessage(state.selectedNode, "Hello mesh!") }
                }
                // Network tab actions
                'a' -> if (state.activeTab == Tab.NETWORK) {
                    scope.launch {
                        val idx = network.addNode()
                        network.startNode(idx)
                        showStatus("Node added: ${network.nodes[idx].name}")
                    }
                }
                'd' -> if (state.activeTab == Tab.NETWORK && network.nodes.size > 1) {
                    scope.launch {
                        val name = network.nodes.getOrNull(state.networkCursor)?.name ?: return@launch
                        network.removeNode(state.networkCursor)
                        state.networkCursor = state.networkCursor.coerceAtMost(network.nodes.size - 1)
                        state.selectedNode = state.selectedNode.coerceAtMost(network.nodes.size - 1)
                        showStatus("Removed $name")
                    }
                }
                'p' -> if (state.activeTab == Tab.NETWORK) {
                    scope.launch {
                        val node = network.nodes.getOrNull(state.networkCursor) ?: return@launch
                        if (node.meshLink.state.value == ch.trancee.meshlink.api.MeshLinkState.PAUSED) {
                            network.resumeNode(state.networkCursor)
                        } else {
                            network.pauseNode(state.networkCursor)
                        }
                    }
                }
                'l' -> if (state.activeTab == Tab.NETWORK) {
                    val next = (state.networkCursor + 1) % network.nodes.size
                    if (next != state.networkCursor) {
                        network.linkNodes(state.networkCursor, next)
                        scope.launch {
                            network.triggerDiscovery(state.networkCursor, next)
                            delay(200)
                            network.triggerDiscovery(next, state.networkCursor)
                        }
                        showStatus("Linked ${network.nodes[state.networkCursor].name} ↔ ${network.nodes[next].name}")
                    }
                }
                'u' -> if (state.activeTab == Tab.NETWORK) {
                    val next = (state.networkCursor + 1) % network.nodes.size
                    if (next != state.networkCursor) {
                        network.unlinkNodes(state.networkCursor, next)
                        showStatus("Unlinked ${network.nodes[state.networkCursor].name} ↔ ${network.nodes[next].name}")
                    }
                }
                'x' -> if (state.activeTab == Tab.NETWORK) {
                    scope.launch { network.simulateDisconnect(state.networkCursor) }
                }
                'r' -> if (state.activeTab == Tab.NETWORK) {
                    scope.launch { network.simulateReconnect(state.networkCursor) }
                }
                else -> {}
            }
            KeyCode.Left -> {
                if (network.nodes.isNotEmpty()) {
                    state.selectedNode = (state.selectedNode - 1 + network.nodes.size) % network.nodes.size
                }
            }
            KeyCode.Right -> {
                if (network.nodes.isNotEmpty()) {
                    state.selectedNode = (state.selectedNode + 1) % network.nodes.size
                }
            }
            KeyCode.Up -> {
                if (state.activeTab == Tab.NETWORK) {
                    state.networkCursor = (state.networkCursor - 1).coerceAtLeast(0)
                } else {
                    state.logOffset = (state.logOffset - 1).coerceAtLeast(0)
                }
            }
            KeyCode.Down -> {
                if (state.activeTab == Tab.NETWORK) {
                    state.networkCursor = (state.networkCursor + 1).coerceAtMost(network.nodes.size - 1)
                } else {
                    state.logOffset = (state.logOffset + 1).coerceAtMost(state.logEntries.size)
                }
            }
            KeyCode.Tab -> {
                if (state.activeTab == Tab.SEND) {
                    if (network.nodes.size > 1) {
                        state.sendTarget = (state.sendTarget + 1) % network.nodes.size
                        if (state.sendTarget == state.selectedNode) {
                            state.sendTarget = (state.sendTarget + 1) % network.nodes.size
                        }
                    }
                }
            }
            else -> {}
        }
    }

    private suspend fun handleInputMode(event: KeyEvent) {
        when (event.code) {
            KeyCode.Escape -> {
                state.inputMode = false
                state.inputBuffer = ""
            }
            KeyCode.Enter -> {
                if (state.inputBuffer.isNotEmpty()) {
                    val msg = state.inputBuffer
                    state.inputBuffer = ""
                    state.inputMode = false
                    scope.launch { network.sendMessage(state.selectedNode, state.sendTarget, msg) }
                }
            }
            KeyCode.Backspace -> {
                state.inputBuffer = state.inputBuffer.dropLast(1)
            }
            is KeyCode.Char -> {
                state.inputBuffer += event.code.c
            }
            else -> {}
        }
    }

    private suspend fun handleCommandMode(event: KeyEvent) {
        when (event.code) {
            KeyCode.Escape -> {
                state.commandMode = false
                state.inputBuffer = ""
            }
            KeyCode.Enter -> {
                val cmd = state.inputBuffer.trim()
                state.commandMode = false
                state.inputBuffer = ""
                executeCommand(cmd)
            }
            KeyCode.Backspace -> {
                state.inputBuffer = state.inputBuffer.dropLast(1)
            }
            is KeyCode.Char -> {
                state.inputBuffer += event.code.c
            }
            else -> {}
        }
    }

    private fun executeCommand(cmd: String) {
        val parts = cmd.split(" ").filter { it.isNotBlank() }
        if (parts.isEmpty()) return

        when (parts[0].lowercase()) {
            "star" -> scope.launch { network.scenarioStar() }
            "ring" -> scope.launch { network.scenarioRing() }
            "line" -> scope.launch { network.scenarioLine() }
            "mesh" -> scope.launch { network.scenarioFullMesh() }
            "partition" -> scope.launch { network.scenarioPartition() }
            "heal" -> scope.launch { network.scenarioHeal() }
            "add" -> {
                val name = parts.getOrNull(1)
                scope.launch {
                    val idx = network.addNode(name)
                    network.startNode(idx)
                    showStatus("Added: ${network.nodes[idx].name}")
                }
            }
            "flood" -> {
                val from = parts.getOrNull(1)?.toIntOrNull() ?: 0
                val to = parts.getOrNull(2)?.toIntOrNull() ?: 1
                val count = parts.getOrNull(3)?.toIntOrNull() ?: 50
                scope.launch { network.floodMessages(from, to, count) }
            }
            "link" -> {
                val a = parts.getOrNull(1)?.toIntOrNull()
                val b = parts.getOrNull(2)?.toIntOrNull()
                if (a != null && b != null) {
                    network.linkNodes(a, b)
                    scope.launch {
                        network.triggerDiscovery(a, b)
                        delay(200)
                        network.triggerDiscovery(b, a)
                    }
                    showStatus("Linked ${network.nodes.getOrNull(a)?.name} ↔ ${network.nodes.getOrNull(b)?.name}")
                } else {
                    showStatus("Usage: :link <idx> <idx>")
                }
            }
            "unlink" -> {
                val a = parts.getOrNull(1)?.toIntOrNull()
                val b = parts.getOrNull(2)?.toIntOrNull()
                if (a != null && b != null) {
                    network.unlinkNodes(a, b)
                    showStatus("Unlinked")
                } else {
                    showStatus("Usage: :unlink <idx> <idx>")
                }
            }
            "help" -> {
                showStatus("star|ring|line|mesh|partition|heal|add|flood|link|unlink")
            }
            else -> showStatus("Unknown: $cmd (try :help)")
        }
    }

    private fun showStatus(message: String) {
        state.statusMessage = message
        state.statusExpiry = System.currentTimeMillis() + 4_000
    }

    private fun stateStyle(state: ch.trancee.meshlink.api.MeshLinkState): Style = when (state) {
        ch.trancee.meshlink.api.MeshLinkState.RUNNING -> Style.DEFAULT.fg(Color.Green)
        ch.trancee.meshlink.api.MeshLinkState.STOPPED -> Style.DEFAULT.fg(Color.Red)
        ch.trancee.meshlink.api.MeshLinkState.PAUSED -> Style.DEFAULT.fg(Color.Yellow)
        else -> Style.DEFAULT
    }
}

private fun ByteArray.toHexString(): String =
    joinToString("") { it.toInt().and(0xFF).toString(16).padStart(2, '0') }
