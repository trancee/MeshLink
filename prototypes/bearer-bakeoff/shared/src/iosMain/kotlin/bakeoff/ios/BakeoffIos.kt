package bakeoff.ios

import bakeoff.BakeoffConfig
import bakeoff.BakeoffRunner
import bakeoff.DeviceInfo
import bakeoff.ResultSink
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
        if (tokens.none { it.startsWith("role=") }) {
            onStatus("idle: launch with arguments role=... run=...")
            return
        }
        val config = BakeoffConfig.parse(tokens)
        val sink = FileLogSink(resultsPath(config.runId))
        val info = DeviceInfo(model = "Apple ${machine()}", os = "iOS ${UIDevice.currentDevice.systemVersion}", platform = "ios")
        val runner = BakeoffRunner(config, info, sink, IosCentral(sink), IosPeripheral(sink), onStatus)
        onStatus("run ${config.runId} role=${config.role}")
        job = runner.start(scope)
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
        NSLog("BAKEOFF %@", jsonLine)
        val handle = NSFileHandle.fileHandleForWritingAtPath(path) ?: return
        handle.seekToEndOfFile()
        handle.writeData("$jsonLine\n".encodeToByteArray().toNSData())
        handle.closeFile()
    }

    override fun log(message: String) {
        NSLog("BAKEOFF-LOG %@", message)
    }
}
