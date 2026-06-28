package ch.trancee.meshlink.proof.android

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : Activity() {
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var stateLabel: TextView
    private lateinit var peersLabel: TextView
    private lateinit var lifecycleLabel: TextView
    private lateinit var peersDetailsLabel: TextView
    private lateinit var peersToggleButton: Button
    private var previousPeerCount: Int = 0
    private lateinit var logLabel: TextView
    private lateinit var startStopButton: Button
    private lateinit var sendHelloButton: Button

    override fun onCreate(savedInstanceState: Bundle?): Unit {
        super.onCreate(savedInstanceState)

        setContentView(buildContentView())
        MeshLinkProofRuntime.initialize(
            context = applicationContext,
            launchConfig = ProofLaunchConfig.fromIntent(intent),
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
        if (requestCode != ProofPermissionContract.REQUEST_PERMISSIONS_CODE) {
            return
        }
        if (ProofPermissionContract.isRequestGranted(grantResults)) {
            MeshLinkProofRuntime.appendLog("Bluetooth permissions granted")
            startIfBluetoothReady()
        } else {
            MeshLinkProofRuntime.appendLog(
                "Bluetooth permissions denied; MeshLink transport will stay idle"
            )
        }
    }

    private fun buildContentView(): ScrollView {
        stateLabel = TextView(this).apply { textSize = 18f }
        peersLabel = TextView(this)
        lifecycleLabel = TextView(this).apply { textSize = 14f }
        peersToggleButton = Button(this).apply {
            text = "Show peer IDs"
            visibility = View.GONE
            setOnClickListener {
                MeshLinkProofRuntime.togglePeerDetails()
            }
        }
        peersDetailsLabel = TextView(this).apply {
            setTextIsSelectable(true)
            visibility = View.GONE
        }
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
            addView(lifecycleLabel)
            addView(peersToggleButton)
            addView(peersDetailsLabel)
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
            peersLabel.text = summarizePeers(snapshot.peers)
            val peerCount = snapshot.peers.size
            val peerDetailsVisibleBeforeUpdate = MeshLinkProofRuntime.isPeerDetailsVisible
            val shouldAutoExpandPeers =
                peerCount > 0 && previousPeerCount == 0 && !peerDetailsVisibleBeforeUpdate
            val shouldCollapsePeers = peerCount == 0 && peerDetailsVisibleBeforeUpdate
            previousPeerCount = peerCount
            if (shouldAutoExpandPeers || shouldCollapsePeers) {
                MeshLinkProofRuntime.togglePeerDetails()
            }
            val peerDetailsVisible = MeshLinkProofRuntime.isPeerDetailsVisible
            val peerDetailsControlVisible = snapshot.peers.isNotEmpty()
            peersToggleButton.visibility = if (peerDetailsControlVisible) View.VISIBLE else View.GONE
            peersDetailsLabel.visibility = if (peerDetailsControlVisible) View.VISIBLE else View.GONE
            peersToggleButton.text = if (peerDetailsVisible) "Hide peer IDs" else "Show peer IDs"
            peersDetailsLabel.text = summarizePeerDetails(snapshot.peers, peerDetailsVisible)
            startStopButton.text = if (snapshot.running) "Stop Proof" else "Start Proof"
            sendHelloButton.isEnabled = snapshot.running && snapshot.peers.isNotEmpty()
            lifecycleLabel.text = summarizeLifecycleStatus(snapshot.logs)
            logLabel.text =
                if (snapshot.logs.isEmpty()) {
                    "Logs will appear here"
                } else {
                    snapshot.logs.joinToString(separator = "\n")
                }
        }
    }

    private fun summarizePeers(peers: List<String>): String {
        return if (peers.isEmpty()) {
            "Peers: 0"
        } else {
            "Peers: ${peers.size}"
        }
    }

    private fun summarizePeerDetails(peers: List<String>, visible: Boolean): String {
        if (!visible) {
            return ""
        }
        return if (peers.isEmpty()) {
            "No peer IDs"
        } else {
            buildString {
                appendLine("Peer IDs:")
                peers.forEach { peer -> appendLine("- ${peer.takeLast(6)}") }
            }.trimEnd()
        }
    }

    private fun summarizeLifecycleStatus(logs: List<String>): String {
        val controlLog =
            logs.asReversed().firstOrNull { line ->
                line.startsWith("runtime stop requested") ||
                    line.startsWith("mesh.start() requested") ||
                    line.startsWith("mesh.start() ->") ||
                    line.startsWith("mesh.start() failed") ||
                    line.contains("Bluetooth preflight blocked") ||
                    line.contains("Bluetooth permissions denied") ||
                    line.contains("Bluetooth permissions granted")
            }
        return if (controlLog == null) {
            "Lifecycle: idle"
        } else {
            "Lifecycle: ${compactLifecycleStatus(controlLog)}"
        }
    }

    private fun compactLifecycleStatus(logLine: String): String {
        return when {
            logLine.startsWith("runtime stop requested") -> "Stop requested"
            logLine.startsWith("mesh.start() requested") -> "Start requested"
            logLine.startsWith("mesh.start() ->") -> logLine
                .removePrefix("mesh.start() -> ")
                .substringBefore(" elapsedMs=")
            logLine.startsWith("mesh.start() failed") -> "Start failed"
            logLine.contains("Bluetooth preflight blocked") -> "Bluetooth blocked"
            logLine.contains("Bluetooth permissions denied") -> "Permissions denied"
            logLine.contains("Bluetooth permissions granted") -> "Permissions granted"
            else -> logLine
        }
    }

    private fun ensurePermissionsAndStart(): Unit {
        val missingPermissions = ProofPermissionContract.missingPermissions(this)
        if (missingPermissions.isEmpty()) {
            startIfBluetoothReady()
        } else {
            requestPermissions(
                missingPermissions.toTypedArray(),
                ProofPermissionContract.REQUEST_PERMISSIONS_CODE,
            )
        }
    }

    private fun startIfBluetoothReady(): Unit {
        val bluetoothReadiness = ProofBluetoothContract.inspect(this)
        if (bluetoothReadiness.ready) {
            MeshLinkProofRuntime.start()
        } else {
            MeshLinkProofRuntime.appendLog(
                "Bluetooth preflight blocked; ${bluetoothReadiness.startupState.renderLogLabel()}; ${bluetoothReadiness.reason}"
            )
        }
    }
}
