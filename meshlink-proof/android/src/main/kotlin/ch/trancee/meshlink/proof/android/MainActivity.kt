package ch.trancee.meshlink.proof.android

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
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

    private fun summarizeLifecycleStatus(logs: List<String>): String {
        val controlLog =
            logs.asReversed().firstOrNull { line ->
                line.startsWith("runtime stop requested") ||
                    line.startsWith("PEER collector coroutine exiting") ||
                    line.startsWith("PEER collector completed") ||
                    line.startsWith("mesh.start() requested") ||
                    line.startsWith("mesh.start() ->") ||
                    line.contains("Bluetooth preflight blocked") ||
                    line.contains("Bluetooth permissions denied") ||
                    line.contains("Bluetooth permissions granted")
            }
        return if (controlLog == null) {
            "Lifecycle: idle"
        } else {
            "Lifecycle: $controlLog"
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
