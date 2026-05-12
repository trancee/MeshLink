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
  `MeshLink.createAndroid()` succeeds on-device even though the JCA Ed25519
  probe still fails.
- OPPO `CPH2689`, Android 16 / API 36: `Ed25519` and `ChaCha20-Poly1305`
  work, but Conscrypt's `OpenSSLXDHKeyPairGenerator` rejects
  `initialize(AlgorithmParameterSpec)` with `InvalidAlgorithmParameterException`.
  This means `XDH` key generation must use `generateKeyPair()` directly instead
  of `NamedParameterSpec.X25519` initialization on Android. With that fix,
  `MeshLink.createAndroid()` also succeeds on-device.
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
