package ch.trancee.meshlink.platform.android

import android.bluetooth.BluetoothProfile
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.transport.BleDiscoveryContract
import ch.trancee.meshlink.wire.WireCodec
import ch.trancee.meshlink.wire.WireFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

class GattNotifyClientTest {
    @Test
    fun startOpensTheSessionOnceAndConnectedEventRequestsDiscoveryWhenMtuRequestFails(): Unit {
        // Arrange
        val session = FakeGattNotifySession(requestMtuResult = false)
        val factory = FakeGattNotifySessionFactory(session)
        val client = createGattNotifyClient(factory = factory)

        client.start()
        client.start()

        // Act
        factory.listener.onConnectionStateChange(
            address = session.address,
            status = 0,
            newState = BluetoothProfile.STATE_CONNECTED,
        )

        // Assert
        assertEquals(1, factory.openCalls)
        assertEquals(1, session.highPriorityRequests)
        assertEquals(1, session.fastPhyRequests)
        assertEquals(listOf(517), session.requestedMtus)
        assertEquals(1, session.discoverServicesCalls)
    }

    @Test
    fun servicesDiscoveredWithMissingServiceClosesTheSession(): Unit {
        // Arrange
        val session =
            FakeGattNotifySession(
                characteristicResolution = GattNotifyCharacteristicResolution.MISSING_SERVICE
            )
        val factory = FakeGattNotifySessionFactory(session)
        val client = createGattNotifyClient(factory = factory)
        client.start()

        // Act
        factory.listener.onServicesDiscovered(status = 0)

        // Assert
        assertEquals(1, session.closeCalls)
        assertEquals(1, session.refreshServiceCacheCalls)
        assertFalse(client.isReady())
    }

    @Test
    fun servicesDiscoveredWithMissingServiceRefreshesStaleCacheAndRetriesDiscoveryOnce(): Unit {
        // Arrange
        val session =
            FakeGattNotifySession(
                characteristicResolutions =
                    listOf(
                        GattNotifyCharacteristicResolution.MISSING_SERVICE,
                        GattNotifyCharacteristicResolution.READY,
                    ),
                refreshServiceCacheResult = true,
            )
        val factory = FakeGattNotifySessionFactory(session)
        val client = createGattNotifyClient(factory = factory)
        client.start()

        // Act
        factory.listener.onServicesDiscovered(status = 0)

        // Assert: the stale cache is refreshed and discovery is retried instead of closing.
        assertEquals(1, session.refreshServiceCacheCalls)
        assertEquals(0, session.closeCalls)
        assertEquals(1, session.discoverServicesCalls)

        // Act: the retried discovery now finds the service - a second miss should not loop again.
        factory.listener.onServicesDiscovered(status = 0)

        // Assert
        assertEquals(1, session.refreshServiceCacheCalls)
    }

    @Test
    fun servicesDiscoveredWithMissingServiceOnlyRefreshesOncePerConnection(): Unit {
        // Arrange
        val session =
            FakeGattNotifySession(
                characteristicResolution = GattNotifyCharacteristicResolution.MISSING_SERVICE,
                refreshServiceCacheResult = true,
            )
        val factory = FakeGattNotifySessionFactory(session)
        val client = createGattNotifyClient(factory = factory)
        client.start()

        // Act: service stays missing even after the refresh-and-retry cycle.
        factory.listener.onServicesDiscovered(status = 0)
        factory.listener.onServicesDiscovered(status = 0)

        // Assert: only one refresh is attempted; the second miss gives up and closes.
        assertEquals(1, session.refreshServiceCacheCalls)
        assertEquals(1, session.closeCalls)
    }

    @Test
    fun startAfterMissingServiceCloseReArmsTheRefreshGuardForTheNextConnectionAttempt(): Unit {
        // Arrange: GattSideLinkCoordinator.ensureStarted() reuses the same GattNotifyClient
        // instance across reconnects (it only replaces it once a real GATT disconnect removes it
        // from clientsByHint) - simulate that reuse directly by calling start() again on the same
        // client after a MISSING_SERVICE close, without going through onDisconnected.
        val session =
            FakeGattNotifySession(
                characteristicResolution = GattNotifyCharacteristicResolution.MISSING_SERVICE,
                refreshServiceCacheResult = true,
            )
        val factory = FakeGattNotifySessionFactory(session)
        val client = createGattNotifyClient(factory = factory)
        client.start()
        factory.listener.onServicesDiscovered(status = 0)
        factory.listener.onServicesDiscovered(status = 0)
        assertEquals(1, session.refreshServiceCacheCalls)
        assertEquals(1, session.closeCalls)

        // Act: the coordinator calls start() again on the same instance for a fresh reconnect.
        client.start()
        factory.listener.onServicesDiscovered(status = 0)

        // Assert: the guard was re-armed by start(), so a second refresh is attempted rather than
        // giving up immediately on the first miss of the new connection attempt.
        assertEquals(2, session.refreshServiceCacheCalls)
        assertEquals(1, session.closeCalls)
    }

    @Test
    fun writeAnnouncesLinkIdentityBeforeDelegatingToTheSession(): Unit = runBlocking {
        // Arrange
        val localHintPeerId = PeerId("local-android")
        val expectedIdentityChunk =
            L2capFrameBuffer().encode(WireCodec.encode(WireFrame.LinkIdentity(localHintPeerId)))
        val expectedEncodedChunk = L2capFrameBuffer().encode(byteArrayOf(0x01, 0x02, 0x03))
        val session =
            FakeGattNotifySession(
                characteristicResolution = GattNotifyCharacteristicResolution.READY,
                hasWriteCharacteristicFlag = true,
                enableNotificationsResult = GattNotifyEnableNotificationsResult.REQUESTED,
            )
        val factory = FakeGattNotifySessionFactory(session)
        session.writeChunkHandler = { chunk ->
            factory.listener.onCharacteristicWrite(
                characteristicUuid = BleDiscoveryContract.GATT_WRITE_CHARACTERISTIC_UUID,
                status = 0,
            )
            true
        }
        val client = createGattNotifyClient(factory = factory, localHintPeerId = localHintPeerId)
        client.start()
        factory.listener.onServicesDiscovered(status = 0)
        factory.listener.onDescriptorWrite(
            descriptorUuid = "00002902-0000-1000-8000-00805f9b34fb",
            status = 0,
        )

        // Act
        val written = client.write(byteArrayOf(0x01, 0x02, 0x03))

        // Assert
        val concatenatedWrites =
            session.writeChunks.fold(ByteArray(0)) { acc, chunk -> acc + chunk }
        assertTrue(client.isReady())
        assertTrue(written)
        assertTrue(session.writeChunks.size >= 2)
        assertEquals(
            (expectedIdentityChunk + expectedEncodedChunk).toList(),
            concatenatedWrites.toList(),
        )
    }

    @Test
    fun writePipelinesMultipleChunksBeforeSuspendingOnTheBoundedWriteWindow(): Unit = runBlocking {
        // Arrange: do not auto-ack from the write handler so the test can observe how many
        // chunks get enqueued with the local BLE stack ahead of any completion callback.
        val session =
            FakeGattNotifySession(
                characteristicResolution = GattNotifyCharacteristicResolution.READY,
                hasWriteCharacteristicFlag = true,
                enableNotificationsResult = GattNotifyEnableNotificationsResult.REQUESTED,
            )
        val factory = FakeGattNotifySessionFactory(session)
        session.writeChunkHandler = { true }
        val client = createGattNotifyClient(factory = factory)
        client.start()
        factory.listener.onServicesDiscovered(status = 0)
        factory.listener.onDescriptorWrite(
            descriptorUuid = "00002902-0000-1000-8000-00805f9b34fb",
            status = 0,
        )
        val payload = ByteArray(300) { it.toByte() }

        // Act: run the suspend write() call cooperatively on this single-threaded runBlocking
        // dispatcher so repeated yield() calls advance it deterministically without real
        // concurrency or timing races.
        val writeJob = launch { client.write(payload) }
        repeat(20) { yield() }

        // Assert: several chunks were pipelined before any completion callback fired - proving
        // this is no longer a stop-and-wait design - while the transfer is still suspended,
        // bounded by the write window.
        assertTrue(session.writeChunks.size > 1)
        assertFalse(writeJob.isCompleted)

        // Act: ack every chunk the session has seen so far, in issue order, letting the
        // remaining chunks flow through the window as earlier ones complete.
        var acked = 0
        while (!writeJob.isCompleted) {
            while (acked < session.writeChunks.size) {
                factory.listener.onCharacteristicWrite(
                    characteristicUuid = BleDiscoveryContract.GATT_WRITE_CHARACTERISTIC_UUID,
                    status = 0,
                )
                acked += 1
            }
            yield()
        }

        // Assert
        assertEquals(session.writeChunks.size, acked)
    }

    /**
     * Isolates the write pipeline's throughput characteristics from real BLE hardware and the rest
     * of the mesh/session stack, using a simulated fixed per-ack round-trip delay (standing in for
     * a BLE connection-interval round trip). This gives a repeatable, hardware-free regression
     * signal for the windowed pipelining fix (see meshlink-benchmark/history.md for the
     * physical-fleet evidence this addresses: the previous stop-and-wait design measured ~8-10 KB/s
     * on the Samsung XCover4 <-> OPPO Reno8 GATT-fallback bearer, matching one 512-byte chunk per
     * round trip).
     */
    @Test
    fun writePipelinesChunksInsteadOfOneRoundTripPerChunk(): Unit = runBlocking {
        // Arrange: an ack for each chunk only arrives after a fixed simulated round-trip delay.
        // A stop-and-wait design would take chunkCount * simulatedRoundTripMs to finish; a
        // pipelined design bounded by the write window should take roughly
        // ceil(chunkCount / windowSize) * simulatedRoundTripMs instead.
        val simulatedRoundTripMs = 15L
        val session =
            FakeGattNotifySession(
                characteristicResolution = GattNotifyCharacteristicResolution.READY,
                hasWriteCharacteristicFlag = true,
                enableNotificationsResult = GattNotifyEnableNotificationsResult.REQUESTED,
            )
        val factory = FakeGattNotifySessionFactory(session)
        val ackScope = CoroutineScope(coroutineContext)
        session.writeChunkHandler = {
            ackScope.launch {
                delay(simulatedRoundTripMs)
                factory.listener.onCharacteristicWrite(
                    characteristicUuid = BleDiscoveryContract.GATT_WRITE_CHARACTERISTIC_UUID,
                    status = 0,
                )
            }
            true
        }
        val client = createGattNotifyClient(factory = factory)
        client.start()
        factory.listener.onServicesDiscovered(status = 0)
        factory.listener.onDescriptorWrite(
            descriptorUuid = "00002902-0000-1000-8000-00805f9b34fb",
            status = 0,
        )
        // Default (un-negotiated) ATT MTU yields ~20-byte chunks; a few KB of payload is enough
        // to exercise many chunks without an unreasonably long real-time test.
        val payload = ByteArray(4_000) { it.toByte() }
        val writeWindowSizeUnderTest = 4

        // Act
        val startedAtMs = System.currentTimeMillis()
        val written = client.write(payload)
        val elapsedMs = System.currentTimeMillis() - startedAtMs

        // Assert: the transfer succeeded, and a stop-and-wait design's expected duration
        // (chunkCount * simulatedRoundTripMs) clearly exceeds what the pipelined design actually
        // took, proving multiple chunks were kept in flight rather than one at a time.
        val chunkCount = session.writeChunks.size
        val stopAndWaitBaselineMs = chunkCount * simulatedRoundTripMs
        assertTrue(written)
        assertTrue(chunkCount > writeWindowSizeUnderTest)
        assertTrue(
            elapsedMs < stopAndWaitBaselineMs / 2,
            "Expected pipelined elapsedMs=$elapsedMs to beat the stop-and-wait baseline of " +
                "$stopAndWaitBaselineMs ms (chunkCount=$chunkCount, roundTripMs=$simulatedRoundTripMs)",
        )
    }
}

private fun createGattNotifyClient(
    factory: GattNotifySessionFactory,
    localHintPeerId: PeerId = PeerId("local-android"),
): GattNotifyClient {
    return GattNotifyClient(
        context = Any(),
        appId = "app",
        peerHintId = PeerId("peer-android"),
        localHintPeerId = localHintPeerId,
        device = Any(),
        log = {},
        onFrameReceived = { _, _ -> true },
        onDisconnected = {},
        sessionFactory = factory,
    )
}

private class FakeGattNotifySessionFactory(private val session: FakeGattNotifySession) :
    GattNotifySessionFactory {
    lateinit var listener: GattNotifySessionListener
    var openCalls: Int = 0

    override fun open(listener: GattNotifySessionListener): GattNotifySession {
        this.listener = listener
        openCalls += 1
        return session
    }
}

private class FakeGattNotifySession(
    private val requestMtuResult: Boolean = true,
    private val characteristicResolution: GattNotifyCharacteristicResolution =
        GattNotifyCharacteristicResolution.READY,
    private val characteristicResolutions: List<GattNotifyCharacteristicResolution>? = null,
    private val enableNotificationsResult: GattNotifyEnableNotificationsResult =
        GattNotifyEnableNotificationsResult.REQUESTED,
    private val hasWriteCharacteristicFlag: Boolean = false,
    private val refreshServiceCacheResult: Boolean = false,
) : GattNotifySession {
    override val address: String = "AA:BB:CC:DD"

    var highPriorityRequests: Int = 0
    var lastRequestedPriority: Int? = null
    var fastPhyRequests: Int = 0
    val requestedMtus: MutableList<Int> = mutableListOf()
    var discoverServicesCalls: Int = 0
    var refreshServiceCacheCalls: Int = 0
    var closeCalls: Int = 0
    val writeChunks: MutableList<ByteArray> = mutableListOf()
    var writeChunkHandler: (ByteArray) -> Boolean = { true }
    private var resolutionCallIndex: Int = 0

    override fun requestConnectionPriority(priority: Int): Unit {
        highPriorityRequests += 1
        lastRequestedPriority = priority
    }

    override fun requestFastPhyIfSupported(): Unit {
        fastPhyRequests += 1
    }

    override fun requestMtu(mtu: Int): Boolean {
        requestedMtus += mtu
        return requestMtuResult
    }

    override fun discoverServices(): Unit {
        discoverServicesCalls += 1
    }

    override fun refreshServiceCache(): Boolean {
        refreshServiceCacheCalls += 1
        return refreshServiceCacheResult
    }

    override fun resolveFallbackCharacteristics(): GattNotifyCharacteristicResolution {
        val resolutions = characteristicResolutions ?: return characteristicResolution
        val resolution = resolutions.getOrElse(resolutionCallIndex) { resolutions.last() }
        resolutionCallIndex += 1
        return resolution
    }

    override fun hasWriteCharacteristic(): Boolean {
        return hasWriteCharacteristicFlag
    }

    override fun enableNotifications(): GattNotifyEnableNotificationsResult {
        return enableNotificationsResult
    }

    override fun writeChunk(chunk: ByteArray): Boolean {
        writeChunks += chunk.copyOf()
        return writeChunkHandler(chunk)
    }

    override fun close(): Unit {
        closeCalls += 1
    }
}
