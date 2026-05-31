package ch.trancee.meshlink.integration

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

internal actual fun supportsSyntheticOutOfOrderChunkDelivery(): Boolean = true

internal actual fun supportsRelayLargeTransferStressScenarios(): Boolean = !isCiRuntime()

internal actual fun supportsRelayRoutingStressScenarios(): Boolean = !isCiRuntime()

@OptIn(ExperimentalForeignApi::class)
private fun isCiRuntime(): Boolean {
    return getenv("CI")?.toKString() != null || getenv("GITHUB_ACTIONS")?.toKString() != null
}
