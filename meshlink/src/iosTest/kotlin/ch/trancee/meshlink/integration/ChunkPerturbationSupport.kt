package ch.trancee.meshlink.integration

// Relay-hop stress scenarios (multi-node harness runs exercising chunked large transfers,
// duplicate/out-of-order delivery, and route-recovery under Kotlin/Native's WorkerDispatcher
// multithreaded coroutine dispatch) have proven unreliable in constrained iOS Simulator
// environments -- not merely slow, but deterministically hanging past even a 5x-inflated test
// timeout (confirmed while diagnosing recurring `LargeTransferIntegrationTest`/
// `MeshRoutingIntegrationTest` timeouts). A `CI`/`GITHUB_ACTIONS` environment-variable-based
// runtime check was tried first (mirroring jvmTest's `isCiRuntime()`), but those host-level
// environment variables never actually reach the iOS Simulator's app process, so the check
// never worked outside of a narrow, GitHub-Actions-specific `NSHomeDirectory()` string-match
// coincidence. Rather than continue patching an environment-detection heuristic that cannot
// reliably distinguish "fast enough" from "not fast enough" iOS Simulator hosts, this now
// matches androidHostTest's ChunkPerturbationSupport.kt precedent: disable these scenarios
// unconditionally on iOS too. Real large-transfer/relay-hop coverage on iOS comes from the
// physical-device proof-app fleet instead (see meshlink-proof/ios and
// meshlink-proof/scripts/run_headless_ios_ios_proof.py).
internal actual fun supportsSyntheticOutOfOrderChunkDelivery(): Boolean = true

internal actual fun supportsRelayLargeTransferStressScenarios(): Boolean = false

internal actual fun supportsRelayRoutingStressScenarios(): Boolean = false
