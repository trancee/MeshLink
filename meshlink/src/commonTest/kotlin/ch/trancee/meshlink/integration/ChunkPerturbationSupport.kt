package ch.trancee.meshlink.integration

internal expect fun supportsSyntheticOutOfOrderChunkDelivery(): Boolean

internal expect fun supportsRelayLargeTransferStressScenarios(): Boolean
