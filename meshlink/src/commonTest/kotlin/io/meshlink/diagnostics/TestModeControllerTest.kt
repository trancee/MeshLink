package io.meshlink.diagnostics

import io.meshlink.diagnostics.TestModeController.MeshSnapshot
import io.meshlink.diagnostics.TestModeController.TestAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TestModeControllerTest {

    @Test
    fun enqueueReturnsTrueWhenEnabled() {
        val controller = TestModeController(enabled = true)
        assertTrue(controller.enqueue(TestAction.ForceGossip))
    }

    @Test
    fun enqueueReturnsFalseWhenDisabled() {
        val controller = TestModeController(enabled = false)
        assertFalse(controller.enqueue(TestAction.ForceGossip))
    }

    @Test
    fun drainActionsReturnsAllAndClearsQueue() {
        val controller = TestModeController(enabled = true)
        controller.enqueue(TestAction.ForceGossip)
        controller.enqueue(TestAction.RequestSnapshot)

        val drained = controller.drainActions()
        assertEquals(2, drained.size)
        assertEquals(0, controller.pendingCount())
    }

    @Test
    fun multipleActionsQueuedInOrder() {
        val controller = TestModeController(enabled = true)
        val actions = listOf(
            TestAction.ForceGossip,
            TestAction.ClearState,
            TestAction.ForceRotation,
        )
        actions.forEach { controller.enqueue(it) }

        val drained = controller.drainActions()
        assertEquals(actions, drained)
    }

    @Test
    fun allTestActionVariantsCanBeEnqueued() {
        val controller = TestModeController(enabled = true)
        val variants = listOf(
            TestAction.ForceGossip,
            TestAction.SimulatePeerLoss(peerIdHex = "aabbccdd"),
            TestAction.InjectDiagnostic(DiagnosticCode.RATE_LIMIT_HIT, Severity.WARN, "test payload"),
            TestAction.ForceMemoryPressure(level = 75),
            TestAction.RequestSnapshot,
            TestAction.ClearState,
            TestAction.ForceRotation,
        )

        variants.forEach { assertTrue(controller.enqueue(it)) }
        assertEquals(variants.size, controller.pendingCount())

        val drained = controller.drainActions()
        assertEquals(variants, drained)
    }

    @Test
    fun storeSnapshotAndLastSnapshotRoundTrip() {
        val controller = TestModeController(enabled = true)
        assertNull(controller.lastSnapshot())

        val snapshot = MeshSnapshot(
            peerCount = 5,
            routeCount = 12,
            activeTransfers = 3,
            pendingMessages = 7,
            reassemblyBuffers = 2,
            tombstoneCount = 1,
            diagnosticEventCount = 42,
            powerMode = "balanced",
            started = true,
            paused = false,
        )
        controller.storeSnapshot(snapshot)
        assertEquals(snapshot, controller.lastSnapshot())
    }

    @Test
    fun pendingCountTracksCorrectly() {
        val controller = TestModeController(enabled = true)
        assertEquals(0, controller.pendingCount())

        controller.enqueue(TestAction.ForceGossip)
        assertEquals(1, controller.pendingCount())

        controller.enqueue(TestAction.ClearState)
        assertEquals(2, controller.pendingCount())

        controller.drainActions()
        assertEquals(0, controller.pendingCount())
    }

    @Test
    fun clearQueueEmptiesPending() {
        val controller = TestModeController(enabled = true)
        controller.enqueue(TestAction.ForceGossip)
        controller.enqueue(TestAction.ForceRotation)
        assertEquals(2, controller.pendingCount())

        controller.clearQueue()
        assertEquals(0, controller.pendingCount())
        assertTrue(controller.drainActions().isEmpty())
    }

    @Test
    fun disabledControllerNeverAcceptsActions() {
        val controller = TestModeController(enabled = false)
        assertFalse(controller.enqueue(TestAction.ForceGossip))
        assertFalse(controller.enqueue(TestAction.ClearState))
        assertFalse(controller.enqueue(TestAction.SimulatePeerLoss("aabb")))
        assertEquals(0, controller.pendingCount())
    }

    @Test
    fun drainOnEmptyQueueReturnsEmptyList() {
        val controller = TestModeController(enabled = true)
        val drained = controller.drainActions()
        assertTrue(drained.isEmpty())
    }

    @Test
    fun isEnabledReflectsConstructionParam() {
        assertTrue(TestModeController(enabled = true).isEnabled())
        assertFalse(TestModeController(enabled = false).isEnabled())
        assertFalse(TestModeController().isEnabled())
    }

    @Test
    fun snapshotDataClassFieldsPreserved() {
        val snapshot = MeshSnapshot(
            peerCount = 10,
            routeCount = 20,
            activeTransfers = 5,
            pendingMessages = 15,
            reassemblyBuffers = 8,
            tombstoneCount = 3,
            diagnosticEventCount = 100,
            powerMode = "low",
            started = false,
            paused = true,
        )

        assertEquals(10, snapshot.peerCount)
        assertEquals(20, snapshot.routeCount)
        assertEquals(5, snapshot.activeTransfers)
        assertEquals(15, snapshot.pendingMessages)
        assertEquals(8, snapshot.reassemblyBuffers)
        assertEquals(3, snapshot.tombstoneCount)
        assertEquals(100, snapshot.diagnosticEventCount)
        assertEquals("low", snapshot.powerMode)
        assertFalse(snapshot.started)
        assertTrue(snapshot.paused)
    }

    @Test
    fun injectDiagnosticWithNullPayload() {
        val controller = TestModeController(enabled = true)
        val action = TestAction.InjectDiagnostic(DiagnosticCode.BUFFER_PRESSURE, Severity.ERROR, null)
        assertTrue(controller.enqueue(action))

        val drained = controller.drainActions()
        assertEquals(1, drained.size)
        val injected = drained.first() as TestAction.InjectDiagnostic
        assertEquals(DiagnosticCode.BUFFER_PRESSURE, injected.code)
        assertEquals(Severity.ERROR, injected.severity)
        assertNull(injected.payload)
    }

    @Test
    fun storeSnapshotOverwritesPrevious() {
        val controller = TestModeController(enabled = true)
        val first = MeshSnapshot(1, 1, 1, 1, 1, 1, 1, "a", true, false)
        val second = MeshSnapshot(2, 2, 2, 2, 2, 2, 2, "b", false, true)

        controller.storeSnapshot(first)
        assertEquals(first, controller.lastSnapshot())

        controller.storeSnapshot(second)
        assertEquals(second, controller.lastSnapshot())
    }
}
