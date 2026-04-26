package ch.trancee.meshlink.sample

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams
import android.widget.ScrollView
import android.widget.TextView
import ch.trancee.meshlink.engine.MeshEngine
import ch.trancee.meshlink.routing.PeerEvent
import ch.trancee.meshlink.transport.MeshLinkService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Main activity for the MeshLink S04 sample harness.
 *
 * Provides Start/Stop service controls, runtime permission handling, and a scrolling log view that
 * displays peer events, inbound messages, and delivery confirmations. Automatically sends a 512-byte
 * payload to any newly-connected peer for end-to-end UAT validation.
 *
 * Uses plain [Activity] + a manual [CoroutineScope] (Main dispatcher) to avoid requiring
 * AndroidX lifecycle-runtime-ktx in the sample module.
 */
class MainActivity : Activity() {

    companion object {
        private const val PERM_REQUEST_CODE = 1001
    }

    // ── UI elements ──────────────────────────────────────────────────────────

    private lateinit var logView: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    // ── Coroutine scope ──────────────────────────────────────────────────────

    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val flowJobs = mutableListOf<Job>()

    // ── Service binding ──────────────────────────────────────────────────────

    private var meshEngine: MeshEngine? = null
    private var serviceBound = false

    private val serviceConnection =
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val binder = service as MeshLinkService.LocalBinder
                val engine = binder.getEngine()
                meshEngine = engine
                addLog("Service connected — subscribing to engine flows")
                subscribeToEngine(engine)
                btnStop.isEnabled = true
                btnStart.isEnabled = false
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                meshEngine = null
                serviceBound = false
                cancelFlowJobs()
                addLog("Service disconnected")
                btnStart.isEnabled = true
                btnStop.isEnabled = false
            }
        }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Programmatic layout — keeps the sample self-contained without layout XML resources.
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 48, 24, 24)
            }

        val buttonRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        btnStart =
            Button(this).apply {
                text = "Start"
                setOnClickListener { checkAndStart() }
            }
        btnStop =
            Button(this).apply {
                text = "Stop"
                isEnabled = false
                setOnClickListener { stopAndUnbind() }
            }

        buttonRow.addView(btnStart)
        buttonRow.addView(btnStop)
        root.addView(buttonRow)

        logView = TextView(this).apply { textSize = 11f }
        scrollView = ScrollView(this).apply { addView(logView) }

        root.addView(
            scrollView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )

        setContentView(root)
        addLog("MeshLink Sample ready — press Start")
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelFlowJobs()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        activityScope.cancel()
    }

    // ── Start / stop helpers ─────────────────────────────────────────────────

    private fun checkAndStart() {
        val required = buildList {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        val missing =
            required.filter {
                checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
            }
        if (missing.isEmpty()) {
            startAndBindService()
        } else {
            requestPermissions(missing.toTypedArray(), PERM_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERM_REQUEST_CODE) {
            val denied =
                grantResults
                    .zip(permissions.toList())
                    .filter { (r, _) -> r != PackageManager.PERMISSION_GRANTED }
                    .map { (_, p) -> p }
            if (denied.isEmpty()) {
                addLog("All permissions granted — starting service")
                startAndBindService()
            } else {
                addLog("Permissions denied: ${denied.joinToString()} — cannot start")
            }
        }
    }

    private fun startAndBindService() {
        val intent = Intent(this, SampleMeshService::class.java)
        startForegroundService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        serviceBound = true
        addLog("Binding to SampleMeshService…")
    }

    private fun stopAndUnbind() {
        cancelFlowJobs()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        stopService(Intent(this, SampleMeshService::class.java))
        meshEngine = null
        addLog("Service stopped")
        btnStart.isEnabled = true
        btnStop.isEnabled = false
    }

    // ── Engine flow subscriptions ────────────────────────────────────────────

    private fun subscribeToEngine(engine: MeshEngine) {
        cancelFlowJobs()

        // Peer events — auto-send 512 B on connect.
        flowJobs +=
            activityScope.launch {
                engine.peerEvents.collect { event ->
                    when (event) {
                        is PeerEvent.Connected -> {
                            val hex = peerHex(event.peerId)
                            addLog("Peer connected: $hex…")
                            addLog("Auto-sending 512 B to $hex…")
                            val payload = ByteArray(512) { i -> (i and 0xFF).toByte() }
                            val result = engine.send(event.peerId, payload)
                            addLog("send() → $result")
                        }
                        is PeerEvent.Disconnected -> {
                            addLog("Peer disconnected: ${peerHex(event.peerId)}…")
                        }
                    }
                }
            }

        // Inbound messages.
        flowJobs +=
            activityScope.launch {
                engine.messages.collect { msg ->
                    addLog(
                        "MSG from ${peerHex(msg.senderId)}… size=${msg.payload.size} kind=${msg.kind}"
                    )
                }
            }

        // Delivery confirmations.
        flowJobs +=
            activityScope.launch {
                engine.deliveryConfirmations.collect { delivered ->
                    addLog("ACK msgId=${peerHex(delivered.messageId)}…")
                }
            }

        // Transfer failures.
        flowJobs +=
            activityScope.launch {
                engine.transferFailures.collect { failed ->
                    addLog("FAIL msgId=${peerHex(failed.messageId)}… outcome=${failed.outcome}")
                }
            }
    }

    private fun cancelFlowJobs() {
        flowJobs.forEach { it.cancel() }
        flowJobs.clear()
    }

    // ── Utilities ────────────────────────────────────────────────────────────

    private fun peerHex(id: ByteArray): String =
        id.take(4).joinToString("") { it.toUByte().toString(16).padStart(2, '0') }

    private fun addLog(line: String) {
        runOnUiThread {
            logView.append("$line\n")
            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }
}
