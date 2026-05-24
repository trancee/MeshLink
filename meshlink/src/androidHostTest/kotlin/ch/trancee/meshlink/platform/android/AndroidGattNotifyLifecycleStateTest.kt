package ch.trancee.meshlink.platform.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidGattNotifyLifecycleStateTest {
    @Test
    fun startedAndroidGattNotifyLifecycleResetsTheDefaultState(): Unit {
        // Act
        val state = startedAndroidGattNotifyLifecycle(defaultAttMtuBytes = 23)

        // Assert
        assertFalse(state.ready)
        assertFalse(state.servicesDiscoveryStarted)
        assertFalse(state.closedByOwner)
        assertEquals(23, state.currentMtu)
    }

    @Test
    fun connectedAndroidGattNotifyLifecycleDefersDiscoveryWhenMtuWasRequested(): Unit {
        // Arrange
        val state = startedAndroidGattNotifyLifecycle(defaultAttMtuBytes = 23)

        // Act
        val plan = connectedAndroidGattNotifyLifecycle(state = state, requestedMtu = true)

        // Assert
        assertFalse(plan.shouldDiscoverServices)
        assertFalse(plan.state.servicesDiscoveryStarted)
        assertFalse(plan.state.closedByOwner)
    }

    @Test
    fun connectedAndroidGattNotifyLifecycleStartsDiscoveryWhenMtuRequestFails(): Unit {
        // Arrange
        val state = startedAndroidGattNotifyLifecycle(defaultAttMtuBytes = 23)

        // Act
        val plan = connectedAndroidGattNotifyLifecycle(state = state, requestedMtu = false)

        // Assert
        assertTrue(plan.shouldDiscoverServices)
        assertTrue(plan.state.servicesDiscoveryStarted)
        assertFalse(plan.state.closedByOwner)
    }

    @Test
    fun mtuChangedAndroidGattNotifyLifecycleUpdatesTheMtuAndStartsDiscoveryOnce(): Unit {
        // Arrange
        val initialState = startedAndroidGattNotifyLifecycle(defaultAttMtuBytes = 23)

        // Act
        val firstPlan =
            mtuChangedAndroidGattNotifyLifecycle(
                state = initialState,
                mtu = 185,
                mtuAccepted = true,
            )
        val secondPlan =
            mtuChangedAndroidGattNotifyLifecycle(
                state = firstPlan.state,
                mtu = 200,
                mtuAccepted = true,
            )

        // Assert
        assertTrue(firstPlan.shouldDiscoverServices)
        assertEquals(185, firstPlan.state.currentMtu)
        assertTrue(firstPlan.state.servicesDiscoveryStarted)
        assertFalse(secondPlan.shouldDiscoverServices)
        assertEquals(200, secondPlan.state.currentMtu)
        assertTrue(secondPlan.state.servicesDiscoveryStarted)
    }

    @Test
    fun descriptorWrittenAndroidGattNotifyLifecycleMarksTheClientReadyOnlyOnSuccess(): Unit {
        // Arrange
        val state = startedAndroidGattNotifyLifecycle(defaultAttMtuBytes = 23)

        // Act
        val successPlan = descriptorWrittenAndroidGattNotifyLifecycle(state = state, success = true)
        val failurePlan =
            descriptorWrittenAndroidGattNotifyLifecycle(state = state, success = false)

        // Assert
        assertTrue(successPlan.ready)
        assertTrue(successPlan.state.ready)
        assertFalse(failurePlan.ready)
        assertFalse(failurePlan.state.ready)
    }

    @Test
    fun closedAndroidGattNotifyLifecycleResetsRuntimeFlagsAndTracksOwnerClosure(): Unit {
        // Act
        val state =
            closedAndroidGattNotifyLifecycle(defaultAttMtuBytes = 23, markClosedByOwner = true)

        // Assert
        assertFalse(state.ready)
        assertFalse(state.servicesDiscoveryStarted)
        assertTrue(state.closedByOwner)
        assertEquals(23, state.currentMtu)
    }
}
