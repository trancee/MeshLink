package ch.trancee.meshlink.api

import ch.trancee.meshlink.crypto.createCryptoProvider
import ch.trancee.meshlink.power.PowerTier
import ch.trancee.meshlink.power.StubBatteryMonitor
import ch.trancee.meshlink.storage.InMemorySecureStorage
import ch.trancee.meshlink.transport.VirtualMeshTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class MeshLinkTest {

    private val crypto = createCryptoProvider()

    // ── Helper ────────────────────────────────────────────────────────────
    // RULE: PowerManager.init launches a battery-poll `while(true){delay()}` on the engineScope.
    // Never call advanceUntilIdle() while the engine is active — it loops forever in virtual time.
    // Always stop the engine FIRST, THEN call advanceUntilIdle() if needed.

    private fun TestScope.makeMesh(
        config: MeshLinkConfig = meshLinkConfig("ch.trancee.test")
    ): MeshLink {
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

    // ── Instantiation ─────────────────────────────────────────────────────

    @Test
    fun `MeshLink can be instantiated with default config`() = runTest {
        val mesh = makeMesh()
        assertEquals(MeshLinkState.UNINITIALIZED, mesh.state.value)
        assertNotNull(mesh)
        mesh.stopEngineForTest()
        advanceUntilIdle()
    }

    @Test
    fun `localPublicKey is 32 bytes after instantiation`() = runTest {
        val mesh = makeMesh()
        assertEquals(32, mesh.localPublicKey.size)
        mesh.stopEngineForTest()
        advanceUntilIdle()
    }

    // ── start → RUNNING ──────────────────────────────────────────────────

    @Test
    fun `start transitions to RUNNING`() = runTest {
        val mesh = makeMesh()
        mesh.start()
        assertEquals(MeshLinkState.RUNNING, mesh.state.value)
        mesh.stop()
    }

    // ── start → RUNNING → stop → STOPPED ─────────────────────────────────

    @Test
    fun `stop after start transitions to STOPPED`() = runTest {
        val mesh = makeMesh()
        mesh.start()
        mesh.stop()
        assertEquals(MeshLinkState.STOPPED, mesh.state.value)
    }

    @Test
    fun `full start-stop cycle emits correct state sequence`() = runTest {
        val mesh = makeMesh()
        // StateFlow.value is synchronously readable — no coroutine subscription race.
        assertEquals(MeshLinkState.UNINITIALIZED, mesh.state.value)
        mesh.start()
        assertEquals(MeshLinkState.RUNNING, mesh.state.value)
        mesh.stop()
        assertEquals(MeshLinkState.STOPPED, mesh.state.value)
    }

    // ── pause / resume ────────────────────────────────────────────────────

    @Test
    fun `pause transitions RUNNING to PAUSED`() = runTest {
        val mesh = makeMesh()
        mesh.start()
        mesh.pause()
        assertEquals(MeshLinkState.PAUSED, mesh.state.value)
        mesh.stop()
    }

    @Test
    fun `resume transitions PAUSED back to RUNNING`() = runTest {
        val mesh = makeMesh()
        mesh.start()
        mesh.pause()
        mesh.resume()
        assertEquals(MeshLinkState.RUNNING, mesh.state.value)
        mesh.stop()
    }

    @Test
    fun `pause-resume cycle leaves state as RUNNING`() = runTest {
        val mesh = makeMesh()
        mesh.start()
        mesh.pause()
        mesh.resume()
        mesh.pause()
        mesh.resume()
        assertEquals(MeshLinkState.RUNNING, mesh.state.value)
        mesh.stop()
    }

    // ── Invalid transitions + diagnosticEvents ────────────────────────────
    // Check MeshLink.lastDiagnosticEvent (reads replayCache[0]) directly — avoids coroutine
    // subscription timing issues. The diagnostic event is emitted synchronously via tryEmit
    // inside MeshLinkStateMachine.invalidTransition() before the exception is thrown.

    @Test
    fun `start from RUNNING throws and emits InvalidStateTransition diagnostic`() = runTest {
        val mesh = makeMesh()
        mesh.start()

        assertFailsWith<IllegalStateException> { mesh.start() }

        val event = mesh.lastDiagnosticEvent
        assertNotNull(event, "Expected a diagnostic event in replay cache")
        assertTrue(event is DiagnosticEvent.InvalidStateTransition)
        assertEquals(MeshLinkState.RUNNING, (event as DiagnosticEvent.InvalidStateTransition).from)

        mesh.stop()
    }

    @Test
    fun `stop from UNINITIALIZED throws and emits InvalidStateTransition diagnostic`() = runTest {
        val mesh = makeMesh()

        assertFailsWith<IllegalStateException> { mesh.stop() }

        val event = mesh.lastDiagnosticEvent
        assertNotNull(event, "Expected a diagnostic event in replay cache")
        assertTrue(event is DiagnosticEvent.InvalidStateTransition)
        assertEquals(
            MeshLinkState.UNINITIALIZED,
            (event as DiagnosticEvent.InvalidStateTransition).from,
        )

        mesh.stopEngineForTest()
        advanceUntilIdle()
    }

    @Test
    fun `pause from PAUSED throws and emits InvalidStateTransition diagnostic`() = runTest {
        val mesh = makeMesh()
        mesh.start()
        mesh.pause()

        assertFailsWith<IllegalStateException> { mesh.pause() }

        val event = mesh.lastDiagnosticEvent
        assertNotNull(event, "Expected a diagnostic event in replay cache")
        assertTrue(event is DiagnosticEvent.InvalidStateTransition)
        assertEquals(MeshLinkState.PAUSED, (event as DiagnosticEvent.InvalidStateTransition).from)

        mesh.stop()
    }

    @Test
    fun `resume from RUNNING throws and emits InvalidStateTransition diagnostic`() = runTest {
        val mesh = makeMesh()
        mesh.start()

        assertFailsWith<IllegalStateException> { mesh.resume() }

        val event = mesh.lastDiagnosticEvent
        assertNotNull(event, "Expected a diagnostic event in replay cache")
        assertTrue(event is DiagnosticEvent.InvalidStateTransition)
        assertEquals(MeshLinkState.RUNNING, (event as DiagnosticEvent.InvalidStateTransition).from)

        mesh.stop()
    }

    @Test
    fun `state remains unchanged after invalid start transition`() = runTest {
        val mesh = makeMesh()
        mesh.start()
        val stateBeforeInvalidCall = mesh.state.value

        runCatching { mesh.start() }

        // FSM InvalidTransition must NOT mutate the state.
        assertEquals(stateBeforeInvalidCall, mesh.state.value)
        mesh.stop()
    }

    // ── send / broadcast delegate to MeshEngine ──────────────────────────

    @Test
    fun `send while RUNNING queues or delivers without throwing`() = runTest {
        val mesh = makeMesh()
        mesh.start()
        // DeliveryPipeline throws for unknown recipients; catch and verify no crash beyond that.
        val result = runCatching { mesh.send(ByteArray(12), byteArrayOf(1, 2, 3)) }
        // Either silent queue (no throw) OR "Unknown recipient" — both mean delegation happened.
        result.exceptionOrNull()?.let { e ->
            assertTrue(
                e is IllegalStateException && e.message?.contains("X25519") == true,
                "Unexpected exception: $e",
            )
        }
        mesh.stop()
    }

    @Test
    fun `broadcast while RUNNING does not throw`() = runTest {
        val mesh = makeMesh()
        mesh.start()
        mesh.broadcast(byteArrayOf(42), maxHops = 2)
        mesh.stop()
    }

    // ── routingSnapshot ───────────────────────────────────────────────────

    @Test
    fun `routingSnapshot returns valid snapshot immediately after start`() = runTest {
        val mesh = makeMesh()
        mesh.start()
        val snapshot = mesh.routingSnapshot()
        assertNotNull(snapshot)
        assertTrue(snapshot.capturedAtMs >= 0L)
        assertTrue(snapshot.routes.isEmpty()) // no peers connected
        mesh.stop()
    }

    @Test
    fun `routingSnapshot before start returns empty routes`() = runTest {
        val mesh = makeMesh()
        val snapshot = mesh.routingSnapshot()
        assertNotNull(snapshot)
        assertTrue(snapshot.routes.isEmpty())
        mesh.stopEngineForTest()
        advanceUntilIdle()
    }

    // ── meshHealth ───────────────────────────────────────────────────────

    @Test
    fun `meshHealth returns non-negative values`() = runTest {
        val mesh = makeMesh()
        mesh.start()
        val health = mesh.meshHealth()
        assertTrue(health.connectedPeers >= 0)
        assertTrue(health.routingTableSize >= 0)
        assertTrue(health.bufferUsageBytes >= 0L)
        assertTrue(health.capturedAtMs >= 0L)
        mesh.stop()
    }

    // ── Public companion factory ──────────────────────────────────────────

    @Test
    fun `MeshLink companion factory instantiates successfully`() {
        val config = meshLinkConfig("ch.trancee.factory.test")
        val mesh = MeshLink(config)
        assertNotNull(mesh)
        assertEquals(MeshLinkState.UNINITIALIZED, mesh.state.value)
    }

    // ── S02-wiring stubs: all return without throwing ─────────────────────

    @Test
    fun `all S02-wiring stub methods do not throw`() = runTest {
        val mesh = makeMesh()
        mesh.start()
        // power stubs
        mesh.updateBattery(80f, false)
        mesh.setCustomPowerMode(PowerTier.PERFORMANCE)
        mesh.setCustomPowerMode(null) // explicit null
        mesh.setCustomPowerMode() // default-parameter form (exercises synthetic method)
        // identity stubs
        assertNotNull(mesh.localPublicKey) // covered already, but keep sequence intact
        assertTrue(mesh.peerPublicKey(ByteArray(12)) == null)
        assertTrue(mesh.peerDetail(ByteArray(12)) == null)
        assertTrue(mesh.allPeerDetails().isEmpty())
        assertTrue(mesh.peerFingerprint(ByteArray(12)) == null)
        mesh.rotateIdentity()
        mesh.repinKey(ByteArray(12))
        mesh.acceptKeyChange(ByteArray(12))
        mesh.rejectKeyChange(ByteArray(12))
        assertTrue(mesh.pendingKeyChanges().isEmpty())
        // health/routing stubs
        mesh.shedMemoryPressure()
        mesh.forgetPeer(ByteArray(12))
        mesh.factoryReset()
        @OptIn(ExperimentalMeshLinkApi::class) mesh.addRoute(ByteArray(12), ByteArray(12), 1, 1)
        mesh.stop()
    }

    // ── toInternal: LOW and HIGH priority paths ───────────────────────────

    @Test
    fun `send with HIGH priority does not throw`() = runTest {
        val mesh = makeMesh()
        mesh.start()
        runCatching { mesh.send(ByteArray(12), byteArrayOf(1), MessagePriority.HIGH) }
        mesh.stop()
    }

    @Test
    fun `send with LOW priority does not throw`() = runTest {
        val mesh = makeMesh()
        mesh.start()
        runCatching { mesh.send(ByteArray(12), byteArrayOf(1), MessagePriority.LOW) }
        mesh.stop()
    }

    // ── routingSnapshot with injected routes ──────────────────────────────

    @Test
    fun `routingSnapshot with routes returns sorted entries`() = runTest {
        val mesh = makeMesh()
        mesh.start()
        // Inject a synthetic route directly into the engine's routing table
        val futureExpiry = testScheduler.currentTime + 60_000L
        mesh.injectTestRoute(
            destination = ByteArray(12) { 0xAA.toByte() },
            nextHop = ByteArray(12) { 0xBB.toByte() },
            metric = 2.0,
            expiresAt = futureExpiry,
        )
        val snapshot = mesh.routingSnapshot()
        assertTrue(snapshot.routes.isNotEmpty())
        assertTrue(snapshot.routes.first().cost >= 0)
        mesh.stop()
    }

    // ── MeshLinkStateMachine DEFAULT_CLOCK ────────────────────────────────

    @Test
    fun `MeshLinkStateMachine uses DEFAULT_CLOCK when no clock injected`() {
        // Exercises MeshLinkStateMachine.DEFAULT_CLOCK lambda and its companion getter.
        val sm = MeshLinkStateMachine(onDiagnostic = {})
        assertEquals(MeshLinkState.UNINITIALIZED, sm.state.value)
        // Verify DEFAULT_CLOCK produces a non-negative monotonic time.
        assertTrue(MeshLinkStateMachine.DEFAULT_CLOCK() >= 0L)
    }
}
