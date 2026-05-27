package ch.trancee.meshlink.test

import ch.trancee.meshlink.api.MeshLink
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.config.MeshLinkConfig
import ch.trancee.meshlink.config.meshLinkConfig
import ch.trancee.meshlink.engine.MeshEngine
import ch.trancee.meshlink.identity.LocalIdentity
import ch.trancee.meshlink.transport.TransportMode

internal class MeshTestHarness {
    private val network = VirtualMeshNetwork()
    private val handles: MutableList<NodeHandle> = mutableListOf()

    internal fun linkPeers(
        first: NodeHandle,
        second: NodeHandle,
        mode: TransportMode = TransportMode.L2CAP,
    ): Unit {
        network.linkPeers(first.peerId, second.peerId, mode)
    }

    internal fun unlinkPeers(first: NodeHandle, second: NodeHandle): Unit {
        network.unlinkPeers(first.peerId, second.peerId)
    }

    internal fun createNode(
        peerIdValue: String,
        identityLabel: String = "default",
        storage: InMemorySecureStorage = InMemorySecureStorage(),
        configOverride: MeshLinkConfig? = null,
    ): NodeHandle {
        val peerId = PeerId(peerIdValue)
        val transport = VirtualMeshTransport(localPeerId = peerId, network = network)
        val diagnosticSink = RecordingDiagnosticSink()
        val config = configOverride ?: defaultConfig(appId = "$peerIdValue-$identityLabel")
        val meshLink =
            MeshEngine.create(
                config = config,
                localIdentity =
                    LocalIdentity.fromPeerId(
                        peerId = peerId,
                        identitySeed = "$peerIdValue-$identityLabel",
                    ),
                secureStorage = storage,
                bleTransport = transport,
                diagnosticSink = diagnosticSink,
            )
        val handle =
            NodeHandle(
                peerId = peerId,
                meshLink = meshLink,
                transport = transport,
                storage = storage,
                diagnosticSink = diagnosticSink,
            )
        handles += handle
        return handle
    }

    internal fun restartNode(handle: NodeHandle): NodeHandle {
        return createNode(peerIdValue = handle.peerId.value, storage = handle.storage)
    }

    internal fun lastDeliveredFrame(): ByteArray? {
        return handles.mapNotNull { it.transport.lastSentFrame() }.lastOrNull()
    }

    internal fun sentFrames(handle: NodeHandle): List<ByteArray> {
        return handle.transport.sentFrames()
    }

    internal fun clearedQueuedOutboundPeers(handle: NodeHandle): List<PeerId> {
        return handle.transport.clearedQueuedOutboundPeers()
    }

    internal fun discoverySuspendedTransitions(handle: NodeHandle): List<Boolean> {
        return handle.transport.discoverySuspendedTransitions()
    }

    internal fun setMaximumPayloadBytesPerDelivery(limit: Int?): Unit {
        network.setMaximumPayloadBytesPerDelivery(limit)
    }

    internal fun dropNextDeliveries(
        sender: NodeHandle,
        recipient: NodeHandle,
        count: Int = 1,
    ): Unit {
        network.dropNextDeliveries(sender.peerId, recipient.peerId, count)
    }

    internal fun duplicateNextDeliveries(
        sender: NodeHandle,
        recipient: NodeHandle,
        count: Int = 1,
    ): Unit {
        network.duplicateNextDeliveries(sender.peerId, recipient.peerId, count)
    }

    internal fun holdNextDeliveries(
        sender: NodeHandle,
        recipient: NodeHandle,
        count: Int = 1,
    ): Unit {
        network.holdNextDeliveries(sender.peerId, recipient.peerId, count)
    }

    internal fun releaseHeldDeliveries(sender: NodeHandle, recipient: NodeHandle): Unit {
        network.releaseHeldDeliveries(sender.peerId, recipient.peerId)
    }

    private fun defaultConfig(appId: String): MeshLinkConfig {
        return meshLinkConfig { this.appId = appId }
    }
}

internal class NodeHandle
internal constructor(
    internal val peerId: PeerId,
    internal val meshLink: MeshLink,
    internal val transport: VirtualMeshTransport,
    internal val storage: InMemorySecureStorage,
    internal val diagnosticSink: RecordingDiagnosticSink,
)
