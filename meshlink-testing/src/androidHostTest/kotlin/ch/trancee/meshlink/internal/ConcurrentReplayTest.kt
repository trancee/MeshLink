package ch.trancee.meshlink.internal

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import java.util.concurrent.Executors
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** A seeded mesh replays the same way whether it runs alone or beside other meshes on other threads. */
class ConcurrentReplayTest {
    @Test
    fun seedRunBesideOtherSeedsReplaysAsAlone() {
        val alone = trace(SEED)
        val pool = Executors.newFixedThreadPool(SEEDS.size)

        val besides =
            try {
                List(ROUNDS) { SEEDS.map { pool.submit<List<String>> { trace(it) } }.map { it.get() }.first() }
            } finally {
                pool.shutdownNow()
            }

        assertEquals(List(ROUNDS) { alone }, besides)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun trace(seed: Long): List<String> {
        val scope = TestScope()
        val random = Random(seed)
        val mesh = SeededMesh(scope, seed, List(NODES) { random.nextDouble(SIDE) to random.nextDouble(SIDE) })
        scope.launch { mesh.start() }
        mesh.dropLinksAtRandom(MEAN_LINK_MILLIS)
        scope.testScheduler.advanceTimeBy(RUN_MILLIS)
        return mesh.trace().also {
            mesh.close()
            scope.cancel()
        }
    }

    private companion object {
        const val SEED = 1L
        val SEEDS = listOf(SEED, 2L, 3L, 4L, 5L)
        const val ROUNDS = 3
        const val NODES = 30
        const val SIDE = 150.0
        const val MEAN_LINK_MILLIS = 20_000L
        const val RUN_MILLIS = 120_000L
    }
}
