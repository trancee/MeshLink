@file:OptIn(ch.trancee.meshlink.benchmarking.UnstableMeshLinkBenchmarkApi::class)

package ch.trancee.meshlink.benchmarks

import ch.trancee.meshlink.api.MeshLink
import ch.trancee.meshlink.api.MeshLinkState
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.benchmarking.BenchmarkBleTransport as BleTransport
import ch.trancee.meshlink.benchmarking.BenchmarkCryptoProvider as JvmCryptoProvider
import ch.trancee.meshlink.benchmarking.BenchmarkDiagnosticSink as DiagnosticSink
import ch.trancee.meshlink.benchmarking.BenchmarkLocalIdentity
import ch.trancee.meshlink.benchmarking.BenchmarkOutboundFrame as OutboundFrame
import ch.trancee.meshlink.benchmarking.BenchmarkTransportEvent as TransportEvent
import ch.trancee.meshlink.benchmarking.BenchmarkTransportMode as TransportMode
import ch.trancee.meshlink.benchmarking.BenchmarkTransportSendResult as TransportSendResult
import ch.trancee.meshlink.benchmarking.createBenchmarkLocalIdentity
import ch.trancee.meshlink.benchmarking.createBenchmarkMeshLink
import ch.trancee.meshlink.config.meshLinkConfig
import ch.trancee.meshlink.diagnostics.DiagnosticEvent
import java.util.concurrent.TimeUnit
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.Blackhole
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.TearDown
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.openjdk.jmh.annotations.Level

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
class MemoryBudgetBenchmark {
    @Param("1", "8") var peerCount: Int = 8

    private val provider = JvmCryptoProvider()
    private lateinit var scenario: BenchmarkMeshScenario

    @Setup(Level.Invocation)
    fun prepare(): Unit {
        val peerIds = List(peerCount) { index -> PeerId("benchmark-peer-$index") }
        val localIdentities = peerIds.map { peerId ->
            createBenchmarkLocalIdentity(
                noiseIdentity = provider.generateNoiseIdentity(),
                peerId = peerId,
            )
        }
        scenario = BenchmarkMeshScenario(peerIds = peerIds, localIdentities = localIdentities)
    }

    @Benchmark
    fun establishPeerSteadyState(blackhole: Blackhole): Unit = runBlocking {
        blackhole.consume(scenario.establishSteadyState())
    }

    @TearDown(Level.Invocation)
    fun cleanup(): Unit = runBlocking {
        if (::scenario.isInitialized) {
            scenario.stop()
        }
    }
}

private class BenchmarkMeshScenario(
    peerIds: List<PeerId>,
    localIdentities: List<BenchmarkLocalIdentity>,
) {
    private val network = BenchmarkVirtualMeshNetwork()
    private val nodes: List<BenchmarkNode> =
        peerIds.indices.map { index -> createNode(peerIds[index], localIdentities[index]) }
    private val expectedDiscoveredPeerEvents: Int = peerIds.size * (peerIds.size - 1)

    init {
        nodes.indices.forEach { leftIndex ->
            ((leftIndex + 1) until nodes.size).forEach { rightIndex ->
                network.linkPeers(nodes[leftIndex].peerId, nodes[rightIndex].peerId)
            }
        }
    }

    suspend fun establishSteadyState(): Int {
        nodes.forEach { node -> node.api.start() }
        awaitSteadyState()
        val runningNodes = nodes.count { node -> node.api.state.value == MeshLinkState.Running }
        val discoveredPeers = nodes.sumOf { node -> node.transport.discoveredPeerCount() }
        check(runningNodes == nodes.size) {
            "All benchmark peers must reach the running state before allocation profiling"
        }
        check(discoveredPeers == expectedDiscoveredPeerEvents) {
            "The benchmark mesh must discover every peer before allocation profiling"
        }
        return discoveredPeers
    }

    private suspend fun awaitSteadyState(): Unit =
        withTimeout(250) {
            while (!isSteadyStateReached()) {
                delay(5)
            }
        }

    private fun isSteadyStateReached(): Boolean {
        val runningNodes = nodes.count { node -> node.api.state.value == MeshLinkState.Running }
        if (runningNodes != nodes.size) {
            return false
        }
        val discoveredPeers = nodes.sumOf { node -> node.transport.discoveredPeerCount() }
        return discoveredPeers == expectedDiscoveredPeerEvents
    }

    suspend fun stop(): Unit {
        nodes.asReversed().forEach { node -> runCatching { node.api.stop() } }
    }

    private fun createNode(peerId: PeerId, localIdentity: BenchmarkLocalIdentity): BenchmarkNode {
        val transport = BenchmarkVirtualMeshTransport(localPeerId = peerId, network = network)
        val api =
            createBenchmarkMeshLink(
                config = meshLinkConfig { appId = "benchmark.mesh" },
                localIdentity = localIdentity,
                bleTransport = transport,
                diagnosticSink = BenchmarkDiagnosticSink,
            )
        return BenchmarkNode(peerId = peerId, api = api, transport = transport)
    }
}

private class BenchmarkNode
internal constructor(
    internal val peerId: PeerId,
    internal val api: MeshLink,
    internal val transport: BenchmarkVirtualMeshTransport,
)

private object BenchmarkDiagnosticSink : DiagnosticSink {
    override fun emit(event: DiagnosticEvent): Unit = Unit
}

private class BenchmarkVirtualMeshTransport(
    internal val localPeerId: PeerId,
    private val network: BenchmarkVirtualMeshNetwork,
) : BleTransport {
    private var eventChannel: Channel<TransportEvent> =
        Channel<TransportEvent>(capacity = Channel.UNLIMITED)
    private var started: Boolean = false
    private var discoveredPeerCount: Int = 0

    override val events: Flow<TransportEvent>
        get() = eventChannel.receiveAsFlow()

    override suspend fun start(): Unit {
        started = true
        network.register(this)
    }

    override suspend fun pause(): Unit {
        started = false
    }

    override suspend fun resume(): Unit {
        started = true
        network.register(this)
    }

    override suspend fun stop(): Unit {
        started = false
        discoveredPeerCount = 0
        network.unregister(localPeerId)
        eventChannel = Channel<TransportEvent>(capacity = Channel.UNLIMITED)
    }

    override suspend fun send(frame: OutboundFrame): TransportSendResult {
        if (!started) {
            return TransportSendResult.Dropped("benchmark transport is not started")
        }
        if (
            network.deliver(
                senderPeerId = localPeerId,
                recipientPeerId = frame.peerId,
                payload = frame.payload,
            )
        ) {
            return TransportSendResult.Delivered
        }
        return TransportSendResult.Dropped("benchmark recipient is unavailable")
    }

    internal fun connect(peerId: PeerId, mode: TransportMode = TransportMode.GATT): Unit {
        discoveredPeerCount += 1
        dispatchEvent(TransportEvent.PeerDiscovered(peerId = peerId, transportMode = mode))
    }

    internal fun disconnect(peerId: PeerId): Unit {
        dispatchEvent(TransportEvent.PeerLost(peerId = peerId))
    }

    internal fun receive(senderPeerId: PeerId, payload: ByteArray): Unit {
        dispatchEvent(TransportEvent.FrameReceived(peerId = senderPeerId, payload = payload))
    }

    internal fun discoveredPeerCount(): Int {
        return discoveredPeerCount
    }

    private fun dispatchEvent(event: TransportEvent): Unit {
        check(eventChannel.trySend(event).isSuccess) {
            "benchmark transport event buffer overflowed for ${localPeerId.value}"
        }
    }
}

private class BenchmarkVirtualMeshNetwork {
    private val transports: MutableMap<String, BenchmarkVirtualMeshTransport> = linkedMapOf()
    private val linkedPeers: MutableSet<BenchmarkLinkKey> = linkedSetOf()

    fun register(transport: BenchmarkVirtualMeshTransport): Unit {
        transports[transport.localPeerId.value] = transport
        transports.values.forEach { other ->
            if (
                other !== transport &&
                    linkedPeers.contains(
                        BenchmarkLinkKey.of(transport.localPeerId, other.localPeerId)
                    )
            ) {
                other.connect(transport.localPeerId)
                transport.connect(other.localPeerId)
            }
        }
    }

    fun unregister(peerId: PeerId): Unit {
        transports.remove(peerId.value)
        transports.values.forEach { other -> other.disconnect(peerId) }
    }

    fun linkPeers(first: PeerId, second: PeerId): Unit {
        val linkKey = BenchmarkLinkKey.of(first, second)
        if (!linkedPeers.add(linkKey)) {
            return
        }
        val firstTransport = transports[first.value]
        val secondTransport = transports[second.value]
        if (firstTransport != null && secondTransport != null) {
            firstTransport.connect(second)
            secondTransport.connect(first)
        }
    }

    fun deliver(senderPeerId: PeerId, recipientPeerId: PeerId, payload: ByteArray): Boolean {
        if (!linkedPeers.contains(BenchmarkLinkKey.of(senderPeerId, recipientPeerId))) {
            return false
        }
        val recipient = transports[recipientPeerId.value] ?: return false
        recipient.receive(senderPeerId = senderPeerId, payload = payload)
        return true
    }
}

private class BenchmarkLinkKey
private constructor(private val left: String, private val right: String) {
    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is BenchmarkLinkKey) {
            return false
        }
        return left == other.left && right == other.right
    }

    override fun hashCode(): Int {
        var result = left.hashCode()
        result = 31 * result + right.hashCode()
        return result
    }

    internal companion object {
        internal fun of(first: PeerId, second: PeerId): BenchmarkLinkKey {
            return if (first.value <= second.value) {
                BenchmarkLinkKey(first.value, second.value)
            } else {
                BenchmarkLinkKey(second.value, first.value)
            }
        }
    }
}
