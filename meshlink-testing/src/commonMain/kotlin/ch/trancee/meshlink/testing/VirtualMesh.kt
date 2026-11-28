package ch.trancee.meshlink.testing

import ch.trancee.meshlink.InternalMeshLinkApi
import ch.trancee.meshlink.MeshLink
import ch.trancee.meshlink.MeshLinkConfig
import ch.trancee.meshlink.internal.SeededRandom
import ch.trancee.meshlink.internal.TestClock
import ch.trancee.meshlink.internal.VirtualAir
import ch.trancee.meshlink.internal.VirtualRadio
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlin.time.Duration

/**
 * A simulated Bluetooth mesh for testing an app's MeshLink code without radios.
 *
 * Each [node] is a real [MeshLink] on a virtual radio. Nodes start out of reach of each other; a
 * test links two with [connect] and parts them with [disconnect]. Time is the virtual time of
 * [scope]: a test lets the mesh work with [advance]. The same test produces the same run each time.
 *
 * Close each node before the test ends.
 */
@OptIn(ExperimentalCoroutinesApi::class, InternalMeshLinkApi::class)
public class VirtualMesh(
    private val scope: TestScope,
) {
    private val clock = TestClock(scope.testScheduler)
    private val air = VirtualAir(scope.backgroundScope, clock, SeededRandom(SEED), recording = false)
    private val nodes = mutableListOf<Pair<MeshLink, VirtualRadio>>()

    /** Creates a stopped node with [config], out of reach of every other node. */
    public fun node(config: MeshLinkConfig): MeshLink {
        val platform = air.platform(StandardTestDispatcher(scope.testScheduler))
        nodes.forEach { (_, radio) -> air.setReachable(radio, platform.radio, reachable = false) }
        val node = MeshLink.create(config, platform.meshPlatform)
        nodes += node to platform.radio
        return node
    }

    /**
     * Brings [first] and [second] within reach of each other at a signal strength of [rssi] dBm. They
     * find each other while both run; below -85 dBm they don't link.
     */
    public fun connect(
        first: MeshLink,
        second: MeshLink,
        rssi: Int = DEFAULT_RSSI,
    ) {
        val (a, b) = radios(first, second)
        air.setReachable(a, b, reachable = true)
        air.setRssi(a, b, rssi)
    }

    /** Takes [first] and [second] out of reach of each other; a link between them drops at once. */
    public fun disconnect(
        first: MeshLink,
        second: MeshLink,
    ) {
        val (a, b) = radios(first, second)
        air.setReachable(a, b, reachable = false)
    }

    /** Advances virtual time by [duration] and runs the work then due. */
    public fun advance(duration: Duration) {
        scope.testScheduler.advanceTimeBy(duration)
        scope.testScheduler.runCurrent()
    }

    private fun radios(
        first: MeshLink,
        second: MeshLink,
    ): Pair<VirtualRadio, VirtualRadio> {
        require(first !== second) { "A node can't connect to itself" }
        return radio(first) to radio(second)
    }

    private fun radio(node: MeshLink): VirtualRadio =
        requireNotNull(nodes.firstOrNull { it.first === node }) { "Not a node of this VirtualMesh" }.second

    private companion object {
        const val SEED = 1L
        const val DEFAULT_RSSI = -60
    }
}
