package ch.trancee.meshlink.integration

internal actual fun supportsSyntheticOutOfOrderChunkDelivery(): Boolean = true

internal actual fun supportsRelayLargeTransferStressScenarios(): Boolean =
    System.getenv("CI") == null
