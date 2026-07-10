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
import kotlinx.coroutines.Dispatchers
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

        // Assert: requestDisconnect() (not close()) races the native stack per the
        // android-ble-gatt-status-133 skill, so the graceful teardown path always requests a
        // disconnect first -- close() only follows after the bounded safety-net delay.
        assertEquals(1, session.requestDisconnectCalls)
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
        assertEquals(1, session.requestDisconnectCalls)
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

    @Test
    fun writeRetriesATransientEnqueueBusyResponseInsteadOfFailingImmediately(): Unit = runBlocking {
        // Arrange: writeCharacteristic() can transiently report busy (return false) right after
        // a previous write's completion callback fires. Simulate that by failing the write
        // handler's first two calls per chunk before succeeding.
        val session =
            FakeGattNotifySession(
                characteristicResolution = GattNotifyCharacteristicResolution.READY,
                hasWriteCharacteristicFlag = true,
                enableNotificationsResult = GattNotifyEnableNotificationsResult.REQUESTED,
            )
        val factory = FakeGattNotifySessionFactory(session)
        var attemptsForCurrentChunk = 0
        session.writeChunkHandler = {
            attemptsForCurrentChunk += 1
            if (attemptsForCurrentChunk < 3) {
                false
            } else {
                attemptsForCurrentChunk = 0
                factory.listener.onCharacteristicWrite(
                    characteristicUuid = BleDiscoveryContract.GATT_WRITE_CHARACTERISTIC_UUID,
                    status = 0,
                )
                true
            }
        }
        val client = createGattNotifyClient(factory = factory)
        client.start()
        factory.listener.onServicesDiscovered(status = 0)
        factory.listener.onDescriptorWrite(
            descriptorUuid = "00002902-0000-1000-8000-00805f9b34fb",
            status = 0,
        )

        // Act
        val written = client.write(byteArrayOf(0x01, 0x02, 0x03))

        // Assert: the transfer still succeeds despite the transient busy responses, and each
        // chunk was retried (3 recorded attempts per chunk) rather than the transfer aborting on
        // the first busy response.
        assertTrue(written)
        assertEquals(0, session.writeChunks.size % 3)
    }

    @Test
    fun writeClosesTheSessionWhenChunkEnqueueFailsAfterExhaustingAllRetryAttempts(): Unit =
        runBlocking {
            // Arrange: writeCharacteristic() always reports busy, so every retry attempt for the
            // chunk fails and enqueueEncodedChunk() gives up.
            val session =
                FakeGattNotifySession(
                    characteristicResolution = GattNotifyCharacteristicResolution.READY,
                    hasWriteCharacteristicFlag = true,
                    enableNotificationsResult = GattNotifyEnableNotificationsResult.REQUESTED,
                )
            val factory = FakeGattNotifySessionFactory(session)
            session.writeChunkHandler = { false }
            val client = createGattNotifyClient(factory = factory)
            client.start()
            factory.listener.onServicesDiscovered(status = 0)
            factory.listener.onDescriptorWrite(
                descriptorUuid = "00002902-0000-1000-8000-00805f9b34fb",
                status = 0,
            )

            // Act
            val written = client.write(byteArrayOf(0x01, 0x02, 0x03))

            // Assert: an unrecoverable enqueue failure closes the session (rather than leaving it
            // open, which could otherwise leave a peer's frame-reassembly buffer permanently
            // desynchronized by a truncated in-flight frame if an earlier chunk in the same
            // payload had already been accepted by the local BLE stack), so the client reports
            // not-ready and GattSideLinkCoordinator will reconnect with a fresh session.
            assertFalse(written)
            assertFalse(client.isReady())
            assertEquals(1, session.closeCalls)
        }

    @Test
    fun writeClosesTheSessionWhenAPipelinedChunkCompletesWithAFailureStatus(): Unit = runBlocking {
        // Arrange: the very first chunk written on this connection (the LinkIdentity announce)
        // completes with a non-success GATT status. drainPendingWrites() must treat this the same
        // way as an enqueue failure or drain timeout -- closing the session -- rather than just
        // recording the transfer as failed while leaving the session open for reuse, since with
        // pipelined writes a failure like this can in general arrive after later chunks of the
        // same payload have already been dispatched to the controller.
        val session =
            FakeGattNotifySession(
                characteristicResolution = GattNotifyCharacteristicResolution.READY,
                hasWriteCharacteristicFlag = true,
                enableNotificationsResult = GattNotifyEnableNotificationsResult.REQUESTED,
            )
        val factory = FakeGattNotifySessionFactory(session)
        var chunkIndex = 0
        session.writeChunkHandler = {
            chunkIndex += 1
            if (chunkIndex == 1) {
                factory.listener.onCharacteristicWrite(
                    characteristicUuid = BleDiscoveryContract.GATT_WRITE_CHARACTERISTIC_UUID,
                    status = 1,
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

        // Act
        val written = client.write(byteArrayOf(0x01, 0x02, 0x03))

        // Assert: a per-chunk write failure closes the session (rather than only marking this
        // transfer as failed while leaving the session open for reuse), because the peer's
        // frame reassembly may already be mid-frame from the chunks that succeeded.
        assertFalse(written)
        assertFalse(client.isReady())
        assertEquals(1, session.closeCalls)
    }

    @Test
    fun writeReportsFailureWhenEveryChunkCompletesSynchronouslyBeforeDrainRuns(): Unit =
        runBlocking {
            // Arrange: regression test for issue #82 -- a drain/completion race in
            // drainPendingWrites(). It used to infer "all chunks succeeded" purely from
            // pendingWrites (the shared FIFO queue) being empty by the time drain() ran, rather
            // than from an explicit count/record of completions actually observed. Here every
            // chunk's onCharacteristicWrite completion (one of them a failure) is delivered
            // *synchronously* inside session.writeChunk() -- i.e. before enqueueEncodedChunk()
            // even returns, let alone before writeViaGattNotify()'s enqueue loop finishes and
            // calls drain(). That means completePendingWrite() removes every entry from the
            // shared queue well before drainPendingWrites() gets a chance to look at it, so the
            // old "queue empty => success" inference would report this transfer as successful
            // even though one chunk explicitly failed. Unlike
            // writeClosesTheSessionWhenAPipelinedChunkCompletesWithAFailureStatus (which only
            // masks the same race behind a 5s drain-timeout on an unresolved next chunk), every
            // chunk here resolves promptly, so this test would hang/timeout under the old bug
            // instead of failing fast if the fix regressed.
            val session =
                FakeGattNotifySession(
                    characteristicResolution = GattNotifyCharacteristicResolution.READY,
                    hasWriteCharacteristicFlag = true,
                    enableNotificationsResult = GattNotifyEnableNotificationsResult.REQUESTED,
                )
            val factory = FakeGattNotifySessionFactory(session)
            var chunkIndex = 0
            val failingChunkIndex = 5
            session.writeChunkHandler = {
                chunkIndex += 1
                val status = if (chunkIndex == failingChunkIndex) 1 else 0
                factory.listener.onCharacteristicWrite(
                    characteristicUuid = BleDiscoveryContract.GATT_WRITE_CHARACTERISTIC_UUID,
                    status = status,
                )
                true
            }
            val client = createGattNotifyClient(factory = factory)
            client.start()
            factory.listener.onServicesDiscovered(status = 0)
            factory.listener.onDescriptorWrite(
                descriptorUuid = "00002902-0000-1000-8000-00805f9b34fb",
                status = 0,
            )
            // Large enough (with the default 23-byte ATT MTU) to span several chunks, so the
            // failure lands mid-payload rather than being the payload's only chunk.
            val payload = ByteArray(300) { it.toByte() }

            // Act
            val written = client.write(payload)

            // Assert: the mid-payload failure is caught directly -- not masked into a false
            // success by the queue-emptiness race, and not merely caught via a drain timeout.
            assertTrue(chunkIndex >= failingChunkIndex)
            assertFalse(written)
            assertFalse(client.isReady())
            assertEquals(1, session.closeCalls)
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

    @Test
    fun writeRetriesInPlaceAfterGattConnectionCongestedInsteadOfClosingTheSession(): Unit =
        runBlocking {
            // Arrange: every chunk's first completion reports GATT_CONNECTION_CONGESTED (status
            // 143) -- a transient local transmit-queue backpressure signal, not a real link
            // failure -- before succeeding on the retry for that same chunk. Completions are
            // delivered asynchronously (after a short delay), matching how a real GATT callback
            // arrives on a separate thread rather than synchronously inside writeCharacteristic()
            // -- delivering them synchronously here would let a chunk's completion race ahead of
            // drainPendingWrites() ever observing it, independent of the behavior under test.
            val session =
                FakeGattNotifySession(
                    characteristicResolution = GattNotifyCharacteristicResolution.READY,
                    hasWriteCharacteristicFlag = true,
                    enableNotificationsResult = GattNotifyEnableNotificationsResult.REQUESTED,
                )
            val factory = FakeGattNotifySessionFactory(session)
            val ackScope = CoroutineScope(coroutineContext)
            val attemptsByChunk = mutableMapOf<List<Byte>, Int>()
            session.writeChunkHandler = { chunk ->
                val chunkKey = chunk.toList()
                val attempt = (attemptsByChunk[chunkKey] ?: 0) + 1
                attemptsByChunk[chunkKey] = attempt
                ackScope.launch {
                    delay(1L)
                    factory.listener.onCharacteristicWrite(
                        characteristicUuid = BleDiscoveryContract.GATT_WRITE_CHARACTERISTIC_UUID,
                        status = if (attempt == 1) GATT_CONNECTION_CONGESTED_STATUS else 0,
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

            // Act
            val written = client.write(byteArrayOf(0x01, 0x02, 0x03))

            // Assert: the transfer still succeeds and the session stays open/ready -- every
            // congested completion is absorbed by reissuing its chunk rather than failing the
            // payload or tearing down the session.
            assertTrue(written)
            assertTrue(client.isReady())
            assertEquals(0, session.closeCalls)
        }

    @Test
    fun writeClosesTheSessionWhenCongestionRetriesAreExhausted(): Unit = runBlocking {
        // Arrange: every completion for every chunk reports GATT_CONNECTION_CONGESTED,
        // exhausting the bounded retry budget. Completions are delivered asynchronously for the
        // same reason as the sibling retry-succeeds test above.
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
                delay(1L)
                factory.listener.onCharacteristicWrite(
                    characteristicUuid = BleDiscoveryContract.GATT_WRITE_CHARACTERISTIC_UUID,
                    status = GATT_CONNECTION_CONGESTED_STATUS,
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

        // Act
        val written = client.write(byteArrayOf(0x01, 0x02, 0x03))

        // Assert: once the retry budget is exhausted this degrades to the same outcome as any
        // other genuine per-chunk write failure -- the session is closed rather than reused with a
        // potentially corrupted frame stream.
        assertFalse(written)
        assertFalse(client.isReady())
        assertTrue(session.closeCalls >= 1)
    }

    @Test
    fun writeDrainTimeoutClosesTheSessionInsteadOfLeavingAStaleCallbackToDesyncTheQueue(): Unit =
        runBlocking {
            // Arrange: the write handler reports every chunk as successfully enqueued but never
            // invokes the onCharacteristicWrite completion callback, simulating a native GATT
            // operation that never completes (or completes so late the drain has already given up).
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

            // Act: this genuinely waits out the real WRITE_TIMEOUT_MILLIS drain timeout.
            val written = client.write(byteArrayOf(0x01, 0x02, 0x03))

            // Assert: the drain timeout closes the session (rather than leaving it open to receive
            // a
            // stale completion callback later that would be wrongly matched to an unrelated future
            // write), so the client reports not-ready and a subsequent write is rejected
            // immediately
            // instead of silently corrupting the pending-write queue.
            assertFalse(written)
            assertFalse(client.isReady())
            assertEquals(1, session.requestDisconnectCalls)
            assertEquals(1, session.closeCalls)
            assertFalse(client.write(byteArrayOf(0x04)))
        }

    @Test
    fun status133DisconnectRetriesTheConnectionInsteadOfSurfacingItAsARealDisconnect(): Unit {
        // Arrange: android's generic GATT_ERROR (133) status most often reflects an immediate,
        // transient connect failure on certain OEM Bluetooth stacks rather than a real peer loss.
        val session = FakeGattNotifySession()
        val factory = FakeGattNotifySessionFactory(session)
        val disconnectedPeerIds = mutableListOf<PeerId>()
        val client =
            createGattNotifyClient(
                factory = factory,
                onDisconnected = { peerId -> disconnectedPeerIds += peerId },
            )
        client.start()
        assertEquals(1, factory.openCalls)

        // Act: the very first connection attempt fails immediately with status=133.
        factory.listener.onConnectionStateChange(
            address = session.address,
            status = 133,
            newState = BluetoothProfile.STATE_DISCONNECTED,
        )

        // Assert: the client retries by starting a fresh connection attempt rather than notifying
        // a real disconnect -- with connectRetryDelayMillis=0 and an Unconfined retry scope (the
        // test default), the retry's start() call runs synchronously within onConnectionStateChange
        // itself.
        assertEquals(2, factory.openCalls)
        assertEquals(emptyList(), disconnectedPeerIds)
    }

    @Test
    fun status147DisconnectRetriesTheConnectionInsteadOfSurfacingItAsARealDisconnect(): Unit {
        // Arrange: BluetoothGatt.GATT_CONNECTION_TIMEOUT (147, added in API 35) is the platform's
        // newer, more specific status for the same underlying direct-connect timeout that older
        // Android versions report as plain GATT_ERROR (133) -- see the android-ble-gatt-status-133
        // skill's "Related status codes" section. It must be retried the same way 133 is, or
        // devices on API 35+ would stop benefiting from this retry entirely.
        val session = FakeGattNotifySession()
        val factory = FakeGattNotifySessionFactory(session)
        val disconnectedPeerIds = mutableListOf<PeerId>()
        val client =
            createGattNotifyClient(
                factory = factory,
                onDisconnected = { peerId -> disconnectedPeerIds += peerId },
            )
        client.start()
        assertEquals(1, factory.openCalls)

        // Act: the very first connection attempt fails immediately with status=147.
        factory.listener.onConnectionStateChange(
            address = session.address,
            status = 147,
            newState = BluetoothProfile.STATE_DISCONNECTED,
        )

        // Assert: retried exactly like status=133 -- a fresh connection attempt, not a real
        // disconnect.
        assertEquals(2, factory.openCalls)
        assertEquals(emptyList(), disconnectedPeerIds)
    }

    @Test
    @Suppress("InjectDispatcher")
    fun concurrentEnsureStartedCallDuringAPendingStatus133RetryDoesNotOpenADuplicateConnection():
        Unit {
        // Arrange: uses a non-zero delay and a real dispatcher (rather than the test default of
        // Unconfined + 0ms, which resolves the retry synchronously and can't exercise the delay
        // window) so a concurrent start() call -- the same call GattSideLinkCoordinator.
        // ensureStarted() makes on every incoming discovery broadcast for a not-yet-ready peer --
        // can genuinely land *during* the pending retry's delay, which is exactly the race the
        // reconnectPending guard exists to prevent. Dispatchers.Default is used deliberately here
        // (rather than an injected test dispatcher) because the whole point of this test is to
        // prove correctness under genuine cross-thread scheduling, not virtual time.
        val session = FakeGattNotifySession()
        val factory = FakeGattNotifySessionFactory(session)
        val client =
            createGattNotifyClient(
                factory = factory,
                connectRetryDelayMillis = RETRY_DELAY_MILLIS,
                connectRetryScope = CoroutineScope(Dispatchers.Default),
            )
        client.start()
        assertEquals(1, factory.openCalls)

        // Act: status=133 schedules a pending retry (RETRY_DELAY_MILLIS out); immediately
        // afterwards, simulate the coordinator's own concurrent start() call for the same
        // not-yet-ready peer, which is exactly what happens in production when a discovery
        // broadcast arrives during the retry delay window.
        factory.listener.onConnectionStateChange(
            address = session.address,
            status = 133,
            newState = BluetoothProfile.STATE_DISCONNECTED,
        )
        client.start()

        // Assert: the concurrent start() call is a no-op (still gated by reconnectPending) --
        // exactly one additional connectGatt() happens once the retry's own delay elapses, not two.
        assertEquals(1, factory.openCalls)
        Thread.sleep(RETRY_DELAY_MILLIS * 2)
        assertEquals(2, factory.openCalls)
    }

    @Test
    fun status133DisconnectGivesUpAfterExhaustingTheRetryBudget(): Unit {
        // Arrange
        val session = FakeGattNotifySession()
        val factory = FakeGattNotifySessionFactory(session)
        val disconnectedPeerIds = mutableListOf<PeerId>()
        val client =
            createGattNotifyClient(
                factory = factory,
                onDisconnected = { peerId -> disconnectedPeerIds += peerId },
            )
        client.start()

        // Act: fail with status=133 MAX_CONNECT_RETRY_ATTEMPTS + 1 times in a row (the constant is
        // private in GattNotifyClient, so this test asserts behavior at a small, deliberately
        // generous bound rather than referencing it directly).
        repeat(3) {
            factory.listener.onConnectionStateChange(
                address = session.address,
                status = 133,
                newState = BluetoothProfile.STATE_DISCONNECTED,
            )
        }

        // Assert: retries are bounded -- eventually the client gives up and surfaces a real
        // disconnect instead of retrying forever.
        assertEquals(listOf(PeerId("peer-android")), disconnectedPeerIds)
        assertFalse(client.isReady())
    }

    @Test
    fun nonGattErrorDisconnectStatusIsNotRetried(): Unit {
        // Arrange: only status=133 (GATT_ERROR) and status=147 (GATT_CONNECTION_TIMEOUT) are
        // treated as retryable transient failures; any other disconnect status (e.g. a
        // clean/expected disconnect, or a different real error) must still surface immediately as
        // a real disconnect.
        val session = FakeGattNotifySession()
        val factory = FakeGattNotifySessionFactory(session)
        val disconnectedPeerIds = mutableListOf<PeerId>()
        val client =
            createGattNotifyClient(
                factory = factory,
                onDisconnected = { peerId -> disconnectedPeerIds += peerId },
            )
        client.start()

        // Act
        factory.listener.onConnectionStateChange(
            address = session.address,
            status = 8, // GATT_CONN_TIMEOUT, an unrelated real failure code
            newState = BluetoothProfile.STATE_DISCONNECTED,
        )

        // Assert: no retry -- exactly the original connection attempt, and a real disconnect fires.
        assertEquals(1, factory.openCalls)
        assertEquals(listOf(PeerId("peer-android")), disconnectedPeerIds)
    }

    @Test
    fun successfulConnectResetsTheStatus133RetryBudgetForTheNextDisconnect(): Unit {
        // Arrange
        val session = FakeGattNotifySession()
        val factory = FakeGattNotifySessionFactory(session)
        val disconnectedPeerIds = mutableListOf<PeerId>()
        val client =
            createGattNotifyClient(
                factory = factory,
                onDisconnected = { peerId -> disconnectedPeerIds += peerId },
            )
        client.start()

        // Act: one status=133 retry, then a real successful connection.
        factory.listener.onConnectionStateChange(
            address = session.address,
            status = 133,
            newState = BluetoothProfile.STATE_DISCONNECTED,
        )
        factory.listener.onConnectionStateChange(
            address = session.address,
            status = 0,
            newState = BluetoothProfile.STATE_CONNECTED,
        )

        // Assert: the retry budget was consumed by the first failure, but a subsequent successful
        // connect resets it, so a fresh sequence of status=133 failures gets its own full budget
        // rather than immediately giving up from where the previous sequence left off.
        repeat(2) {
            factory.listener.onConnectionStateChange(
                address = session.address,
                status = 133,
                newState = BluetoothProfile.STATE_DISCONNECTED,
            )
        }
        assertEquals(emptyList(), disconnectedPeerIds)
    }
}

// Mirrors GattNotifyClient's private ANDROID_GATT_CONNECTION_CONGESTED constant (Android's
// BluetoothGatt.GATT_CONNECTION_CONGESTED = 143) for tests exercising the congestion-retry path.
private const val GATT_CONNECTION_CONGESTED_STATUS: Int = 143
// Used only by the concurrent-retry race test above, which needs a real (non-zero) delay to
// exercise genuine cross-thread scheduling rather than the Unconfined+0ms synchronous default.
private const val RETRY_DELAY_MILLIS: Long = 200L

private fun createGattNotifyClient(
    factory: GattNotifySessionFactory,
    localHintPeerId: PeerId = PeerId("local-android"),
    onDisconnected: (PeerId) -> Unit = {},
    connectRetryDelayMillis: Long = 0L,
    connectRetryScope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined),
    disconnectConfirmTimeoutMillis: Long = 0L,
    teardownScope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined),
): GattNotifyClient {
    return GattNotifyClient(
        context = Any(),
        appId = "app",
        peerHintId = PeerId("peer-android"),
        localHintPeerId = localHintPeerId,
        device = Any(),
        log = {},
        onFrameReceived = { _, _ -> true },
        onDisconnected = onDisconnected,
        sessionFactory = factory,
        connectRetryDelayMillis = connectRetryDelayMillis,
        connectRetryScope = connectRetryScope,
        disconnectConfirmTimeoutMillis = disconnectConfirmTimeoutMillis,
        teardownScope = teardownScope,
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
    var requestDisconnectCalls: Int = 0
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

    override fun requestDisconnect(): Unit {
        requestDisconnectCalls += 1
    }
}
