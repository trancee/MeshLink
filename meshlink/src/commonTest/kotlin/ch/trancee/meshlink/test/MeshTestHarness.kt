package ch.trancee.meshlink.test

import ch.trancee.meshlink.api.MeshLinkApi
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.config.MeshLinkConfig
import ch.trancee.meshlink.config.meshLinkConfig
import ch.trancee.meshlink.engine.MeshEngine
import ch.trancee.meshlink.identity.LocalIdentity

internal class MeshTestHarness {
    private val network = VirtualMeshNetwork()
    private val handles: MutableList<NodeHandle> = mutableListOf()

    internal fun linkPeers(first: NodeHandle, second: NodeHandle): Unit {
        network.linkPeers(first.peerId, second.peerId)
    }

    internal fun unlinkPeers(first: NodeHandle, second: NodeHandle): Unit {
        network.unlinkPeers(first.peerId, second.peerId)
    }

    internal fun createNode(
        peerIdValue: String,
        identityLabel: String = "default",
        storage: InMemorySecureStorage = InMemorySecureStorage(),
    ): NodeHandle {
        val peerId = PeerId(peerIdValue)
        val transport = VirtualMeshTransport(localPeerId = peerId, network = network)
        val diagnosticSink = RecordingDiagnosticSink()
        val config = defaultConfig(appId = "$peerIdValue-$identityLabel")
        val api = MeshEngine.create(
            config = config,
            localIdentity = LocalIdentity.fromPeerId(peerId = peerId, identitySeed = "$peerIdValue-$identityLabel"),
            secureStorage = storage,
            bleTransport = transport,
            diagnosticSink = diagnosticSink,
        )
        val handle = NodeHandle(
            peerId = peerId,
            api = api,
            transport = transport,
            storage = storage,
            diagnosticSink = diagnosticSink,
        )
        handles += handle
        return handle
    }

    internal fun restartNode(handle: NodeHandle): NodeHandle {
        return createNode(
            peerIdValue = handle.peerId.value,
            storage = handle.storage,
        )
    }

    internal fun lastDeliveredFrame(): ByteArray? {
        return handles.mapNotNull { it.transport.lastSentFrame() }.lastOrNull()
    }

    internal fun sentFrames(handle: NodeHandle): List<ByteArray> {
        return handle.transport.sentFrames()
    }

    private fun defaultConfig(appId: String): MeshLinkConfig {
        return meshLinkConfig {
            this.appId = appId
        }
    }
}

internal class NodeHandle internal constructor(
    internal val peerId: PeerId,
    internal val api: MeshLinkApi,
    internal val transport: VirtualMeshTransport,
    internal val storage: InMemorySecureStorage,
    internal val diagnosticSink: RecordingDiagnosticSink,
)
