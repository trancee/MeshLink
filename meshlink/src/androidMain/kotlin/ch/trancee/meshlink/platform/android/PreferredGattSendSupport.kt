package ch.trancee.meshlink.platform.android

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.engine.DirectWireFrame
import ch.trancee.meshlink.transport.BleDiscoveryPlatformFamily
import ch.trancee.meshlink.transport.GattDataBearerMode
import ch.trancee.meshlink.transport.OutboundFrame
import ch.trancee.meshlink.transport.TransportSendResult
import ch.trancee.meshlink.transport.resolveGattDataBearerMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

internal class PreferredGattSendContext
internal constructor(
    internal val hintPeerId: PeerId,
    internal val localPlatformFamily: BleDiscoveryPlatformFamily,
    internal val remotePlatformFamily: BleDiscoveryPlatformFamily,
    internal val localL2capClientSocketsSupported: Boolean,
)

internal interface PreferredGattSendClient {
    fun isReady(): Boolean

    suspend fun write(payload: ByteArray): Boolean
}

internal class PreferredGattSendDependencies
internal constructor(
    internal val ensureSideLink: () -> Unit,
    internal val currentClient: () -> PreferredGattSendClient?,
    internal val restartSideLink: (String) -> Unit,
    internal val log: (String) -> Unit,
)

internal suspend fun sendViaPreferredGattSideLinkOrNull(
    frame: OutboundFrame,
    context: PreferredGattSendContext,
    dependencies: PreferredGattSendDependencies,
): TransportSendResult? {
    val directFrame = runCatching { DirectWireFrame.decode(frame.payload) }.getOrNull()
    if (directFrame == null) {
        return null
    }

    val dataBearerMode =
        resolveGattDataBearerMode(
            localPlatformFamily = context.localPlatformFamily,
            remotePlatformFamily = context.remotePlatformFamily,
            preferredMode = frame.preferredMode,
            localL2capClientSocketsSupported = context.localL2capClientSocketsSupported,
        )
    dependencies.log(
        "preferred GATT side-link evaluation for ${context.hintPeerId.value.takeLast(6)} mode=$dataBearerMode local=${context.localPlatformFamily} remote=${context.remotePlatformFamily} preferred=${frame.preferredMode ?: "none"}"
    )
    if (dataBearerMode == GattDataBearerMode.L2CAP_ONLY) {
        dependencies.log(
            "preferred GATT side-link bypassed for ${context.hintPeerId.value.takeLast(6)} because bearerMode=L2CAP_ONLY"
        )
        return null
    }

    dependencies.ensureSideLink()
    val client =
        dependencies.currentClient()
            ?: run {
                dependencies.log(
                    "preferred GATT side-link missing client for ${context.hintPeerId.value.takeLast(6)}"
                )
                return null
            }
    val readyWaitTimeoutMillis = 2_000L
    dependencies.log(
        "preferred GATT side-link waiting for readiness for ${context.hintPeerId.value.takeLast(6)} timeoutMs=$readyWaitTimeoutMillis"
    )
    val ready =
        withTimeoutOrNull(readyWaitTimeoutMillis) {
            while (!client.isReady()) {
                delay(25)
            }
            true
        } == true
    if (!ready) {
        dependencies.log(
            "preferred GATT side-link send skipped for ${context.hintPeerId.value.takeLast(6)}: client not ready"
        )
        return null
    }

    val delivered =
        runCatching { client.write(frame.payload) }
            .onFailure { error ->
                dependencies.log(
                    "preferred GATT side-link send failed for ${context.hintPeerId.value.takeLast(6)}: ${error.message.orEmpty()}"
                )
            }
            .getOrDefault(false)

    return if (delivered) {
        dependencies.log(
            "sent ${frame.payload.size} bytes via GATT write side link for ${context.hintPeerId.value.takeLast(6)}"
        )
        TransportSendResult.Delivered
    } else {
        dependencies.log(
            "preferred GATT side-link send returned false for ${context.hintPeerId.value.takeLast(6)} bytes=${frame.payload.size}"
        )
        dependencies.restartSideLink("write failed for ${frame.payload.size} bytes")
        null
    }
}
