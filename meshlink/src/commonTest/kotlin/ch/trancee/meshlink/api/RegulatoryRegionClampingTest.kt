package ch.trancee.meshlink.api

import ch.trancee.meshlink.crypto.createCryptoProvider
import ch.trancee.meshlink.power.StubBatteryMonitor
import ch.trancee.meshlink.storage.InMemorySecureStorage
import ch.trancee.meshlink.transport.VirtualMeshTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class RegulatoryRegionClampingTest {

    private val crypto = createCryptoProvider()

    private fun TestScope.makeMesh(config: MeshLinkConfig): MeshLink {
        val storage = InMemorySecureStorage()
        val battery = StubBatteryMonitor()
        val transport = VirtualMeshTransport(ByteArray(12) { it.toByte() }, testScheduler)
        return MeshLink.create(
            config = config,
            cryptoProvider = crypto,
            transport = transport,
            storage = storage,
            batteryMonitor = battery,
            parentScope = this,
            clock = { testScheduler.currentTime },
        )
    }

    // ── DEFAULT region tests ──────────────────────────────────────────────────

    @Test
    fun `DEFAULT region passes values unchanged with no clampWarnings`() {
        val config =
            meshLinkConfig("com.example.test") {
                transport {
                    advertisementIntervalMillis = 200L
                    scanDutyCyclePercent = 100
                }
                region(RegulatoryRegion.DEFAULT)
            }
        assertEquals(200L, config.transport.advertisementIntervalMillis)
        assertEquals(100, config.transport.scanDutyCyclePercent)
        // No clamp warnings about advertisement or scan from DEFAULT region.
        assertTrue(
            config.clampWarnings.none { it.startsWith("advertisementIntervalMillis") },
            "DEFAULT region should not clamp advertisementIntervalMillis",
        )
        assertTrue(
            config.clampWarnings.none { it.startsWith("scanDutyCyclePercent") },
            "DEFAULT region should not clamp scanDutyCyclePercent",
        )
    }

    @Test
    fun `DEFAULT region with values below EU minimum produces zero clampWarnings`() {
        val config =
            meshLinkConfig("com.example.test") {
                transport {
                    advertisementIntervalMillis = 100L
                    scanDutyCyclePercent = 95
                }
                region(RegulatoryRegion.DEFAULT)
            }
        assertEquals(100L, config.transport.advertisementIntervalMillis)
        assertEquals(95, config.transport.scanDutyCyclePercent)
        assertTrue(config.clampWarnings.none { it.startsWith("advertisementIntervalMillis") })
        assertTrue(config.clampWarnings.none { it.startsWith("scanDutyCyclePercent") })
    }

    @Test
    fun `DEFAULT region toBleTransportConfig passes raw values`() {
        val config =
            meshLinkConfig("com.example.test") {
                transport {
                    advertisementIntervalMillis = 150L
                    scanDutyCyclePercent = 80
                }
            }
        val bleConfig = config.toBleTransportConfig()
        assertEquals(150L, bleConfig.advertisementIntervalMillis)
        assertEquals(80, bleConfig.scanDutyCyclePercent)
    }

    // ── EU region: clamping active ────────────────────────────────────────────

    @Test
    fun `EU region clamps advertisementIntervalMillis from 250 to 300`() {
        val config =
            meshLinkConfig("com.example.test") {
                transport { advertisementIntervalMillis = 250L }
                region(RegulatoryRegion.EU)
            }
        assertEquals(300L, config.transport.advertisementIntervalMillis)
        assertTrue(
            config.clampWarnings.any { it.startsWith("advertisementIntervalMillis") },
            "Expected clamping warning for advertisementIntervalMillis",
        )
    }

    @Test
    fun `EU region clamps scanDutyCyclePercent from 100 to 70`() {
        val config =
            meshLinkConfig("com.example.test") {
                transport { scanDutyCyclePercent = 100 }
                region(RegulatoryRegion.EU)
            }
        assertEquals(70, config.transport.scanDutyCyclePercent)
        assertTrue(
            config.clampWarnings.any { it.startsWith("scanDutyCyclePercent") },
            "Expected clamping warning for scanDutyCyclePercent",
        )
    }

    @Test
    fun `EU region clamps both fields together`() {
        val config =
            meshLinkConfig("com.example.test") {
                transport {
                    advertisementIntervalMillis = 200L
                    scanDutyCyclePercent = 90
                }
                region(RegulatoryRegion.EU)
            }
        assertEquals(300L, config.transport.advertisementIntervalMillis)
        assertEquals(70, config.transport.scanDutyCyclePercent)
        assertEquals(
            2,
            config.clampWarnings.count {
                it.startsWith("advertisementIntervalMillis") ||
                    it.startsWith("scanDutyCyclePercent")
            },
        )
    }

    @Test
    fun `EU region toBleTransportConfig returns clamped values`() {
        val config =
            meshLinkConfig("com.example.test") {
                transport {
                    advertisementIntervalMillis = 250L
                    scanDutyCyclePercent = 100
                }
                region(RegulatoryRegion.EU)
            }
        val bleConfig = config.toBleTransportConfig()
        assertEquals(300L, bleConfig.advertisementIntervalMillis)
        assertEquals(70, bleConfig.scanDutyCyclePercent)
    }

    // ── EU region: already-compliant values ───────────────────────────────────

    @Test
    fun `EU region with already-compliant values produces no additional warnings`() {
        val config =
            meshLinkConfig("com.example.test") {
                transport {
                    advertisementIntervalMillis = 500L
                    scanDutyCyclePercent = 50
                }
                region(RegulatoryRegion.EU)
            }
        assertEquals(500L, config.transport.advertisementIntervalMillis)
        assertEquals(50, config.transport.scanDutyCyclePercent)
        assertTrue(config.clampWarnings.none { it.startsWith("advertisementIntervalMillis") })
        assertTrue(config.clampWarnings.none { it.startsWith("scanDutyCyclePercent") })
    }

    @Test
    fun `EU region with exact boundary values (300ms and 70 percent) produces no warnings`() {
        val config =
            meshLinkConfig("com.example.test") {
                transport {
                    advertisementIntervalMillis = 300L
                    scanDutyCyclePercent = 70
                }
                region(RegulatoryRegion.EU)
            }
        assertEquals(300L, config.transport.advertisementIntervalMillis)
        assertEquals(70, config.transport.scanDutyCyclePercent)
        assertTrue(config.clampWarnings.none { it.startsWith("advertisementIntervalMillis") })
        assertTrue(config.clampWarnings.none { it.startsWith("scanDutyCyclePercent") })
    }

    // ── CONFIG_CLAMPED diagnostic emission ────────────────────────────────────

    @Test
    fun `CONFIG_CLAMPED diagnostic emitted on start when clampWarnings non-empty`() = runTest {
        val config =
            meshLinkConfig("com.example.test") {
                transport {
                    advertisementIntervalMillis = 250L
                    scanDutyCyclePercent = 100
                }
                region(RegulatoryRegion.EU)
            }
        val mesh = makeMesh(config)
        val collected = mutableListOf<DiagnosticEvent>()
        val collectJob = launch { mesh.diagnosticEvents.collect { collected += it } }
        testScheduler.runCurrent()

        mesh.start()
        testScheduler.runCurrent()

        val configClampedEvents = collected.filter { it.code == DiagnosticCode.CONFIG_CLAMPED }
        assertEquals(
            2,
            configClampedEvents.size,
            "Expected 2 CONFIG_CLAMPED events (ad interval + scan duty), got: $configClampedEvents",
        )

        collectJob.cancel()
        mesh.stop()
    }

    @Test
    fun `no CONFIG_CLAMPED diagnostic when clampWarnings is empty`() = runTest {
        val config =
            meshLinkConfig("com.example.test") {
                transport {
                    advertisementIntervalMillis = 500L
                    scanDutyCyclePercent = 50
                }
                region(RegulatoryRegion.EU)
            }
        val mesh = makeMesh(config)
        val collected = mutableListOf<DiagnosticEvent>()
        val collectJob = launch { mesh.diagnosticEvents.collect { collected += it } }
        testScheduler.runCurrent()

        mesh.start()
        testScheduler.runCurrent()

        val configClampedEvents = collected.filter { it.code == DiagnosticCode.CONFIG_CLAMPED }
        assertTrue(
            configClampedEvents.isEmpty(),
            "Should not emit CONFIG_CLAMPED when no fields are clamped",
        )

        collectJob.cancel()
        mesh.stop()
    }

    // ── Builder round-trip ────────────────────────────────────────────────────

    @Test
    fun `MeshLinkConfigBuilder with region EU compiles and round-trips`() {
        val config = meshLinkConfig("com.example.test") { region(RegulatoryRegion.EU) }
        assertEquals(RegulatoryRegion.EU, config.region)
    }

    @Test
    fun `MeshLinkConfigBuilder with region DEFAULT compiles and round-trips`() {
        val config = meshLinkConfig("com.example.test") { region(RegulatoryRegion.DEFAULT) }
        assertEquals(RegulatoryRegion.DEFAULT, config.region)
    }

    @Test
    fun `config region defaults to DEFAULT when not set`() {
        val config = meshLinkConfig("com.example.test")
        assertEquals(RegulatoryRegion.DEFAULT, config.region)
    }
}
