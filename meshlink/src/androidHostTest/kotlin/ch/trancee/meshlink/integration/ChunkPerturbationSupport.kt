package ch.trancee.meshlink.integration

internal actual fun supportsSyntheticOutOfOrderChunkDelivery(): Boolean = false

internal actual fun supportsRelayLargeTransferStressScenarios(): Boolean = false

internal actual fun supportsRelayRoutingStressScenarios(): Boolean = false
