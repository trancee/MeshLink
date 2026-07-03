# Physical reference-app integration findings

This page summarizes what recent retained physical reference-app runs taught us,
why the physical scenario matrix is shaped the way it is, and which
optimizations are worth keeping or pursuing.

## Quick takeaways

| Area | What we learned |
|---|---|
| direct proof | it is still the baseline physical signal, but release review now falls back to Android-only direct-guided when mixed live bootstrap is unsupported; doze-sensitive Android devices now start a foreground wake-lock mitigation during live-proof automation |
| direct vs relay placement | they often need different room geometry; running them back-to-back without changing placement can create fake failures |
| direct passive automation | moving it off the UI surface closed a misleading blind spot |
| remaining direct failures | pause/resume recovery and large-transfer proof still expose real sender/runtime blockers |
| relay proof | sender-only success is not enough; `routeIsDirect=false` plus passive completion is the real invariant |
| relay identity handling | canonical advertisement peer IDs and temporary-peer promotion are both load-bearing |
| retained review surface | per-run analysis artifacts, the browser/runtime proof note, and the fleet-test history HTML are much easier to review than raw log scrolling |

## Current milestone outcome

Milestone **M001** validated the release-review campaign end-to-end: one-command fleet discovery, ordered happy-path execution, retained report-data persistence, and the offline HTML review surface all passed on a real 4-device discovered fleet. That closed the validation-surface gap for **R010** by adding retained browser/runtime proof for the offline review path, so reviewers can trust the disk-backed surface without reconstructing raw logs. The campaign now treats Android-only sender symmetry as a deliberate follow-up rather than a hidden assumption.

## Why physical scenarios still matter

The scripted Android and iOS workflow tests already cover a lot of the
reference-app surface:

- **Guided first exchange** labels and navigation
- **Advanced controls**
- **Technical timeline** filtering and export UI
- blocked-start guidance
- **Solo exploration** behavior

Physical runs prove a different class of behavior:

- whether nearby peers are actually discovered on real radios
- whether trust and route state converge across platforms
- whether lifecycle controls still allow the next send to recover
- whether a trust reset really clears and rebuilds peer state
- whether larger payloads still move on real transports
- whether a routed send is truly routed instead of accidentally direct
- whether retained redacted artifacts survive a real session end
- whether teardown signals make the next failure diagnosable

That is why the physical matrix is intentionally smaller than the full scripted
surface matrix. It covers only the behaviors that become meaningful when real
devices, real Bluetooth stacks, and real route convergence are involved.

## What the direct proof taught us

The direct guided proof is the baseline physical scenario.

On the current release-review host, the campaign may still discover a mixed iOS sender and Android passive pair, but release review now falls back to Android-only direct-guided when the live mixed bootstrap path is not supported. That keeps the campaign honest without pretending a runnable-but-unwired mixed path is the baseline for release review.

When it passes, we know:

- the iPhone sender can discover an Android peer
- first trust establishment can complete on real hardware
- one-hop message delivery works on mixed iOS ↔ Android transport paths
- the passive Android peer can retain the session and export a redacted artifact
- the retained artifact still enforces the redaction policy by default

The direct runs also exposed one secondary resilience signal on the sender
side: the iPhone can keep a GATT-notify side link alive after the L2CAP channel
closes. That is not the primary pass criterion, but it shows that the mixed
bearer posture is doing real work.

## What the expanded direct scenarios taught us

The direct baseline is necessary, but not sufficient on its own.

### 1. Direct and relay proofs usually need different placement

A direct proof wants one Android peer in clear range of the iPhone. A
constrained relay proof wants the passive recipient out of direct range.

Trying to run both phases back-to-back without changing placement creates a fake
failure: the one-hop direct phase inherits the relay topology and never
converges.

That is why the matrix now supports a separate `--direct-android-serial` and
why the docs treat direct and relay placement as separate phases when needed.

### 2. Non-participating Android peers must be force-stopped

One extra Android device can silently contaminate a supposedly direct proof.

Typical symptoms are:

- extra peers appear on the sender or passive side
- route updates reflect the wrong topology
- a "direct" result stops meaning direct

That is why the direct runner now force-stops the non-participating Android
peer when the matrix knows about it.

### 3. Moving passive automation off the UI surface fixed the direct blind spot

The direct passive automation used to live behind the app UI lifecycle. On the
current Samsung direct-passive setup, that created a misleading failure mode:

- the Android runtime logged real inbound delivery
- the shared session state contained the inbound evidence
- but the passive proof orchestration never advanced to end/export/proof-complete

Moving the live-proof automation onto the shared session and timeline flows
fixed that blind spot. After the change:

- `direct-guided` passed end-to-end again on iPhone 15 → Samsung
- `direct-trust-reset-recovery` passed end-to-end with `deliveries=2`
- the passive side reached retained export without depending on Compose
  recomposition timing

That narrowed the remaining direct failures to sender and runtime behavior
instead of passive UI orchestration.

### 4. Two direct scenarios still expose real runtime blockers

After the off-UI automation fix, two advanced direct scenarios still fail on
the current iPhone 15 → Samsung setup:

- **Pause/resume recovery** — the warmup send succeeds, but the post-resume
  recovery send still expires as `SendResult.NotSent(UNREACHABLE)`
- **Large transfer** — the first 13,824-byte send never establishes a route on
  the sender and expires as `SendResult.NotSent(UNREACHABLE)` before the
  passive side receives anything

These are no longer passive-automation problems. They are now honest transport
or runtime recovery problems worth debugging in the sender/runtime surface.

## What the relay failures taught us

The constrained relay proof turned out to be much more valuable than a simple
"did a send succeed" check.

### 1. Sender-only success can be a false positive

Some early relay attempts reached sender-side `proof.complete` even though the
send never became a real `A → B → C` routed proof.

The failure pattern was:

- the sender found the middle hop directly
- the sender issued a send before the final routed target had converged
- the sender completed on a direct route
- the passive recipient never completed or exported anything

That is why relay proof must never pass on sender completion alone. The hard
invariant is `routeIsDirect=false` plus passive completion.

### 2. Routed targeting must use the canonical advertisement peer ID

The routed recipient in physical automation is not "the first peer that looks
reachable right now". It is the canonical 24-hex advertisement peer ID for the
intended passive recipient.

Without that discipline, relay automation drifts toward whichever direct peer is
currently available and produces misleading green runs.

That is why the relay runner now derives the passive peer ID from retained
identity material instead of trusting UI order.

### 3. Bootstrap is sometimes required before the final routed send

Physical routed proofs do not always converge in one jump.

In practice, the sender may need to bootstrap the middle hop first, then wait
for the final route advertisement to the passive recipient, and only then send
the real proof message.

That is not a workaround to hide instability. It is the honest model of what a
cold routed mesh needs to do on real devices.

### 4. Temporary-peer promotion is load-bearing

One of the most revealing relay failures surfaced as `transport.data.noSession`
on the middle hop.

The failure sequence was:

- the relay accepted a transport connection on a temporary peer ID such as
  `bt-...`
- the runtime later learned the canonical advertisement peer ID
- later inbound delivery still arrived on the transport seam
- session lookup no longer resolved the established session cleanly

The fix was not cosmetic. The transport/session seam needed to keep alias
lookups valid after promotion from temporary peer IDs to canonical
advertisement peer IDs.

That is why the relay scenario keeps temporary-peer promotion as a valuable
observability signal. It is especially useful when a transport path begins on a
temporary peer ID, even though a successful routed proof does not require every
run to exercise that exact path.

## What a successful relay proof now proves

A good constrained relay run proves an ordered chain of real behaviors:

1. the passive recipient and relay both start cleanly
2. the sender bootstraps the relay when the final target is not yet routable
3. the final route to the passive recipient appears with `routeIsDirect=false`
4. the sender completes on that routed target rather than on the relay itself
5. the relay logs both queueing and forwarding delivery work
6. the passive recipient receives the inbound message
7. the passive recipient retains the session and exports a redacted artifact
8. teardown emits route retraction or route expiry signals instead of silently
   leaving stale state behind

That level of evidence makes the next debugging step much clearer when
something regresses.

### 5. A foreground wake-lock mitigation is worth keeping on Android direct proof

The Nokia X20 incident showed that some Android OEM builds can enter quick-doze
fast enough to stall peer discovery even when Bluetooth permissions and the app
startup path are healthy. On the current Nokia X20 + DN2103 pair, retained
logcat breadcrumbs showed `interactive=false` at `onCreate` and `onResume`, and
the passive activity also reached `onStop` before the run timed out. That makes
doze/screen-off a plausible contributor, but not a proven sole root cause.

The reference app now starts a foreground service plus partial wake lock during
live-proof automation so the device stays awake long enough for discovery and
proof completion. The app also now emits retained `power.state` breadcrumbs so
future runs can tell whether the device was interactive and whether lifecycle
transitions suggest backgrounding or idle behavior when proof stalls.

This is worth keeping because it:

- reduces false transport failures caused by aggressive device sleep policies
- keeps the mitigation inside the supported reference-app shell rather than the runner
- makes doze-related issues visible as readiness and lifecycle state instead of silent timeouts
- gives later runs a retained signal to compare against pair-specific discovery failures

The mitigation is not a substitute for clean battery policy on every device, but
it turns a flaky environment dependence into an explicit, documented operator
step.

## Optimizations worth keeping

| Keep this | Why |
|---|---|
| scenario-specific `analysis.json` and `analysis.md` artifacts | they turn physical validation from log archaeology into a repeatable review surface |
| failing relay runs on routed invariants, not sender optimism | this prevents the matrix from drifting back toward false positives |
| explicit bootstrap markers | reviewers can tell whether a routed proof needed warm-up or reached the passive target directly |
| explicit direct-scenario isolation | force-stopping non-participating Android peers removes a whole class of accidental false results |
| teardown diagnostics such as `ROUTE_RETRACTED` and `ROUTE_EXPIRED` | without them, stale route state and fresh route state are much harder to distinguish |

## Optimizations still worth pursuing

| Next improvement | Why it matters |
|---|---|
| reduce connection-attempt log noise during cold convergence | routine retained-proof review becomes easier without losing important transport signals |
| keep route-target derivation automatic | reduces manual copy-paste and improves repeatability |
| prefer structured diagnostics over ad-hoc text parsing | makes scenario analysis less dependent on stable log text |
| keep hardening sender-side recovery scenarios | the remaining direct failures are now honest sender/runtime issues, not passive automation problems |

## What the Android two-device direct fleet run taught us

Running the `meshlink-proof` Android app fleet script against a real two-device
pair (no simulator, no in-memory transport) surfaced two distinct bugs that no
existing unit or integration test caught, because both depend on behavior that
only diverges from the in-memory test harness on physical BLE hardware.

### 1. Unbonded BLE connections can present two different MACs for one peer

MeshLink never Bluetooth-bonds devices (no `createBond`/IRK anywhere in
`platform/android` or `platform/ios`). Without bonding, a device's outbound
GATT-client ("side link") connection and its inbound GATT-server connection
for the same physical peer can legitimately present two different MAC
addresses. The receiver has no way to know they are the same peer, so it minted
a throwaway temporary peer ID for the second address that could never be
promoted to the canonical session peer — every inbound frame on that address
was dropped as `transport.data.noSession`.

The fix adds a cleartext `LinkIdentity` wire frame: each side announces its
canonical key-hash peer ID once per GATT connection lifetime. The receiver
only honors a `LinkIdentity` claim for addresses it has not already bound,
so an established mapping can never be hijacked by a spoofed claim — a
spoofed claim could at most cause local misrouting, not a confidentiality
compromise, since the actual Noise hop-session still requires correct key
material to decrypt.

### 2. A peer ID byte-length mismatch made every direct message untrusted

After fixing (1), the fleet run kept failing a message a few seconds later with
`trust.verify.untrusted` on both devices, immediately after each device's own
local `delivery.send` success. That stage never re-established trust — it is
the strict, no-first-contact trust check for inbound messages, so it fails
outright if the sender's peer ID is not already pinned in the trust store.

The root cause was a plain string mismatch, not a missing handshake:

- `LocalIdentity.fromNoiseIdentity()` (the production identity path used by
  both platforms via `LocalIdentityStore.loadOrCreate()`) derived the local
  `peerId` from a **20-byte** prefix of `sha256(ed25519Pub + x25519Pub)`.
- Every other canonical peer ID in the mesh — BLE advertisement, discovery,
  and HOP-level Noise XX trust pinning — uses a **12-byte** prefix of the same
  hash (the "canonical 24-hex advertisement peer ID" referenced above).
- Both are truncated prefixes of the same hash, so they looked related but
  were different strings. The sender embedded its 40-hex-char self-identity as
  `envelope.senderPeerId`; the receiver had just pinned HOP trust for that same
  physical peer under a 24-hex-char canonical ID. The trust-store lookup on
  the receiver always missed.

This was 100% reproducible on every real two-device run, and invisible in
`commonTest` integration coverage because that test suite constructs
identities via `LocalIdentity.fromPeerId()` with an explicit literal peer ID,
sidestepping the derived-length code path entirely.

The fix aligned `PEER_ID_SIZE_BYTES` to the same 12-byte length used
everywhere else, so a `fromNoiseIdentity()`-derived local peer ID is always
identical to the canonical ID a remote peer pins trust under. After the fix,
the fleet run showed `transport.data.deliver` (true inbound delivery
confirmation, not just local send success) for the first time, across two
consecutive live runs on Nokia X20 ↔ Nothing A063.

### 3. The Bluetooth stack can settle into a `BLE_ON` limbo state after `disable`

The fleet script recovers a device whose advertise/scan retries are exhausted
(see "Recover a device whose advertise or scan retries are exhausted" in
`meshlink-proof/android/README.md`) by fully disabling and re-enabling its
Bluetooth stack:

```bash
adb -s <serial> shell cmd bluetooth_manager disable
adb -s <serial> shell cmd bluetooth_manager wait-for-state:STATE_OFF
adb -s <serial> shell cmd bluetooth_manager enable
adb -s <serial> shell cmd bluetooth_manager wait-for-state:STATE_ON
```

Running this automatically at the end of a fleet run (as part of hardening
the "always force-stop the proof app" cleanup step) surfaced a third
Bluetooth state beyond the expected `ON`/`OFF`: `BLE_ON`. `dumpsys
bluetooth_manager` reports `BLE_ON` as a hybrid state where "classic"
Bluetooth is disabled (`enabled: false`) but a BLE-only client registration
(scan/advertise/GATT) keeps the stack partially alive.

This was reproduced repeatedly on a Nothing A063 immediately after a proof-app
run in the same script invocation: the proof app was still holding a live BLE
scan/advertise/GATT client registration when `cmd bluetooth_manager disable`
was issued, so the stack settled into `BLE_ON` instead of transitioning fully
to `OFF`. `cmd bluetooth_manager wait-for-state:STATE_OFF` does not treat this
as an intermediate state worth waiting through — it fails outright with exit
status 255, which crashed the whole fleet run before the per-device cleanup
hardening was in place to protect against it.

Manual recovery from `BLE_ON` turned out to be simple: `cmd bluetooth_manager
enable` followed by `wait-for-state:STATE_ON` brings the device straight back
to a clean `ON` state, exactly as it would from a true `OFF`. So the fix was
not a new recovery path, just tolerance for an outcome the rigid
`wait-for-state` check refused to accept:

- Force-stop the proof app **before** issuing `disable`, releasing any BLE
  client registration held by our own app so it cannot be the cause of the
  limbo state.
- Replace the `wait-for-state:STATE_OFF`/`wait-for-state:STATE_ON` shell
  subcommands with a polling helper (`poll_bluetooth_state`) that queries
  `dumpsys bluetooth_manager` directly on a bounded timeout, and accepts
  either `OFF` or `BLE_ON` as a valid "disabled enough" outcome before
  re-enabling.

This was verified by deliberately reproducing the `BLE_ON` limbo state on the
A063 and confirming the hardened `restart_bluetooth_stack()` now recovers
automatically (landing in `BLE_ON` as an intermediate step, then reaching a
clean `ON`) instead of crashing the run.

### 4. A hardcoded 2s GATT-readiness timeout was too short for slower devices

While isolating the A063 ↔ HUAWEI NAM-LX9 pair's fleet-run failure (after
ruling out stray interference from other still-running proof-app instances,
see finding 2 above under "What the expanded direct scenarios taught us"),
logcat showed `preferred GATT side-link waiting for readiness ...
timeoutMs=2000` immediately followed by `client not ready` and `send(...)
dropped: peer is GATT-only`, with no retry. The underlying GATT connection
itself did not fail outright - it disconnected roughly 28 seconds later with
`status=147` (`GATT_CONN_TERMINATE_LOCAL_HOST`), consistent with the local
side abandoning a connection that was still mid-negotiation once nothing
used it.

`PreferredGattSendSupport.kt`'s `sendViaPreferredGattSideLinkOrNull` waits for
the GATT client to finish connect -> MTU negotiation -> service discovery ->
CCCD write before the first handshake message can be sent, bounded by a
single hardcoded `readyWaitTimeoutMillis = 2_000L` with no retry. That budget
was fine on fast/recent devices but consistently too short for older/slower
hardware like the NAM-LX9 (API 31) to complete the full sequence, causing the
entire handshake attempt - and therefore the whole pairing - to fail.

The fix raised the timeout to 10 seconds
(`PREFERRED_GATT_READY_WAIT_TIMEOUT_MILLIS`), giving slower devices realistic
headroom to finish the handshake sequence while staying comfortably under the
~28s window where an unused connection gets torn down locally.

### 5. The BLE scanner can silently miss a specific peer's advertisements (OEM/chipset compatibility)

Isolating a persistently failing pair, OPPO CPH2359 ↔ OnePlus DN2103, showed a
one-directional discovery gap: DN2103 discovered CPH2359 fine and even
connected to CPH2359's GATT server (accepted and correlated via `LinkIdentity`
binding), but CPH2359 never discovered DN2103 at all. When CPH2359 tried to
reply, `send(...)` failed with `dropped: peer not discovered` because CPH2359
never had an outbound discovery-registry entry for DN2103 - its scan simply
never surfaced that peer.

No `SCAN_FAILED_*` diagnostic fired; the scan callback reported success
(`scan started`) throughout. The investigation had to go around the app layer
entirely and inspect CPH2359's raw, unfiltered Bluetooth-stack logcat
(`BtGatt.GattService`) to find the real picture:

- CPH2359's scanner was demonstrably healthy - it was actively receiving
  `onScanResult()` callbacks for *many other* nearby BLE devices during the
  same window.
- DN2103's actual BLE MAC address (obtained from CPH2359's own GATT-server
  connection logs, since DN2103 connected *to* CPH2359) **never once
  appeared** in a `onScanResult()` line on CPH2359, across the entire test
  window.
- Location services, all three Bluetooth runtime permissions, appops, app
  standby bucket (ACTIVE), doze state (ACTIVE), and battery-optimization
  whitelisting were all checked and ruled out as causes.

This points to a genuine radio/RF-level or chipset-specific BLE advertising
incompatibility between these two specific devices - most likely an
extended-advertising (BT5 LE Coded/2M PHY) vs. legacy-advertising mismatch, or
an OPPO ColorOS-specific scan-filter/duty-cycle restriction on this particular
combination - rather than a bug in MeshLink's transport code. It was not
possible to conclusively pin down which side (or which specific PHY/interval
setting) is responsible without a `btsnoop` HCI trace, which was out of scope
for this pass.

**Takeaway:** treat this as a known physical-fleet limitation of the
CPH2359 ↔ DN2103 pair specifically, not a MeshLink defect. If it resurfaces
with other OPPO/OnePlus pairs, a `btsnoop` capture on both sides during the
failing scan window is the next diagnostic step.

**Related tooling gap found during this investigation:** the fleet script's
`capture_full_logcat()` reads only the last `--logcat-tail-lines` (previously
4000) lines of the *entire* device log buffer before filtering for the
`MeshLinkReferenceAutomation` tag. On a noisy device like CPH2359 - one
producing heavy non-MeshLink log traffic from other apps/system services -
this pushed every tagged line out of the tail entirely, producing a silent,
misleading 0-line capture that looked like "the app logged nothing" when it
had actually logged plenty, just further back in the buffer. Re-running with
`--logcat-tail-lines 40000` surfaced the evidence above. The default has since
been raised to 20000, and `capture_full_logcat()` now automatically retries
once with a much wider tail (100000 lines) whenever the requested window
captures 0 matching lines, so this blind spot cannot recur silently.

### 6. Full-fleet ACK/receipt evidence was contaminated by cross-talk, and untamed concurrency masked genuine failures

Re-running the full 14-device/7-pair fleet found two compounding, previously
hidden problems with the fleet script's own evidence collection - both
serious enough to call the trustworthiness of *every* prior full-fleet report
into question.

**Problem 1: `summarize_pair()` matched evidence with no regard for peer
identity.** The original implementation scanned each device's entire
`proof.log` for generic marker substrings (`DELIVERY_SUCCEEDED`,
`auto-send attempt 1 -> Sent`, `BENCHMARK receipt sent`, etc.) across the
*combined* text of both devices in a pair, without checking which peer the
matched line actually referenced. In a full-fleet run, every phone can hear
BLE traffic from *other* pairs as well as its own assigned partner, so a
device's log could legitimately contain a genuine success from a completely
unrelated pair - and that got misattributed as "ACK: yes" evidence for
whichever pair happened to include that device. This was proven concretely:
motorola edge 30 fusion's own handshake attempt toward CPH2385 failed outright
(`DIAG HOP_SESSION_FAILED ... reason=DELIVERY_FAILURE`), yet the report showed
"ACK: yes" purely because CPH2385's log also contained an unrelated
"hello mesh from DN2103" delivery from a different pair entirely.

The fix (`summarize_pair()` rewrite) extracts each device's own `keyHash` from
its `proof.log` startup line and scopes marker matching to lines that also
reference the *other side's* own identity - the full hex hash for structured
`DIAG` lines, or its last-6-hex-character hint form (`peerId.value.takeLast(6)`)
for free-text lines. A new `evidenceVerified` flag (plus
`initiatorOwnKeyHash`/`receiverOwnKeyHash`) is now surfaced per pair so a
report can flag reduced confidence when one side's own `keyHash` line could
not be found (e.g. truncated by logcat buffer wraparound) and matching fell
back to unscoped search for that direction.

Re-analyzing an existing 7-pair fleet run's already-captured logs against the
corrected, peer-ID-scoped logic found only **1 of 7 pairs** had genuine
evidence; the other 6 - including every pair the original report had marked
"ACK: yes / Receipt: yes" - were false positives from cross-talk. Their real,
scoped handshake attempts showed `DELIVERY_FAILURE`/`routeAvailable=false`.

**Problem 2: running the whole fleet at once causes BLE RF congestion severe
enough to prevent genuine delivery almost entirely.** With the scoping fix
applied, a *fresh* 14-device/7-pair run (all 7 pairs launched simultaneously,
as the script had always done) showed **0 of 7 pairs** completing genuine
delivery. Digging into one pair's logs directly explained why:

- CPH2359's peer entry for its assigned partner flapped from `found` to
  `lost` within **5 milliseconds** - nowhere near long enough to establish a
  connection, let alone complete a handshake.
- Even devices whose peer entries stayed visible for longer routinely failed
  handshakes with `AEADBadTagException` (a corrupted/failed AEAD
  authentication tag) - consistent with BLE packet collisions/corruption
  under heavy simultaneous RF activity, not a cryptographic logic bug.
- One device (motorola edge 30 fusion) logged `HOP_SESSION_FAILED` against
  eight or more different peers within the same short window, ruling out a
  pair-specific explanation - this was fleet-wide congestion, not a defect
  isolated to any one pair.

This tracks: with 14 devices simultaneously advertising and scanning, both
the shared 2.4 GHz airtime and each device's limited concurrent-GATT-
connection budget (a handful of slots on most Android BLE stacks) are heavily
oversubscribed, so most pairs never get a stable enough connection window to
complete a handshake before their peer visibility flaps away again.

**Fix:** the fleet script now runs pairs in sequential batches
(`--max-concurrent-pairs`, default `2`) instead of launching every pair at
once. Each batch's proof app is stopped before the next batch launches,
freeing BLE connection slots and airtime between batches. Re-running the full
fleet with this fix raised the genuine, peer-ID-verified success rate from
0/7 to **3/5 completed pairs** in the same run - including the previously-
failing motorola edge 30 fusion ↔ CPH2385 pair now succeeding outright.

Batching also surfaced a related script fragility worth noting: a single
batch raising an unrecoverable error (in this run, a device stuck
mid-Bluetooth-restart during auto-recovery - see finding 3 above) used to
crash the entire fleet run and lose every other batch's already-captured
evidence. The batch loop now catches and records such errors in a
`batchErrors` list surfaced in the compact summary, so the rest of the fleet
run's results are preserved even when one batch cannot be recovered.

**Takeaway:** full-fleet, all-at-once runs are not a reliable way to judge
per-pair reliability - they measure fleet-wide RF congestion tolerance more
than they measure MeshLink's actual protocol correctness. Prefer batched
(`--max-concurrent-pairs`, small isolated `--pair-label`/`--device` subsets)
runs when the goal is validating a specific pair or transport path, and treat
`evidenceVerified: false` rows in any report with reduced confidence until
re-confirmed.

## What should stay out of the physical matrix

Do not force every UI feature into a physical-device scenario.

That would make the matrix slower, less stable, and harder to interpret.
Instead:

- keep physical scenarios focused on transport, routing, trust, and retained
  artifact behavior
- keep scripted automation responsible for **Solo exploration**,
  blocked-start guidance, **Advanced controls** layout, and **Lab** or
  UI-only behavior

## Related docs

- [How to evaluate MeshLink with the reference app](../how-to/evaluate-meshlink-with-the-reference-app.md)
- [How to run the reference-app physical integration scenarios](../how-to/run-reference-app-physical-integration-scenarios.md)
- [MeshLink reference app overview](../../meshlink-reference/README.md)
- [Contributor build, test, and verification reference](../reference/contributor-reference.md)

That split is not a compromise. It is what makes the validation story honest
and maintainable.
