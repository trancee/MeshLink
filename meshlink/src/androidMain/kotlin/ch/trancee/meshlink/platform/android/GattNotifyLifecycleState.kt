package ch.trancee.meshlink.platform.android

internal data class GattNotifyLifecycleState(
    val ready: Boolean,
    val servicesDiscoveryStarted: Boolean,
    val closedByOwner: Boolean,
    val currentMtu: Int,
)

internal data class GattNotifyLifecyclePlan(
    val state: GattNotifyLifecycleState,
    val shouldDiscoverServices: Boolean,
)

internal data class GattNotifyDescriptorPlan(
    val state: GattNotifyLifecycleState,
    val ready: Boolean,
)

internal fun startedGattNotifyLifecycle(defaultAttMtuBytes: Int): GattNotifyLifecycleState {
    return GattNotifyLifecycleState(
        ready = false,
        servicesDiscoveryStarted = false,
        closedByOwner = false,
        currentMtu = defaultAttMtuBytes,
    )
}

internal fun connectedGattNotifyLifecycle(
    state: GattNotifyLifecycleState,
    requestedMtu: Boolean,
): GattNotifyLifecyclePlan {
    val shouldDiscoverServices = !requestedMtu
    return GattNotifyLifecyclePlan(
        state =
            state.copy(
                ready = false,
                servicesDiscoveryStarted = shouldDiscoverServices,
                closedByOwner = false,
            ),
        shouldDiscoverServices = shouldDiscoverServices,
    )
}

internal fun mtuChangedGattNotifyLifecycle(
    state: GattNotifyLifecycleState,
    mtu: Int,
    mtuAccepted: Boolean,
): GattNotifyLifecyclePlan {
    val shouldDiscoverServices = !state.servicesDiscoveryStarted
    return GattNotifyLifecyclePlan(
        state =
            state.copy(
                currentMtu = if (mtuAccepted) mtu else state.currentMtu,
                servicesDiscoveryStarted = state.servicesDiscoveryStarted || shouldDiscoverServices,
            ),
        shouldDiscoverServices = shouldDiscoverServices,
    )
}

internal fun descriptorWrittenGattNotifyLifecycle(
    state: GattNotifyLifecycleState,
    success: Boolean,
): GattNotifyDescriptorPlan {
    return GattNotifyDescriptorPlan(
        state = if (success) state.copy(ready = true) else state,
        ready = success,
    )
}

internal fun closedGattNotifyLifecycle(
    defaultAttMtuBytes: Int,
    markClosedByOwner: Boolean,
): GattNotifyLifecycleState {
    return GattNotifyLifecycleState(
        ready = false,
        servicesDiscoveryStarted = false,
        closedByOwner = markClosedByOwner,
        currentMtu = defaultAttMtuBytes,
    )
}
