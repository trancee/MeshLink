package ch.trancee.meshlink.platform.ios

import ch.trancee.meshlink.api.PeerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

class GattNotifyLinkTest {
    @Test
    fun enqueueReturnsFalseWhenNoPeripheralAdapterIsAvailable(): Unit = runBlocking {
        // Arrange
        val fixture = GattNotifyLinkFixture(peripheralAdapter = null)

        // Act
        val delivered = fixture.link.enqueue(byteArrayOf(0x01, 0x02))

        // Assert
        assertFalse(delivered)
        assertEquals(0, fixture.schedulePumpRetryCalls)
    }

    @Test
    fun enqueueUsesThePeripheralAdapterAndRequestsLowLatencyOnce(): Unit = runBlocking {
        // Arrange
        val peripheralAdapter =
            FakeIosGattNotifyPeripheralAdapter(updateResults = listOf(true, true))
        val fixture = GattNotifyLinkFixture(peripheralAdapter = peripheralAdapter)

        // Act
        val firstDelivered = fixture.link.enqueue(byteArrayOf(0x01, 0x02))
        val secondDelivered = fixture.link.enqueue(byteArrayOf(0x03, 0x04))

        // Assert
        assertTrue(firstDelivered)
        assertTrue(secondDelivered)
        assertEquals(1, peripheralAdapter.lowLatencyRequests)
        assertEquals(2, peripheralAdapter.updateCalls)
        assertEquals(0, fixture.schedulePumpRetryCalls)
    }

    @Test
    fun enqueueSchedulesRetryWhenThePeripheralAdapterBackpressures(): Unit = runBlocking {
        // Arrange
        val peripheralAdapter =
            FakeIosGattNotifyPeripheralAdapter(updateResults = listOf(false, true))
        val fixture = GattNotifyLinkFixture(peripheralAdapter = peripheralAdapter)

        // Act
        val delivery = async { fixture.link.enqueue(byteArrayOf(0x01, 0x02)) }
        while (fixture.schedulePumpRetryCalls == 0) {
            yield()
        }
        val pumpResult = fixture.link.pump()
        val delivered = delivery.await()

        // Assert
        assertTrue(pumpResult)
        assertTrue(delivered)
        assertEquals(1, fixture.schedulePumpRetryCalls)
        assertEquals(2, peripheralAdapter.updateCalls)
    }
}

private class GattNotifyLinkFixture(peripheralAdapter: FakeIosGattNotifyPeripheralAdapter?) {
    var schedulePumpRetryCalls: Int = 0

    val link: GattNotifyLink =
        GattNotifyLink(
            peer =
                GattNotifyPeer(
                    hintPeerId = PeerId("peer-ios"),
                    centralIdentifier = "central-1",
                    maximumUpdateValueLength = 182,
                ),
            dependencies =
                GattNotifyDependencies(
                    peripheralAdapterProvider = { peripheralAdapter },
                    runPump = { block -> block() },
                    logger = {},
                    schedulePumpRetry = { schedulePumpRetryCalls += 1 },
                ),
        )
}

private class FakeIosGattNotifyPeripheralAdapter(updateResults: List<Boolean>) :
    GattNotifyPeripheralAdapter {
    private val queuedResults: ArrayDeque<Boolean> = ArrayDeque(updateResults)
    var lowLatencyRequests: Int = 0
    var updateCalls: Int = 0

    override fun requestLowConnectionLatency(): Unit {
        lowLatencyRequests += 1
    }

    override fun updateValue(chunk: ByteArray): Boolean {
        updateCalls += 1
        return if (queuedResults.isNotEmpty()) queuedResults.removeFirst() else true
    }
}
