package bakeoff.ios

import bakeoff.BakeoffConfig
import bakeoff.BakeoffRunner
import bakeoff.DeviceInfo
import bakeoff.Recorder
import bakeoff.ResultSink
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBManager
import platform.CoreBluetooth.CBPeripheralManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import platform.CoreBluetooth.CBManagerAuthorizationAllowedAlways
import platform.CoreBluetooth.CBManagerAuthorizationDenied
import platform.CoreBluetooth.CBManagerAuthorizationNotDetermined
import platform.CoreBluetooth.CBManagerAuthorizationRestricted
import platform.Foundation.NSDate
import platform.Foundation.NSISO8601DateFormatter
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSLog
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.closeFile
import platform.Foundation.fileHandleForWritingAtPath
import platform.Foundation.seekToEndOfFile
import platform.Foundation.writeData
import platform.UIKit.UIDevice
import platform.posix.uname
import platform.posix.utsname

/** Swift entry point: `BakeoffIos.shared.start(arguments: CommandLine.arguments) { status in ... }`. */
object BakeoffIos {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    fun start(arguments: List<String>, onStatus: (String) -> Unit) {
        val tokens = arguments.filter { '=' in it }
        if ("grant=1" in tokens) {
            grant(onStatus)
            return
        }
        if (tokens.none { it.startsWith("role=") }) {
            onStatus("idle: launch with arguments role=... run=...")
            return
        }
        val config = BakeoffConfig.parse(tokens)
        val sink = FileLogSink(resultsPath(config.runId))
        val info = DeviceInfo(model = "Apple ${machine()}", os = "iOS ${UIDevice.currentDevice.systemVersion}", platform = "ios")
        val central = IosCentral(sink)
        sink.emit(authorizationRecord(config, info))
        config.raw["survey"]?.toIntOrNull()?.let { seconds ->
            val recorder = Recorder(config, info, sink)
            val filtered = config.raw["survey_filter"] != "0"
            onStatus("survey ${config.runId} ${seconds}s filtered=$filtered")
            job = scope.launch {
                val outcome = runCatching {
                    central.survey(seconds, filtered) { entry ->
                        recorder.record("scan_survey_advertiser", "ok", null, null, extra = entry + ("filtered" to filtered))
                    }
                }
                recorder.record("done", if (outcome.isSuccess) "ok" else "fail", null, null, error = outcome.exceptionOrNull()?.toString())
                onStatus("survey done")
            }
            return
        }
        val runner = BakeoffRunner(config, info, sink, central, IosPeripheral(sink), onStatus)
        onStatus("run ${config.runId} role=${config.role} bt_auth=${authorizationName()}")
        job = runner.start(scope)
    }

    private var grantManagers: List<Any> = emptyList()

    /** Creates both managers (raising the permission alert if undetermined) and reports authorization until decided. */
    private fun grant(onStatus: (String) -> Unit) {
        grantManagers = listOf(CBCentralManager(delegate = null, queue = null), CBPeripheralManager(delegate = null, queue = null))
        scope.launch {
            while (true) {
                val auth = authorizationName()
                onStatus("grant bt_auth=$auth")
                if (auth != "not_determined") break
                delay(250)
            }
        }
    }

    private fun authorizationName(): String = when (CBManager.authorization) {
        CBManagerAuthorizationAllowedAlways -> "allowed_always"
        CBManagerAuthorizationDenied -> "denied"
        CBManagerAuthorizationRestricted -> "restricted"
        CBManagerAuthorizationNotDetermined -> "not_determined"
        else -> "unknown(${CBManager.authorization})"
    }

    /** Launch-time Bluetooth authorization; runs whose record is not `allowed_always` are invalid. */
    private fun authorizationRecord(config: BakeoffConfig, info: DeviceInfo): String {
        val auth = authorizationName()
        val outcome = if (auth == "allowed_always") "ok" else "fail"
        return "{\"run_id\":\"${config.runId}\",\"commit\":\"${config.commit}\",\"date\":\"${NSISO8601DateFormatter().stringFromDate(NSDate())}\",\"role\":\"${config.role.name.lowercase()}\"," +
            "\"reporter\":{\"model\":\"${info.model}\",\"os\":\"${info.os}\",\"platform\":\"ios\"}," +
            "\"test\":\"ios_bt_authorization\",\"outcome\":\"$outcome\",\"authorization\":\"$auth\"}"
    }

    private fun resultsPath(runId: String): String {
        val documents = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String
        val directory = "$documents/results"
        NSFileManager.defaultManager.createDirectoryAtPath(directory, withIntermediateDirectories = true, attributes = null, error = null)
        return "$directory/$runId.jsonl"
    }

    private fun machine(): String = memScoped {
        val name = alloc<utsname>()
        uname(name.ptr)
        name.machine.toKString()
    }
}

class FileLogSink(private val path: String) : ResultSink {
    init {
        if (!NSFileManager.defaultManager.fileExistsAtPath(path)) {
            NSFileManager.defaultManager.createFileAtPath(path, contents = null, attributes = null)
        }
    }

    override fun emit(jsonLine: String) {
        nsLog("BAKEOFF $jsonLine")
        val handle = NSFileHandle.fileHandleForWritingAtPath(path) ?: return
        handle.seekToEndOfFile()
        handle.writeData("$jsonLine\n".encodeToByteArray().toNSData())
        handle.closeFile()
    }

    override fun log(message: String) {
        nsLog("BAKEOFF-LOG $message")
    }
}

/** Kotlin Strings passed as NSLog varargs are not bridged to NSString (crashes in objc_opt_respondsToSelector), so escape and use no varargs. */
internal fun nsLog(message: String) = NSLog(message.replace("%", "%%"))
