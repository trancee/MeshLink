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
