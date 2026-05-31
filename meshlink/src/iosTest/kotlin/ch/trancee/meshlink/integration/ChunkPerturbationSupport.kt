package ch.trancee.meshlink.integration

import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSProcessInfo

internal actual fun supportsSyntheticOutOfOrderChunkDelivery(): Boolean = true

internal actual fun supportsRelayLargeTransferStressScenarios(): Boolean = !isCiRuntime()

internal actual fun supportsRelayRoutingStressScenarios(): Boolean = !isCiRuntime()

private fun isCiRuntime(): Boolean {
    val environment = NSProcessInfo.processInfo.environment
    return environment["CI"] != null ||
        environment["GITHUB_ACTIONS"] != null ||
        NSHomeDirectory().contains("/Users/runner")
}
