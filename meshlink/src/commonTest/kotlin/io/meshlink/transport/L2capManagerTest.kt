package io.meshlink.transport

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// ---------------------------------------------------------------------------
// Fake L2capChannel used by tests
// ---------------------------------------------------------------------------

class FakeL2capChannel(
    override val psm: Int = 128,
    var open: Boolean = true,
) : L2capChannel {
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 16)
    override val incoming: SharedFlow<ByteArray> = _incoming.asSharedFlow()
    override val isOpen: Boolean get() = open

    val written = mutableListOf<ByteArray>()

    override suspend fun write(data: ByteArray) {
        written.add(data)
    }

    override fun close() {
        open = false
    }
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

class L2capManagerTest {

    private fun peerId(vararg bytes: Int) = ByteArray(bytes.size) { bytes[it].toByte() }

    // --- openChannel: happy path ---

    @Test
    fun openChannelReturnsChannelOnSuccess() = runTest {
        val fake = FakeL2capChannel()
        val manager = L2capManager(channelFactory = { fake })

        val ch = manager.openChannel(peerId(1, 2, 3))
        assertNotNull(ch, "Should return a channel")
        assertTrue(ch.isOpen)
    }

    @Test
    fun openChannelReturnsCachedChannel() = runTest {
        var calls = 0
        val fake = FakeL2capChannel()
        val manager = L2capManager(channelFactory = { calls++; fake })

        val ch1 = manager.openChannel(peerId(1))
        val ch2 = manager.openChannel(peerId(1))
        assertEquals(1, calls, "Factory should be called only once for the same peer")
        assertEquals(ch1, ch2)
    }

    // --- openChannel: retries ---

    @Test
    fun openChannelRetriesOnFailure() = runTest {
        var attempt = 0
        val fake = FakeL2capChannel()
        val manager = L2capManager(
            channelFactory = {
                attempt++
                if (attempt < 3) throw RuntimeException("fail")
                fake
            },
            maxRetries = 3,
            initialBackoffMillis = 1,
            delayFn = { /* no-op for test speed */ },
        )

        val ch = manager.openChannel(peerId(1))
        assertNotNull(ch, "Should succeed on 3rd attempt")
        assertEquals(3, attempt)
    }

    @Test
    fun openChannelReturnsNullAfterAllRetriesFail() = runTest {
        var attempt = 0
        val manager = L2capManager(
            channelFactory = { attempt++; throw RuntimeException("always fails") },
            maxRetries = 3,
            initialBackoffMillis = 1,
            delayFn = { },
        )

        val ch = manager.openChannel(peerId(1))
        assertNull(ch, "Should return null after exhausting retries")
        assertEquals(4, attempt, "1 initial + 3 retries = 4 attempts")
    }

    @Test
    fun openChannelReturnsNullWhenFactoryReturnsNull() = runTest {
        val manager = L2capManager(
            channelFactory = { null },
            maxRetries = 3,
            initialBackoffMillis = 1,
            delayFn = { },
        )

        val ch = manager.openChannel(peerId(1))
        assertNull(ch, "Factory returning null should exhaust retries and return null")
    }

    // --- circuit breaker ---

    @Test
    fun circuitBreakerTripsAfterRepeatedFailures() = runTest {
        var now = 0L
        var attempt = 0
        val manager = L2capManager(
            channelFactory = { attempt++; throw RuntimeException("fail") },
            maxRetries = 0,          // fail immediately (1 attempt each)
            initialBackoffMillis = 1,
            circuitBreakerFailures = 3,
            circuitBreakerWindowMillis = 300_000L,
            circuitBreakerCooldownMillis = 1_800_000L,
            clock = { now },
            delayFn = { },
        )
        val peer = peerId(0xAA)

        // 3 failed openChannel calls
        assertNull(manager.openChannel(peer))
        now = 1000L
        assertNull(manager.openChannel(peer))
        now = 2000L
        assertNull(manager.openChannel(peer))
        assertEquals(3, attempt, "3 factory calls before breaker trips")

        // Breaker is open → factory should NOT be called
        now = 3000L
        attempt = 0
        assertNull(manager.openChannel(peer))
        assertEquals(0, attempt, "Factory should not be called while breaker is open")
    }

    @Test
    fun circuitBreakerResetsAfterCooldown() = runTest {
        var now = 0L
        val fake = FakeL2capChannel()
        var shouldFail = true
        val manager = L2capManager(
            channelFactory = {
                if (shouldFail) throw RuntimeException("fail")
                fake
            },
            maxRetries = 0,
            initialBackoffMillis = 1,
            circuitBreakerFailures = 3,
            circuitBreakerWindowMillis = 300_000L,
            circuitBreakerCooldownMillis = 1_800_000L,
            clock = { now },
            delayFn = { },
        )
        val peer = peerId(0xBB)

        // Trip the breaker (last failure at t=2000 → breaker opens until t=2000+1_800_000)
        repeat(3) { i -> now = i * 1000L; manager.openChannel(peer) }

        // Advance past cooldown (must be >= 2000 + 1_800_000 = 1_802_000)
        now = 1_802_001L
        shouldFail = false
        val ch = manager.openChannel(peer)
        assertNotNull(ch, "After cooldown, breaker resets and factory is called again")
    }

    @Test
    fun circuitBreakerOnlyCountsFailuresInWindow() = runTest {
        var now = 0L
        val manager = L2capManager(
            channelFactory = { throw RuntimeException("fail") },
            maxRetries = 0,
            initialBackoffMillis = 1,
            circuitBreakerFailures = 3,
            circuitBreakerWindowMillis = 5_000L,
            circuitBreakerCooldownMillis = 1_800_000L,
            clock = { now },
            delayFn = { },
        )
        val peer = peerId(0xCC)

        // 2 failures, then time passes beyond the window
        now = 1000L; manager.openChannel(peer)
        now = 2000L; manager.openChannel(peer)

        // Advance so the first two failures expire
        now = 8000L; manager.openChannel(peer) // 3rd failure but only 1 in window

        // Breaker should still be closed (only 1 failure in window)
        // If breaker were open, this would return null without calling factory.
        // We can verify by checking that the factory IS called again.
        var factoryCalled = false
        val manager2 = L2capManager(
            channelFactory = { factoryCalled = true; throw RuntimeException("fail") },
            maxRetries = 0,
            initialBackoffMillis = 1,
            circuitBreakerFailures = 3,
            circuitBreakerWindowMillis = 5_000L,
            circuitBreakerCooldownMillis = 1_800_000L,
            clock = { now },
            delayFn = { },
        )
        manager2.openChannel(peer)
        assertTrue(factoryCalled, "Breaker should still be closed; factory should be called")
    }

    // --- getChannel ---

    @Test
    fun getChannelReturnsNullWhenNoneOpen() {
        val manager = L2capManager()
        assertNull(manager.getChannel(peerId(1)))
    }

    @Test
    fun getChannelReturnsOpenChannel() = runTest {
        val fake = FakeL2capChannel()
        val manager = L2capManager(channelFactory = { fake })
        manager.openChannel(peerId(1))

        val ch = manager.getChannel(peerId(1))
        assertNotNull(ch)
        assertTrue(ch.isOpen)
    }

    @Test
    fun getChannelReturnsNullForClosedChannel() = runTest {
        val fake = FakeL2capChannel()
        val manager = L2capManager(channelFactory = { fake })
        manager.openChannel(peerId(1))
        fake.open = false

        assertNull(manager.getChannel(peerId(1)), "Closed channel should not be returned")
    }

    // --- closeChannel ---

    @Test
    fun closeChannelClosesAndRemoves() = runTest {
        val fake = FakeL2capChannel()
        val manager = L2capManager(channelFactory = { fake })
        manager.openChannel(peerId(1))

        manager.closeChannel(peerId(1))
        assertFalse(fake.isOpen, "Channel should be closed")
        assertNull(manager.getChannel(peerId(1)), "Channel should be removed")
    }

    @Test
    fun closeChannelNoOpForUnknownPeer() {
        val manager = L2capManager()
        // Should not throw
        manager.closeChannel(peerId(99))
    }

    // --- exponential back-off ---

    @Test
    fun retriesUseExponentialBackoff() = runTest {
        val delays = mutableListOf<Long>()
        val manager = L2capManager(
            channelFactory = { throw RuntimeException("fail") },
            maxRetries = 3,
            initialBackoffMillis = 100,
            delayFn = { delays.add(it) },
        )

        manager.openChannel(peerId(1))
        assertEquals(listOf(100L, 200L, 400L), delays, "Backoff should be 100, 200, 400")
    }

    // --- separate peers are independent ---

    @Test
    fun separatePeersHaveIndependentChannels() = runTest {
        val manager = L2capManager(
            channelFactory = { FakeL2capChannel() },
        )

        val ch1 = manager.openChannel(peerId(1))
        val ch2 = manager.openChannel(peerId(2))
        assertNotNull(ch1)
        assertNotNull(ch2)
        assertTrue(ch1 !== ch2, "Different peers should have different channels")

        manager.closeChannel(peerId(1))
        assertNull(manager.getChannel(peerId(1)))
        assertNotNull(manager.getChannel(peerId(2)), "Peer 2 channel should be unaffected")
    }

    // --- re-opening after close ---

    @Test
    fun openChannelCreatesNewChannelAfterClose() = runTest {
        var callCount = 0
        val manager = L2capManager(
            channelFactory = { callCount++; FakeL2capChannel() },
        )
        val peer = peerId(1)

        manager.openChannel(peer)
        manager.closeChannel(peer)
        manager.openChannel(peer)

        assertEquals(2, callCount, "Factory should be called again after close")
    }
}
