# MeshLink library refactor plan

This is a planning artifact, not binding guidance — `constitution.md` remains the
source of truth if anything here conflicts with it. It captures a point-in-time
analysis of the `:meshlink` library and a proposed refactor sequence covering
efficiency, performance, security, stability, and code organization.

## Method

Four parallel read-only reviews were run against the actual source: the
`engine/` package, the `crypto/` layer (plus Android JCA/fallback and JVM
providers), the Android BLE transport layer (`platform/android/`), and the
wire/routing/trust/identity layers. Findings below are cited to specific files;
see the review summaries for file:line detail where noted.

## What's already good (don't disturb)

The codebase is **not** a typical "needs refactoring" mess — it's already
deliberately decomposed per Principle V, with `internal`-only types,
mutex-guarded state concentrated in a few registries, well-documented crypto
(constant-time discipline is explicit and mostly correct), sound Noise XX
handshake logic, correct replay-window ordering, and a genuinely good TOFU
trust implementation. The plan below is deliberately surgical — it targets
concrete, cited defects and organizational debt, not a rewrite.

## Findings, by subsystem

### 1. `engine/` (55 files, flat package)

- Should split into subpackages: `engine.assembly`, `engine.handshake`,
  `engine.transport`, `engine.transfer`, `engine.routing`, `engine.lifecycle`,
  `engine.trust`, `engine.internal`. Pure reorganization (all `internal`),
  near-zero risk.
- The `XxxSupport`/`XxxCallbacks`/`buildXxx(...)` pattern (35 factory
  functions, 23 hand-rolled callback bags) is genuine decomposition at the
  logic level, but the **assembly/wiring layer** is a de facto god object —
  the real dependency graph only exists as lambda plumbing across 4 assembly
  files, with no typealiases anywhere and a runtime-checked late-binding cell
  (`MeshEngineRuntimeLateBindingContext`) papering over a genuine cycle
  between routing and hop-transport.
- Duplication: repeated inline function-type signatures (`emitDiagnostic`,
  `sendEncryptedWireFrame`, `peerRouteMetadata`) across 20+ files with no
  `typealias`; `isTransientLinkNotReady()` duplicated verbatim (with a comment
  admitting it); hex-snippet/peer-suffix diagnostic formatting duplicated
  dozens of times.
- Coroutines: no `CoroutineExceptionHandler` on the top-level scope; several
  fire-and-forget `launch{}` sites (per-advertisement, per-forwarded-frame);
  two `runCatching` sites wrap genuinely suspending calls and will swallow
  `CancellationException`.
- Minor perf: `MeshEngineSequenceGenerator` takes a full `Mutex` to increment
  a `Long`.
- No handshake/replay correctness bugs found — this area is solid.

### 2. `crypto/` (+ Android JCA/fallback, JVM)

- `MessageSealer.requireContributorySharedSecret` duplicates and *weakens*
  the already-correct constant-time check in `X25519SharedSecretValidation`
  (short-circuiting `.all{}` instead of an OR-fold) — should be deleted in
  favor of the shared helper.
- No key material is ever zeroed anywhere (Noise handshake state, expanded
  Ed25519 keys, `LocalIdentity`) — relies entirely on GC. The engine layer
  does drop references promptly on completion, which bounds exposure, but
  there's no explicit wipe-on-dispose.
- `JvmCryptoProvider`/`JcaCryptoProvider` duplicate a large amount of JCA
  plumbing (ThreadLocal caching, sha256/hmac/ChaCha20-Poly1305 bodies) that
  differs only in X25519/Ed25519 key encoding.
- `Ed25519Fallback.kt` (930 lines) and `NoiseXXHandshakeManager.kt` (425
  lines) each mix multiple concerns (field arithmetic / point arithmetic /
  comb-table / signing API; protocol orchestration / `HandshakeState` /
  free-standing `hkdfSha256`) and should be split for independent audit.
- `PlaceholderCryptoProvider` is reachable from production-facing default
  parameters (`MeshEngine.create`, `LocalIdentity.fromAppId`) even though no
  real factory ever uses it — should be quarantined into a test-fixtures
  source set or renamed unmistakably.
- Per-message allocation waste in `MessageSealer.seal`/`open` (repeated
  concatenation of associatedData+nonce+ciphertext).

### 3. Android BLE transport (`platform/android/`, 42 files, flat)

- Should split into `platform.android.{gatt,l2cap,scan,crypto,storage,power}`
  + a thin core/composition package.
- GATT operation sequencing is correctly guarded (no classic
  overlapping-callback race) — a strength, not a risk.
- Two concrete, fixable hot-path waste points: `L2capLink.writeFrame`
  allocates a full 1KB `L2capFrameBuffer` per write only to call a stateless
  method; the read path unconditionally calls the diagnostics-heavy
  `appendDetailed()` (hex snippets + object allocation) on every single
  L2CAP read instead of gating it behind the existing debug-logging flag.
  This is the most concrete throughput/memory finding in the whole review,
  directly relevant to the ≥80KB/s and ≤8MB constitution targets.
- Two real, unsynchronized-shared-state stability risks: `L2capReconnectGuard`'s
  backoff map has no lock (every other shared-map class in this codebase
  does), and most of `BleTransportDiscoveryLifecycle`'s cross-thread fields
  aren't `@Volatile` even though they're written from both the scan-callback
  thread and IO-dispatcher coroutines.
- `L2capFrameBuffer` is byte-for-byte identical core logic between Android
  and iOS and is a clean, ready-to-extract `commonMain` candidate; several
  other same-named platform files (`SendDispatchSupport`,
  `PreferredGattSendSupport`, `PowerMonitor`) have diverged into
  parity-risk territory rather than clean duplication, which is a
  slower-burning risk against Principle III (cross-platform parity).
- Lifecycle/leak handling is generally a strength (careful bounded-drain
  teardown); one self-documented tension in `GattNotifyClient.closeInternal`'s
  disconnect-timeout path, and a noisy-neighbor fairness gap in the shared
  `InboundFrameQueue`.

### 4. wire/routing/trust/identity

- Wire codec: 5–6 full-payload copies and 2 hashmap+list allocations per
  encoded message, due to the envelope-in-envelope design (payload fully
  serialized, then re-embedded as an opaque byte vector) plus redundant
  defensive copies of freshly-allocated, never-aliased arrays. Directly
  relevant to the <1µs/message target; no committed benchmark result found
  to confirm current margin.
- FlatBuffers backward-compatibility is enforced by pinned byte fixtures for
  only 3 of 17 frame types; the other 14 rely on convention only.
- Routing (Babel-inspired): feasibility condition and route selection
  correctly match RFC 8966. But sequence numbers are locally-minted per
  direct-connect event rather than destination-sourced, creating an untested
  edge case where a relay's higher local seqno can displace a live direct
  route with a worse-metric indirect one. Three full wire frame types
  (`Hello`, `Ihu`, `SeqNoRequest`) are fully encoded/decoded/dispatched but
  never produced and are no-op on receipt — dead protocol surface.
  `RouteDigest` is sent on nearly every advertisement but `onRouteDigest` is
  an empty stub — the "differential updates" half of the design is unbuilt.
- TOFU trust and hop-level replay protection are both sound.
- Directory sizes for storage/identity/trust/presence/power/diagnostics/
  config/routing/transfer are already appropriately small — no further
  splitting needed there.

## The plan

Five workstreams, ordered by risk/value, each independently shippable as its
own PR (satisfying Principle V's "one clear reason to change" and the
branch/commit-per-task workflow).

### Workstream A — Pure reorganization (near-zero risk, do first)

1. Split `engine/` into the 8 subpackages listed above.
2. Split `platform/android/` into `gatt`/`l2cap`/`scan`/`crypto`/`storage`/
   `power` + core.
3. Split `Ed25519Fallback.kt` into field-arithmetic / point-arithmetic /
   signing-API files; extract `HandshakeState` and `hkdfSha256` out of
   `NoiseXXHandshakeManager.kt`.
4. Introduce typealiases for the repeated engine callback function-type
   signatures; extract the duplicated `isTransientLinkNotReady()`,
   hex-snippet, and peer-suffix helpers.

Each is mechanical (all types `internal`), verifiable by full test suite +
BCV (no public API change), and directly earns "smaller logical units"
without behavior risk.

### Workstream B — Performance (targets constitution Principle IV directly)

1. Fix `L2capLink.writeFrame`'s throwaway buffer allocation; gate
   `appendDetailed()` behind the existing debug-logging flag on the L2CAP
   read hot path.
2. Reduce wire codec copy count — collapse the redundant defensive copies
   where the source array is freshly allocated and never aliased; replace
   `FlatBufferTable`'s `LinkedHashMap`+`sortedBy` field bookkeeping with a
   dense array indexed by field index.
3. Replace `MeshEngineSequenceGenerator`'s mutex-guarded counter with an
   atomic.
4. Add/confirm the missing committed JMH benchmark baseline for wire codec
   and re-run against the <1µs target before/after.

Each change should ship with a benchmark diff per Principle IV's regression
gate.

### Workstream C — Security/stability hardening

1. Delete `MessageSealer.requireContributorySharedSecret`; route through the
   shared `X25519SharedSecretValidation` helper only.
2. Add `@Volatile` (or a lock) to `BleTransportDiscoveryLifecycle`'s
   cross-thread fields and to `L2capReconnectGuard`'s backoff map — bring
   both in line with the codebase's own established synchronized-map
   pattern.
3. Add a `CoroutineExceptionHandler` on the top-level engine scope; guard the
   two `runCatching`-around-suspend sites to rethrow `CancellationException`.
4. Quarantine `PlaceholderCryptoProvider` out of reachable production
   defaults (test-fixtures source set or unmistakable rename).
5. Decide and act on the dead Babel wire surface: either wire up
   `Hello`/`Ihu`/`SeqNoRequest`/`RouteDigest`-receive, or remove them
   (Principle V "simplest design" — carrying unused wire-compatible surface
   forever is a real cost once release-bound).

### Workstream D — Cross-platform parity / shared abstraction

1. Extract `L2capFrameBuffer`'s core framing logic into `commonMain/transport/`
   (Android and iOS are byte-for-byte identical here) — direct precedent
   already exists (`PendingFrameWindow`, `PeerLinkLifecycle`).
2. Audit `SendDispatchSupport`/`PreferredGattSendSupport`/`PowerMonitor`
   Android-vs-iOS divergence against Principle III; either intentionally
   document the difference or lift the shared policy into `commonMain`.
3. Unify JVM/Android JCA plumbing (ThreadLocal caching, symmetric-primitive
   bodies) behind a shared helper, isolating only the genuinely necessary
   X25519/Ed25519 key-encoding differences.

### Workstream E — Wire/routing correctness follow-ups (lower urgency, no release yet)

Per `CHANGELOG.md`, no public MeshLink release has been cut yet, so these are
lower urgency but should land before the wire format is considered stable.

1. Widen the pinned wire-compat byte fixtures from 3 to all 17 frame types
   before the first release.
2. Add a test covering the untested "relay reports higher local seqno than a
   live direct route" case, and decide whether to move to destination-sourced
   (rather than locally-minted) sequence numbers before the wire format is
   considered stable.

## Why this order

- **A** is pure structure, zero behavioral risk, and immediately delivers the
  "split into smaller logical units" goal — do it first so later PRs land on
  the new file layout instead of fighting it.
- **B** and **C** are both cheap, targeted, and each maps directly to one of
  the explicit goals (performance, security/stability) with concrete
  file:line evidence already in hand.
- **D** and **E** are real but lower urgency: D is a parity/maintainability
  investment, E is pre-release protocol-completeness work that the project's
  own docs and specs already flag as open (`RouteDigest` differential
  updates, Hello/IHU dead surface).
