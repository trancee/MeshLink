package ch.trancee.meshlink.platform.ios

import ch.trancee.meshlink.engine.DirectWireFrame

internal class IosL2capFrameTelemetry
internal constructor(
    internal val directType: String,
    internal val dataClass: String,
    internal val innerBytes: Int,
)

internal fun classifyL2capFrame(payload: ByteArray): IosL2capFrameTelemetry {
    return runCatching { DirectWireFrame.decode(payload) }
        .getOrNull()
        ?.let { frame ->
            when (frame) {
                is DirectWireFrame.HandshakeMessage1 ->
                    IosL2capFrameTelemetry(
                        directType = "HANDSHAKE_MESSAGE_1",
                        dataClass = "handshake",
                        innerBytes = frame.payload.size,
                    )
                is DirectWireFrame.HandshakeMessage2 ->
                    IosL2capFrameTelemetry(
                        directType = "HANDSHAKE_MESSAGE_2",
                        dataClass = "handshake",
                        innerBytes = frame.payload.size,
                    )
                is DirectWireFrame.HandshakeMessage3 ->
                    IosL2capFrameTelemetry(
                        directType = "HANDSHAKE_MESSAGE_3",
                        dataClass = "handshake",
                        innerBytes = frame.payload.size,
                    )
                is DirectWireFrame.Data ->
                    IosL2capFrameTelemetry(
                        directType = "DATA",
                        dataClass =
                            if (frame.payload.size <= ACK_LIKELY_ENCRYPTED_BYTES) {
                                "ackLikely"
                            } else {
                                "bulkLikely"
                            },
                        innerBytes = frame.payload.size,
                    )
            }
        }
        ?: IosL2capFrameTelemetry(
            directType = "UNKNOWN",
            dataClass = "unknown",
            innerBytes = payload.size,
        )
}

internal fun emitL2capTelemetry(
    telemetryLogger: (String) -> Unit,
    event: String,
    fields: Map<String, String>,
): Unit {
    val body =
        fields.entries.joinToString(separator = " ") { entry -> "${entry.key}=${entry.value}" }
    telemetryLogger("MeshLinkTransportTelemetry event=$event $body")
}

internal const val ACK_LIKELY_ENCRYPTED_BYTES: Int = 192
