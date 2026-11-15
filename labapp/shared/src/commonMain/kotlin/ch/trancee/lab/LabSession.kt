package ch.trancee.lab

import ch.trancee.meshlink.MeshLink
import ch.trancee.meshlink.MeshLinkException
import ch.trancee.meshlink.MeshPlatform
import ch.trancee.meshlink.Preset
import ch.trancee.meshlink.SecurityProfile
import ch.trancee.meshlink.meshLinkConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.time.TimeMark

/**
 * One lab run on this phone. [start] parses the launch arguments, runs the scenario on [platform]
 * through the public API, timing it with [markNow], and writes `<run>.jsonl` to [results]. With
 * `grant=1` it only starts a node, so iOS asks for Bluetooth, and reports the node's state.
 */
public class LabSession internal constructor(
    private val platform: MeshPlatform,
    private val results: String,
    private val tokens: List<String>,
    private val markNow: () -> TimeMark,
    dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    /** Starts the run; [onStatus] receives a one-line status after each step, on a background thread. */
    public fun start(onStatus: (String) -> Unit) {
        scope.launch { if (GRANT in tokens) grant(onStatus) else run(onStatus) }
    }

    /** Stops the run without a `done` record. */
    public fun cancel() {
        scope.cancel()
    }

    private suspend fun run(onStatus: (String) -> Unit) {
        val runId = LabArgs.runId(tokens)
        val recorder =
            Recorder({ line ->
                if (runId != null) appendLine(results, "$runId.jsonl", line)
                onStatus(line)
            }, markNow())
        val failures =
            try {
                val args = LabArgs.parse(tokens)
                recorder.record(
                    "start",
                    "scenario" to args.scenario,
                    "run" to args.run,
                    "role" to (args.role?.name?.lowercase() ?: "any"),
                    "peer" to args.peer,
                    "mesh" to args.mesh,
                    "profile" to args.profile.label,
                    "duration_s" to args.duration.inWholeSeconds,
                )
                node(args.mesh).use { DirectMessage(it, args, recorder).run() }
            } catch (e: LabArgsException) {
                listOf(e.message.orEmpty())
            } catch (e: MeshLinkException) {
                listOf(e.message.orEmpty())
            }
        failures.forEach { recorder.record("error", "message" to it) }
        recorder.record("done", "result" to if (failures.isEmpty()) "pass" else "fail", "failures" to failures)
    }

    private suspend fun grant(onStatus: (String) -> Unit) {
        val node = node(GRANT_MESH)
        scope.launch { node.state.collect { onStatus("state=$it") } }
        node.start()
    }

    private suspend fun MeshLink.use(block: suspend (MeshLink) -> List<String>): List<String> =
        try {
            block(this)
        } finally {
            stop()
            close()
        }

    private fun node(mesh: String): MeshLink {
        val config = meshLinkConfig(Preset.OfflineChat, SecurityProfile.Unsecured) { meshId = mesh }.build()
        return MeshLink.create(config, platform)
    }

    private companion object {
        const val GRANT = "grant=1"
        const val GRANT_MESH = "meshlink-lab-grant"
    }
}
