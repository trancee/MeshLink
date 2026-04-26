package ch.trancee.meshlink.api

import ch.trancee.meshlink.crypto.createCryptoProvider
import ch.trancee.meshlink.power.StubBatteryMonitor
import ch.trancee.meshlink.storage.InMemorySecureStorage
import ch.trancee.meshlink.transport.VirtualMeshTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class MeshLinkTest {

    private val crypto = createCryptoProvider()

    // ── Helper ────────────────────────────────────────────────────────────
    // Call from inside runTest so the engine's child scope shares the TestScope's scheduler.

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
    }

    @Test
    fun `localPublicKey is 32 bytes after instantiation`() = runTest {
        val mesh = makeMesh()
        assertEquals(32, mesh.localPublicKey.size)
        mesh.stopEngineForTest()
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
        val states = mutableListOf<MeshLinkState>()
        backgroundScope.launch { mesh.state.collect { states.add(it) } }
        advanceUntilIdle()

        mesh.start()
        mesh.stop()
        advanceUntilIdle()

        assertTrue(states.contains(MeshLinkState.UNINITIALIZED))
        assertTrue(states.contains(MeshLinkState.RUNNING))
        assertTrue(states.contains(MeshLinkState.STOPPED))
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

    // ── Invalid transitions ───────────────────────────────────────────────
    // _diagnosticFlow has replay=0: advance the collector before triggering
    // the invalid transition so tryEmit finds an active subscriber.

    @Test
    fun `start from RUNNING throws and emits InvalidStateTransition diagnostic`() = runTest {
        val mesh = makeMesh()
        mesh.start()

        val captured = mutableListOf<DiagnosticEvent>()
        backgroundScope.launch { mesh.diagnosticEvents.collect { captured.add(it) } }
        advanceUntilIdle()

        assertFailsWith<IllegalStateException> { mesh.start() }
        advanceUntilIdle()

        assertTrue(captured.isNotEmpty(), "Expected a diagnostic event but got none")
        val event = captured.first()
        assertTrue(event is DiagnosticEvent.InvalidStateTransition)
        assertEquals(MeshLinkState.RUNNING, (event as DiagnosticEvent.InvalidStateTransition).from)

        mesh.stop()
    }

    @Test
    fun `stop from UNINITIALIZED throws and emits InvalidStateTransition diagnostic`() = runTest {
        val mesh = makeMesh()

        val captured = mutableListOf<DiagnosticEvent>()
        backgroundScope.launch { mesh.diagnosticEvents.collect { captured.add(it) } }
        advanceUntilIdle()

        assertFailsWith<IllegalStateException> { mesh.stop() }
        advanceUntilIdle()

        assertTrue(captured.isNotEmpty(), "Expected a diagnostic event but got none")
        assertTrue(captured.first() is DiagnosticEvent.InvalidStateTransition)

        mesh.stopEngineForTest() // engine was never started; bypass FSM
    }

    @Test
    fun `pause from PAUSED throws and emits InvalidStateTransition diagnostic`() = runTest {
        val mesh = makeMesh()
        mesh.start()
        mesh.pause()

        val captured = mutableListOf<DiagnosticEvent>()
        backgroundScope.launch { mesh.diagnosticEvents.collect { captured.add(it) } }
        advanceUntilIdle()

        assertFailsWith<IllegalStateException> { mesh.pause() }
        advanceUntilIdle()

        assertTrue(captured.isNotEmpty(), "Expected a diagnostic event but got none")
        assertTrue(captured.first() is DiagnosticEvent.InvalidStateTransition)

        mesh.stop()
    }

    @Test
    fun `resume from RUNNING throws and emits InvalidStateTransition diagnostic`() = runTest {
        val mesh = makeMesh()
        mesh.start()

        val captured = mutableListOf<DiagnosticEvent>()
        backgroundScope.launch { mesh.diagnosticEvents.collect { captured.add(it) } }
        advanceUntilIdle()

        assertFailsWith<IllegalStateException> { mesh.resume() }
        advanceUntilIdle()

        assertTrue(captured.isNotEmpty(), "Expected a diagnostic event but got none")
        assertTrue(captured.first() is DiagnosticEvent.InvalidStateTransition)

        mesh.stop()
    }

    @Test
    fun `state remains unchanged after invalid start transition`() = runTest {
        val mesh = makeMesh()
        mesh.start()
        val stateBeforeInvalidCall = mesh.state.value

        runCatching { mesh.start() }

        assertEquals(stateBeforeInvalidCall, mesh.state.value)
        mesh.stop()
    }

    // ── send / broadcast delegate to MeshEngine ──────────────────────────

    @Test
    fun `send while RUNNING does not throw`() = runTest {
        val mesh = makeMesh()
        mesh.start()
        mesh.send(ByteArray(12), byteArrayOf(1, 2, 3))
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
        assertTrue(snapshot.routes.isEmpty())
        mesh.stop()
    }

    @Test
    fun `routingSnapshot before start returns empty routes`() = runTest {
        val mesh = makeMesh()
        val snapshot = mesh.routingSnapshot()
        assertNotNull(snapshot)
        assertTrue(snapshot.routes.isEmpty())
        mesh.stopEngineForTest()
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
        // Note: the scope for the companion factory is Dispatchers.Default — not cancelable here.
        // This test only checks instantiation; the engine coroutines are background-only.
    }
}
