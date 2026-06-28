package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.wire.WireFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class MeshEngineRuntimeLateBindingContextTest {
    @Test
    fun `routing advertisement sender fails before registration`() {
        // Arrange
        val context = MeshEngineRuntimeLateBindingContext()

        // Act
        val error = assertFailsWith<IllegalStateException> { context.routingAdvertisementSender() }

        // Assert
        assertEquals("routingAdvertisementSender is not registered", error.message)
    }

    @Test
    fun `routing advertisement sender returns the registered callback`() =
        runBlocking<Unit> {
            // Arrange
            val context = MeshEngineRuntimeLateBindingContext()
            var observedPeerId: String? = null
            var observedFramePeerId: String? = null
            var observedAction: String? = null
            var observedEpoch: Long? = null
            context.registerRoutingAdvertisementSender { peerId, frame, action, hardRunToken ->
                observedPeerId = peerId.value
                observedFramePeerId = (frame as WireFrame.Hello).peerId.value
                observedAction = action
                observedEpoch = hardRunToken?.epoch
                true
            }
            val sender = context.routingAdvertisementSender()
            val peerId = PeerId("peer-abcdef")
            val frame = WireFrame.Hello(PeerId("peer-fedcba"), 1_000)
            val hardRunToken = MeshEngineHardRunToken(epoch = 7L)

            // Act
            val result = sender(peerId, frame, "routing.advertise", hardRunToken)

            // Assert
            assertTrue(result)
            assertEquals("peer-abcdef", observedPeerId)
            assertEquals("peer-fedcba", observedFramePeerId)
            assertEquals("routing.advertise", observedAction)
            assertEquals(7L, observedEpoch)
        }

    @Test
    fun `routing advertisement sender rejects second registration`() {
        // Arrange
        val context = MeshEngineRuntimeLateBindingContext()
        context.registerRoutingAdvertisementSender { _, _, _, _ -> true }

        // Act
        val error =
            assertFailsWith<IllegalStateException> {
                context.registerRoutingAdvertisementSender { _, _, _, _ -> false }
            }

        // Assert
        assertEquals("routingAdvertisementSender is already registered", error.message)
    }
}
