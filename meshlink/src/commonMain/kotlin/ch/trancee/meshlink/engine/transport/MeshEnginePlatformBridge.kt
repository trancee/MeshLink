package ch.trancee.meshlink.engine.transport

import ch.trancee.meshlink.api.MeshLinkException
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.platform.PlatformPermissionDeniedException
import ch.trancee.meshlink.power.PowerPolicy
import ch.trancee.meshlink.transport.BleTransport
import ch.trancee.meshlink.transport.OutboundFrame
import ch.trancee.meshlink.transport.TransportEvent
import ch.trancee.meshlink.transport.TransportSendResult
import kotlinx.coroutines.flow.Flow

internal class MeshEnginePlatformBridge(private val bleTransport: BleTransport?) {
    internal val hasTransport: Boolean
        get() = bleTransport != null

    internal val events: Flow<TransportEvent>?
        get() = bleTransport?.events

    internal suspend fun start(): Unit {
        runPlatformCall("start") { bleTransport?.start() }
    }

    internal suspend fun pause(): Unit {
        runPlatformCall("pause") { bleTransport?.pause() }
    }

    internal suspend fun resume(): Unit {
        runPlatformCall("resume") { bleTransport?.resume() }
    }

    internal suspend fun stop(): Unit {
        runPlatformCall("stop") { bleTransport?.stop() }
    }

    internal suspend fun updatePowerPolicy(policy: PowerPolicy): Unit {
        runPlatformCall("updatePowerPolicy") { bleTransport?.updatePowerPolicy(policy) }
    }

    internal suspend fun setDiscoverySuspended(action: String, suspended: Boolean): Unit {
        runPlatformCall(action) { bleTransport?.setDiscoverySuspended(suspended) }
    }

    internal suspend fun clearQueuedOutboundFrames(peerId: PeerId, action: String): Unit {
        runPlatformCall(action) { bleTransport?.clearQueuedOutboundFrames(peerId) }
    }

    internal suspend fun promoteTemporaryPeer(
        temporaryPeerId: PeerId,
        canonicalPeerId: PeerId,
    ): Unit {
        runPlatformCall("promoteTemporaryPeer") {
            bleTransport?.promoteTemporaryPeer(temporaryPeerId, canonicalPeerId)
        }
    }

    internal fun maximumPayloadBytesPerDelivery(peerId: PeerId): Int? {
        return bleTransport?.maximumPayloadBytesPerDelivery(peerId)
    }

    internal suspend fun send(frame: OutboundFrame, action: String): TransportSendResult {
        val transport =
            bleTransport ?: return TransportSendResult.Dropped("BLE transport is unavailable")
        return runPlatformCall(action) { transport.send(frame) }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> runPlatformCall(action: String, block: suspend () -> T): T {
        return try {
            block()
        } catch (exception: PlatformPermissionDeniedException) {
            throw MeshLinkException.PermissionDenied(
                message = "Platform transport denied permission during $action",
                cause = exception,
            )
        } catch (exception: Throwable) {
            throw MeshLinkException.PlatformFailure(
                message = "Platform transport failed during $action",
                cause = exception,
            )
        }
    }
}
