package ch.trancee.meshlink

import ch.trancee.meshlink.internal.BuildInfo
import ch.trancee.meshlink.internal.DiagnosticsExport
import ch.trancee.meshlink.internal.DiagnosticsHub
import ch.trancee.meshlink.internal.ExportHeader
import ch.trancee.meshlink.internal.LinkPolicy
import ch.trancee.meshlink.internal.PlatformPort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * A node's diagnostics (ADR 0020): its [events] as they happen, a [snapshot] of its state, and an
 * [export] for a bug report. Each works in any state, also once the node is closed.
 */
public class MeshDiagnostics internal constructor(
    private val hub: DiagnosticsHub,
    private val port: PlatformPort,
    private val state: (DiagnosticCounters) -> DiagnosticsSnapshot,
) {
    /**
     * This node's diagnostic events, from the moment a collector starts, without replay; the flow
     * completes when the node closes. Every collector gets every event. Each buffers 256 events: a
     * collector that falls further behind loses its oldest ones and then gets
     * [DiagnosticEvent.DiagnosticsDropped]. [DiagnosticEvent.Severity.Debug] events appear only with
     * [DiagnosticsBuilder.debug] on.
     */
    public val events: Flow<DiagnosticEvent> = hub.events

    /**
     * This node's redacted state now (ADR 0020 decision 5): its configuration with the mesh ID
     * redacted, its density class and open links while it runs, what it holds, and what it counted
     * since its last [MeshLink.start].
     */
    public suspend fun snapshot(): DiagnosticsSnapshot = withContext(port.dispatcher) { current() }

    /**
     * A JSON document for a bug report (ADR 0020 decision 6, `docs/reference/diagnostics-export.md`):
     * the MeshLink, protocol, platform and OS versions, the [snapshot], and the last 500 events this
     * node emitted since it was created, each with the wall-clock time it was emitted at. Holds
     * nothing an event or the snapshot doesn't show.
     */
    public suspend fun export(): String =
        withContext(port.dispatcher) {
            val header = ExportHeader(BuildInfo.VERSION, LinkPolicy.PROTOCOL_VERSION, port.platformName, port.osVersion)
            DiagnosticsExport.encode(header, current(), hub.history)
        }

    private fun current(): DiagnosticsSnapshot {
        val tallies = hub.tallies
        return state(
            DiagnosticCounters(
                hub.counts,
                tallies.getOrElse(DiagnosticsHub.Tally.FramesSent) { 0L },
                tallies.getOrElse(DiagnosticsHub.Tally.FramesRelayed) { 0L },
                tallies.getOrElse(DiagnosticsHub.Tally.HandshakesSucceeded) { 0L },
            ),
        )
    }
}
