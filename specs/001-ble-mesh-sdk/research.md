# Research: MeshLink Offline BLE Mesh SDK

## Decision: Build the SDK as a Kotlin Multiplatform library with shared domain logic in `commonMain`

**Rationale:** The feature is explicitly cross-platform and the constitutions require the
public API surface to behave identically on Android and iOS. KMP lets routing,
transfer, trust, diagnostics, power policy, and wire codec live in `commonMain`,
while BLE and secure-storage glue stays in `androidMain` and `iosMain`.
Using interfaces plus factory functions keeps the common API testable and Swift
interop-friendly.

**Alternatives considered:**
- Separate Android and iOS SDKs — rejected because it would duplicate protocol logic,
  drift behavior across platforms, and violate the shared-library goal.
- `expect`/`actual` classes for the whole API — rejected because the public surface is
  better modeled as common interfaces and factories; `expect`/`actual` remains for
  minimal platform seams only.

## Decision: Honor the “no external dependencies” request by shipping no new third-party runtime libraries

**Rationale:** The user explicitly asked for no external dependencies, and the root
constitution already restricts runtime dependencies to `kotlinx-coroutines-core`.
The plan therefore ships only the Kotlin runtime plus the constitutionally allowed
coroutines dependency at runtime. Codec, routing, trust, and crypto-provider
logic stay in-repo. Build/test tooling is allowed, but it must not leak into the
published runtime artifact.

**Alternatives considered:**
- Zero runtime dependencies including coroutines — rejected because the project
  constitution explicitly budgets `kotlinx-coroutines-core`, and structured
  concurrency is central to the lifecycle/transport model.
- Pulling in FlatBuffers, DI, logging, or crypto runtime libraries — rejected
  because they violate the no-extra-runtime-dependency goal and create KMP or
  audit complexity.

## Decision: Keep the public API thin: `MeshLinkApi` + `MeshLink` shell over an internal `MeshEngine` coordinator

**Rationale:** Existing repository guidance favors a thin public shell and a single
internal coordinator that wires subsystems together. This keeps the public API
small, reduces coupling, and centralizes multi-subsystem operations such as peer
forget, route retraction, transfer cleanup, and diagnostic emission.

**Alternatives considered:**
- Exposing subsystems directly on the public API — rejected because it couples
  consumers to internals and makes cross-platform parity harder to hold.
- Service locator / DI framework — rejected because constructor wiring is more
  transparent, testable, and dependency-free.

## Decision: Use L2CAP-first transport with deterministic GATT fallback on both Android and iOS

**Rationale:** Existing design notes show L2CAP offers the throughput and latency needed
for 64 KiB transfers and multi-hop relay. GATT remains necessary for older devices,
OS limitations, or proven L2CAP failure modes. Platform BLE layers will discover,
connect, and notify using built-in APIs only, while common code sees a unified
`BleTransport` abstraction.

**Alternatives considered:**
- GATT-only transport — rejected because it adds discovery overhead and constrains
  throughput for large-payload mesh delivery.
- L2CAP-only transport — rejected because OEM and platform gaps still require a
  reliable fallback path.

## Decision: Keep the wire codec pure Kotlin and FlatBuffers-compatible

**Rationale:** The repository already argues for a pure-Kotlin FlatBuffers-compatible
codec because official runtimes are not suitable for `commonMain`. Staying
FlatBuffers-compatible preserves backward evolution rules while avoiding a JVM-only
runtime or code generator dependency.

**Alternatives considered:**
- `flatbuffers-java` or generated Java/Kotlin code — rejected because it is not a
  good fit for shared KMP runtime code.
- Custom ad-hoc binary frames with no schema discipline — rejected because forward
  compatibility and contract review would become harder.

## Decision: Use TOFU trust pinning plus explicit trust-failure diagnostics

**Rationale:** The clarified spec chose TOFU. That matches the offline, serverless model
and existing trust-model guidance. The SDK pins a peer’s first accepted static
identity, treats unexpected key changes as untrusted, and emits an explicit
trust-failure diagnostic instead of silently downgrading behavior.

**Alternatives considered:**
- Mandatory out-of-band verification for first contact — rejected because it harms
  first-use UX and is not required by the spec.
- Silent rejection on mismatch — rejected because reviewers and apps need a
  measurable failure signal.

## Decision: Use `Noise_XX_25519_ChaChaPoly_SHA256` for hop-to-hop handshakes and Noise K for end-to-end payload sealing

**Rationale:** Existing design guidance already prefers Noise XX as the single
handshake path to avoid dual-state-machine complexity. The spec still requires an
end-to-end layer, so the plan keeps hop-to-hop session establishment on Noise XX
and uses trusted static keys for end-to-end Noise K message sealing.
Both layers stay behind a `CryptoProvider` abstraction with in-repo implementations
and Wycheproof-backed validation.

**Alternatives considered:**
- Noise IK for reconnect acceleration — rejected for state-machine complexity and
  wrong-key fallback races.
- Platform crypto libraries directly in public/common code — rejected because the
  constitution forbids external crypto libraries in the shipped artifact.

## Decision: Treat Android JCA support for X25519 and Ed25519 as opportunistic, not guaranteed

**Rationale:** Android's own generated crypto support data in
`platform/libcore/tools/docs/crypto/data/crypto_support.json` lists `XDH` for
`KeyAgreement`, `KeyFactory`, and `KeyPairGenerator` as **33+**, and `Ed25519`
for `Signature` as **33+**, while `ChaCha20` is **28+**. That means Android 12
(API 31) is outside the documented JCA support window for the exact Noise XX
primitive set. Public issue tracker entry `399856239` also shows `Ed25519`
support mismatches surfacing even on newer Android releases, so vendor images
cannot be assumed to expose a uniform provider surface. User-reported Samsung
Android 12 failures are therefore consistent with the broader platform contract.

**Chosen solution:** MeshLink should prefer the official Android `XDH`
algorithm name, probe the full primitive set at runtime with real
keygen/sign/agreement/AEAD operations, and fall back to an in-repo pure-Kotlin
Ed25519 implementation when `Ed25519` JCA support is missing but `XDH` and
`ChaCha20-Poly1305` remain available. The fallback uses TweetNaCl-style 16-limb
field arithmetic on primitive arrays, thread-local SHA-512 digests, and an
identity-key expansion cache so the hot path avoids `BigInteger` and repeated
seed re-hashing. Raw key material stays provider-agnostic so persisted
identities survive switching between the JCA-backed and software paths.

**Real device evidence (2026-05-11):**
- Samsung `SM-G970U1`, Android 12 / API 31: `XDH` works, `X25519`
  `KeyPairGenerator` alias does not, `Ed25519` JCA is unavailable, and
  `ChaCha20-Poly1305` works. After installing the in-repo Ed25519 fallback,
  `MeshLink.create(config, context)` succeeds on-device even though the JCA Ed25519
  probe still fails.
- OPPO `CPH2689`, Android 16 / API 36: `Ed25519` and `ChaCha20-Poly1305`
  work, but Conscrypt's `OpenSSLXDHKeyPairGenerator` rejects
  `initialize(AlgorithmParameterSpec)` with `InvalidAlgorithmParameterException`.
  This means `XDH` key generation must use `generateKeyPair()` directly instead
  of `NamedParameterSpec.X25519` initialization on Android. With that fix,
  `MeshLink.create(config, context)` also succeeds on-device.
- Samsung `SM-G970U1` ↔ OPPO `CPH2689` real-device proof run: Android's new
  direct BLE transport now establishes a real L2CAP channel using the
  advertised PSM, completes the Noise XX hop handshake, pins trust, and
  delivers a provider-backed encrypted direct message from OPPO to Samsung.
  The responder side needed to tolerate accepted L2CAP sockets before scan
  discovery caught up, so the transport now binds unknown inbound sockets to a
  temporary address-based peer handle instead of discarding them immediately.
- iOS direct transport now has a compile-verified CoreBluetooth L2CAP path in
  `IosBleTransport.kt`, including PSM publication, advertisement, central
  connection/open-channel flow, incoming/outgoing channel registration, and
  framed stream IO. The repo now also contains a committed `meshlink-sample/ios`
  Xcode project generated from `project.yml`; simulator build succeeds, physical
  iPhone 15 build succeeds when a local development team is supplied, and app
  installation via `devicectl` succeeds. The remaining real-device blocker is
  first launch: iOS still denies opening the app until the developer profile is
  explicitly trusted on the phone.

**Alternatives considered:**
- Assuming `X25519` / `Ed25519` JCA works on all Android 12+ devices — rejected
  because the documented Android support window starts later and OEM/provider
  variance is real.
- Shipping an external crypto runtime for fallback — rejected because the
  project forbids additional third-party runtime dependencies.
- Failing hard on unsupported devices with no fallback — rejected because it
  would exclude known OEM/device combinations that can still be supported with
  in-repo software primitives.

## Decision: Use Babel-inspired proactive routing with feasibility distance, seqno freshness, and differential updates

**Rationale:** The feature explicitly calls for Babel-style routing. Existing repo notes
already explain feasibility distance, seqno-based freshness, differential updates,
and route retraction. The plan adopts these concepts with a BLE-friendly hop-count
metric first, preserving room for ETX-style link costing later.

**Alternatives considered:**
- Flooding-only or blind relay — rejected because it does not scale to the desired
  multi-hop topology and wastes battery.
- Reactive on-demand routing — rejected because the spec calls for proactive route
  propagation and fast reconvergence.

## Decision: Keep large-payload delivery bounded with a configurable in-memory delivery deadline and no retry persistence across restart

**Rationale:** Clarification locked in a 64 KiB v1 limit, explicit pre-transfer
rejection above that limit, and no persisted retry queue across restart. For the
no-route case, the public API exposes a configurable `deliveryRetryDeadline`
rather than a fixed retry count or hardcoded wall-clock constant. While no route
exists, the runtime schedules attempts using bounded, jittered exponential
backoff and retries immediately when topology updates reveal a valid path.
Session and scheduler state remain in memory only, expose explicit unreachable
and size-limit outcomes, and keep ACK state in common code so later
selective-ack improvements remain possible without changing the public API.

**Alternatives considered:**
- Unlimited payloads — rejected because the spec now defines a bounded release limit.
- Retry persistence across restart — rejected by clarification and because it adds
  storage/privacy complexity to v1.
- Restart-from-zero only with no explicit transfer state — rejected because the spec
  requires observable, typed delivery outcomes.

## Decision: Apply the three-tier power model and regulatory clamping in shared policy code

**Rationale:** Existing design notes already define PERFORMANCE / BALANCED /
POWER_SAVER tiers, hysteresis, bootstrap behavior, and EU clamping for scan/advertise
limits. Keeping that policy in common code preserves parity across Android and iOS,
while platform layers only apply the resulting transport settings.

**Alternatives considered:**
- Let each platform decide power behavior independently — rejected because it would
  erode the cross-platform consistency guarantee.
- Ignore regional clamping — rejected because the repo already treats it as a design
  constraint.

## Decision: Use direct iOS integration first, with static frameworks and no CocoaPods

**Rationale:** The repo is a mono-repo with no stated CocoaPods requirement. Direct
integration keeps the dependency story simple, works with no external package manager,
and fits the “no external dependencies” request. If distribution needs change later,
XCFramework export can be added without changing the core library shape.

**Alternatives considered:**
- CocoaPods integration — rejected because it introduces another external integration
  layer and is unnecessary for the current repo shape.
- Immediate SwiftPM binary export — deferred until the core module exists and stabilizes.

## Deployed-wire compatibility baseline (2026-05-13)

The repository now retains explicit v1 deployed-wire baseline fixtures under
`meshlink/src/commonTest/resources/wire-compat/` for representative message,
route-update, and transfer-ack envelopes. The shared
`WireEnvelopeContractTest` replays those fixtures by decoding them with the
current codec, asserting the expected envelope family and representative field
values, and then re-encoding the decoded frame byte-for-byte.

This establishes a durable compatibility baseline for future wire evolution:
changes that accidentally alter the deployed v1 binary layout will now fail the
contract test instead of being noticed only during physical validation.

## LOW-power delivery benchmark coverage update (2026-05-13)

The proof-benchmark suites now include an explicit LOW-power 256-byte delivery
check on both platforms:

- Android: `PowerProfileBenchmark.lowBatteryPowerSaverModeDelivers256ByteMessageWithinFiveSeconds()`
- iOS: `PowerProfileBenchmark.testLowBatteryPowerSaverModeDelivers256ByteMessageWithinFiveSeconds()`

Two proof-harness corrections were required before the runs produced durable,
scored evidence instead of raw harness failures:

- the benchmark wait windows had to be widened to 60 seconds because the proof
  apps now emit the scored `BENCHMARK transport ... result=...` line only after
  recipient-confirmed completion or receipt-timeout settlement, not immediately
  after launch; shorter waits surfaced raw harness timeouts instead of scored
  outcomes
- the passive 1-hop proof receipt path now remaps a full `originPeerId` back to
  the discovered direct peer handle when that handle is only the 12-byte
  advertisement key-hash prefix of the origin identity; without that remap, the
  passive proof peer could hold a usable direct route but still treat the
  receipt target as unavailable

Fresh retained evidence from this repository state:

- Android install command: `./gradlew :meshlink-sample:android:meshlink-sample-android-app:installDebug --console=plain`
  - result: `BUILD SUCCESSFUL`
- Android scored benchmark command:
  `ANDROID_SERIAL=R38M10P930F ./gradlew :meshlink-sample:android:meshlink-sample-android-app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=ch.trancee.meshlink.proof.android.PowerProfileBenchmark#lowBatteryPowerSaverModeDelivers256ByteMessageWithinFiveSeconds -Pandroid.testInstrumentationRunnerArguments.meshlinkBenchmarkEnablePeerTests=true --console=plain`
  - result: `BUILD SUCCESSFUL`
  - sender Samsung line:
    `BENCHMARK transport bytes=256 elapsedMs=411 throughputKBps=0.61 result=Sent`
  - passive OPPO lines:
    - `BENCHMARK receipt peer remapped origin=f48c9d direct=ea85e3`
    - `BENCHMARK receipt send(ea85e3) -> Sent token=00015a7ea2cfe2e1 attempt=1`
- iOS simulator compile gate:
  `xcodebuild -project meshlink-sample/ios/ProofApp.xcodeproj -scheme ProofApp -destination 'id=6C7DD73A-EC9C-46F9-B0B9-DD136F748621' -only-testing:ProofBenchmarks/PowerProfileBenchmark/testLowBatteryPowerSaverModeDelivers256ByteMessageWithinFiveSeconds test`
  - result: `TEST SUCCEEDED`
  - benchmark case result: `Test skipped`
  - note: this remains a compile gate only because the simulator has no nearby BLE proof peer
- iOS physical build/install/launch path using the paired iPhone 15 plus a
  transient local development team from the already-signed local build artifact:
  - device build: `xcodebuild -project meshlink-sample/ios/ProofApp.xcodeproj -scheme ProofApp -destination 'generic/platform=iOS' -allowProvisioningUpdates DEVELOPMENT_TEAM=<redacted> build`
  - install: `xcrun devicectl device install app --device 7F5320D7-1D8E-56F5-9D6E-0A6967B25467 ~/Library/Developer/Xcode/DerivedData/ProofApp-bkjaxkflegnxwlflhhedzuolagbp/Build/Products/Debug-iphoneos/ProofApp.app`
  - headless sender launch:
    `xcrun devicectl device process launch --device 7F5320D7-1D8E-56F5-9D6E-0A6967B25467 --terminate-existing --console -e '{"MESHLINK_APP_ID":"demo.meshlink.benchmark.power.delivery","MESHLINK_POWER_MODE":"powersaver","MESHLINK_BENCHMARK_PAYLOAD_BYTES":"256","MESHLINK_BENCHMARK_BATTERY_LEVEL":"0.5","MESHLINK_BENCHMARK_IS_CHARGING":"false"}' ch.trancee.meshlink.proof.ios`
  - sender iPhone 15 line:
    `BENCHMARK transport bytes=256 elapsedMs=21331 throughputKBps=0.01 result=ReceiptTimeout`
  - passive OPPO lines:
    - `Peer lost: db2ccaf6228459d8aa9dc685`
    - repeated `BENCHMARK correlation role=passive.receipt.wait ... knownPeers=[]`
    - `BENCHMARK receipt abandoned token=00007689200a0c25 peer=7b76bb deadlineMs=18000`

Interpretation:

- the `SC-006` coverage gap is now closed; both proof integrations have fresh,
  retained scored LOW-power 256-byte evidence
- Android currently satisfies the LOW-power 256-byte target on the proof path
  (`411 ms <= 5000 ms`)
- the first iPhone 15 -> OPPO scored LOW-power rerun still ended
  `ReceiptTimeout` at `21331 ms`, but that failure shape was then traced to the
  passive Android proof app clearing `knownPeers` before the receipt path ran,
  even though the same peer was still reachable over the active inbound L2CAP
  path that had just delivered the benchmark payload

### LOW-power follow-up: passive proof-peer bookkeeping (2026-05-13)

The next bounded investigation step was to determine whether the iPhone
LOW-power sender was genuinely failing transport-wise or whether the passive
Android proof app was suppressing the return receipt too early.

Root-cause evidence from the failing iPhone 15 -> OPPO rerun:

- passive OPPO log retained `Peer found: db2ccaf6228459d8aa9dc685 (CONNECTED)`
  followed by `Peer lost: db2ccaf6228459d8aa9dc685`
- the same passive OPPO proof app later retained
  `DIAG HOP_SESSION_ESTABLISHED ... peerId=db2ccaf6228459d8aa9dc685`,
  `DIAG ROUTE_DISCOVERED ... peerId=db2ccaf6228459d8aa9dc685`, and
  `MSG from db2ccaf6228459d8aa9dc6859a1fd004ca7b76bb bytes=256 benchmarkToken=...`
- despite having just received the benchmark payload over that path, the proof
  app still logged repeated `passive.receipt.wait ... knownPeers=[]` lines and
  never attempted the receipt send before `BENCHMARK receipt abandoned ...`

That showed the immediate benchmark blocker was proof-app bookkeeping, not the
sender's ability to deliver the 256-byte LOW-power payload. The passive proof
app was keying receipt retries off `knownPeers`, but `knownPeers` had already
been cleared by scan/presence churn and was not restored when the inbound
benchmark payload proved that a usable direct peer handle still existed.

A bounded proof-harness fix then restored the resolved direct peer handle to the
passive Android proof app's `knownPeers` map when an inbound benchmark payload
arrived.

Fresh rerun after that change, using the same iPhone 15 LOW-power sender path:

- passive OPPO proof app:
  - `BENCHMARK receipt peer remapped origin=7b76bb direct=9dc685`
  - `BENCHMARK receipt send(9dc685) -> Sent token=0000770594a52dcf attempt=1`
- headless iPhone 15 sender log:
  - `BENCHMARK receipt from f2cf6b88d1d61034b4b20fca314eb5dca9a4d9c3 token=0000770594a52dcf bytes=256`
  - `BENCHMARK transport bytes=256 elapsedMs=239 throughputKBps=1.05 result=Sent`
  - the later `MeshLinkTransport closing L2CAP link b20fca: peripheral disconnected ...`
    line still occurred, but only after the receipt had already arrived and the
    scored sender result was `Sent`

Updated interpretation:

- the iPhone LOW-power 256-byte sender path is no longer the active `SC-006`
  blocker on reference hardware; with corrected passive proof-peer bookkeeping,
  the latest retained rerun passed in `239 ms`
- the remaining iPhone blocker continues to be large-transfer throughput plus
  broader recipient-confirmed proof stability, not the LOW-power sender slice

### LOW-power connection-interval contract closure (2026-05-14)

The shared power-policy contract now exposes an explicit
`connectionIntervalMillis` field and uses a LOW-tier maintained connection
interval of `500 ms`.

Fresh retained verification from this repository state:

- shared JVM contract tests:
  `./gradlew :meshlink:jvmTest --tests 'ch.trancee.meshlink.power.PowerPolicyTest' --tests 'ch.trancee.meshlink.api.MeshLinkApiContractTest' --tests 'ch.trancee.meshlink.api.CrossPlatformParityTest' --console=plain`
  - result: `BUILD SUCCESSFUL`
  - shared policy assertions now verify:
    - automatic LOW-tier policy resolves `connectionIntervalMillis=500`
    - fixed `PowerMode.PowerSaver` also resolves `connectionIntervalMillis=500`
    - API diagnostics expose `connectionIntervalMillis` in `POWER_MODE_CHANGED`
- Android proof benchmark compile gate:
  `./gradlew :meshlink-sample:android:meshlink-sample-android-app:compileDebugAndroidTestKotlin --console=plain`
  - result: `BUILD SUCCESSFUL`
  - benchmark assertion now requires `connectionIntervalMillis=500` on the
    LOW-power diagnostic line
- iOS proof benchmark build-for-testing gate:
  `xcodebuild -project meshlink-sample/ios/ProofApp.xcodeproj -scheme ProofApp -destination 'generic/platform=iOS Simulator' build-for-testing`
  - result: `TEST BUILD SUCCEEDED`
  - iOS proof benchmark assertion now requires `connectionIntervalMillis=500`
    on the LOW-power diagnostic line

This closes the remaining written-contract gap between the constitutions, the
shared power policy, and the proof-benchmark assertions: LOW mode is now
reviewable as one combined requirement covering scan duty, maintained
connection interval, and the 256-byte latency path.

## Quickstart reader-test attempt (2026-05-13)

The `SC-001` timing method is now mirrored in `quickstart.md`. A fresh timed
reader-test attempt in this implementation session recorded:

- start: `2026-05-13T18:16:45Z`
- end: `2026-05-13T18:16:46Z`
- elapsed: `1s`
- observer note: blocked immediately because this session did not have a fresh
  reader / observer pair or a reserved two-device quickstart validation window,
  so no `SC-001` pass claim is made from this attempt.

## Physical validation update (2026-05-12)

The benchmark module README now carries the consolidated baseline table for JVM,
Android, and iPhone proof measurements. This section captures the architectural
learnings behind those numbers.

### Proof-integration role and blocker handling

The runnable proof apps now serve four project-local purposes at once:
reference implementation, quickstart aid, benchmark harness, and physical-
validation vehicle. That combination is useful, but it also means reviewers
must distinguish product failures from environment failures when a run does not
complete cleanly.

The working rule for this repository is:

- keep the product requirement unchanged
- keep the raw run evidence
- label the outcome as passed, failed, or blocked explicitly

Examples of environmental blockers seen during this feature include local iOS
signing/team setup, trusted-device-profile state, physical-device availability,
and nearby-device interference when using the default proof `appId`. Those
conditions should be recorded as blocker evidence, not smoothed into a passing
claim and not treated as justification to weaken the underlying requirement.

### Pairing was not the throughput blocker

The direct-link pairing prompt turned out to be a transport-configuration problem, not
part of MeshLink's security model. After switching Android LE CoC sockets to the
no-pairing path and publishing the iOS L2CAP channel without link-layer encryption,
current OPPO ↔ iPhone proof runs no longer require an OS pairing dialog. That
confirmed the intended architecture: direct links should rely on app-layer Noise and
TOFU rather than platform pairing.

Removing pairing did not fix the open 64 KiB iPhone throughput issue. It removed a
UX blocker and allowed the proof apps to stay on the intended security model, but the
remaining large-transfer bottleneck survived unchanged.

### Android API 36 needed explicit insecure LE socket settings

On OPPO Android 16 / API 36, preferring explicit insecure LE socket settings on the
newer Bluetooth socket API produced more reliable no-pairing behavior than relying on
legacy insecure LE CoC calls alone. The implementation now prefers the explicit API
on API 36+ and falls back to the legacy insecure L2CAP APIs on older Android
releases.

### iOS must honor the deterministic initiator policy

The iOS transport was still attempting an outbound L2CAP open whenever `send()` saw
no active link. When iOS lost the key-hash tie-break and should have waited for the
Android-initiated inbound channel, that extra outbound connect created duplicate-link
or collision churn. Honoring the initiator policy by waiting for the inbound channel
when iOS loses the tie-break restored reliable 64 KiB delivery.

### Current iPhone 15 benchmark finding

The iPhone 15 still completes direct 64 KiB transfers, but the queued-writer follow-up
kept the link in roughly the same throughput class and did not remove the release
blocker:

- best retained clean Samsung run: `BENCHMARK transport bytes=65536 elapsedMs=3210 throughputKBps=19.94 result=Sent`
- fresh clean OPPO rerun on the queued-writer build: `BENCHMARK transport bytes=65536 elapsedMs=3192 throughputKBps=20.05 result=Sent`
- fresh telemetry-enabled Samsung rerun on the queued-writer build: `BENCHMARK transport bytes=65536 elapsedMs=3262 throughputKBps=19.62 result=Sent`
- fresh passive Samsung rerun on the queued-writer build: `BENCHMARK transport bytes=65536 elapsedMs=15012 throughputKBps=4.26 result=NotSent(reason=UNREACHABLE)`
- target: `>= 60 KB/s`

The fresh queued-writer reruns therefore show two things at once:

- throughput is still far below target even when the transfer completes cleanly
- Samsung-path stability remains variable, because one fresh passive rerun still failed
  after peer discovery with repeated `HOP_SESSION_FAILED stage=transport.handshake.message1.send`

Cold start, LOW-power scan duty, and the 256-byte latency path all currently meet
their targets on the iPhone 15. The only remaining physical benchmark blocker is
large-payload throughput and stability.

### Rejected follow-up experiment

A follow-up experiment increased the direct-transfer chunk budget on the iPhone path
after the initiator fix. That made the latest OPPO run worse rather than better
(`14.07 KB/s`) and was reverted. The later queued-writer / hot-path-log remediation
(`c45b7dc`) changed the shape of the transfer but not the throughput class: the clean
OPPO rerun reached `20.05 KB/s` and the telemetry-enabled Samsung rerun reached only
`19.62 KB/s`.

The telemetry itself is still useful. On the fresh Samsung diagnostic rerun, the
writer emitted coalesced batches up to `coalescedFrames=16` / `coalescedBytes=8144`,
with `writeBatches=2`, `writeCalls=3..4`, `backpressureSpins=94..246`,
`readyFalseCount=94..246`, and `maxInterWriteGapMs=61..146`, while ACK-side reads
arrived in small `141..423` byte batches (`1..3` frames per read batch). That points
more strongly to CoreBluetooth L2CAP stream availability / OS scheduling than to pure
application queue starvation.

The useful learning is therefore no longer just that iOS backpressure matters; it is
that the app-side queue can stay fed and still land in the same ~20 KB/s class, while
a separate Samsung rerun can fail before the bulk transfer phase begins. The remaining
bottleneck still appears to live in iOS / CoreBluetooth L2CAP large-transfer behavior
or backpressure, not in pairing, route establishment, or the 392-byte chunk size
itself.

### Additional bounded conformance-path experiment: widen the inner iOS write batch (2026-05-14)

A later bounded experiment tested whether the iOS sender was still paying avoidable
app-side overhead from the inner `WRITE_BATCH_BYTES` split at `4 KiB`, even after the
queued-writer path had already started coalescing up to `8144` bytes (`16` encoded
frames) per batch.

Experiment change:

- temporarily widen `WRITE_BATCH_BYTES` from `4 * 1024` to `16 * 1024`
- keep the rest of the queued-writer pipeline unchanged
- rerun a fresh physical iPhone 15 -> OPPO 64 KiB MeshLink benchmark on a transient
  isolated `appId`

Fresh retained evidence:

- passive OPPO launch used transient appId `demo.meshlink.benchmark.throughput.1778758876`
  with passive benchmark mode enabled
- sender iPhone 15 proof log:
  - `BENCHMARK transport bytes=65536 elapsedMs=3484 throughputKBps=18.37 result=Sent`
  - `BENCHMARK receipt from ... token=000087152badcdc3 bytes=65536`
- passive OPPO proof log:
  - `MSG from ... bytes=65536 benchmarkToken=000087152badcdc3`
  - `BENCHMARK receipt send(2e0aa2) -> Sent token=000087152badcdc3 attempt=1`
- sender telemetry on the widened-batch run:
  - `coalescedFrames=16`
  - `coalescedBytes=8144`
  - `writeBatches=1`
  - `writeCalls=3..4`
  - `backpressureSpins=183..312`
  - `readyFalseCount=183..312`
  - `maxInterWriteGapMs=64..148`

Interpretation:

- the larger inner batch did remove the extra inner write-batch boundary for the
  existing `8144`-byte coalesced writes (`writeBatches=1` instead of `2`)
- despite that, the measured throughput got worse than the earlier clean OPPO rerun
  (`18.37 KB/s` vs `20.05 KB/s`)
- this strengthens the earlier hypothesis that the dominant bottleneck is still
  CoreBluetooth stream availability / OS scheduling under L2CAP large-transfer load,
  not the prior `4 KiB` inner batch split itself

Outcome:

- the 16 KiB inner-batch experiment was rejected and reverted
- `SC-004` remains unmet on iOS
- the conformance path stays open, but the next remediation should target a different
  suspected bottleneck than the removed 4 KiB write-batch boundary

### Additional rejected Samsung follow-up matrix after `T092` (2026-05-14)

After the widened-inner-batch experiment was rejected, several more bounded
follow-ups were run against the faster Samsung passive-peer fixture to check
whether any remaining local iOS transport or sender-settlement knob could move
`SC-004` materially closer to the `>= 60 KB/s` target.

Fresh recipient-confirmed Samsung reruns:

- run-loop scheduling for the iOS L2CAP streams: `27.33 KB/s`
- shorter sender ACK-settlement timers (`500 ms` max / `25 ms` idle):
  `24.90 KB/s`
- wider transport coalescing window (`32` pending/coalesced frames):
  `28.33 KB/s`
- re-enabled 64 KiB large-inline MeshLink path: `31.84 KB/s`
- re-enabled 64 KiB large-inline path plus `16 KiB` inner write batches:
  `23.05 KB/s`

Retained run directories:

- `/tmp/ios_stream_runloop_samsung_console2_20260514T140308`
- `/tmp/ios_acktune_samsung_console_20260514T140521`
- `/tmp/ios_coalesce32_samsung_console_20260514T140830`
- `/tmp/ios_inline64_samsung_console_20260514T141109`
- `/tmp/ios_inline64_batch16_samsung_console_20260514T141300`

Most informative result:

- the re-enabled inline path did switch the benchmark onto one large MeshLink
  `WireFrame.Message` instead of the chunked-transfer protocol (`transfer`
  diagnostics disappeared), but it still emitted a single `66037`-byte
  coalesced write with:
  - `writeCalls=28`
  - `writeBatches=17`
  - `readyFalseCount=1100`
  - `maxInterWriteGapMs=190`
  - `totalElapsedMs=1471`
- that run still finished at only `31.84 KB/s`, and widening the inner write
  batch to `16 KiB` made it worse (`23.05 KB/s`)

Interpretation:

- none of the additional bounded follow-ups beat the already retained Samsung
  best case (`33.56 KB/s`)
- the failure of the inline path is especially important because it removes the
  transfer-chunk ACK machinery from the large-payload path while still landing
  far below target
- this sharply strengthens the conclusion that the remaining blocker is rooted
  in CoreBluetooth / iOS large-write backpressure and scheduling behavior, not
  just in MeshLink chunk-windowing or ACK cadence

Escalation state:

- no additional bounded local iOS transport tweak from this matrix produced a
  materially better result than the already retained baseline
- continuing on the conformance path now likely requires a more invasive iOS
  large-payload design change (or a specification decision), not another small
  constant-only follow-up

### More invasive iOS-only design change: request low connection latency on inbound L2CAP channels (2026-05-14)

The next follow-up targeted a lower-level iOS/CoreBluetooth lever instead of
another batching constant. When the iPhone hosts an inbound L2CAP channel, the
remote Android peer is the BLE central and iOS is the peripheral. Core
Bluetooth exposes one peripheral-side connection-parameter hint for that case:
`setDesiredConnectionLatency(.low, for: central)`.

Implementation change:

- keep the queued-writer / chunked-transfer path intact
- detect incoming L2CAP channels whose `channel.peer` can be treated as a
  `CBCentral`
- on the first outbound write for that link, request
  `CBPeripheralManagerConnectionLatencyLow`
- retain an explicit transport log line so the physical evidence can separate
  runs that actually exercised the new path from runs where the iPhone ended up
  as the initiating/central side and therefore could not issue the request

Retained evidence:

- initial Samsung series: `/tmp/ios_latencyhint_samsung_series_20260514T170604`
  - run 1: `38.28 KB/s` with
    `requested low connection latency for 766b71 central=3e5f1dba-ddc8-4f19-9dc6-caf49311dce0`
  - run 2: `18.16 KB/s` with no low-latency request log
  - run 3: `36.14 KB/s` with
    `requested low connection latency for 505c9b central=3e5f1dba-ddc8-4f19-9dc6-caf49311dce0`
- fresh final-code refresh: `/tmp/ios_final_latencyhint_refresh_20260514T171050`
  - run 1: `34.33 KB/s` with
    `requested low connection latency for 58e934 central=3e5f1dba-ddc8-4f19-9dc6-caf49311dce0`
  - run 2: `19.85 KB/s` with no low-latency request log
- single-run confirmation before the final refresh:
  `/tmp/ios_latencyhint_samsung_retry_20260514T170528`
  - `29.09 KB/s` with
    `requested low connection latency for 593bc7 central=3e5f1dba-ddc8-4f19-9dc6-caf49311dce0`

Interpretation:

- the new iOS-only design change **did** improve the retained Samsung best case:
  `38.28 KB/s` now exceeds the earlier `33.56 KB/s` best result from the queue-
  flush stabilization series
- the improvement is not uniform because it only applies on runs where the
  iPhone hosts the inbound L2CAP channel and can legally issue the peripheral-
  side low-latency request
- same-design runs where the iPhone took the initiating/central role never
  emitted the request and fell back to the prior lower throughput class
  (`18.16-19.85 KB/s` in the retained evidence)

This changes the blocker diagnosis again:

- the issue is no longer just "iOS large writes are slow"
- role-dependent connection-parameter control now matters materially on the
  Samsung path
- however, even the improved low-latency incoming-channel runs remain well
  below the normative `>= 60 KB/s` target, so the design change improves the
  best case without closing `SC-004`

### Rejected combination: low-latency incoming channel + re-enabled 64 KiB inline path (2026-05-14)

Because the incoming-channel low-latency request improved the chunked MeshLink
path, the next check was whether that same connection-parameter hint could make
an already re-enabled 64 KiB inline MeshLink path viable.

Retained evidence:

- series directory: `/tmp/ios_latencyhint_inline_samsung_series_20260514T170804`
- run 1: `23.78 KB/s` with no low-latency request log
- run 2: `33.97 KB/s` with
  `requested low connection latency for 951f4c central=3e5f1dba-ddc8-4f19-9dc6-caf49311dce0`
- run 3: `20.68 KB/s` with no low-latency request log

Interpretation:

- even with the low-latency request active, the inline path still did not beat
  the chunked + low-latency best case (`33.97 KB/s` vs `38.28 KB/s`)
- the inline path therefore remains rejected
- the retained evidence still points back to the chunked MeshLink path plus
  connection-latency control as the stronger of the tested iOS-only options,
  but still insufficient for `SC-004`

### Deeper cross-platform role-policy redesign: deterministic Android-initiated mixed-platform L2CAP (2026-05-14)

The remaining problem after the inbound low-latency change was that it still
only helped when the iPhone happened to host the inbound L2CAP channel. That
made the faster path role-dependent and non-deterministic across reruns.

To remove that randomness, the discovery payload now uses the previously
reserved low 3 header bits as a shared platform-family hint:

- `0` = unknown / legacy
- `1` = Android
- `2` = iOS

New mixed-platform initiator policy:

- Android -> iOS pair: Android deterministically initiates the L2CAP channel
- iOS -> Android pair: iOS waits for the inbound channel instead of attempting
  its own competing outbound open
- same-platform peers and unknown / legacy peers keep the old key-hash tie-break
  ordering for backward compatibility

This keeps the same 16-byte discovery payload shape and the same single-
advertisement contract; only the meaning of the previously-unused low header
bits changes.

Fresh retained debug proof of the final mixed-platform policy:

- directory: `/tmp/ios_rolepolicy_samsung_debug_clean_20260514T173346`
- key sender-side iPhone lines:
  - `scan found aa07ed mode=L2CAP psm=234 platform=ANDROID ...`
  - `send(aa07ed) waiting for inbound L2CAP link`
  - `binding incoming L2CAP channel to discovered peer aa07ed ...`
  - `requested low connection latency for aa07ed central=...`
  - `BENCHMARK transport bytes=65536 elapsedMs=1750 throughputKBps=36.57 result=Sent`

Fresh retained final matrix on the clean-built deterministic path:

- directory: `/tmp/ios_rolepolicy_final_matrix_20260514T173422`
- Samsung runs:
  - `34.92 KB/s` (`latency_requested=yes`)
  - `24.37 KB/s` (`latency_requested=yes`)
  - `39.48 KB/s` (`latency_requested=yes`)
- OPPO runs:
  - `52.03 KB/s` (`latency_requested=yes`)
  - `45.33 KB/s` (`latency_requested=yes`)
- every retained run ended with a matching passive-peer receipt send line

Interpretation:

- the redesign achieved its immediate goal: the faster inbound / iPhone-
  peripheral-hosted path is now deterministic on the mixed Android/iOS proof
  path, and the low-latency request is present on every retained final-matrix
  run
- this resolves the earlier role-dependent randomness seen after the first
  low-latency-only design change
- the resulting throughput class is materially better than the pre-redesign
  baseline:
  - Samsung best case improved from `33.56 KB/s` to `39.48 KB/s`
  - OPPO best case improved from `20.05 KB/s` to `52.03 KB/s`
- despite that improvement, `SC-004` still remains unmet because the retained
  best case is still below `>= 60 KB/s`

Updated blocker state after the redesign:

- the active blocker is no longer mixed-platform initiator randomness
- the active blocker is the remaining throughput shortfall on the now-
  deterministic optimized path
- the next conformance step is therefore a further throughput optimization on
  this path, not another initiator-policy change

### Additional rejected follow-ups on the deterministic path (2026-05-14)

After the mixed-platform initiator policy was fixed, several more bounded
follow-ups were run to test whether any remaining app-layer transport lever
could push the deterministic path over the final gap to `>= 60 KB/s`.

Retained evidence:

- 8-frame iOS coalescing window:
  - `/tmp/ios_coalesce8_oppo_20260514T174718` -> `35.77 KB/s`
  - `/tmp/ios_coalesce8_samsung_20260514T174731` -> `37.08 KB/s`
- deterministic-path inline MeshLink rerun:
  - `/tmp/ios_rolepolicy_inline_oppo_20260514T174934` -> `36.99 KB/s`
  - `/tmp/ios_rolepolicy_inline_samsung_20260514T174945` -> `35.50 KB/s`
- inbound ACK batch threshold raised from `16` to `32`:
  - `/tmp/ios_ack32_oppo_20260514T175147` -> `37.08 KB/s`
  - `/tmp/ios_ack32_samsung_20260514T175200` -> `30.70 KB/s`

Interpretation:

- none of these post-redesign follow-ups beat the retained deterministic-path
  baseline (`52.03 KB/s` on OPPO, `39.48 KB/s` on Samsung)
- shrinking the iOS coalescing window moved the path back toward the slower
  mid-30 KB/s class
- re-enabling the inline MeshLink path remained worse than the chunked
  deterministic path even after the role-policy redesign
- reducing reverse-direction ACK traffic by acknowledging every 32 chunks did
  not help and in practice regressed both peers relative to the best retained
  deterministic-path evidence

This is important because it narrows the blocker again: the obvious app-layer
levers that remained after the role-policy redesign have now been exercised and
rejected.

### Blocker interpretation after exhausting the obvious app-layer levers

At this point the remaining gap looks increasingly platform-limited:

- the current public Android `BluetoothSocket` / LE L2CAP APIs used by the
  MeshLink product path expose listen/connect/stream operations and packet-size
  introspection, but not an additional deterministic connection-priority, PHY,
  or controller scheduling control comparable to what a GATT client can request
- iOS Core Bluetooth does allow a peripheral-side low-connection-latency hint,
  and MeshLink now exercises that deterministically on mixed Android/iOS runs
- after that, plus the earlier batching, queueing, inline-path, and ACK-cadence
  experiments, the repository has no remaining small public-API lever that has
  a strong evidence-based case for closing the gap to `>= 60 KB/s`

That means the next conformance branch is no longer a straightforward coding
follow-up inside the current public product path. It is either:

1. a materially different transport / platform strategy with new product-scope
   implications, or
2. the explicit waiver path

For the current release, option 2 was subsequently selected in the canonical
release-decision docs. The measurements in this section are therefore the
supporting evidence for that waiver, not an open-ended request for one more
small tuning pass.

### Proof-only Android GATT server + iOS GATT client fallback prototype

A proof-only native fallback prototype is now implemented in the sample apps for
benchmark investigation only:

- Android proof app: native `BluetoothGattServer` passive benchmark host
- iOS proof app: native `CBCentralManager` / `CBPeripheral` benchmark client
- proof-only direction: iPhone 15 -> Android bulk write plus Android receipt notification
- proof-only activation: launch config `benchmarkTransport=gatt` / `MESHLINK_BENCHMARK_TRANSPORT=gatt`

Fresh retained physical evidence on the reference devices:

- iPhone 15 -> Samsung, 64 KiB: `BENCHMARK transport bytes=65536 elapsedMs=2676 throughputKBps=23.92 result=Sent mode=gattPrototype setupMs=1994`
- iPhone 15 -> OPPO, 64 KiB: `BENCHMARK transport bytes=65536 elapsedMs=2915 throughputKBps=21.96 result=Sent mode=gattPrototype setupMs=1926`
- iPhone reported `maxWriteWithoutResponse=512` on both runs
- Android servers reported `mtu=517` on both runs
- both Android proof apps emitted full 64 KiB receipt notifications

Key findings:

- The fallback path is technically feasible on the target hardware and avoids the
  MeshLink handshake / route layer entirely for this benchmark slice.
- Samsung improved modestly relative to the best retained clean iPhone 15 -> Samsung
  L2CAP evidence (`23.92 KB/s` vs `19.94 KB/s`), but the gain is not large enough to
  change the release posture.
- OPPO remained in roughly the same throughput class as the current iOS L2CAP
  evidence (`21.96 KB/s`).
- The prototype still remains far below the normative iOS `>= 60 KB/s` target, so it
  does not by itself justify pivoting the product claim from L2CAP-first to GATT.
- The first discovery attempt encoded the proof-only app-id marker as GATT service
  data and hit Android legacy advertising limits (`ADVERTISE_FAILED_DATA_TOO_LARGE`).
  Moving that marker to manufacturer data fixed physical discovery on the next rerun.

This prototype therefore narrows the decision space rather than resolving it: a
proof-only GATT path is possible, but on the current hardware it does not provide
an obvious throughput escape hatch for `SC-004`.

### Recipient-confirmed MeshLink matrix rerun

The proof apps now emit benchmark lines only after a proof receipt comes back
from the passive peer. With that stricter benchmark semantic in place, a fresh
physical iPhone 15 MeshLink rerun across Samsung/OPPO and 256 B / 64 KiB now
shows a cleaner failure shape than the earlier sender-side-only numbers.

Fresh retained evidence:

- iPhone 15 -> Samsung, 256 B:
  `BENCHMARK transport bytes=256 elapsedMs=21296 throughputKBps=0.01 result=ReceiptTimeout`
- iPhone 15 -> Samsung, 64 KiB:
  `BENCHMARK transport bytes=65536 elapsedMs=21402 throughputKBps=2.99 result=ReceiptTimeout`
- iPhone 15 -> OPPO, 256 B:
  `BENCHMARK transport bytes=256 elapsedMs=21358 throughputKBps=0.01 result=ReceiptTimeout`
- iPhone 15 -> OPPO, 64 KiB:
  `BENCHMARK transport bytes=65536 elapsedMs=21428 throughputKBps=2.99 result=ReceiptTimeout`

Passive Android proof logs for those same runs retained matching receipt-send
attempts that failed on the return path:

- Samsung 256 B: `BENCHMARK receipt send(b11305) -> NotSent(reason=UNREACHABLE) token=000065f04a5217b1`
- Samsung 64 KiB: `BENCHMARK receipt send(fcbf19) -> NotSent(reason=UNREACHABLE) token=000065f7e1569e9e`
- OPPO 256 B: `BENCHMARK receipt send(262742) -> NotSent(reason=UNREACHABLE) token=000065ff936e78d5`
- OPPO 64 KiB: `BENCHMARK receipt send(a9ccef) -> NotSent(reason=UNREACHABLE) token=00006607417725e7`

Key interpretation:

- The current MeshLink iPhone path is not only missing the 64 KiB throughput
  target; it is also failing the stricter recipient-confirmed proof completion
  check across both peers and both payload sizes.
- The passive Android receipt-send lines show that the benchmark payload reached
  the passive peer often enough to trigger proof receipt logic, but the return
  delivery path still expired `UNREACHABLE` before the sender saw completion.
- Because even 256-byte cases fail under the recipient-confirmed protocol, the
  open issue is broader than bulk L2CAP throughput alone. The remaining problem
  space still includes return-path session/route stability.
- The proof-only GATT fallback prototype remains useful as a comparison point,
  but it does not resolve this stricter MeshLink-path failure shape.

### Token-correlated minimal return-path repro

A follow-up minimal repro on the MeshLink path now adds token-correlated sender
and passive-peer context to a single Samsung 256-byte case.

Retained evidence:

- sender proof log:
  - `BENCHMARK correlation role=sender.benchmark.send token=000067243710210d ... state=Running knownPeers=[08676e]`
  - `BENCHMARK transport bytes=256 elapsedMs=21347 throughputKBps=0.01 result=ReceiptTimeout`
  - `BENCHMARK correlation role=sender.benchmark.result token=000067243710210d ... outcome=ReceiptTimeout`
- sender correlation summaries still retained recent diagnostics including
  `DELIVERY_SUCCEEDED` and `HOP_SESSION_ESTABLISHED`
- passive Samsung proof log:
  - `Peer lost: 327782fbdda5d37776143c2d`
  - `BENCHMARK receipt send(fb41c7) -> NotSent(reason=UNREACHABLE) token=000067243710210d`
  - `BENCHMARK correlation role=passive.receipt.result token=000067243710210d ... outcome=NotSent(reason=UNREACHABLE)`
- passive correlation summaries retained recent diagnostics including
  `HOP_SESSION_FAILED stage=transport.handshake.message1.send`

Interpretation:

- The forward sender-to-passive leg looked healthy enough to preserve recent
  `DELIVERY_SUCCEEDED` / `HOP_SESSION_ESTABLISHED` context on the iPhone sender.
- By the time the passive Samsung peer tried to send the proof receipt back,
  that side had already observed `Peer lost` and fresh
  `transport.handshake.message1.send` failures.
- This narrows the next remediation target: the open issue is more consistent
  with reverse-direction session / route collapse after payload receipt than
  with benchmark-token parsing, receipt formatting, or sender-side timeout logic.

### Bounded passive-receipt retry follow-up matrix

A proof-app-only bounded remediation was then added on the passive Android path:

- passive benchmark mode now uses a shorter `deliveryRetryDeadline`
- proof receipts retry within an 18-second proof window instead of burning the
  entire sender receipt window on one long `UNREACHABLE` attempt

Fresh post-remediation matrix evidence:

- iPhone 15 -> Samsung, 256 B:
  `BENCHMARK transport bytes=256 elapsedMs=21350 throughputKBps=0.01 result=ReceiptTimeout`
- iPhone 15 -> Samsung, 64 KiB:
  `BENCHMARK transport bytes=65536 elapsedMs=15048 throughputKBps=4.25 result=NotSent(reason=UNREACHABLE)`
- iPhone 15 -> OPPO, 256 B:
  `BENCHMARK transport bytes=256 elapsedMs=15017 throughputKBps=0.02 result=NotSent(reason=UNREACHABLE)`
- iPhone 15 -> OPPO, 64 KiB:
  `BENCHMARK transport bytes=65536 elapsedMs=15016 throughputKBps=4.26 result=NotSent(reason=UNREACHABLE)`

The most informative cell remained Samsung 256 B. Its passive Android proof log
retained:

- repeated `BENCHMARK correlation role=passive.receipt.wait token=000067ba0db1394c ... knownPeers=[]`
- `BENCHMARK receipt abandoned token=000067ba0db1394c peer=e4383b deadlineMs=18000`
- `BENCHMARK correlation role=passive.receipt.expired token=000067ba0db1394c ... knownPeers=[]`

Interpretation:

- The passive retry helper changed the failure mode from one long blocked send
  attempt to an explicit `peerUnavailable` / `receipt abandoned` sequence.
- That means the helper improved diagnosis, but it did not restore recipient-
  confirmed completion.
- The passive peer never re-established a known-peer view of the iPhone within
  the proof retry window (`knownPeers=[]` throughout the wait sequence), which
  makes the remaining problem look more like peer reappearance / reverse-path
  availability than receipt formatting or single-attempt retry policy.
- Three post-remediation matrix cells failed even earlier on the sender side as
  `NotSent(reason=UNREACHABLE)`, so the overall blocker remains broader than
  one passive receipt loop.

### Fresh headless 256-byte Samsung vs OPPO reverse-path reruns

A fresh pair of 256-byte recipient-confirmed reruns was then captured on the
physical iPhone 15 using a headless proof-app launch path instead of XCTest UI
automation. XCTest UI automation on the device now fails with `Timed out while
enabling automation mode`, so the proof app was updated to mirror its proof log
stream to stderr and was launched with `xcrun devicectl device process launch
--console` plus benchmark environment variables. The passive Android proof apps
ran with matching per-peer app IDs and `meshlink.disableAutoSend=true` so the
return path stayed isolated to one Android peer at a time.

Fresh retained evidence:

- iPhone 15 -> Samsung, 256 B sender log:
  - `BENCHMARK correlation role=sender.benchmark.send token=000074048e0c137a ... state=Running knownPeers=[a5495f]`
  - `BENCHMARK transport bytes=256 elapsedMs=21363 throughputKBps=0.01 result=ReceiptTimeout`
  - `BENCHMARK correlation role=sender.benchmark.result token=000074048e0c137a ... outcome=ReceiptTimeout state=Running knownPeers=[a5495f]`
  - sender `recentDiags` / `routeTimeline` still retained `DELIVERY_SUCCEEDED`,
    `HOP_SESSION_ESTABLISHED`, and `ROUTE_DISCOVERED`, and the sender retained
    `routeState=available` at timeout
- Samsung passive Android log for token `000074048e0c137a`:
  - `Peer found: b7b286a92d32d218758b6e34 (CONNECTED)`
  - `Peer lost: b7b286a92d32d218758b6e34`
  - `DIAG ROUTE_EXPIRED stage=transport.peerLost.routeExpired ...`
  - repeated `BENCHMARK correlation role=passive.receipt.wait ... knownPeers=[]`
  - `BENCHMARK receipt abandoned token=000074048e0c137a ... deadlineMs=18000`
  - `BENCHMARK correlation role=passive.receipt.expired token=000074048e0c137a ... outcome=deadlineExpired`
- iPhone 15 -> OPPO, 256 B sender log:
  - `BENCHMARK correlation role=sender.benchmark.send token=0000741524b4f6cb ... state=Running knownPeers=[add166]`
  - `BENCHMARK transport bytes=256 elapsedMs=21356 throughputKBps=0.01 result=ReceiptTimeout`
  - `DIAG ROUTE_EXPIRED stage=transport.peerLost.routeExpired ...`
  - `Peer lost: 2dfbea3408c4c93e47add166`
  - `BENCHMARK correlation role=sender.benchmark.result token=0000741524b4f6cb ... outcome=ReceiptTimeout state=Running knownPeers=[]`
  - sender `routeTimeline` retained `ROUTE_DISCOVERED`,
    sender-side `DELIVERY_SUCCEEDED`, `ROUTE_EXPIRED`, and `Peer lost`
- OPPO passive Android log for token `0000741524b4f6cb`:
  - `Peer found: 04b8e07748cdec6b33459367 (CONNECTED)`
  - `Peer lost: 04b8e07748cdec6b33459367`
  - `DIAG ROUTE_EXPIRED stage=transport.peerLost.routeExpired ...`
  - repeated `BENCHMARK correlation role=passive.receipt.wait ... knownPeers=[]`
  - `BENCHMARK receipt abandoned token=0000741524b4f6cb ... deadlineMs=18000`
  - `BENCHMARK correlation role=passive.receipt.expired token=0000741524b4f6cb ... outcome=deadlineExpired`

Comparison and narrowed hypothesis:

- The two passive Android peers now agree on the central failure shape: the
  forward payload is received, but within the bounded receipt window the passive
  proof app loses the iPhone as a known peer, never rediscovers it, and expires
  the proof receipt as `deadlineExpired`.
- The Samsung path still shows an asymmetry that the OPPO path does not: the
  sender-side iPhone retained `knownPeers=[a5495f]` and `routeState=available`
  at timeout, even though the passive Samsung side had already logged `Peer
  lost`, `ROUTE_EXPIRED`, and `knownPeers=[]` for the same token.
- The OPPO path is more symmetric: both sender and passive logs retained
  `ROUTE_EXPIRED` / `Peer lost` before the receipt window elapsed.
- This narrows the blocker further. The remaining issue is no longer well
  described as receipt formatting, token parsing, or a single passive retry
  policy problem. The concrete failure mode is post-payload peer disappearance /
  reverse-path non-reappearance during the receipt window, with a likely stale-
  route or stale-presence view lingering on the Samsung sender path.

Next bounded remediation target:

- Focus the next bounded remediation on post-payload peer-lost / route-reentry
  reconciliation for the direct peer during the receipt window. Specifically:
  verify why the passive peer drops to `knownPeers=[]` immediately after payload
  receipt on both Samsung and OPPO, and why the Samsung sender can still retain
  `routeState=available` after the passive side has already expired that peer.
  That is the smallest next target that explains both the symmetric OPPO case
  and the stale-state Samsung asymmetry.

### Recipient-confirmed 64 KiB proof completion restored on the MeshLink path (2026-05-14)

A later bounded remediation sequence restored recipient-confirmed 64 KiB
physical proof completion on both reference Android peers without changing the
normative throughput posture.

Bounded changes that materially altered the failure shape:

- reduced the inbound transfer ACK batch threshold from 64 to 16 chunks so the
  passive Android peers can acknowledge progress before the direct link dies
  around the earlier 25–60 chunk window
- increased the iOS L2CAP write-stall timeout from 5 s to 10 s so the sender
  does not self-close while the Android peer is still draining and
  acknowledging late-transfer chunks
- delivered completed inbound transfers immediately on full chunk receipt
  instead of waiting for a later `TransferComplete` control frame
- flushed queued stale iOS chunk frames before enqueuing `TransferComplete`, so
  the completion signal and immediate proof-receipt traffic are no longer stuck
  behind obsolete duplicate chunk frames in the sender queue

The most important new physical evidence is the post-queue-flush stable series:

- iPhone 15 -> OPPO, 64 KiB recipient-confirmed MeshLink series (5 runs):
  - retained run directory: `/tmp/oppo_flush_queue_series_20260514T112028`
  - sender results: 5/5 `Sent`
  - passive OPPO results: 5/5 `BENCHMARK receipt send(...) -> Sent`
  - sender throughput range: `14.50-17.09 KB/s` (average `15.57 KB/s`)
- iPhone 15 -> Samsung, 64 KiB recipient-confirmed MeshLink series (5 runs):
  - retained run directory: `/tmp/samsung_flush_queue_series_20260514T112327`
  - sender results: 5/5 `Sent`
  - passive Samsung results: 5/5 `BENCHMARK receipt send(...) -> Sent`
  - sender throughput range: `27.85-33.56 KB/s` (average `30.64 KB/s`)

This closes the earlier proof-completion blocker hypothesis from the retained
2026-05-12 / 2026-05-13 recipient-confirmed failures. Those older
`ReceiptTimeout` / `UNREACHABLE` runs remain useful diagnostic history, but they
no longer describe the current MeshLink physical path.

The remaining non-conformance is now narrower and purely normative:

- recipient-confirmed 64 KiB round-trip proof completion is restored on the
  current reference path for both OPPO and Samsung
- iOS single-hop 64 KiB throughput still remains well below the required
  `>= 60 KB/s` target even on the restored-stability path
- therefore the remaining open issue at that point was iOS `SC-004`
  throughput non-conformance, not proof-completion instability itself

For the current release, that non-conformance is now recorded as an accepted
known limitation under the explicit waiver path in the canonical release
artifacts.

### Permission-denied blocked-validation expectation

Automated contract coverage now asserts that blocked validation runs surface
`MeshLinkException.PermissionDenied` rather than a generic platform failure when
BLE access is denied:

- Android missing or revoked runtime grants now fail the transport contract with
  explicit missing-permission detail (`BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` /
  `BLUETOOTH_ADVERTISE` on API 31+, `ACCESS_FINE_LOCATION` below API 31).
- iOS denied or restricted CoreBluetooth authorization now maps to the same
  `PermissionDenied` category, while `NotDetermined` remains promptable so the
  platform may still ask the user for Bluetooth access on first run.
- Reviewers should therefore retain both the blocked / permission-denied
  outcome and the observed permission state when a quickstart or benchmark run
  cannot proceed.

### Explicit compatibility rejection coverage

Automated compatibility coverage now also makes the rejected-participation paths
explicit:

- A future-version wire envelope now fails fast with
  `Unsupported WireEnvelope version 2` in
  `WireEnvelopeContractTest` instead of silently decoding a mismatched major
  version.
- A GATT-only discovered peer now emits
  `TRANSPORT_MODE_CHANGED stage=transport.peerDiscovered.rejected` and remains
  unreachable in `MeshRoutingIntegrationTest`; the current MeshLink release no
  longer treats `l2capPsm=0x00` advertisements as acceptable mesh participants.

### Post-waiver future SC-004 redesign comparison (2026-05-14)

Once the current release moved onto the explicit waiver path, the next future
closure branch could no longer be "one more" L2CAP batching experiment. Three
materially different redesign shapes were considered:

1. **Multi-channel L2CAP striping on the existing mixed-platform path**
   - shape: keep the current iPhone-peripheral-hosted deterministic L2CAP path,
     but open multiple LE CoC channels and stripe large-transfer frames across
     them
   - what it hides: channel fan-out, striping, resequencing, and per-channel
     backpressure inside the transport layer
   - why it is unattractive: all channels still share the same BLE connection,
     the current bottleneck already looks dominated by CoreBluetooth stream
     scheduling, and this path doubles implementation complexity before any
     evidence suggests aggregate throughput would rise materially

2. **Reverse-direction proof branch: iPhone peripheral GATT notifications into
   Android central GATT client**
   - shape: for proof benchmarking only, move the iPhone sender off the current
     `NSStream`-backed L2CAP API path and onto `CBPeripheralManager.updateValue`
     notifications, while Android acts as a GATT client that subscribes,
     reassembles the payload, and writes back a receipt ACK
   - what it hides: GATT service layout, notification flow control,
     subscription gating, and receipt-ack framing inside a dedicated proof
     transport
   - why it is attractive: it exercises a genuinely different iOS API surface,
     preserves the iPhone-peripheral-hosted / low-latency shape that already
     helped the current L2CAP branch, and can be evaluated without changing the
     product-path spec or weakening the current waiver guardrails

3. **Dual-bearer hybrid striping across L2CAP plus GATT**
   - shape: keep the current deterministic L2CAP branch but add a second GATT
     bearer and stripe large-transfer data over both bearers simultaneously
   - what it hides: bearer scheduling, link health scoring, resequencing,
     duplicate suppression, and fallback logic inside a new transfer planner
   - why it is unattractive: it is the most complex design, still shares the
     same half-duplex BLE airtime, and should not be attempted before a single
     alternative bearer proves that it can outperform the retained L2CAP
     baseline on its own

Comparison and recommendation:

- The multi-channel L2CAP option keeps the smallest conceptual delta from the
  current product path, but it is shallow in the worst way: it exposes a great
  deal of new complexity while betting against the same iOS stream-scheduling
  surface that already failed to hit target.
- The dual-bearer hybrid is even riskier. It broadens the misuse surface and
  multiplies state-space before the repository has any evidence that the second
  bearer is itself worth promoting.
- The reverse-direction GATT notification branch is the deepest next module
  boundary. It keeps the current product path untouched, but cleanly isolates a
  different iOS platform API (`CBPeripheralManager.updateValue`) plus a
  different Android API (`BluetoothGatt` client) behind proof-only benchmark
  hosts.

**Recommendation:** pursue option 2 first. If the reverse-direction GATT proof
branch cannot materially outperform the retained deterministic L2CAP baseline,
then the repository should treat that as strong evidence that closing iOS
`SC-004` will require a larger product-scope change than another BLE-bearer
swap.

### Reverse-direction proof-only GATT-notify prototype results (2026-05-14)

The selected proof branch was then implemented in the runnable proof apps as:

- iPhone 15 sender: `CBPeripheralManager` service host that streams benchmark
  frames via `updateValue(..., .notify, ...)`, requests `.low` connection
  latency for the subscribed Android central, and waits for a GATT write ACK
- Android passive peer: `BluetoothGatt` client that scans, connects, requests
  `CONNECTION_PRIORITY_HIGH`, requests `MTU=517`, reads an app-hash
  characteristic, subscribes to the notify characteristic, reassembles the
  payload, and writes back a receipt ACK

Retained physical evidence:

- OPPO initial run: `/tmp/ios_gatt_notify_oppo_20260514T182709`
  - sender: `73.65 KB/s`
  - passive OPPO: `MSG from gattNotify bytes=65536 benchmarkToken=...`
  - passive OPPO: `BENCHMARK receipt send(a377e7) -> Sent ... attempt=1`
- Samsung initial run: `/tmp/ios_gatt_notify_samsung_20260514T182741`
  - sender: `65.98 KB/s`
  - passive Samsung: `MSG from gattNotify bytes=65536 benchmarkToken=...`
  - passive Samsung: `BENCHMARK receipt send(cac80f) -> Sent ... attempt=1`
- OPPO rerun: `/tmp/ios_gatt_notify_oppo_rerun_20260514T182809`
  - sender: `43.13 KB/s`
- Samsung rerun: `/tmp/ios_gatt_notify_samsung_rerun_20260514T182820`
  - sender: `53.87 KB/s`
- 300 ms delayed-start OPPO rerun:
  `/tmp/ios_gatt_notify_delay_oppo_20260514T183029`
  - sender: `NotSent(reason=CENTRAL_UNSUBSCRIBED)`
  - passive OPPO: retained `GATT notify benchmark connection status=8 state=DISCONNECTED`
- 300 ms delayed-start Samsung rerun:
  `/tmp/ios_gatt_notify_delay_samsung_20260514T183047`
  - sender: `68.52 KB/s`
  - passive Samsung: retained recipient-confirmed completion

Interpretation:

- this is the first post-waiver future branch to exceed the normative iOS
  target on retained physical evidence
- the branch is promising enough to justify product-path integration work and a
  future spec amendment discussion
- the branch is not yet deterministic: follow-up reruns fell back into the
  `43-54 KB/s` range on both peers, and one OPPO delayed-start rerun dropped
  the connection before completion
- the new dominant question is no longer whether a different iOS API surface
  can cross `>= 60 KB/s`; it clearly can. The next question is whether MeshLink
  can integrate that bearer into the product path with enough stability to
  replace the current waived posture

### First shared-Kotlin product-path integration attempt bridge blocker (2026-05-14)

A first product-path integration attempt then tried to keep the current L2CAP
path for discovery / handshake / reverse-direction control while adding a
mixed-platform iPhone->Android GATT-notify side channel for outbound
`DirectWireFrame.Data` traffic.

Retained blocker evidence:

- Samsung product-path attempt with side-link connect success:
  `/tmp/ios_meshlink_gattside_samsung_bridge_20260514T191931`
  - Android logcat retained:
    - `initiating GATT notify side link ...`
    - `GATT notify side link ... status=0 state=CONNECTED`
    - `GATT notify side link ... mtu=517 status=0`
    - `GATT notify side link ready ...`
  - iPhone console retained repeated
    `sending 505 bytes via GATT notify side link ...`
  - the same iPhone console then retained the concrete blocker:
    `kotlin.TypeCastException: class kotlinx.cinterop.CPointer cannot be cast to class platform.Foundation.NSData`
  - scored result remained
    `BENCHMARK transport bytes=65536 elapsedMs=15050 throughputKBps=4.25 result=NotSent(reason=UNREACHABLE)`
- Samsung product-path attempt with side-link instability:
  `/tmp/ios_meshlink_gattside_samsung_pump_20260514T191127`
  - Android logcat retained `status=133 state=DISCONNECTED` on the GATT side
    link before it became usable

Interpretation at that point:

- the future branch was still promising at the proof-app level
- but the first real MeshLink transport integration was blocked one layer
  lower than transport policy: getting a supported `ByteArray -> NSData`
  bridge into `CBPeripheralManager.updateValue` from shared Kotlin/Native code
- the proof-only Swift host already proved that the iOS API path itself was
  viable; the blocker was specifically the shared-Kotlin integration seam, not
  the BLE bearer concept

### Optional Swift transport bridge resolved the KMP `NSData` seam (2026-05-14)

That specific bridge blocker was then resolved by adding a small optional
public iOS-native transport bridge, analogous to the existing crypto bridge:

- Swift installs `IosBleTransportBridge.install(...)` during iOS app startup
- the callback receives opaque iOS-native handles for the active
  `CBPeripheralManager`, notify characteristic, subscribed `CBCentral`, and the
  raw payload bytes
- Swift converts the Kotlin byte array to `Data` and calls
  `CBPeripheralManager.updateValue(...)` on the main queue
- if the bridge is not installed, MeshLink falls back to the current transport
  path and does not require the future bearer

Fresh verification after the bridge landed:

- `./gradlew :meshlink:compileKotlinIosSimulatorArm64 :meshlink:compileKotlinIosArm64`
  passed
- `xcodebuild -project meshlink-sample/ios/ProofApp.xcodeproj -scheme ProofApp -destination 'generic/platform=iOS Simulator' build`
  passed
- `xcodebuild ... -destination 'id=00008120-00011DEE0105A01E' ... build`
  passed
- the follow-up product-path reruns no longer retained the earlier
  `TypeCastException`

### Reopened product-path reruns revealed the next blocker (2026-05-14)

Once the bridge seam was removed, fresh product-path reruns finally proved that
real MeshLink frames were flowing over the new GATT-notify side bearer — but
that was not enough to close the full transfer.

Retained evidence:

- Samsung product-path rerun:
  `/tmp/ios_meshlink_gattside_samsung_bridge2_20260514T194413`
  - sender result still ended
    `BENCHMARK transport bytes=65536 elapsedMs=15069 throughputKBps=4.25 result=NotSent(reason=UNREACHABLE)`
  - iPhone console retained repeated `GATT notify pump ... didSend=true/false`
    lines and continued `sending 505 bytes via GATT notify side link ...`
  - Android logcat retained real side-bearer delivery:
    - `received notification bytes=...`
    - `decoded frame bytes=505`
  - retained decoded-frame count for the run: `35`
  - passive Samsung never reached `MSG from ... bytes=65536`
- OPPO product-path rerun:
  `/tmp/ios_meshlink_gattside_oppo_bridge2_20260514T194439`
  - sender result still ended
    `BENCHMARK transport bytes=65536 elapsedMs=15063 throughputKBps=4.25 result=NotSent(reason=UNREACHABLE)`
  - Android logcat retained real side-bearer delivery with decoded frames
  - retained decoded-frame count for the run: `47`
  - passive OPPO never reached `MSG from ... bytes=65536`

Interpretation:

- the product-path candidate now has proof that the optional GATT-notify side
  bearer can carry genuine MeshLink encrypted data frames on Android/iOS peers
- however, the one-way side bearer is still not enough to close the transfer,
  because reverse transfer ACKs and the final proof receipt remain tied to the
  L2CAP path
- in both retained product-path reruns, the direct peer route expired before
  the 64 KiB transfer completed, so sender-side completion still degraded to
  `NotSent(reason=UNREACHABLE)` even though Android had already decoded some
  side-bearer frames
- the next blocker is therefore no longer `NSData` bridging. It is the mixed-
  bearer control plane itself: either reverse transfer / receipt traffic must
  move onto GATT as well, L2CAP must be kept alive explicitly for the whole
  transfer, or route-presence semantics must be redesigned so the mixed bearer
  is treated as a durable direct path instead of a transient side channel

### Phase-22 follow-up after reverse-control remediations (2026-05-17)

Fresh product-path reruns after the first reverse-control remediations narrowed
that blocker again, but did not close it.

Retained Samsung evidence:

- product-path rerun:
  `/tmp/ios_meshlink_gattside_samsung_controlplane8_20260517T133555`
  - sender retained `DIAG TRANSFER_COMPLETED stage=transfer.send.complete`
  - passive Samsung retained full delivery:
    `MSG from 2f219ad6dbb23f8c9249e7a6dc62f6ec9cd24c3f bytes=65536 benchmarkToken=0000a1e8fe2e29d0`
  - passive Samsung still failed the proof receipt path repeatedly:
    `BENCHMARK receipt send(49e7a6) -> NotSent(reason=UNREACHABLE)`
    while the passive-side diagnostics still retained
    `routeAvailable=true, routeIsDirect=true`
- diagnostic rerun on the same branch:
  `/tmp/ios_meshlink_gattside_samsung_controlplane9_20260517T134214`
  - iPhone retained `GATT write request for 96ee2f requests=1 decodedFrames=1 accepted=true`
    lines, which proves Android->iOS GATT writes can reach the iOS runtime
  - even so, sender-side product-path closure still remained unstable and the
    same branch later retained repeated `TRANSFER_TIMED_OUT` outcomes

Retained OPPO evidence:

- product-path rerun:
  `/tmp/ios_meshlink_gattside_oppo_controlplane10_20260517T134622`
  - sender retained `BENCHMARK transport bytes=65536 elapsedMs=15034 throughputKBps=4.26 result=NotSent(reason=TRANSFER_TIMED_OUT)`
  - sender-side progress plateaued at `ackedChunks=96`
  - this branch therefore still lacks even forward sender-side completion on
    the OPPO reference peer

Interpretation:

- the branch is no longer blocked on the original `NSData` bridge seam
- it is also no longer blocked solely on forward-data movement: Samsung now
  proves that product-path forward transfer completion can be restored through
  the mixed bearer
- the remaining blocker is narrower and more product-specific:
  recipient-confirmed proof closure after delivery still fails, and the branch
  is not yet stable across both reference peers
- the most likely remaining causes are stale duplicate post-completion traffic,
  incomplete Android->iOS direct-receipt reuse of the GATT side bearer, or an
  OPPO-specific queue / sender-stability issue that still leaves the sender at
  `ackedChunks=96`

### Mixed-bearer product-path closure restored on current HEAD (2026-05-17)

A final 2026-05-17 remediation sequence then cleared the remaining Phase-21/22
mixed-bearer blocker on the current branch.

Bounded fixes that materially changed the failure shape:

- iOS GATT-notify sends now wait for a full framed payload to drain before the
  shared runtime reports `Delivered`, instead of treating local queueing as
  transport success
- Android GATT-notify receive handling now snapshots callback values, serializes
  notification decoding, and dispatches decoded frames through an ordered queue
  so OEM stacks that invoke notification callbacks concurrently cannot corrupt
  or reorder the direct encrypted frame stream
- Android->iOS GATT side-link writes now chunk large framed payloads and cap
  the per-write chunk size to a safer `512` bytes, which avoids the
  `status=9` / `write enqueue failed` receipt-path failures that still appeared
  after the earlier reverse-control remediations

Fresh retained evidence:

- Samsung rerun:
  `/tmp/ios_meshlink_gattside_samsung_framingfix_20260517T154549`
  - iPhone sender retained:
    - `DIAG TRANSFER_COMPLETED stage=transfer.send.complete`
    - `BENCHMARK receipt from ... token=0000a8ff28258725 bytes=65536`
    - `BENCHMARK transport bytes=65536 elapsedMs=1406 throughputKBps=45.52 result=Sent`
  - passive Samsung retained:
    - `MSG from ... bytes=65536 benchmarkToken=0000a8ff28258725`
    - `BENCHMARK receipt send(651947) -> Sent token=0000a8ff28258725 attempt=1`
- OPPO rerun:
  `/tmp/ios_meshlink_gattside_oppo_writecap_20260517T155015`
  - iPhone sender retained:
    - `DIAG TRANSFER_COMPLETED stage=transfer.send.complete`
    - `BENCHMARK receipt from ... token=0000a93d37a4d03a bytes=65536`
    - `BENCHMARK transport bytes=65536 elapsedMs=1218 throughputKBps=52.55 result=Sent`
  - passive OPPO retained:
    - `MSG from ... bytes=65536 benchmarkToken=0000a93d37a4d03a`
    - `BENCHMARK receipt send(320fdf) -> Sent token=0000a93d37a4d03a attempt=1`

Root-cause notes supported by the retained intermediate reruns:

- Samsung’s remaining receipt-path failure was no longer a generic
  `UNREACHABLE` mystery. The passive Android log retained repeated
  `GATT notify side link ... write failed status=9` for the 533-byte framed
  proof receipt, which narrowed the issue to Android->iOS GATT write sizing.
- OPPO’s remaining forward-path corruption was no longer explained by the
  earlier nonce hypothesis. The passive Android log showed notification
  callbacks landing on multiple different threads while the shared frame buffer
  was still mutable and unsynchronized, which fits the repeated
  `AEADBadTagException` failures observed before the ordered decode fix.

Updated interpretation:

- the mixed-bearer product path is no longer blocked on the original iOS
  `NSData` bridge seam
- it is no longer blocked on forward-only delivery or on recipient-confirmed
  proof closure either; both Samsung and OPPO now complete the 64 KiB
  recipient-confirmed product-path benchmark on current HEAD
- the remaining blocker therefore collapses back to the long-standing iOS
  throughput gap against `SC-004`: the best fresh product-path reruns now land
  at `45.52 KB/s` on Samsung and `52.55 KB/s` on OPPO, which still remains
  below the normative iOS target of `>= 60 KB/s`

Engineering learnings worth retaining:

- for the iOS peripheral-hosted GATT-notify bearer, transport success cannot
  mean local queue acceptance; it must mean the full framed payload drained far
  enough through Core Bluetooth that sender/session state will not outrun the
  real encrypted wire stream
- for Android GATT clients, notification handling must treat callback ordering
  as hostile: snapshot the byte value immediately and run frame-buffer decode /
  downstream delivery through one ordered pipeline, because at least one OEM
  stack delivered notification callbacks concurrently on different threads
- for Android->iOS GATT side-link writes, negotiated MTU headroom was not a
  sufficient safety signal by itself; the retained Samsung receipt failure shows
  that conservative chunking (`<= 512` bytes here) is still required for stable
  cross-vendor closure even when larger framed payloads appear theoretically
  admissible
- the last mixed-bearer failures were therefore transport bookkeeping and
  callback-ordering bugs, not new evidence that the encrypted MeshLink frame
  format or nonce model is fundamentally incompatible with the GATT-notify side
  bearer
