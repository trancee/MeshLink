package ch.trancee.meshlink.api

import ch.trancee.meshlink.config.PowerMode
import ch.trancee.meshlink.config.RegulatoryRegion
import ch.trancee.meshlink.config.meshLinkConfig
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.engine.MeshEngine
import ch.trancee.meshlink.platform.PlatformPermissionDeniedException
import ch.trancee.meshlink.test.MeshTestHarness
import ch.trancee.meshlink.test.RecordingDiagnosticSink
import ch.trancee.meshlink.transport.BleTransport
import ch.trancee.meshlink.transport.OutboundFrame
import ch.trancee.meshlink.transport.TransportEvent
import ch.trancee.meshlink.transport.TransportSendResult
import ch.trancee.meshlink.trust.TofuTrustStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class MeshLinkContractTest {
    @Test
    fun `deliveryRetryDeadline must be greater than zero`() {
        // Arrange / Act
        val error =
            assertFailsWith<MeshLinkException.InvalidConfiguration> {
                meshLinkConfig {
                    appId = "demo.meshlink"
                    deliveryRetryDeadline = kotlin.time.Duration.ZERO
                }
            }

        // Assert
        assertEquals(
            expected = "deliveryRetryDeadline must be greater than zero",
            actual = error.message,
        )
    }

    @Test
    fun `diagnostic catalog exposes 26 stable codes`() {
        // Arrange
        val expectedCount = 26

        // Act
        val entries = DiagnosticCode.entries

        // Assert
        assertEquals(expectedCount, entries.size)
        assertTrue(entries.contains(DiagnosticCode.TRUST_FAILURE))
        assertTrue(entries.contains(DiagnosticCode.DELIVERY_UNREACHABLE))
    }

    @Test
    fun `meshLinkConfig applies documented defaults when optional fields are omitted`() {
        // Arrange
        val expectedRegion = RegulatoryRegion.DEFAULT
        val expectedPowerMode = PowerMode.Automatic
        val expectedRetryDeadline = 15.seconds

        // Act
        val config = meshLinkConfig { appId = "defaults.meshlink" }

        // Assert
        assertEquals("defaults.meshlink", config.appId)
        assertEquals(expectedRegion, config.regulatoryRegion)
        assertEquals(expectedPowerMode, config.powerMode)
        assertEquals(expectedRetryDeadline, config.deliveryRetryDeadline)
    }

    @Test
    fun `meshLinkConfig rejects blank appId`() {
        // Arrange / Act
        val error =
            assertFailsWith<MeshLinkException.InvalidConfiguration> {
                meshLinkConfig { appId = "   " }
            }

        // Assert
        assertEquals("appId must not be blank", error.message)
    }

    @Test
    fun `meshLinkConfig preserves the same values regardless of builder assignment order`() {
        // Arrange
        val expectedDeadline = 9.seconds

        // Act
        val first = meshLinkConfig {
            appId = "ordered.meshlink"
            regulatoryRegion = RegulatoryRegion.EU
            powerMode = PowerMode.Balanced
            deliveryRetryDeadline = expectedDeadline
        }
        val second = meshLinkConfig {
            deliveryRetryDeadline = expectedDeadline
            powerMode = PowerMode.Balanced
            appId = "ordered.meshlink"
            regulatoryRegion = RegulatoryRegion.EU
        }

        // Assert
        assertEquals(first.appId, second.appId)
        assertEquals(first.regulatoryRegion, second.regulatoryRegion)
        assertEquals(first.powerMode, second.powerMode)
        assertEquals(first.deliveryRetryDeadline, second.deliveryRetryDeadline)
    }

    @Test
    fun `android and ios factories expose matching lifecycle results`() =
        runBlocking<Unit> {
            // Arrange
            installFactoryTestBridges()
            val config = meshLinkConfig {
                appId = "demo.meshlink.${kotlin.random.Random.nextInt()}"
            }
            val androidMeshLink = createAndroidFactoryParityMeshLink(config = config)
            val iosMeshLink = createIosFactoryParityMeshLink(config = config)

            // Act
            val androidResults =
                listOf(
                    androidMeshLink.start(),
                    androidMeshLink.pause(),
                    androidMeshLink.resume(),
                    androidMeshLink.stop(),
                )
            val iosResults =
                listOf(
                    iosMeshLink.start(),
                    iosMeshLink.pause(),
                    iosMeshLink.resume(),
                    iosMeshLink.stop(),
                )

            // Assert
            assertEquals(
                androidResults.map { it::class.simpleName },
                iosResults.map { it::class.simpleName },
            )
            assertEquals(MeshLinkState.Stopped, androidMeshLink.state.value)
            assertEquals(MeshLinkState.Stopped, iosMeshLink.state.value)
        }

    @Test
    fun `android and ios factories honor the same non default shared configuration`() =
        runBlocking<Unit> {
            // Arrange
            installFactoryTestBridges()
            val config = meshLinkConfig {
                appId = "config.meshlink.${kotlin.random.Random.nextInt()}"
                regulatoryRegion = RegulatoryRegion.EU
                powerMode = PowerMode.Performance
                deliveryRetryDeadline = 9.seconds
            }
            val androidMeshLink = createAndroidFactoryParityMeshLink(config = config)
            val iosMeshLink = createIosFactoryParityMeshLink(config = config)
            val androidPowerChanged =
                async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(1_000) {
                        androidMeshLink.diagnosticEvents.first {
                            it.code == DiagnosticCode.POWER_MODE_CHANGED
                        }
                    }
                }
            val iosPowerChanged =
                async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(1_000) {
                        iosMeshLink.diagnosticEvents.first {
                            it.code == DiagnosticCode.POWER_MODE_CHANGED
                        }
                    }
                }

            try {
                // Act
                androidMeshLink.start()
                iosMeshLink.start()
                androidMeshLink.updateBattery(BatterySnapshot(level = 0.42f, isCharging = false))
                iosMeshLink.updateBattery(BatterySnapshot(level = 0.42f, isCharging = false))
                val androidDiagnostic = androidPowerChanged.await()
                val iosDiagnostic = iosPowerChanged.await()

                // Assert
                assertEquals("EU", androidDiagnostic.metadata["region"])
                assertEquals("PERFORMANCE", androidDiagnostic.metadata["tier"])
                assertEquals("300", androidDiagnostic.metadata["advertisementIntervalMillis"])
                assertEquals("100", androidDiagnostic.metadata["connectionIntervalMillis"])
                assertEquals("70", androidDiagnostic.metadata["scanDutyCyclePercent"])
                assertEquals("7", androidDiagnostic.metadata["maxConnections"])
                assertEquals("4096", androidDiagnostic.metadata["chunkBudgetBytes"])
                assertEquals(androidDiagnostic.metadata, iosDiagnostic.metadata)
            } finally {
                runCatching { androidMeshLink.stop() }
                runCatching { iosMeshLink.stop() }
            }
        }

    @Test
    fun `creating meshengine stays uninitialized without touching transport or diagnostics`() {
        // Arrange
        val transport = RecordingBleTransport()
        val diagnosticSink = RecordingDiagnosticSink()

        // Act
        val meshLink =
            MeshEngine.create(
                config = meshLinkConfig { appId = "create.meshlink" },
                bleTransport = transport,
                diagnosticSink = diagnosticSink,
            )

        // Assert
        assertEquals(MeshLinkState.Configured, meshLink.state.value)
        assertEquals(0, transport.startCalls)
        assertEquals(0, transport.pauseCalls)
        assertEquals(0, transport.resumeCalls)
        assertEquals(0, transport.stopCalls)
        assertTrue(diagnosticSink.events().isEmpty())
    }

    @Test
    fun `lifecycle methods enforce the documented state machine`() =
        runBlocking<Unit> {
            // Arrange
            val meshLink =
                MeshEngine.create(config = meshLinkConfig { appId = "lifecycle.meshlink" })

            // Act
            val stopFromConfigured = meshLink.stop()
            val restartFromStopped = meshLink.start()
            val pauseFromRunning = meshLink.pause()
            val startFromPaused = meshLink.start()
            val resumeFromPaused = meshLink.resume()
            val stopFromRunning = meshLink.stop()
            val pauseFromStopped = meshLink.pause()
            val resumeFromStopped = meshLink.resume()

            // Assert
            assertEquals(StopResult.Stopped, stopFromConfigured)
            assertEquals(StartResult.Started, restartFromStopped)
            assertEquals(PauseResult.Paused, pauseFromRunning)
            assertEquals(
                MeshLinkState.Paused,
                (startFromPaused as StartResult.InvalidState).currentState,
            )
            assertEquals(ResumeResult.Resumed, resumeFromPaused)
            assertEquals(StopResult.Stopped, stopFromRunning)
            assertEquals(
                MeshLinkState.Stopped,
                (pauseFromStopped as PauseResult.InvalidState).currentState,
            )
            assertEquals(
                MeshLinkState.Stopped,
                (resumeFromStopped as ResumeResult.InvalidState).currentState,
            )
        }

    @Test
    fun `send throws invalid state when meshlink is not running`() =
        runBlocking<Unit> {
            // Arrange
            val meshLink =
                MeshEngine.create(config = meshLinkConfig { appId = "send-invalid.meshlink" })
            meshLink.start()
            meshLink.pause()

            // Act
            val error =
                assertFailsWith<MeshLinkException.InvalidStateTransition> {
                    meshLink.send(PeerId("peer-abcdef"), "hello".encodeToByteArray())
                }

            // Assert
            assertEquals("send() requires MeshLinkState.Running but was Paused", error.message)
        }

    @Test
    fun `start wraps permission exceptions as permission denied`() {
        // Arrange
        val meshLink =
            MeshEngine.create(
                config = meshLinkConfig { appId = "permission.meshlink" },
                bleTransport =
                    object : BleTransport {
                        override val events: Flow<TransportEvent> = emptyFlow()

                        override suspend fun start(): Unit {
                            throw PlatformPermissionDeniedException("permissions denied")
                        }

                        override suspend fun pause(): Unit = Unit

                        override suspend fun resume(): Unit = Unit

                        override suspend fun stop(): Unit = Unit

                        override suspend fun send(frame: OutboundFrame): TransportSendResult =
                            TransportSendResult.Delivered
                    },
                diagnosticSink = RecordingDiagnosticSink(),
            )

        // Act / Assert
        assertFailsWith<MeshLinkException.PermissionDenied> {
            runBlocking<Unit> { meshLink.start() }
        }
    }

    @Test
    fun `start wraps transport exceptions as platform failures`() {
        // Arrange
        val meshLink =
            MeshEngine.create(
                config = meshLinkConfig { appId = "failing.meshlink" },
                bleTransport =
                    object : BleTransport {
                        override val events: Flow<TransportEvent> = emptyFlow()

                        override suspend fun start(): Unit = error("boom")

                        override suspend fun pause(): Unit = Unit

                        override suspend fun resume(): Unit = Unit

                        override suspend fun stop(): Unit = Unit

                        override suspend fun send(frame: OutboundFrame): TransportSendResult =
                            TransportSendResult.Delivered
                    },
                diagnosticSink = RecordingDiagnosticSink(),
            )

        // Act / Assert
        assertFailsWith<MeshLinkException.PlatformFailure> {
            runBlocking<Unit> { meshLink.start() }
        }
    }

    @Test
    fun `successful trust verification preserves first seen and refreshes last verified timestamp`() =
        runBlocking<Unit> {
            // Arrange
            val harness = harness()
            val receiver = harness.createNode("peer-receiver")
            val sender = harness.createNode("peer-sender")
            val trustStore = TofuTrustStore(receiver.storage)

            receiver.meshLink.start()
            sender.meshLink.start()
            sender.meshLink.send(receiver.peerId, "hello".encodeToByteArray())
            val initialRecord =
                withTimeout(1_000) {
                    while (trustStore.read(sender.peerId.value) == null) {
                        kotlinx.coroutines.delay(10)
                    }
                    trustStore.read(sender.peerId.value)
                        ?: error("Expected initial trust record to be persisted")
                }
            sender.meshLink.stop()
            kotlinx.coroutines.delay(10)
            val restartedSender = harness.restartNode(sender)
            restartedSender.meshLink.start()

            // Act
            restartedSender.meshLink.send(receiver.peerId, "hello-again".encodeToByteArray())
            val refreshedRecord =
                withTimeout(1_000) {
                    var candidate = trustStore.read(sender.peerId.value)
                    while (
                        candidate == null ||
                            candidate.lastVerifiedAtEpochMillis <
                                initialRecord.lastVerifiedAtEpochMillis
                    ) {
                        kotlinx.coroutines.delay(10)
                        candidate = trustStore.read(sender.peerId.value)
                    }
                    candidate
                }

            // Assert
            assertEquals(
                initialRecord.firstSeenAtEpochMillis,
                refreshedRecord.firstSeenAtEpochMillis,
            )
            assertTrue(
                refreshedRecord.lastVerifiedAtEpochMillis >= initialRecord.lastVerifiedAtEpochMillis
            )
        }

    @Test
    fun `forgetPeer deletes trust and same identity is treated as fresh tofu on recontact`() =
        runBlocking<Unit> {
            // Arrange
            val harness = harness()
            val receiver = harness.createNode("peer-receiver")
            val sender = harness.createNode("peer-sender")
            val trustStore = TofuTrustStore(receiver.storage)

            receiver.meshLink.start()
            sender.meshLink.start()
            sender.meshLink.send(receiver.peerId, "hello".encodeToByteArray())
            val initialRecord =
                withTimeout(1_000) {
                    while (trustStore.read(sender.peerId.value) == null) {
                        kotlinx.coroutines.delay(10)
                    }
                    trustStore.read(sender.peerId.value)
                        ?: error("Expected initial trust record to be persisted")
                }
            val initialTrustEstablishedCount =
                receiver.diagnosticSink.events().count {
                    it.code == DiagnosticCode.TRUST_ESTABLISHED
                }
            sender.meshLink.stop()
            kotlinx.coroutines.delay(50)

            // Act
            val forgetResult = receiver.meshLink.forgetPeer(sender.peerId)
            assertEquals(ForgetPeerResult.Forgotten, forgetResult)
            assertNull(trustStore.read(sender.peerId.value))
            val restartedSender = harness.restartNode(sender)
            restartedSender.meshLink.start()
            restartedSender.meshLink.send(receiver.peerId, "hello-again".encodeToByteArray())
            val relearnedRecord =
                withTimeout(1_000) {
                    var candidate = trustStore.read(sender.peerId.value)
                    while (
                        candidate == null ||
                            candidate.firstSeenAtEpochMillis <= initialRecord.firstSeenAtEpochMillis
                    ) {
                        kotlinx.coroutines.delay(10)
                        candidate = trustStore.read(sender.peerId.value)
                    }
                    candidate
                }

            // Assert
            assertTrue(
                relearnedRecord.firstSeenAtEpochMillis > initialRecord.firstSeenAtEpochMillis,
                "Expected a forgotten peer to be relearned as fresh TOFU state",
            )
            assertTrue(
                receiver.diagnosticSink.events().count {
                    it.code == DiagnosticCode.TRUST_ESTABLISHED
                } > initialTrustEstablishedCount,
                "Expected recontact after forgetPeer to emit a fresh TRUST_ESTABLISHED diagnostic",
            )
        }

    @Test
    fun `identity change emits trust failure diagnostics`() =
        runBlocking<Unit> {
            // Arrange
            val harness = harness()
            val receiver = harness.createNode("peer-receiver")
            val trustedSender = harness.createNode("peer-sender")

            receiver.meshLink.start()
            trustedSender.meshLink.start()
            trustedSender.meshLink.send(receiver.peerId, "hello".encodeToByteArray())

            val replacedSender = harness.createNode("peer-sender", identityLabel = "rotated")
            replacedSender.meshLink.start()
            val trustFailure = async {
                withTimeout(1_000) {
                    receiver.meshLink.diagnosticEvents.first {
                        it.code == DiagnosticCode.TRUST_FAILURE
                    }
                }
            }

            // Act
            replacedSender.meshLink.send(receiver.peerId, "hello-again".encodeToByteArray())

            // Assert
            assertEquals(DiagnosticCode.TRUST_FAILURE, trustFailure.await().code)
            assertTrue(
                receiver.diagnosticSink.events().any { it.code == DiagnosticCode.TRUST_FAILURE }
            )
        }

    private val harnesses: MutableList<MeshTestHarness> = mutableListOf()

    @AfterTest
    fun tearDownHarnesses(): Unit =
        runBlocking<Unit> {
            harnesses.asReversed().forEach { harness -> runCatching { harness.stopAll() } }
            harnesses.clear()
        }

    private fun harness(): MeshTestHarness = MeshTestHarness().also(harnesses::add)

    private class RecordingBleTransport : BleTransport {
        var startCalls: Int = 0
        var pauseCalls: Int = 0
        var resumeCalls: Int = 0
        var stopCalls: Int = 0

        override val events: Flow<TransportEvent> = emptyFlow()

        override suspend fun start(): Unit {
            startCalls += 1
        }

        override suspend fun pause(): Unit {
            pauseCalls += 1
        }

        override suspend fun resume(): Unit {
            resumeCalls += 1
        }

        override suspend fun stop(): Unit {
            stopCalls += 1
        }

        override suspend fun send(frame: OutboundFrame): TransportSendResult {
            return TransportSendResult.Delivered
        }
    }
}
