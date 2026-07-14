package ch.trancee.meshlink.engine.internal

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity
import ch.trancee.meshlink.engine.assembly.MeshEngineHardRunToken
import ch.trancee.meshlink.wire.WireFrame

/**
 * Shared callback shape used throughout the engine to report a diagnostic event: code, severity, a
 * short stage label, an optional log-safe peer suffix, an optional structured reason, and a
 * free-form metadata map. Declared once here so every
 * `MeshEngine*Support`/`buildMeshEngineRuntime*` constructor that accepts an `emitDiagnostic`
 * callback shares one canonical type instead of each redeclaring the same six-parameter function
 * type inline.
 */
internal typealias MeshEngineEmitDiagnostic =
    (
        code: DiagnosticCode,
        severity: DiagnosticSeverity,
        stage: String,
        peerSuffix: String?,
        reason: DiagnosticReason?,
        metadata: Map<String, String>,
    ) -> Unit

/**
 * Shared callback shape used to send an already-encrypted [WireFrame] as a hop-encrypted frame
 * towards a single next-hop peer, given a log-safe stage label and an optional
 * [MeshEngineHardRunToken] gating the send. Declared once here so the many
 * `MeshEngine*Support`/`buildMeshEngineRuntime*` constructors that accept a
 * `sendEncryptedWireFrame` callback share one canonical type.
 */
internal typealias MeshEngineSendEncryptedWireFrame =
    suspend (
        peerId: PeerId, frame: WireFrame, stage: String, hardRunToken: MeshEngineHardRunToken?,
    ) -> Boolean

/**
 * Shared callback shape used to compute the route-metadata map advertised for a peer, given the
 * peer id and the metadata gathered so far. Declared once here so the constructors that accept a
 * `peerRouteMetadata` callback share one canonical type.
 */
internal typealias MeshEnginePeerRouteMetadata =
    suspend (peerId: PeerId, metadata: Map<String, String>) -> Map<String, String>
