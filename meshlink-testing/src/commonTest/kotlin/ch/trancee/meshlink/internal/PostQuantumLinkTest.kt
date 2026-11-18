package ch.trancee.meshlink.internal

import ch.trancee.meshlink.SecurityProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PostQuantumLinkTest : SecuredLinkTest(SecurityProfile.PostQuantum, WireProfile.POST_QUANTUM) {
    @Test
    fun anXXhfsHandshakeSendsEachMessageAsFragmentsOfAtMost4096Bytes() =
        listOf(null to listOf(OPEN_1, OPEN_2, OPEN_3), MESH_KEY to listOf(GATED_1, GATED_2, GATED_3))
            .forEach { (meshKey, sizes) ->
                runTest {
                    // Arrange
                    val air = air()
                    val alice = air.platform(StandardTestDispatcher(testScheduler))
                    val bob = air.platform(StandardTestDispatcher(testScheduler))
                    val nodes = listOf(securedNode(alice, meshKey), securedNode(bob, meshKey))

                    // Act
                    nodes.forEach { it.start() }
                    advanceTimeBy(SETTLE_MILLIS)

                    // Assert
                    val ends = listOf(alice, bob).map { platform -> platform.radio.ends.single { it.open } }
                    val (initiator, responder) = ends.sortedBy { handshakeIn(clearFrames(it).first()).step }
                    val frames = written(initiator, clear = 2).first + written(responder, clear = 1).first
                    val bySteps = frames.map(::handshakeIn).sortedBy { it.step }
                    assertEquals(listOf(1, 2, 3), bySteps.map { it.step })
                    assertEquals(sizes, bySteps.map { it.noiseMessage.size })
                    assertTrue(bySteps.all { it.noiseMessage.size <= LinkFrame.Handshake.NOISE_MESSAGE_MAX })
                    val sealed = written(initiator, clear = 2).second.size to written(responder, clear = 1).second.size
                    assertEquals(RECORDS_1 + RECORDS_3, bodies(initiator).size - sealed.first)
                    assertEquals(RECORDS_2, bodies(responder).size - sealed.second)
                    assertTrue(
                        nodes.all { node ->
                            node.peers.value
                                .single()
                                .secured
                        },
                    )
                }
            }

    private companion object {
        // Noise message sizes with an 1,357 B payload: the certificate in a TLV.
        const val OPEN_1 = 1_216
        const val OPEN_2 = 2_557
        const val OPEN_3 = 1_421

        // `psk3` keys the cipher from `e` on, which tags message 1's `e1` and payload; messages 2
        // and 3 are already encrypted after `ee`, and add the 39 B RESOLVING_KEY TLV to the payload.
        const val GATED_1 = 1_248
        const val GATED_2 = 2_596
        const val GATED_3 = 1_460
        const val RECORDS_1 = 2
        const val RECORDS_2 = 3
        const val RECORDS_3 = 2
        val MESH_KEY = ByteArray(32) { 0x11 }
    }
}
