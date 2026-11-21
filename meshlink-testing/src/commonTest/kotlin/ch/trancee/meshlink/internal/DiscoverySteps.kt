package ch.trancee.meshlink.internal

import ch.trancee.meshlink.MeshLink
import ch.trancee.meshlink.MeshState
import ch.trancee.meshlink.Preset
import ch.trancee.meshlink.Reachability
import ch.trancee.meshlink.SecurityProfile
import ch.trancee.meshlink.meshLinkConfig
import io.github.menjoo.cucumberkmp.core.runner.StepsBuilder
import io.github.menjoo.cucumberkmp.core.runner.given
import io.github.menjoo.cucumberkmp.core.runner.then
import io.github.menjoo.cucumberkmp.core.runner.whenever
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
internal fun StepsBuilder.discoverySteps() {
    lateinit var scope: TestScope
    var scopeInitialized = false
    val platforms = mutableListOf<VirtualPlatform>()
    val nodes = mutableListOf<MeshLink>()

    after {
        nodes.forEach { it.close() }
        if (scopeInitialized) scope.backgroundScope.cancel()
    }

    suspend fun createNodes(
        differentMesh: Boolean,
        differentProfile: Boolean = false,
    ) {
        val dispatcher = currentCoroutineContext()[ContinuationInterceptor] as TestDispatcher
        scope = TestScope(dispatcher)
        scopeInitialized = true
        val air = scope.air()
        repeat(2) { index ->
            val platform = air.platform(StandardTestDispatcher(scope.testScheduler))
            platforms += platform
            nodes +=
                if (differentProfile && index == 1) {
                    MeshLink.create(
                        meshLinkConfig(Preset.OfflineChat, SecurityProfile.Classical) { meshId = MESH }.build(),
                        platform.meshPlatform,
                    )
                } else if (differentMesh && index == 1) {
                    MeshLink.create(
                        meshLinkConfig(Preset.OfflineChat, SecurityProfile.Unsecured) { meshId = "Elsewhere" }.build(),
                        platform.meshPlatform,
                    )
                } else {
                    node(platform)
                }
        }
    }

    given("Alice and Bob are configured for the same mesh") {
        createNodes(differentMesh = false)
    }

    given("Alice and Bob are configured for different meshes") {
        createNodes(differentMesh = true)
    }

    given("Alice uses the unsecured profile and Bob uses the classical profile in the same mesh") {
        createNodes(differentMesh = false, differentProfile = true)
    }

    given("Alice and Bob use different keys for the same classical gated mesh") {
        val dispatcher = currentCoroutineContext()[ContinuationInterceptor] as TestDispatcher
        scope = TestScope(dispatcher)
        scopeInitialized = true
        val air = scope.air()
        repeat(2) { index ->
            val platform = air.platform(StandardTestDispatcher(scope.testScheduler))
            platforms += platform
            nodes +=
                MeshLink.create(
                    meshLinkConfig(Preset.OfflineChat, SecurityProfile.Classical) {
                        meshId = MESH
                        meshKey = ByteArray(32) { (index + 1).toByte() }
                    }.build(),
                    platform.meshPlatform,
                )
        }
    }

    whenever("the discovery nodes start") {
        nodes.forEach { it.start() }
        scope.testScheduler.advanceTimeBy(SETTLE_MILLIS)
    }

    then("Alice and Bob list each other as direct peers") {
        nodes.forEachIndexed { index, node ->
            assertEquals(1, node.peers.value.size)
            val peer = node.peers.value.single()
            assertContentEquals(nodeId(platforms[1 - index]), peer.nodeId.toByteArray())
            assertEquals(Reachability.Direct, peer.reachability)
        }
    }

    then("neither node lists a peer") {
        nodes.forEach { assertEquals(emptyList(), it.peers.value) }
    }

    then("both discovery nodes are running") {
        nodes.forEach { assertEquals(MeshState.Running, it.state.value) }
    }
}
