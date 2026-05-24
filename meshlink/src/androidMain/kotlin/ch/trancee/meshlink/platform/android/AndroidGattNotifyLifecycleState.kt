package ch.trancee.meshlink.platform.android

internal data class AndroidGattNotifyLifecycleState(
    val ready: Boolean,
    val servicesDiscoveryStarted: Boolean,
    val closedByOwner: Boolean,
    val currentMtu: Int,
)

internal data class AndroidGattNotifyLifecyclePlan(
    val state: AndroidGattNotifyLifecycleState,
    val shouldDiscoverServices: Boolean,
)

internal data class AndroidGattNotifyDescriptorPlan(
    val state: AndroidGattNotifyLifecycleState,
    val ready: Boolean,
)

internal fun startedAndroidGattNotifyLifecycle(
    defaultAttMtuBytes: Int
): AndroidGattNotifyLifecycleState {
    return AndroidGattNotifyLifecycleState(
        ready = false,
        servicesDiscoveryStarted = false,
        closedByOwner = false,
        currentMtu = defaultAttMtuBytes,
    )
}

internal fun connectedAndroidGattNotifyLifecycle(
    state: AndroidGattNotifyLifecycleState,
    requestedMtu: Boolean,
): AndroidGattNotifyLifecyclePlan {
    val shouldDiscoverServices = !requestedMtu
    return AndroidGattNotifyLifecyclePlan(
        state =
            state.copy(
                ready = false,
                servicesDiscoveryStarted = shouldDiscoverServices,
                closedByOwner = false,
            ),
        shouldDiscoverServices = shouldDiscoverServices,
    )
}

internal fun mtuChangedAndroidGattNotifyLifecycle(
    state: AndroidGattNotifyLifecycleState,
    mtu: Int,
    mtuAccepted: Boolean,
): AndroidGattNotifyLifecyclePlan {
    val shouldDiscoverServices = !state.servicesDiscoveryStarted
    return AndroidGattNotifyLifecyclePlan(
        state =
            state.copy(
                currentMtu = if (mtuAccepted) mtu else state.currentMtu,
                servicesDiscoveryStarted = state.servicesDiscoveryStarted || shouldDiscoverServices,
            ),
        shouldDiscoverServices = shouldDiscoverServices,
    )
}

internal fun descriptorWrittenAndroidGattNotifyLifecycle(
    state: AndroidGattNotifyLifecycleState,
    success: Boolean,
): AndroidGattNotifyDescriptorPlan {
    return AndroidGattNotifyDescriptorPlan(
        state = if (success) state.copy(ready = true) else state,
        ready = success,
    )
}

internal fun closedAndroidGattNotifyLifecycle(
    defaultAttMtuBytes: Int,
    markClosedByOwner: Boolean,
): AndroidGattNotifyLifecycleState {
    return AndroidGattNotifyLifecycleState(
        ready = false,
        servicesDiscoveryStarted = false,
        closedByOwner = markClosedByOwner,
        currentMtu = defaultAttMtuBytes,
    )
}
