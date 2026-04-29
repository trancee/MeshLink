package ch.trancee.meshlink.testing

import ch.trancee.meshlink.api.ExperimentalMeshLinkApi
import ch.trancee.meshlink.api.MeshLink
import ch.trancee.meshlink.api.MeshLinkConfig
import ch.trancee.meshlink.crypto.createCryptoProvider
import ch.trancee.meshlink.power.FixedBatteryMonitor
import ch.trancee.meshlink.storage.InMemorySecureStorage
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

// ── linkedTransports ─────────────────────────────────────────────────────────

/**
 * Creates a pair of [VirtualMeshTransport] instances that are bidirectionally linked. Frames sent
 * by one transport are delivered to the other's [VirtualMeshTransport.incomingData] flow.
 *
 * ```
 * val (transportA, transportB) = linkedTransports()
 * val meshA = MeshLink.createForTest(config, transport = transportA)
 * val meshB = MeshLink.createForTest(config, transport = transportB)
 * ```
 *
 * @return A [Pair] of linked transports.
 */
@ExperimentalMeshLinkApi
public fun linkedTransports(): Pair<VirtualMeshTransport, VirtualMeshTransport> {
    val a = VirtualMeshTransport(peerId = randomPeerId())
    val b = VirtualMeshTransport(peerId = randomPeerId())
    a.linkTo(b)
    return Pair(a, b)
}

// ── MeshLink.createForTest ───────────────────────────────────────────────────

/**
 * Creates a [MeshLink] instance wired to the given [transport] for integration testing.
 *
 * Uses [InMemorySecureStorage] (keys are not persisted across test runs) and a software
 * [CryptoProvider]. No BLE hardware is required.
 *
 * ```
 * val (transportA, transportB) = linkedTransports()
 * val meshA = MeshLink.createForTest(config, transport = transportA)
 * val meshB = MeshLink.createForTest(config, transport = transportB)
 * meshA.start()
 * meshB.start()
 * // transportA and transportB exchange frames as if on real BLE
 * ```
 *
 * @param config MeshLink configuration for the test node.
 * @param transport A [VirtualMeshTransport] (typically from [linkedTransports]).
 * @param scope Optional [CoroutineScope] for the engine. Defaults to a new supervisor scope on
 *   [Dispatchers.Default].
 * @return A new [MeshLink] instance ready to [MeshLink.start].
 */
@ExperimentalMeshLinkApi
public fun MeshLink.Companion.createForTest(
    config: MeshLinkConfig,
    transport: VirtualMeshTransport,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
): MeshLink {
    val origin = TimeSource.Monotonic.markNow()
    val clock: () -> Long = { origin.elapsedNow().inWholeMilliseconds }
    return MeshLink.create(
        config = config,
        cryptoProvider = createCryptoProvider(),
        transport = transport.transport,
        storage = InMemorySecureStorage(),
        batteryMonitor = FixedBatteryMonitor(),
        parentScope = scope,
        clock = clock,
    )
}

// ── Helpers ──────────────────────────────────────────────────────────────────

private fun randomPeerId(): ByteArray {
    val bytes = ByteArray(12)
    for (i in bytes.indices) {
        bytes[i] = (kotlin.random.Random.nextInt(256) and 0xFF).toByte()
    }
    return bytes
}
