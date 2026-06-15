@file:Suppress("MaxLineLength")

package ch.trancee.meshlink.proof.android

import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log

private const val BRIDGE_LOG_TAG = "MeshLinkReferenceAutomation"

/**
 * Sender-side bridge used by the reference app during Android direct proof when the passive side
 * runs the proof GATT server fixture. It reuses the proof app's GATT-notify client but keeps the
 * reference app as the launched binary.
 */
public class ReferenceDirectProofGattNotifyBridge(
    context: Context,
    private val appId: String,
) {
    private val appContext = context.applicationContext
    private val bluetoothManager =
        appContext.getSystemService(BluetoothManager::class.java)
            ?: error("BluetoothManager is unavailable")

    private var client: ProofGattBenchmarkClient? = null
    private var started: Boolean = false

    public fun start(): Unit {
        if (started) {
            return
        }
        started = true
        Log.i(
            BRIDGE_LOG_TAG,
            "REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=SENDER scenario=direct-guided appId=$appId transport=gatt-benchmark",
        )
        Log.i(BRIDGE_LOG_TAG, "REFERENCE_AUTOMATION send.requested role=sender")
        val bridgeClient =
            ProofGattBenchmarkClient(
                context = appContext,
                bluetoothManager = bluetoothManager,
                logger = ::logBridgeLine,
                stateDidChange = ::handleStateChange,
                appId = appId,
            )
        client = bridgeClient
        runCatching { bridgeClient.start() }
            .onSuccess {
                Log.i(BRIDGE_LOG_TAG, "gatt.start() -> Started")
            }
            .onFailure { error ->
                Log.i(
                    BRIDGE_LOG_TAG,
                    "gatt.start() failed: ${error.javaClass.simpleName}: ${error.message.orEmpty()}",
                )
            }
    }

    public fun stop(): Unit {
        client?.stop()
        client = null
        started = false
    }

    private fun logBridgeLine(message: String): Unit {
        Log.i(BRIDGE_LOG_TAG, message)
    }

    private fun handleStateChange(state: String): Unit {
        Log.i(BRIDGE_LOG_TAG, "REFERENCE_AUTOMATION bridge.state $state")
        when {
            state.startsWith("Connecting(") -> {
                Log.i(BRIDGE_LOG_TAG, "REFERENCE_AUTOMATION peer.discovered role=SENDER peer=gatt-notify-bridge")
            }
            state.startsWith("Receiving(") -> {
                Log.i(BRIDGE_LOG_TAG, "REFERENCE_AUTOMATION send.requested role=sender")
            }
            state.startsWith("Completed(") -> {
                Log.i(BRIDGE_LOG_TAG, "REFERENCE_AUTOMATION proof.complete role=sender")
            }
            state.startsWith("Error(") -> {
                Log.i(BRIDGE_LOG_TAG, "REFERENCE_AUTOMATION proof.failed role=sender reason=$state")
            }
        }
    }
}
