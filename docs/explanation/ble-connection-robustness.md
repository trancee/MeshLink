# BLE connection robustness: flakiness findings and fixes

## Status

Implemented on branch `fix/ble-connection-robustness` (PR #70). This document
explains the "BLE-flakiness" symptoms observed during physical multi-device
testing (Peer Lost/Found cycling, `HOP_SESSION_FAILED
stage=transport.handshake.timeout` loops, and the need for artificial settle
delays in the reference app), the root causes identified for each, the fixes
applied, and their risks/trade-offs. It complements
[hop-session-replay-protection.md](hop-session-replay-protection.md), which
covers a related but distinct `AEADBadTagException`/nonce-desync bug found
during the same broader BLE-robustness effort.

## Background: why this investigation happened

Running the reference app's guided-exchange scenario matrix across 2 Android
+ 2 iOS physical devices surfaced a pattern of transient instability that was
initially dismissed as "BLE is just flaky" -- connections cycling between
lost and found every 5-10 seconds, occasional `HOP_SESSION_FAILED
stage=transport.handshake.timeout` loops (most persistently on a Nokia X20 +
iPhone 12 mini pairing), and two spots in the reference app
(`GuidedFirstExchangeViewModel.kt`) that only worked reliably with an
artificial `delay(3_000L)` inserted after `resume()`/`forgetPeer()` before the
next send.

Rather than accept this as unavoidable RF physics, a targeted investigation
traced each symptom to its exact code path. The result: **most of the
observed flakiness was fixable software behavior**, not radio-layer physics.
Six root causes were identified, seven of them addressed by concrete code
changes (eight fixes total, since one recommendation split into two related
changes). This document covers each symptom, its root cause, and its fix in
turn.

## Symptom 1: Peer Lost/Found cycling every 5-10 seconds

### Root cause

Every Android GATT connection requested `CONNECTION_PRIORITY_HIGH`
unconditionally, regardless of the device's active power tier
(`GattConnectionAdapter.kt`, `GattNotifyClient.kt`). `CONNECTION_PRIORITY_HIGH`
trades a much shorter supervision timeout (roughly 2 seconds) for lower
latency. In a dense environment with multiple devices contending for the same
2.4GHz spectrum, momentary radio contention easily exceeds a 2-second
supervision window, causing the link supervision timer to expire and the
connection to drop -- even though the peer is still physically in range and
otherwise healthy.

This was compounded by two paths that fired `PeerLost` **immediately, with
zero debounce**, on every such disconnect:

- `GattSideLinkCoordinator.handleDisconnected()` (GATT side-link path)
- `BleTransportAdapterL2capSupport.closeLink()` (L2CAP path)

And by `L2capReconnectGuard`, which only allowed **one** automatic L2CAP
reconnect attempt per peer for the entire transport lifecycle (the
retried-peer set was only ever cleared in `stopTransports()`) -- so any peer
that needed more than one retry to recover fell straight through to a full
`PeerLost`/rediscovery cycle.

### Fix

**1. Tier GATT connection priority by power mode.** `PowerProfile` (in
`PowerMonitor.kt`) now carries a `connectionPriority` field, tiered:
`PERFORMANCE` → `CONNECTION_PRIORITY_HIGH`, `BALANCED` →
`CONNECTION_PRIORITY_BALANCED`, `POWER_SAVER` → `CONNECTION_PRIORITY_LOW_POWER`.
`GattNotifyClient` now reads this live, via a `connectionPriorityProvider: ()
-> Int` lambda threaded through `GattSideLinkCoordinator`/
`createGattSideLinkClient`/`BleTransportAdapter`, at the moment each
connection reaches `STATE_CONNECTED` -- not baked in at client construction
time, since a client can outlive a power-mode change. This means only devices
actually running in `PERFORMANCE` mode pay the short-supervision-timeout
trade-off; `BALANCED` and `POWER_SAVER` tiers get a longer, contention-tolerant
supervision window by default.

**2. Debounce `PeerLost` (GATT side).** `GattSideLinkCoordinator` now delays
the `PeerLost` signal by `PEER_LOST_DEBOUNCE_MILLIS` (4 seconds) after a GATT
disconnect, scheduled on the transport's `CoroutineScope`. If the peer
reconnects (`ensureStarted()` is called again for the same hint peer ID)
before the debounce window elapses, the pending signal is cancelled and
`PeerLost` never fires. A brief radio blip that recovers within 4 seconds is
now invisible to callers/UI instead of surfacing as a visible Lost→Found
cycle. The debounce window is exposed as
`GattSideLinkCoordinatorDependencies.peerLostDebounceMillis` (default 4000,
overridable) specifically so tests can use `0L` with an unconfined dispatcher
scope to observe the debounced signal deterministically, without depending on
`kotlinx-coroutines-test`.

**3. Count-limited backoff for L2CAP reconnects.** `L2capReconnectGuard`
replaced its one-shot-per-lifecycle retry with a 3-attempt backoff schedule
(500ms, 1s, 2s), reset back to the full budget on any successful reconnect
(`registerConnectedSocket`, the single choke point both the initial-connect
and the reconnect path funnel through). A peer with a genuinely rough patch
now gets three increasingly-spaced chances to recover before the transport
gives up and surfaces `PeerLost`.

### Trade-off

Lower connection priority means slightly higher latency for peers not
running in `PERFORMANCE` mode, and the 4-second GATT debounce means a
*genuine* peer loss takes up to 4 seconds longer to surface than before.
Both are deliberate: the goal is absorbing transient blips silently while
still surfacing a real loss "promptly enough not to feel unresponsive" (the
standard this investigation was scoped against), not eliminating latency
entirely.

## Symptom 2: `HOP_SESSION_FAILED stage=transport.handshake.timeout` looping

### Root cause

Traced the exact call chain: `ensureHopSession()` →
`initiateReservedHopSession()` → send handshake message1 →
`awaitSessionEstablishment()` → `withTimeoutOrNull(HANDSHAKE_TIMEOUT)` on the
pending handshake's completion deferred
(`MeshEngineSessionSupport.kt`) → timeout → `failPendingInitiatorHandshake(stage
= "transport.handshake.timeout")`.

`HANDSHAKE_TIMEOUT` was 3 seconds. The full GATT side-link setup sequence
(connect → MTU negotiation → service discovery → CCCD write,
`GattNotifyLifecycleState.kt`) can take several seconds on its own, and was
observed to exceed 3 seconds consistently on slower/older BLE hardware (most
persistently an iPhone 12 mini). Once the 3-second window expired, the whole
handshake attempt was declared failed and had to restart from scratch --
hence the "looping".

This was compounded by `isTransientLinkNotReady()` (in both
`MeshEngineSessionSupport.kt` and `MeshEngineResponderHandshakeSupport.kt`),
a guard that decides whether a `TransportSendResult.Dropped` reason should be
retried within the handshake window. It matched **only** the literal string
`"L2CAP connection is not ready"`. Any GATT-specific "not ready" wording
(e.g. `"client not ready"`, emitted while a GATT side-link is still mid
connect/MTU/discovery) did not match, so a send attempt that failed for a
GATT-specific reason was not retried within the window at all, wasting most
of the window doing nothing before ultimately timing out anyway.

### Fix

**1. Widen the transient-not-ready guard.** Both copies of
`isTransientLinkNotReady()` now match any bearer's "not ready" wording
(`reason.contains("connection is not ready", ignoreCase = true) ||
reason.contains("client not ready", ignoreCase = true)`), not just the
L2CAP-specific string. Deliberately excluded: permanent/configuration
failures like `"transport is not implemented"` or `"peer has not been
discovered"`, which still correctly fall through as non-retryable.

**2. Increase handshake timing.** Three related constants were increased to
give slower hardware realistic headroom:

| Constant | Before | After | File |
|---|---|---|---|
| `HANDSHAKE_TIMEOUT` | 3s | 6s | `MeshEngineSessionSupport.kt` |
| `MESSAGE2_SEND_RETRY_WINDOW` | 3s | 6s | `MeshEngineResponderHandshakeSupport.kt` |
| `HANDSHAKE_RETRY_ATTEMPTS` | 3 | 5 | `MeshEngineSessionSupport.kt` |

### Trade-off

A genuinely unreachable peer now takes longer to be declared unreachable (up
to 6 seconds per attempt, across up to 5 attempts, versus 3 seconds/3
attempts before). This is an intentional trade of "fails fast" for "succeeds
more often on real, if slow, hardware" -- 3 seconds was measured to be
consistently too short for at least one real device pairing, so the previous
"fast failure" was in practice mostly firing on healthy links that just
needed a bit longer, not on genuinely dead ones.

## Symptom 3: duplicate `HandshakeMessage2` / `AEADBadTagException`

This symptom has two parts. The data-frame nonce-desync case is a distinct,
already-fully-fixed issue covered separately in
[hop-session-replay-protection.md](hop-session-replay-protection.md) (explicit
sequence numbers + a 64-entry sliding replay window, replacing an older
implicit-nonce-counter design). This document covers the remaining gap: an
in-flight race in handshake message2 processing itself.

### Root cause

MeshLink's redundant GATT/L2CAP side-link transports can deliver the same
handshake message2 frame twice. Two mechanisms already existed to tolerate
this:

- `lastInitiatorMessage2` tracking (`MeshEngineSessionRegistry.kt`) safely
  ignores a duplicate delivery **after** the handshake has already completed.
- Duplicate message1 handling on the responder side is analogous.

But neither covered the case where **two copies of message2 arrive while the
handshake is still pending** (before `completeInitiatorHandshake()` runs).
Both deliveries would find the same `PendingInitiatorHandshake` object via
`pendingInitiatorHandshake()` and both would call
`processMessage2AndCreateMessage3()` on the *same*
`NoiseXXHandshakeManager` instance concurrently. The Noise state machine is
not designed to be re-entered from a second call while the first is still in
progress; the second call throws `InvalidStateTransition`, which was caught
by a `runCatching` block that aborted the entire handshake -- turning a
harmless duplicate delivery into a hard failure requiring a full retry.

### Fix

`PendingInitiatorHandshake` (`MeshEngineInternalModels.kt`) now carries a
`processing: Boolean` flag. `MeshEngineSessionRegistry.tryBeginProcessingMessage2()`
atomically tests-and-sets this flag under the registry's existing
`sessionMutex`, returning `true` only to the first caller that claims it.
`MeshEngineInitiatorHandshakeSupport.handleHandshakeMessage2()` calls this
before invoking `processMessage2AndCreateMessage3()`; if it returns `false`
(a concurrent duplicate is already being processed), the second delivery is
safely dropped -- logged at `transport.handshake.message2.duplicateInFlightIgnored`
-- instead of racing the shared `NoiseXXHandshakeManager` instance.

Because every failure path for a pending initiator handshake
(`failPendingInitiatorHandshake` and friends) removes the
`PendingInitiatorHandshake` from the registry entirely, the `processing` flag
never needs to be reset on failure -- a failed handshake's pending object is
simply discarded, and a subsequent legitimate retry constructs a fresh one
with `processing = false`.

### Trade-off

None significant: this closes a race window that could previously only ever
make things worse (turning a harmless duplicate into a hard failure), with no
new behavior on the non-racing path.

## Symptom 4/5: `pause()`/`resume()`/`forgetPeer()` needing artificial settle delays

### Root cause

`resume()` (`MeshEngineLifecycleSupport.kt`) and `forgetPeer()`
(`MeshEnginePeerForgetSupport.kt`) both return as soon as the mesh
runtime/transport is restarted -- **before** any peer route, hop session, or
BLE link is re-established. Callers that immediately try to send afterward
race the physical reconnection. The reference app's `GuidedFirstExchangeViewModel.kt`
worked around this for its `direct-pause-resume` scenario with a flat
`delay(PAUSE_RESUME_SEND_SETTLE_MILLIS)` (3 seconds) before sending -- a
guess that happened to be long enough on the devices it was tuned against,
but with no principled connection to how long reconnection actually takes.

An awaitable "peer is ready" signal already existed in spirit --
`ROUTE_DISCOVERED` and `HOP_SESSION_ESTABLISHED` diagnostics fire once a
peer's hop session completes -- but nothing surfaced it as something a
caller could directly wait on. Notably, the `direct-trust-reset-recovery`
scenario's *second* send already did the right thing by construction: because
it's driven by the same snapshot-flow `collectLatest` loop that redrives the
whole scenario state machine, it naturally re-evaluates and proceeds only
once `snapshot.peers` reports `PeerConnectionSnapshotState.CONNECTED`. The
`direct-pause-resume` scenario's single `scope.launch { ... }` block, by
contrast, ran once and used a flat delay instead of that same pattern.

### Fix

Added a suspend helper in `GuidedFirstExchangeViewModel.kt`:

```kotlin
private suspend fun awaitPeerConnected(peerId: String?): Unit {
    withTimeoutOrNull(PEER_READY_AWAIT_TIMEOUT_MILLIS) {
        meshLinkController.snapshot
            .filter { snapshot ->
                snapshot.peers.any {
                    (peerId == null || it.peerId == peerId) &&
                        it.connectionState == PeerConnectionSnapshotState.CONNECTED
                }
            }
            .first()
    }
}
```

The `direct-pause-resume` scenario now calls `awaitPeerConnected(peer.peerId)`
after `resume()` instead of `delay(PAUSE_RESUME_SEND_SETTLE_MILLIS)`. This
reacts to the controller's own snapshot flow as soon as the link is actually
ready (which is often faster than the fixed 3-second guess), while still
bounding the wait with a 15-second timeout (`PEER_READY_AWAIT_TIMEOUT_MILLIS`)
so automation can't hang forever if the link never recovers.

The `direct-trust-reset-recovery` scenario's first send (before
`forgetPeer()`) retains its `delay(TRUST_RESET_DELIVERY_SETTLE_MILLIS)`: that
delay confirms the *previous send actually transmitted* before tearing down
the peer, which is a different property than "is the peer connected" and has
no equivalent snapshot-flow signal to wait on instead.

### Trade-off

`awaitPeerConnected()` is currently implemented at the reference-app
view-model layer (observing the existing `ReferenceMeshLinkController.snapshot`
`StateFlow`), not as a new public API surface on the core engine. This was a
deliberate, narrower-scope choice: it fixes the concrete race without
committing the engine to a new awaitable-readiness API contract that would
need its own design review. A future engine-level `awaitFirstPeerReady()` (or
an exposed `peerConnectionState(peerId): StateFlow<PeerConnectionState>`)
remains a reasonable follow-up if other callers need the same pattern outside
the reference app.

## Symptom 6: first-ever device pairing needing a long warm-up

This symptom was determined to be **mixed**: partially genuine OS-level BLE
cache/advertisement-cache warm-up (a cold Android/iOS BLE stack simply takes
longer to build up its scan-result cache on first contact with a new device,
which is outside MeshLink's control), and partially something the awaitable
readiness fix (symptom 4/5) helps with indirectly, since callers now wait for
an actual ready signal instead of timing out on a fixed guess during that
initial slow period. No dedicated fix was scoped for the OS-level portion of
this symptom; it is called out here for completeness since it was part of the
original investigation.

## Symptom 7: a Noise-XX handshake *responder* can permanently deadlock if its peer's scanner never discovers it

Found 2026-07-11 while investigating why a specific device (Huawei WAS-LX1A,
API 26) was substantially interacting with other fleet devices in
simultaneous full-mesh runs but never once completing a confirmed round trip
with any of them (see `meshlink-benchmark/history.md`'s 2026-07-11 entries).

### Root cause

MeshLink's Android GATT-notify side link piggybacks its `LinkIdentity`
announcement (`GattNotifyClient.write()`'s `identityAnnounced` guard) onto
the *first real payload write* on that link, rather than sending it
proactively as soon as the link becomes ready. This is harmless for a device
that is the Noise-XX handshake *initiator* towards a given peer (the
deterministic key-hash tie-break -- see
`shouldLocalPeerInitiateL2capConnection`/`shouldInitiateHandshakeTowards` --
decides this per pairing), since an initiator always has a real handshake
message to write almost immediately, and the identity rides along with it.

But a device that is the handshake *responder* towards a given peer
legitimately never writes anything first -- by protocol design, a Noise-XX
responder waits for the initiator's message1. If that peer's own BLE
scanner also never independently discovers the responder's advertisement
(confirmed as a real, reproducible occurrence in this same investigation --
see the scan-wedge findings in the "Platform limits" section below, which hit
two different devices' scanners across repeated runs), the peer has no way
to learn the responder's identity at all: not from its own scan (wedged),
and not from the responder's GATT link (never announced, because the
responder never had a reason to write). The pairing deadlocks permanently --
the underlying GATT connection is fully established (connected, MTU
negotiated, notifications subscribed) and stays that way for the rest of the
test window, but zero further protocol activity ever happens on it.

Live-hardware log evidence (isolated 2-device pair, Huawei WAS-LX1A as GATT
client/Noise responder, Xiaomi 23129RN51X as GATT server/Noise initiator,
Xiaomi's scanner wedged that run): Xiaomi's logcat shows its GATT *server*
accepting WAS-LX1A's inbound connection, negotiating MTU, and completing the
CCCD descriptor write (WAS-LX1A subscribing to notifications) -- then
nothing else, ever, on either device's log for the rest of the 45s run.
WAS-LX1A's own logcat confirms `GATT notify side link ready for <peer>` fired
normally, but no `GATT side-link send attempt` / `BEARER decision` line ever
followed, because nothing ever called `write()` on that ready client.

This is the same class of asymmetric-discovery deadlock that
`TransportEvent.InboundPeerClaimed` (`transport/BleTransport.kt`) already
exists to mitigate (its own doc comment describes "neither side ever calls
prewarmHopSession() and the pairing deadlocks with no handshake ever
attempted"), but reached through a different path: `InboundPeerClaimed`
assumes the peer *can* still learn the responder's identity some other way
once it claims the inbound connection, but that assumption silently failed
here because the responder's own GATT link never carried the identity
announcement that `InboundPeerClaimed`'s handling depends on.

### Fix

Extracted the identity-announce logic already used by `write()` into
`announceIdentityIfNeededLocked()` and added `ensureIdentityAnnounced()`,
called from `GattNotifyClient`'s `onDescriptorWrite` ready callback --
immediately once the link becomes ready, independent of whether this device
ever calls `write()` with a real payload. A pure Noise-XX responder now
announces its identity as soon as its side of the GATT link is ready,
closing the deadlock regardless of the peer's own scan-discovery state.

The proactive announce runs on a new overridable `identityAnnounceScope`
(matching the existing `connectRetryScope`/`teardownScope` testability
pattern), launched rather than awaited from the synchronous
`BluetoothGattCallback`-driven `onDescriptorWrite`. It reuses the same
`writeMutex` and `identityAnnounced` guard as `write()`, so a real payload
write that races the proactive announcement never double-announces.

### Trade-off

A failed proactive announce attempt (e.g. a transient write failure right as
the link becomes ready) has no dedicated retry loop of its own -- it relies
on the next real `write()` call, or the next successful reconnect, to retry
the announcement, matching every other best-effort recovery path already in
this class. This was judged acceptable because the alternative (a permanent,
silent deadlock with a fully-connected but otherwise-dead GATT link) is
strictly worse, and a genuinely repeated write failure on an established link
is already handled by this class's existing close-and-reconnect paths.

### Validation

Live hardware, same fleet used in the original 2026-07-11 investigation:

- Before the fix: isolated 2-device pairs failed 1/2 (Huawei+Xiaomi) and 0/2
  (Huawei+Samsung), both times with the exact "ready but zero further
  activity" signature above on at least one side.
- After the fix: 6/6 confirmed round trips across 4 repeated Huawei+Xiaomi
  isolated pairs and 3/3 across repeated Huawei+Samsung isolated pairs -- a
  clean reversal on both previously-failing pairings.
- A full 5-device `--full-mesh` rerun after the fix still showed the
  already-documented RF-congestion-driven failures at that fleet size (`4/20`
  confirmed, `HOP_SESSION_FAILED stage=transport.handshake.message1.send`
  after a real, logged send *attempt* -- not the silent no-attempt deadlock
  this fix targets). This fix closes the silent-deadlock failure mode
  specifically; it does not change the separate, already-documented N-way RF
  congestion behavior at larger fleet sizes.

## What was *not* changed: BLE pairing/bonding

During this investigation it was confirmed (and is worth stating explicitly,
since it's a common point of confusion) that **MeshLink uses no OS-level BLE
pairing/bonding APIs at all** -- no `createBond`, no `BOND_BONDED` checks, no
encrypted GATT characteristics (iOS explicitly sets `encryptionRequired =
false`). All security on top of the raw BLE transport is provided by the
app-layer Noise-XX handshake (see [the trust model](trust-model.md)), not by
OS-level pairing. The connection-cycling and handshake-timeout symptoms
described in this document are generic BLE connection-establishment/radio
behavior, unrelated to pairing/bonding.

## Dense-environment context

`PowerPolicy.kt` already exposes tiered knobs per power mode
(`advertisementIntervalMillis`, `scanDutyCyclePercent`,
`connectionIntervalMillis`, `maxConnections`, `chunkBudgetBytes`), and EU
regulatory clamping on advertisement/scan parameters. Prior to this work,
`connectionIntervalMillis` was computed and emitted in diagnostics but never
actually applied to a live GATT connection -- the connection-priority tiering
fix (symptom 1) is what closes that specific gap; the GATT connection
priority value now genuinely reflects the active power tier instead of being
hardcoded regardless of it. Android's scan-mode mapping (`PowerMonitor.kt`:
`PERFORMANCE` → `SCAN_MODE_LOW_LATENCY` continuous, `BALANCED` →
`SCAN_MODE_BALANCED` ~50% duty cycle, `POWER_SAVER` → `SCAN_MODE_LOW_POWER`
~10% duty cycle) combined with the previously-hardcoded
`CONNECTION_PRIORITY_HIGH`'s short supervision timeout was identified as the
single most likely amplifier of radio contention in a dense, many-device
environment -- exactly what symptom 1's fix targets.

## Testing

- `./gradlew :meshlink:jvmTest` -- covers `L2capReconnectGuardTest` (new
  count-limited-backoff semantics) and the full common-module handshake/
  session test suite (including the widened not-ready guard and the message2
  in-flight-race fix).
- `./gradlew :meshlink:testAndroidHostTest` -- covers the renamed
  `requestConnectionPriority(priority: Int)` (was
  `requestHighConnectionPriority()`) on `GattConnectionAdapter` and
  `GattNotifySession`, and `GattSideLinkCoordinatorTest`'s new PeerLost
  debounce assertions (using an unconfined `CoroutineScope` and
  `peerLostDebounceMillis = 0L` to observe the debounced signal
  deterministically without depending on `kotlinx-coroutines-test`).
- `./gradlew :meshlink:compileKotlinIosSimulatorArm64` and
  `:meshlink-reference:compileKotlinIosSimulatorArm64` -- confirm the
  commonMain changes (widened retry guard, message2 race fix, diagnostic
  severity change, reference-app `awaitPeerConnected()`) compile cleanly for
  iOS, since none of those changes are Android-specific.

Physical device re-verification of the full scenario matrix (the workflow
used to originally surface these symptoms) is recommended before merging
this work, to confirm the fixes measurably reduce the visible flakiness on
real hardware, but was left to reviewer discretion given the scope of the
automated test coverage above.

## Platform limits on concurrent GATT/L2CAP connections (research, 2026-07-11)

Produced while investigating the 2026-07-11 simultaneous 5-device fleet run
(see `meshlink-benchmark/history.md`'s 2026-07-11 entry) to understand
whether the fleet's observed handshake failures under N-way simultaneous
load could be a hard connection-count ceiling rather than a
negotiation/timing issue.

### There is no public Android API to query or raise the limit

There is no `BluetoothAdapter`/`BluetoothManager` method that reports "how
many more GATT connections can I open" and no way for an app to raise the
ceiling. Apps only discover it empirically, by having a `connectGatt()` call
fail or hang once the ceiling is reached.

### The limit is a global, per-device (not per-app) host-stack constant

Android's Bluetooth host stack (Bluedroid, and its successor Fluoride/
Gabeldorsche, both living in `packages/modules/Bluetooth` / historically
`system/bt`) hardcodes a maximum number of simultaneous LE ACL links shared
by *every app on the device*, not a per-app budget. The two governing
constants are `GATT_MAX_PHY_CHANNEL` (GATT/ATT layer) and `MAX_L2CAP_LINKS`
(link layer) -- both were historically set to `7` and are compiled into the
Bluetooth mainline module, independent of what the underlying radio/chipset
can actually support. A commit in `platform/external/bluetooth/bluedroid`
titled "LE: Increase number of simultaneous connections" raised an earlier
`4`-connection ceiling (Android 4.3-era) to what became the long-standing
`7`; at least one later report describes `MAX_L2CAP_LINKS` as `13` on a
specific Android 10 build, so the exact value has continued to drift across
AOSP releases and is not guaranteed stable across Android versions.
(Sources: <https://stackoverflow.com/questions/41365009/what-is-the-max-concurrent-ble-connections-android-m-can-have>,
<https://stackoverflow.com/questions/64360109/maximum-concurrent-bluetooth-le-devices-on-ios-and-android>,
<https://github.com/dariuszseweryn/RxAndroidBle/issues/696>.)

### OEMs frequently patch the constant, so it is genuinely per-device

Several independent reports agree that some OEMs (Samsung specifically named
in multiple sources) raise the AOSP default to match what their Bluetooth
chipset can actually sustain (one report cites a Samsung Galaxy Tab A 10.1
handling 15 concurrent connections unmodified), while stock/Pixel-class
devices stay at the AOSP default. Google's own AOSP guidance for Android 8+
is that vendors should stop patching this value, but adoption is inconsistent
in practice. **There is no way to determine a specific device's real ceiling
other than testing that device.**

### L2CAP CoC channels ride the same ACL link, plus their own per-request cap

An L2CAP LE Credit-Based Connection Request can ask for up to 5 channels in
one request per the Bluetooth Core specification, but every LE L2CAP CoC
channel to a given peer still consumes that peer's single shared ACL link
slot (one of the `MAX_L2CAP_LINKS`/`GATT_MAX_PHY_CHANNEL` budget) -- multiple
L2CAP channels to the *same* peer do not cost multiple ACL-link slots, but a
separate peer always does.

### Adjacent, separately-documented throttles that also apply per fleet device

These are not connection-count limits but were confirmed relevant during the
same investigation and can independently starve handshake attempts in a
congested fleet, so are worth listing alongside the connection ceiling:

- **Scan-start throttle**: Android silently rate-limits `startScan()` to 5
  calls per 30 seconds per app (undocumented, no `onScanFailed` callback --
  scanning just silently stops finding new results). Already covered by the
  `android-17-ble-migration` skill and `ScanResultSupport.kt`'s watchdog.
- **Unfiltered-scan 30-second auto-stop**: since Android 7, an unfiltered
  scan (no `ScanFilter`) is silently stopped by the OS after 30 seconds with
  no callback.
- **GATT operations are effectively serialized per connection**: issuing a
  second `read`/`write`/`setCharacteristicNotification` before the previous
  operation's callback fires silently corrupts stack state on many chipsets
  (see the `android-ble-gatt-sequential-queue` skill; MeshLink already
  serializes this via its own per-link operation queue).

### Relevance to the observed 2026-07-11 fleet failures

None of the 5-device `--full-mesh` runs came close to the classic 7-link
ceiling: each device only ever needed at most 4 simultaneous peer links (one
per other fleet device), well under even the conservative AOSP default. The
connection-*count* ceiling is therefore **not** the explanation for why the
two oldest/lowest-spec devices failed every handshake in that investigation
-- the bottleneck there was negotiation/CPU/radio-scheduling capacity under
N simultaneous *in-flight* connection attempts, not a hard slot limit being
reached. The ceiling would become directly relevant at larger fleet sizes
(a single device meshed with 8+ peers) or if MeshLink's mixed-bearer design
ever opened a second physical GATT connection per peer for tuning purposes,
which `GattSideLinkCoordinator.kt` already explicitly avoids for exactly this
reason (see its class doc comment).

### `PowerPolicy.maxConnections` is now an enforced admission-control cap on Android (was diagnostics-only)

MeshLink's own `PowerPolicy.kt` already modeled a per-power-tier connection
budget (`PERFORMANCE` = 7, `BALANCED` = 5, `POWER_SAVER` = 3) that not
coincidentally mirrors the classic AOSP `GATT_MAX_PHY_CHANNEL` default of 7
for its least-conservative tier. When this section was first written, a
full-codebase search found `maxConnections` was only ever read to populate a
diagnostics map (`MeshEngineInternalModels.kt`'s `POWER_MODE_CHANGED`
payload) -- there was no code path that refused a new connection, deferred a
handshake, or otherwise admission-controlled against this budget.

That gap is now closed on Android (PR #100, `feat/enforce-connection-budget`):

- `hasConnectionBudget()` (commonMain, `power/PowerPolicy.kt`) is the shared,
  platform-agnostic admission decision: an already-connected peer is always
  admitted (a budget check never forcibly drops an existing connection), and
  a not-yet-connected peer is only admitted while the device's active
  connection count is under the tier's `maxConnections`.
- Android's `BleTransportAdapterScanSupport.onScanResult` calls this before
  starting a new GATT side-link or L2CAP connection for a newly discovered
  peer, using the union of active L2CAP links
  (`BleTransportLinkRegistry.activeHintIds()`) and active GATT side-links
  (`GattSideLinkCoordinator.activeHintIds()`, added alongside this change) as
  the active connection count. A peer that arrives once the budget is already
  spent stays known/discovered and is admitted later once a slot frees up;
  nothing already connected is torn down to make room for it.

A live 5-device `--full-mesh` hardware rerun after this change confirmed it
is a behavior-preserving no-op at the fleet sizes exercised in the
2026-07-11 investigation above (all runs stayed well under the 7-connection
PERFORMANCE-tier budget, so the gate never actually triggered) -- this
closes the admission-control gap for future larger-fleet or many-peer
scenarios without changing any of the results already recorded in
`meshlink-benchmark/history.md`'s 2026-07-11 entries, none of which were
connection-count-related.

**Known scope limitation (closed 2026-07-11):** enforcement was Android-only
when this section was first written. It is now wired into iOS as well
(issue #101): `PowerMonitor.profileFor`/`defaultProfile` on iOS thread
`PowerPolicy.maxConnections` into `PowerProfile.maxConnections` exactly as
Android's `PowerMonitor` already did, and
`BleTransportAdapterDiscoverySupport.handleL2capDiscovery` calls
`hasConnectionBudget()` before initiating an outgoing L2CAP connect, using
the union of `activeLinksByHint.keys` (active L2CAP channel links) and the
new `BleTransportGattNotifyRegistry.activeHintIds()` (active GATT notify
side-links) as the active connection count -- mirroring Android's union of
`BleTransportLinkRegistry.activeHintIds()` and
`GattSideLinkCoordinator.activeHintIds()`. iOS only gates the L2CAP connect
because it is the only connection its transport adapter initiates locally;
iOS's GATT notify side-links are inherently peer-initiated (the local device
is the GATT peripheral there, so a remote central subscribing to the notify
characteristic is what creates the link, not a local decision this adapter
could defer), but they still count toward the active-connection total so the
budget reflects the device's real concurrent BLE connection load.

**Second, related gap closed (same day, follow-up):** the discovery-driven
admission gate above only covered the scan-result-driven auto-connect path.
Both platforms' send path (`L2capSendSupport.kt`'s `sendViaL2capWhenReady`)
had its own, separate way to trigger a brand-new outgoing L2CAP connection --
when a host app calls `send()` for a peer with no active link yet and this
device would locally initiate the connection, the send path called
`connectIfNeeded()` (`ensureConnectAttempt`/`triggerConnectIfNeeded`)
unconditionally, with no `hasConnectionBudget()` check at all. This meant a
send to a freshly-discovered, not-yet-connected peer could still spend a new
connection slot even once the device was already at its tier's budget cap,
regardless of whether discovery-time auto-connect was itself correctly gated.
This existed identically on Android (unchanged since PR #100 introduced the
discovery-time gate) and iOS (PR #105), since both platforms share the same
send-triggered-connect architecture. Both `L2capSendDependencies` now carry a
`hasConnectionBudget` callback threaded from the same active-link-union
computation the discovery path already uses, checked before
`ensureConnectAttempt`/`triggerConnectIfNeeded`; when the budget is already
spent, the send path logs a deferral (mirroring the discovery-time log line)
and falls through to its existing wait-for-inbound-link behavior instead of
forcing a new connection.

## Related documentation

- [hop-session-replay-protection.md](hop-session-replay-protection.md) --
  the related but distinct nonce-desync/`AEADBadTagException` fix for
  hop-session data frames.
- [power-management.md](power-management.md) -- background on `PowerPolicy`,
  `PowerProfile`, and the power-tier system this work integrates with.
- [trust-model.md](trust-model.md) -- why MeshLink's security is entirely
  app-layer (Noise-XX) rather than relying on BLE pairing/bonding.
- [peer-lifecycle.md](peer-lifecycle.md) -- background on peer
  discovery/connection/loss state transitions this work modifies the timing
  of.
