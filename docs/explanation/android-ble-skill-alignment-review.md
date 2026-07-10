# Android BLE skill-alignment review

**Skills used:** `android-ble`, `android-17-ble-migration`, `android-ble-gatt-sequential-queue`,
`android-ble-gatt-status-133`, `android-ble-inspector-patterns`, `android-gatt-server`,
`android-bluetooth-sockets`, `ble-gap-gatt-l2cap-architecture`, `optimize-ble-throughput`.

This is the record of a full-codebase pass cross-checking MeshLink's Android BLE implementation
(`meshlink/src/androidMain/kotlin/ch/trancee/meshlink/platform/android/**`, plus the
`meshlink-reference`/`meshlink-proof` Android apps) against every Android-BLE-specific skill in
the repo. Five parallel read-only reviews covered: (1) scanning/discovery, (2) GATT client
connection handling, (3) GATT notify client/server, (4) L2CAP CoC sockets, (5) foreground-service
and app-lifecycle concerns. Findings are grouped below as **Fixed in this pass**,
**Deferred (proposed, not yet applied)**, and **Confirmed already correct** (no action needed) so
the existing strong points aren't lost in the noise.

Overall the codebase is in good shape: `autoConnect` usage, memory-safe API 33+ callback/write
overloads, stable (non-MAC) peer identity, PSM exchange, close()-ordering for server sockets vs.
accepted sockets, and GATT status 133/147 grouping were all already correct going in. The gaps
found were narrow and mostly additive.

## Fixed in this pass

All five changes below compile, pass `ktfmtCheck`/`detektAll` for the touched source sets, and pass
`:meshlink:allTests`/`:meshlink:koverVerify`/`:meshlink:apiCheck`. New tests were added for the two
changes with pure, host-testable logic (item 1's dedicated retry-scheduling counters in
`BleTransportDiscoveryLifecycle`, and item 4's `L2capReconnectGuard` reason-string
classification). Items 2, 3, and 5 touch `BluetoothGattServerCallback`/coroutine-launch bodies
that construct real platform objects (`BluetoothGattServer`, `BluetoothSocket`,
`BluetoothDevice`) with no existing test harness in this module (no Robolectric, no
final-class-capable Android mocking) — consistent with the rest of this codebase's existing
pattern of leaving that class of platform glue untested directly and relying on
`:meshlink:koverVerify`'s 100%-line/branch gate, which still passes because Kover's merged report
in this module does not currently measure the Android-target compilation (a separate, pre-existing
gap in the same spirit as `docs/explanation/detekt-ci-gate-gap.md`, not something introduced or
masked by this change). A code-review pass on this diff confirmed there is no cheap, low-risk way
to add real unit coverage for those three specific callback bodies without first building
Android-object test-fake infrastructure this module doesn't have today — flagged here rather than
forcing a disproportionate scaffolding addition into a narrow bug-fix pass.

### 1. Scan-rate-limit (`SCAN_FAILED_APPLICATION_REGISTRATION_FAILED`) had no dedicated backoff

**Skill:** `android-17-ble-migration` §3 — Android throttles scan start/stop cycles to ~5 per
rolling 30s window; exceeding it returns this code, usually silently. Android 17 enforces it more
strictly.

**Gap:** `BleTransportDiscoveryLifecycle.RETRYABLE_SCAN_ERROR_CODES` did not include this code, so
a rate-limited scan start got zero automatic retry and was left entirely to the idle-based scan
watchdog (which can also mistakenly escalate to "manual Bluetooth recovery needed" for what may
just be a transient, self-clearing 30s throttle window).

**Fix:** Added a dedicated `RATE_LIMITED_SCAN_ERROR_CODES` retry family with its own attempt
counter (`scanRateLimitRetryAttempt`), independent of the fast family's 3-attempt budget, backing
off 2s/4s/8s/16s/30s (capped) over up to 6 attempts — long enough to clear a 30s throttle window
without competing with the fast family's short retries for transient hardware errors. The two
scan-retry guard clauses share one extracted `canScheduleScanRetry` predicate rather than each
repeating the same `hardware == null || isStopped || isDiscoverySuspended || !hardware.hasScanner`
condition (a Duplicated-Code/ComplexCondition finding a code-review pass on this change caught —
fixed by extraction instead of by adding another detekt baseline suppression).
`BleTransportDiscoveryLifecycle.kt`, plus new tests in `BleTransportDiscoveryLifecycleTest.kt`.

### 2. `GattNotifyServer` never implemented `onExecuteWrite`

**Skill:** `android-gatt-server` — "`sendResponse()` is REQUIRED for ... `onExecuteWrite`."

**Gap:** The write characteristic is exposed with `PERMISSION_WRITE`. Any external GATT central
(not necessarily this app's own client, which only ever issues `WRITE_TYPE_NO_RESPONSE`) can
attempt a queued/"reliable" write against it, which drives Android to call
`onCharacteristicWriteRequest(preparedWrite = true, ...)` followed by `onExecuteWrite`. With no
override, the default no-op never answers that final request and the remote peer hangs until its
own ATT timeout.

**Fix:** Added `onExecuteWrite` responding with `GATT_SUCCESS`/no payload. `GattNotifyServer.kt`.

### 3. L2CAP read loop had no `catch` for `IOException`/frame-decode failures

**Skill:** `android-17-ble-migration` §1 (the read loop already did the required `-1` clean-EOF
check correctly) combined with `android-bluetooth-sockets`.

**Gap:** The read loop was `try { ... } finally { closeLink(...) }` with **no catch clause**. A
real I/O error (broken pipe, stack teardown) or a `MeshLinkException.TransportFailure` thrown by
`L2capFrameBuffer` on a malformed/oversized length prefix would propagate uncaught out of a
`coroutineScope.launch` running under a bare `SupervisorJob` with **no
`CoroutineExceptionHandler` installed anywhere in the module** — on Android, an uncaught exception
from a top-level `launch` in that shape is fatal to the process. A transient socket error or a
buggy/malicious peer's corrupt frame length could crash the app instead of just closing the link.

**Fix:** Added `catch (e: IOException)` and `catch (e: MeshLinkException.TransportFailure)`
around the read loop, logging and falling through to the existing `finally { closeLink(...) }`.
`BleTransportAdapterL2capSupport.kt`.

### 4. Keepalive-write failures were silently excluded from L2CAP reconnect retry

**Skill:** `android-bluetooth-sockets` (branch on structured error info, don't string-match) —
applied here to the codebase's own internal reason-string classification, which had the same
fragility.

**Gap:** `L2capReconnectGuard.isTransientL2capDisconnect()` allowlists reason strings by prefix
(`"socket closed"`, `"send failed:"`, `"connect failed:"`). The keepalive loop closed links with
reason `"keepalive write failed"`, which matches none of those prefixes, so a keepalive-triggered
disconnect — the same class of transient write I/O failure as a payload send failure — never got
an automatic reconnect retry.

**Fix:** Prefixed the keepalive-failure reason with `"send failed: "` so it's classified
identically to any other transient write failure. `BleTransportAdapterL2capSupport.kt`.

### 5. `DirectProofPowerService` didn't check `BLUETOOTH_CONNECT` before `startForeground()`

**Skill:** `android-17-ble-migration` §7 — Android validates at service-start time that the app
holds every permission its declared `foregroundServiceType` requires; a `connectedDevice`-typed
service without `BLUETOOTH_CONNECT` at that exact moment throws `SecurityException` (a crash, not
a silent failure), including when the user revokes the permission while the service is running.

**Fix:** Added a `BLUETOOTH_CONNECT` check (API 31+) at the top of `onStartCommand`, logging and
calling `stopSelf()`/returning `START_NOT_STICKY` if missing, instead of unconditionally calling
`startForeground()`. `DirectProofPowerService.kt`.

## Code review (Standards + Spec) on this diff

A `code-review`-skill pass (Standards axis vs. `constitution.md`/`CONTRIBUTING.md`/the Fowler smell
baseline, Spec axis vs. this document's "Fixed in this pass" section) was run against
`main...HEAD` after the fixes above landed. Findings and resolutions:

- **Fixed:** the rate-limited scan-retry guard duplicated the fast-retry guard's exact
  four-clause condition and, combined with the new fifth/sixth clause, tripped a new
  `ComplexCondition` finding that the first pass suppressed via another `config/detekt/baseline-
  androidMain.xml` entry. Re-reviewed and fixed properly instead: extracted the shared
  `canScheduleScanRetry` predicate so both guards reuse one three-or-fewer-clause condition, which
  removed the need for a new suppression *and* let a stale pre-existing baseline entry (whose
  condition text no longer matched after the extraction) be deleted rather than accumulate.
- **Fixed:** the original wording claimed tests were "added where behavior changed" for all five
  fixes; only item 1 (and, after this pass, item 4) has host/common-test coverage. Corrected the
  claim above rather than leave an inaccurate spec statement in place, and added the missing
  `L2capReconnectGuardTest` coverage for item 4's reason-string classification (cheap, pure logic,
  already had a matching test file). Items 2/3/5 remain without dedicated unit tests for the
  reasons given above; `:meshlink:koverVerify` still passes because the Android-target compilation
  isn't part of this module's merged coverage report today.
- **Confirmed correct (no changes needed):** all five "Fixed in this pass" items' actual logic
  matches this document's description — including the exponential/capped backoff arithmetic for
  item 1, the SDK-version gate for item 5's permission check, the exact exception types caught for
  item 3, and the exact reason-string prefix match for item 4. No part of the diff touches any
  Deferred item (A–G below); no scope creep found.

Re-verified after the fixes above: `:meshlink:allTests`, `:meshlink:detektAll`,
`:meshlink:apiCheck`, `:meshlink:koverVerify`, and `verifyDocs` all pass.

## Deferred — proposed, not applied in this pass

These require larger, riskier refactors of tested state machines or product decisions the team
should make explicitly (per `AGENTS.md`: *"When a decision is needed, do not choose alone. Present
the available options clearly and concisely, then wait for the user to decide."*). Each is
described with its skill citation, evidence, and a concrete proposed diff so it can be picked up
as follow-on work.

### A. `gatt.close()` timing -- decided: A1, built

**Skill:** `android-ble-gatt-status-133` — *"Never call `close()` while `STATE_CONNECTING` or
`STATE_CONNECTED`; doing so races the native stack and can orphan internal resources."*

**Decision:** A1 (do the refactor now, bundled with B1 since both touch the same connect/teardown
path in `GattNotifyClient`).

**What changed:** `GattNotifySession.close()` (`GattNotifySessionAdapter.kt`) now does only
`connection.close()`; a new `requestDisconnect()` does only `connection.disconnect()`.
`GattNotifyClient.closeInternal(markClosedByOwner, alreadyDisconnected)` gained a second parameter:

- `alreadyDisconnected = true` at the two call sites already inside a confirmed
  `onConnectionStateChange(.., STATE_DISCONNECTED)` callback (the give-up branch, and the
  retryable-connect-failure branch's inline close) — the connection is already torn down there, so
  closing immediately is correct per the skill ("close() ... regardless of the status code" once
  `STATE_DISCONNECTED` has actually been reported).
- The default `alreadyDisconnected = false` (every other call site: `onServicesDiscovered`
  failures, `onDescriptorWrite` failure, a rejected inbound frame, a write timeout, and the public
  `close()`) now calls `requestDisconnect()` and defers the real `close()` behind a bounded
  `disconnectConfirmTimeoutMillis` (2s in production) via an injectable `teardownScope`, instead of
  calling both synchronously back-to-back.

**Known, disclosed limitation:** this does not correlate a later `onConnectionStateChange`
callback back to the specific session being torn down to close exactly on confirmation --
`session` is nulled immediately (as before) so a fresh reconnect can start right away, and the same
`GattNotifySessionListener` instance is reused across reconnects, so there is no reliable way to
tell "this STATE_DISCONNECTED event is for the old, closing session" from "this is for a brand new
one" from the callback parameters alone. Building genuine event-based confirmation would need a
larger identity-tracking change across sessions/callbacks; the bounded-timeout approach instead
directly fixes the core bug (no more synchronous `disconnect()`-then-`close()`) with much less risk
to the existing state machine. Tests assert the new call ordering
(`requestDisconnectCalls` before `closeCalls`) but, per the same disclosed limitation as items
2/3/5 above, cannot assert the real-world timing gap deterministically without wall-clock waits or
a fake-clock DI seam this test file doesn't already have.

### B. GATT connect retry backoff -- decided: B1, built

**Skill:** `android-ble-gatt-status-133` §5 — *"Wrap `connectGatt` in a bounded retry loop with
exponential backoff (e.g. 2s, 4s, 8s...)."*

**What changed:** `GattNotifyClient`'s retryable-connect-failure branch now computes
`connectRetryDelayMillis * (1L shl (connectRetryAttempts - 1))` instead of a fixed delay --
400ms/800ms across the two allowed attempts (`MAX_CONNECT_RETRY_ATTEMPTS = 2`).

### C. `DirectProofPowerService` is unwired dead code

**Skill:** `android-17-ble-migration` §4/§7 (foreground-service lifecycle correctness).

**Finding:** Zero call sites start this service anywhere in the app (`AndroidPlatformServices`'s
`stopPowerMitigation` hook is a no-op and nothing calls `ContextCompat.startForegroundService`
against it), yet the reference app's own user-facing guidance text promises this mitigation is
active. This is a product/feature-completeness gap, not a BLE-correctness bug per se — flagging it
here because wiring it up must be done carefully (start it only from a point reachable while the
app is visible, e.g. `MainActivity`, never from boot/alarm, so it keeps While-In-Use eligibility
per skill §4) once the fix from item 5 above is in place. Needs a product decision on whether this
mitigation should ship at all before wiring it in.

### D. No `PendingIntent`-based background scanning path — decided: D1, built with a narrowed scope

**Skill:** `android-17-ble-migration` §6 — modern OEM Doze increasingly suspends non-batched,
callback-based scanning once the screen is off, even with a `ScanFilter`; `PendingIntent`-based
scanning lets the OS wake the process on a match without a persistent scan.

**Decision:** D1 (build it). The original framing of this item bundled two distinct capabilities
under one description — (a) a supplementary scan channel that keeps delivering matches to the
*current, still-running* process even when OEM Doze suspends regular `ScanCallback` delivery once
the screen is off, and (b) waking a *fully-killed* process from a manifest-declared receiver and
resuming meshing without a live `MeshEngine` instance. Only (a) was built in this pass; (b) was
intentionally scoped out rather than silently decided, for the reasons below.

**What was built:** `BackgroundScanSupport.kt` adds a second, supplementary scan registration
using `BluetoothLeScanner.startScan(filters, settings, pendingIntent)` alongside (not replacing)
the existing `ScanCallback`-based scan driven by `BleTransportDiscoveryLifecycle`. It reuses the
same mandatory `buildScanFilters()` ScanFilter, uses `SCAN_MODE_LOW_POWER` per the skill's own
example, and funnels every delivered `ScanResult` through the exact same `handleScanResult()` used
by the callback path, so `PeerRegistry`/`BleTransportLinkRegistry` stay a single source of truth
regardless of which channel delivered a given result. The receiver is registered dynamically
(`Context.registerReceiver`, `RECEIVER_NOT_EXPORTED` on API 33+) in `startTransport()` and
unregistered in `stopTransports()` — the same pattern as item E's Bluetooth-toggle receiver.

**What was deliberately not built (scope boundary, not a gap):** waking a fully-killed process via
a manifest-declared `BroadcastReceiver` and resuming meshing without an existing `MeshEngine`
instance. MeshLink's current architecture has no facility for a receiver to reconstruct engine
state, decide what to do with a discovered peer, or re-establish a connection headlessly — that
is a standalone background-service architecture question (does MeshLink need a host-app-independent
headless mesh service at all? what does it do on a cold wake with no UI and no existing session?)
far larger than a BLE scanning fix, and building it silently as a side effect of this decision would
be exactly the kind of unreviewed architecture call `AGENTS.md` asks not to make alone. If
dead-process-wake meshing is wanted, it needs its own dedicated design conversation and likely its
own Decision-lettered item.

**Testing:** `backgroundScanAction(packageName)` (the only pure, non-Android-API-calling piece of
this change) has host-test coverage in `BackgroundScanSupportTest.kt`. The receiver
registration/PendingIntent construction/scan start-stop calls are platform glue with no existing
test harness in this module, the same disclosed limitation as items 2/3/5 in "Fixed in this pass"
above.

### E. No Bluetooth-toggle (`ACTION_STATE_CHANGED`) receiver — decided: E1, built

**Skill:** `android-17-ble-migration` §3.

**Decision:** E1 (add the debounced receiver). Built as `BluetoothStateChangeSupport.kt`: a pure,
host-tested `BluetoothStateChangeDebouncer` (generation-counter debounce, collapsing rapid
off/on/off/on toggling into a single restart) plus `BroadcastReceiver` glue in `BleTransportAdapter`
following the same register-in-`startTransport()`/unregister-in-`stopTransports()` pattern as item
D's background-scan receiver. On `BluetoothAdapter.STATE_ON`, schedules a 1.5s-debounced
`refreshDiscoveryState()` call so discovery proactively resumes after the user re-enables
Bluetooth, rather than waiting for some unrelated caller to invoke `start()`/`refresh()` again.

### F. Windowed GATT write pipeline intentionally pipelines up to 4 in-flight writes

**Skill:** `android-ble-gatt-sequential-queue` — "never call two `BluetoothGatt` methods without
awaiting the first operation's callback."

**Finding:** `GattNotifyClient`'s `WRITE_WINDOW_SIZE = 4` deliberately violates this rule for
throughput, with extensive existing doc comments explaining the tradeoff and a busy-retry
mechanism for the `false`-return case. This is a **known, mitigated, intentional deviation** (not
a gap) — recorded here only so a future reader doesn't mistake it for an oversight when they next
touch this file, and so the residual risk (this assumes local stacks always signal busy via a
`false` return rather than silently corrupting under concurrent in-flight writes — an assumption,
not a guarantee, across all OEM stacks) is visible alongside the rest of this review.

### G. No structural GATT operation queue at the `GattConnectionAdapter` layer

**Skill:** `android-ble-gatt-sequential-queue` — recommends a generic `Channel`/`Mutex` +
`CompletableDeferred` queue so *any* future GATT operation added to a session automatically gets
one-at-a-time serialization.

**Finding:** Today's correctness depends entirely on each call site in `GattNotifyClient`'s
lifecycle state machine (`GattNotifyLifecycleState.kt`) remembering to gate on the right flag
before issuing the next `BluetoothGatt` call — which it currently does correctly for the fixed
connect → MTU → discover → subscribe → write sequence, but has no structural guard against a
future addition (e.g. a read operation, or a second concurrent write path) violating the rule.
Recommend introducing a generic queue at the `GattConnectionAdapter` layer the next time this
lifecycle needs a new operation type added, rather than as a standalone refactor with no immediate
behavioral motivation.

## Confirmed already correct (no action needed)

Recorded so these strong points aren't re-litigated in a future pass:

- **Stable peer identity, not MAC-keyed** (`android-17-ble-migration` §8): `hintPeerId` is derived
  from the advertised key hash; MAC address is treated purely as an ephemeral connection-binding
  key in `PeerBindings`, with conflict logging already in place for the MAC-rotation case.
- **`connectGatt(..., autoConnect = false, ...)`** at every call site — reconnection is
  implemented as fresh direct connects, never relying on system-level `autoConnect = true`
  background reconnection.
- **GATT status 133/147 correctly grouped as one retryable family**, with the reset-on-success and
  bounded-retry-cap behavior the skill recommends; 135 correctly left unhandled since every
  connection is direct.
- **Memory-safe API 33+ overloads** used throughout for characteristic/descriptor reads, writes,
  and change notifications, with a clean dual-overload split for `minSdk < 33`, and defensive
  `.copyOf()` on values crossing the platform/app boundary.
- **`BluetoothGattCallback` kept lean**: all real work is dispatched through a non-blocking
  channel-backed `InboundFrameQueue`, never performed directly on the callback thread.
- **`sendResponse()`** called for every other required `BluetoothGattServerCallback` method
  (read/write/descriptor requests), and `BluetoothManager.getConnectedDevices(GATT)` used instead
  of the `BluetoothGattServer` methods that throw `UnsupportedOperationException`.
- **PSM exchange** carried in-band in the app's own BLE advertisement payload, restricted to the
  dynamic LE range, exactly as the sockets skill requires.
- **`cancelDiscovery()` before L2CAP `connect()`**, blocking `accept()`/`connect()` always run off
  the main/callback thread, and correct close-ordering for `BluetoothServerSocket` vs. accepted
  sockets (no reliance on cascading closure).
- **L2CAP frame/chunk sizing** correctly derived from the OS-negotiated
  `maxReceivePacketSize`/`maxTransmitPacketSize` (not hardcoded ATT-specific 244/495-byte
  constants, which are correctly reserved for the GATT-notify fallback bearer only, where they
  *do* apply and are used).
- **Mandatory `ScanFilter` for background scanning**, deliberate `SCAN_MODE_*` selection per power
  tier, and a self-healing idle-scan watchdog explicitly tuned to stay outside the ~30s scan
  restart throttle window.
- **No BAL violations, no loopback-socket usage requiring `USE_LOOPBACK_INTERFACE`, no boot/alarm
  triggered foreground-service starts** anywhere in the codebase.

## Summary table

| # | Finding | Severity | Status |
|---|---|---|---|
| 1 | Scan rate-limit code had no dedicated backoff | Blocker | **Fixed** |
| 2 | `onExecuteWrite` unimplemented in GATT server | Blocker | **Fixed** |
| 3 | L2CAP read loop missing `catch` (crash risk) | Blocker | **Fixed** |
| 4 | Keepalive failures excluded from reconnect retry | Gap | **Fixed** |
| 5 | Missing `BLUETOOTH_CONNECT` check before `startForeground` | Blocker | **Fixed** |
| A | `gatt.close()` not gated on `STATE_DISCONNECTED` | Blocker | Decided A1 -- **Fixed** |
| B | Connect retry backoff is fixed, not exponential | Gap | Decided B1 -- **Fixed** |
| C | `DirectProofPowerService` is unwired dead code | Gap | Decided C2 -- **Fixed** (removed) |
| D | No `PendingIntent` background scanning | Backlog | Decided D1 -- **Fixed** (supplementary scan channel; dead-process wake intentionally out of scope, see above) |
| E | No Bluetooth-toggle receiver | Note | Decided E1 -- **Fixed** |
| F | Windowed write pipeline (4 in-flight) | Note | Documented, intentional |
| G | No generic GATT operation queue | Note | Documented, deferred until needed |
