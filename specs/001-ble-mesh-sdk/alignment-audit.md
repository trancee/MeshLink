# MeshLink spec-to-code alignment audit

**Date:** 2026-05-18  
**Branch audited:** `001-ble-mesh-sdk-spec-alignment-audit` (based on `main` at `1275e63`)  
**Audience:** maintainers and reviewers validating whether the current MeshLink codebase still matches the approved `001-ble-mesh-sdk` specification.  
**After reading:** you should know which parts of the implementation are aligned, which gaps are still open, and which items are missing as implementation, coverage, or retained evidence.

## Scope and method

This audit compared the current codebase against the canonical feature artifacts:

- `specs/001-ble-mesh-sdk/spec.md`
- `specs/001-ble-mesh-sdk/plan.md`
- `specs/001-ble-mesh-sdk/tasks.md`
- `specs/001-ble-mesh-sdk/contracts/meshlink-api.md`
- `specs/001-ble-mesh-sdk/contracts/discovery-advertisement.md`
- `benchmarks/README.md`

The audit also inspected the current implementation and evidence surfaces in:

- `meshlink/src/commonMain/`
- `meshlink/src/androidMain/`
- `meshlink/src/iosMain/`
- `meshlink/src/commonTest/`
- `benchmarks/src/jvmMain/`
- `meshlink-proof/android/`
- `meshlink-proof/ios/`

Fresh verification was run during this audit:

- targeted API / routing / transfer / parity / discovery / power tests
- Android + iOS compile checks for the SDK
- JVM benchmark module compilation through `:benchmarks:jvmBenchmark`
- open-task and evidence scans against the current spec artifacts

## Overall assessment

The current MeshLink runtime is **mostly aligned** with the approved `001-ble-mesh-sdk` spec.

The public API, TOFU trust model, routing, selective-ACK large transfer path, discovery advertisement contract, Android/iOS parity model, and retained `SC-004` future-branch evidence are all present and supported by current code and tests.

However, this audit still finds **one remaining evidence gap**:

1. **Success-criteria evidence gap:** `SC-001` still lacks a retained passing timed reader-test; the retained attempts are explicitly blocked and do not claim success. This gap is now tracked in `tasks.md` as open `T112`.

## Areas verified as aligned

### 1. Public API, lifecycle, and cross-platform parity

**Spec references:** `FR-012`, `FR-014`, `FR-014a`, `SC-007`  
**Status:** Aligned

The shared public API matches the approved contract:

- `MeshLink.create(config)` and `MeshLink.create(config, context)` in `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/api/MeshLink.kt`
- lifecycle, send, forget, and battery update APIs in `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/api/MeshLinkModels.kt`
- shared config DSL in `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/config/MeshLinkConfig.kt`
- shared 26-code diagnostic catalog in `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/diagnostics/DiagnosticModel.kt`

Supporting verification:

- `meshlink/src/commonTest/kotlin/ch/trancee/meshlink/api/MeshLinkApiContractTest.kt`
- `meshlink/src/commonTest/kotlin/ch/trancee/meshlink/api/CrossPlatformParityTest.kt`
- fresh `apiCheck` pass

### 2. TOFU trust, identity change handling, and trust reset

**Spec references:** `FR-002`, `FR-003`, `FR-003a`, `FR-015a`  
**Status:** Aligned

The codebase still implements TOFU trust and explicit trust reset:

- trust persistence in `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/trust/TofuTrustStore.kt`
- trust records in `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/trust/TrustRecord.kt`
- trust verification/pinning in `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/engine/MeshEngine.kt`

Supporting verification:

- `persisted trust survives sdk restart` in `DirectMessagingIntegrationTest`
- `forgetPeer forces fresh trust establishment...` in `DirectMessagingIntegrationTest`
- trust timestamp preservation in `MeshLinkApiContractTest`
- storage minimization check in `DirectMessagingIntegrationTest`

### 3. Offline encrypted direct messaging and relay confidentiality

**Spec references:** `FR-001`, `FR-004`, `FR-005`, `FR-015`, `FR-018`  
**Status:** Aligned

The current runtime remains offline-first and keeps shared messaging logic in common code:

- end-to-end and hop-by-hop crypto flow in `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/crypto/MessageSealer.kt` and `MeshEngine.kt`
- Android/iOS radio glue isolated to platform source sets
- no extra runtime dependency beyond `kotlinx-coroutines-core` in `meshlink/build.gradle.kts`

Supporting verification:

- `two trusted peers can exchange a direct offline message` in `DirectMessagingIntegrationTest`
- `transport frames do not expose plaintext payloads` in `DirectMessagingIntegrationTest`
- fresh Android/iOS SDK compile checks

### 4. Routing, no-route retry, and multi-hop delivery

**Spec references:** `FR-006`, `FR-007`, `SC-002`  
**Status:** Aligned

The codebase still contains the required routing and retry machinery:

- route coordination in `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/routing/RouteCoordinator.kt`
- in-memory retry scheduler in `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/engine/DeliveryRetryScheduler.kt`
- retry integration in `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/engine/MeshEngine.kt`

Supporting verification:

- `a sender can reach a destination through a single relay hop` in `MeshRoutingIntegrationTest`
- `routing reconverges onto an alternate relay...` in `MeshRoutingIntegrationTest`
- `send retries immediately when a route appears...` in `MeshRoutingIntegrationTest`
- `send returns unreachable when no route appears...` in `MeshRoutingIntegrationTest`
- `pending no route retries do not survive runtime restart until the host resubmits` in `MeshRoutingIntegrationTest`
- retained convergence evidence in `benchmarks/README.md`

### 5. Large transfer chunking, selective acknowledgement, and resume semantics

**Spec references:** `FR-009`, `FR-010`, `SC-003`  
**Status:** Aligned

The current implementation still uses chunked transfer state with selective acknowledgement data:

- outbound and inbound transfer sessions in `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/transfer/TransferSession.kt`
- selective acknowledgement handling through `TransferAck.selectiveRanges`
- transfer orchestration in `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/engine/MeshEngine.kt`

Supporting verification:

- `a large transfer resumes after the active route changes...` in `LargeTransferIntegrationTest`
- `pending large-transfer retries do not survive runtime restart until the host resubmits` in `LargeTransferIntegrationTest`
- `duplicate and out-of-order chunk delivery does not corrupt...` in `LargeTransferIntegrationTest`
- `partial acknowledgements still allow the sender to complete the transfer` in `LargeTransferIntegrationTest`
- deployed wire compatibility fixtures in `WireEnvelopeContractTest`

### 6. Discovery advertisement contract

**Spec references:** `FR-015b`  
**Status:** Aligned

The current discovery contract matches the approved spec:

- contract encoding in `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/transport/BleDiscoveryContract.kt`
- Android/iOS platform-family hint usage in `AndroidBleTransport.kt` and `IosBleTransport.kt`

Supporting verification:

- `meshlink/src/commonTest/kotlin/ch/trancee/meshlink/transport/BleDiscoveryContractTest.kt`
- fresh targeted JVM test pass for `BleDiscoveryContractTest`

### 7. Wire compatibility

**Spec references:** `FR-016`  
**Status:** Aligned

The codebase still retains explicit backward-compatibility fixtures and decoding checks:

- fixtures in `meshlink/src/commonTest/resources/wire-compat/`
- contract coverage in `meshlink/src/commonTest/kotlin/ch/trancee/meshlink/wire/WireEnvelopeContractTest.kt`

Supporting verification:

- fresh targeted JVM test pass for `WireEnvelopeContractTest`

### 8. Power policy and LOW-mode contract

**Spec references:** `FR-013`, `FR-013a`, `FR-013b`, `SC-006`  
**Status:** Aligned

The shared power policy still exposes the expected LOW-mode behavior:

- policy logic in `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/power/`
- Android/iOS hooks in platform source sets
- diagnostic metadata contract in common code and docs

Supporting verification:

- `meshlink/src/commonTest/kotlin/ch/trancee/meshlink/power/PowerPolicyTest.kt`
- fresh targeted JVM test pass for `PowerPolicyTest`
- retained physical LOW-mode evidence in `benchmarks/README.md`

### 9. `SC-004` current-head future-branch closure framing

**Spec references:** `SC-004`, `SC-004a`, release-decision framing in `spec.md`  
**Status:** Aligned with current written posture

The canonical artifacts consistently distinguish:

- the **released baseline**, which remains waived
- the **current-head future branch**, which now has retained passing mixed-bearer evidence

Evidence is synchronized across:

- `specs/001-ble-mesh-sdk/spec.md`
- `specs/001-ble-mesh-sdk/plan.md`
- `benchmarks/README.md`
- `meshlink-proof/ios/README.md`

This audit did not rerun physical benchmarks, but the retained evidence and wording are internally consistent.

## Confirmed gaps and differences

### Gap 1 — `SC-001` still lacks a retained passing timed reader-test

**Spec references:** `SC-001`  
**Status:** Evidence gap  
**Severity:** Medium

The quickstart docs now explain how to measure a timed reader-test, but the retained evidence still stops short of a pass:

- `specs/001-ble-mesh-sdk/research.md` contains blocked reader-test attempts from `2026-05-13` and `2026-05-18`
- those retained entries explicitly stop short of any `SC-001` pass claim
- `specs/001-ble-mesh-sdk/quickstart.md` says the direct flow works on attached hardware, but that is not the same as the required timed fresh-reader proof

So the implementation and quickstart may be usable, but the spec's success-criterion evidence is still incomplete.

### Closed gap — `SC-005` now retains an explicit measured heap byte count

**Spec references:** `SC-005`  
**Status:** Closed during the follow-up on this branch

The memory budget test still enforces the <= 8 MiB cap, and the retained benchmark docs now also include the measured byte-count artifact from a fresh JVM run:

- `MEMORY_BUDGET baselineBytes=7437064 usedBytes=11430280 steadyStateBytes=3993216`

That closes the earlier evidence-quality gap where only a pass/fail budget statement was retained.

## What was not found as a current mismatch

This audit did **not** find evidence that the following areas are currently out of sync with the spec:

- the public MeshLink API shape
- the 26-code diagnostic catalog
- Android/iOS public parity expectations
- discovery advertisement encoding
- selective-ACK transfer semantics
- trust reset behavior
- backward wire compatibility fixtures
- the released-baseline waiver vs future-branch `SC-004` framing

## Recommended next actions

1. **Retain a real `SC-001` pass run.** Run and document a timed fresh-reader quickstart validation instead of only a blocked attempt.

## Fresh audit verification

### Command 1 — API, routing, transfer, discovery, parity, and platform compile checks

```bash
./gradlew apiCheck \
  :meshlink:jvmTest --tests 'ch.trancee.meshlink.api.MeshLinkApiContractTest' \
  --tests 'ch.trancee.meshlink.integration.DirectMessagingIntegrationTest' \
  --tests 'ch.trancee.meshlink.integration.LargeTransferIntegrationTest' \
  --tests 'ch.trancee.meshlink.integration.MeshRoutingIntegrationTest' \
  --tests 'ch.trancee.meshlink.api.CrossPlatformParityTest' \
  --tests 'ch.trancee.meshlink.transport.BleDiscoveryContractTest' \
  --tests 'ch.trancee.meshlink.wire.WireEnvelopeContractTest' \
  --tests 'ch.trancee.meshlink.power.PowerPolicyTest' \
  :meshlink:compileDebugKotlinAndroid \
  :meshlink:compileKotlinIosSimulatorArm64 \
  --console=plain
```

Result:

- `BUILD SUCCESSFUL`

### Command 2 — JVM benchmark gate

```bash
./gradlew :benchmarks:jvmBenchmark --console=plain
```

Result:

- `BUILD SUCCESSFUL`

### Command 3 — spec/task/evidence scan

```bash
rg -n "^- \[ \]" specs/001-ble-mesh-sdk/tasks.md
rg -n 'Quickstart reader-test attempt|no `SC-001` pass claim|Current physical evidence on attached hardware now shows' \
  specs/001-ble-mesh-sdk/research.md specs/001-ble-mesh-sdk/quickstart.md -S
rg -n 'MEMORY_BUDGET baselineBytes=7437064 usedBytes=11430280 steadyStateBytes=3993216' \
  benchmarks/README.md -S
```

Result:

- `tasks.md` now leaves only the explicit `SC-001` closure task (`T112`) open
- `SC-001` still has only blocked reader-test attempts, not a retained pass
- `SC-005` now retains an explicit raw heap-byte artifact in `benchmarks/README.md`
