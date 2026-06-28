package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.MeshLinkState
import ch.trancee.meshlink.config.meshLinkConfig
import ch.trancee.meshlink.transport.BleTransport
import ch.trancee.meshlink.transport.OutboundFrame
import ch.trancee.meshlink.transport.TransportEvent
import ch.trancee.meshlink.transport.TransportSendResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking

class MeshEngineTest {
    @Test
    fun `create wires the provided transport into the returned MeshLink lifecycle`() =
        runBlocking<Unit> {
            // Arrange
            val transport = RecordingMeshEngineBleTransport()
            val meshLink =
                MeshEngine.create(
                    config = meshLinkConfig { appId = "mesh-engine-test" },
                    bleTransport = transport,
                )

            // Act
            val startResult = meshLink.start()
            val stopResult = meshLink.stop()

            // Assert
            assertEquals(ch.trancee.meshlink.api.StartResult.Started, startResult)
            assertEquals(ch.trancee.meshlink.api.StopResult.Stopped, stopResult)
            assertEquals(1, transport.startCalls)
            assertEquals(1, transport.stopCalls)
            assertEquals(MeshLinkState.Stopped, meshLink.state.value)
        }
}

private class RecordingMeshEngineBleTransport : BleTransport {
    override val events: Flow<TransportEvent> = emptyFlow()
    var startCalls: Int = 0
    var stopCalls: Int = 0

    override suspend fun start() {
        startCalls += 1
    }

    override suspend fun pause(): Unit = Unit

    override suspend fun resume(): Unit = Unit

    override suspend fun stop() {
        stopCalls += 1
    }

    override suspend fun send(frame: OutboundFrame): TransportSendResult =
        TransportSendResult.Delivered
}
