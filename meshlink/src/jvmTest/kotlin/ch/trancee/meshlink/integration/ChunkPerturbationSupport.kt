package ch.trancee.meshlink.integration

internal actual fun supportsSyntheticOutOfOrderChunkDelivery(): Boolean = true

internal actual fun supportsRelayLargeTransferStressScenarios(): Boolean = !isCiRuntime()

internal actual fun supportsRelayRoutingStressScenarios(): Boolean = !isCiRuntime()

private fun isCiRuntime(): Boolean {
    val ciProperty = System.getProperty("meshlink.ci")
    if (!ciProperty.isNullOrBlank() && ciProperty != "false") {
        return true
    }
    return System.getenv("CI") != null
}
