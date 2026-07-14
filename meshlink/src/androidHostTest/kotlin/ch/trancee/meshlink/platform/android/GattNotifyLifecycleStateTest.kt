package ch.trancee.meshlink.platform.android

import ch.trancee.meshlink.platform.android.gatt.closedGattNotifyLifecycle
import ch.trancee.meshlink.platform.android.gatt.connectedGattNotifyLifecycle
import ch.trancee.meshlink.platform.android.gatt.descriptorWrittenGattNotifyLifecycle
import ch.trancee.meshlink.platform.android.gatt.mtuChangedGattNotifyLifecycle
import ch.trancee.meshlink.platform.android.gatt.startedGattNotifyLifecycle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GattNotifyLifecycleStateTest {
    @Test
    fun startedGattNotifyLifecycleResetsTheDefaultState(): Unit {
        // Act
        val state = startedGattNotifyLifecycle(defaultAttMtuBytes = 23)

        // Assert
        assertFalse(state.ready)
        assertFalse(state.servicesDiscoveryStarted)
        assertFalse(state.closedByOwner)
        assertEquals(23, state.currentMtu)
    }

    @Test
    fun connectedGattNotifyLifecycleDefersDiscoveryWhenMtuWasRequested(): Unit {
        // Arrange
        val state = startedGattNotifyLifecycle(defaultAttMtuBytes = 23)

        // Act
        val plan = connectedGattNotifyLifecycle(state = state, requestedMtu = true)

        // Assert
        assertFalse(plan.shouldDiscoverServices)
        assertFalse(plan.state.servicesDiscoveryStarted)
        assertFalse(plan.state.closedByOwner)
    }

    @Test
    fun connectedGattNotifyLifecycleStartsDiscoveryWhenMtuRequestFails(): Unit {
        // Arrange
        val state = startedGattNotifyLifecycle(defaultAttMtuBytes = 23)

        // Act
        val plan = connectedGattNotifyLifecycle(state = state, requestedMtu = false)

        // Assert
        assertTrue(plan.shouldDiscoverServices)
        assertTrue(plan.state.servicesDiscoveryStarted)
        assertFalse(plan.state.closedByOwner)
    }

    @Test
    fun mtuChangedGattNotifyLifecycleUpdatesTheMtuAndStartsDiscoveryOnce(): Unit {
        // Arrange
        val initialState = startedGattNotifyLifecycle(defaultAttMtuBytes = 23)

        // Act
        val firstPlan =
            mtuChangedGattNotifyLifecycle(state = initialState, mtu = 185, mtuAccepted = true)
        val secondPlan =
            mtuChangedGattNotifyLifecycle(state = firstPlan.state, mtu = 200, mtuAccepted = true)

        // Assert
        assertTrue(firstPlan.shouldDiscoverServices)
        assertEquals(185, firstPlan.state.currentMtu)
        assertTrue(firstPlan.state.servicesDiscoveryStarted)
        assertFalse(secondPlan.shouldDiscoverServices)
        assertEquals(200, secondPlan.state.currentMtu)
        assertTrue(secondPlan.state.servicesDiscoveryStarted)
    }

    @Test
    fun descriptorWrittenGattNotifyLifecycleMarksTheClientReadyOnlyOnSuccess(): Unit {
        // Arrange
        val state = startedGattNotifyLifecycle(defaultAttMtuBytes = 23)

        // Act
        val successPlan = descriptorWrittenGattNotifyLifecycle(state = state, success = true)
        val failurePlan = descriptorWrittenGattNotifyLifecycle(state = state, success = false)

        // Assert
        assertTrue(successPlan.ready)
        assertTrue(successPlan.state.ready)
        assertFalse(failurePlan.ready)
        assertFalse(failurePlan.state.ready)
    }

    @Test
    fun closedGattNotifyLifecycleResetsRuntimeFlagsAndTracksOwnerClosure(): Unit {
        // Act
        val state = closedGattNotifyLifecycle(defaultAttMtuBytes = 23, markClosedByOwner = true)

        // Assert
        assertFalse(state.ready)
        assertFalse(state.servicesDiscoveryStarted)
        assertTrue(state.closedByOwner)
        assertEquals(23, state.currentMtu)
    }
}
