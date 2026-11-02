package ch.trancee.lab.host

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.TextView
import bakeoff.BakeoffConfig
import bakeoff.BakeoffRunner
import bakeoff.DeviceInfo
import bakeoff.Recorder
import bakeoff.ResultSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private var job: Job? = null
    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        statusView = TextView(this).apply { textSize = 18f; setPadding(32, 96, 32, 32) }
        setContentView(statusView)
        val args = intent.getStringExtra("args")
        if (args == null) {
            setStatus("idle: launch with --es args role=...,run=...")
            return
        }
        val missing = requiredPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 1)
            setStatus("missing permissions: $missing")
            Log.e(TAG, "missing permissions: $missing")
            return
        }
        startRun(BakeoffConfig.parse(listOf(args)))
    }

    private fun startRun(config: BakeoffConfig) {
        val manager = getSystemService(BluetoothManager::class.java)
        val adapter = manager.adapter
        val info = DeviceInfo(
            model = "${Build.MANUFACTURER} ${Build.MODEL}",
            os = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            platform = "android",
        )
        val resultsDir = File(filesDir, "results").apply { mkdirs() }
        val sink = FileLogSink(File(resultsDir, "${config.runId}.jsonl"))
        if (adapter == null || !adapter.isEnabled) {
            sink.emit("{\"run_id\":\"${config.runId}\",\"test\":\"done\",\"outcome\":\"fail\",\"error\":\"bluetooth off\"}")
            setStatus("bluetooth off")
            return
        }
        val central = AndroidCentral(this, adapter, sink, CentralTuning.from(config.raw))
        config.raw["survey"]?.toIntOrNull()?.let { seconds ->
            val recorder = Recorder(config, info, sink)
            val filtered = config.raw["survey_filter"] != "0"
            setStatus("survey ${config.runId} ${seconds}s filtered=$filtered")
            job = scope.launch {
                val outcome = runCatching {
                    central.survey(seconds, filtered) { entry ->
                        recorder.record("scan_survey_advertiser", "ok", null, null, extra = entry + ("filtered" to filtered))
                    }
                }
                recorder.record(
                    "done", if (outcome.isSuccess) "ok" else "fail", null, null,
                    error = outcome.exceptionOrNull()?.toString(),
                    extra = mapOf("advertisers_seen" to outcome.getOrNull(), "filtered" to filtered),
                )
                runOnUiThread { setStatus("survey done") }
            }
            return
        }
        val runner = BakeoffRunner(
            config, info, sink,
            central,
            AndroidPeripheral(this, manager, adapter, sink),
        ) { text -> runOnUiThread { setStatus(text) } }
        setStatus("run ${config.runId} role=${config.role}")
        job = runner.start(scope)
    }

    private fun setStatus(text: String) {
        statusView.text = "MeshLink bearer bake-off (PROTOTYPE)\n\n$text"
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun requiredPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    companion object {
        const val TAG: String = "BAKEOFF"
    }
}

class FileLogSink(private val file: File) : ResultSink {
    @Synchronized
    override fun emit(jsonLine: String) {
        Log.i(MainActivity.TAG, jsonLine)
        file.appendText(jsonLine + "\n")
    }

    override fun log(message: String) {
        Log.d(MainActivity.TAG, message)
    }
}
