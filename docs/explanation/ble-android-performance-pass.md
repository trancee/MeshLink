# Android BLE performance/stability pass

## Status

A codebase-wide review of `meshlink/src/commonMain` and
`meshlink/src/androidMain/platform/android` for performance and stability
issues, followed by concrete fixes for the actionable findings. This document
records what was found, what was changed, what was deliberately left as
documentation-only, and one new issue the fix work surfaced that is **not**
fixed here.

## Fixes applied

**1. Deduplicated `L2capFrameBuffer`'s frame-reassembly loop.**
`append()` (the hot receive path) and `appendDetailed()` (a diagnostics
variant that also records `DecodedFrameObservation`s) each reimplemented the
same length-prefixed frame-boundary scan. Extracted the scan into a single
`inline fun decodeAvailableFrames(onFrameDecoded: ...)` that both now call, so
future fixes to frame-boundary handling only need to be made once. `append()`
still pays no extra allocation cost for sharing the logic (the shared
function is `inline`).

**2. `PeerRegistry.resolve()` no longer re-decodes hex per candidate.**
The fallback path (used when the exact hint-id lookup misses) was calling
`peerId.value.hexStartsWith(candidate.keyHash)` once per discovered peer,
re-decoding the same leading hex characters of `peerId.value` from scratch
every time. Since `keyHash` is a fixed size
(`LocalIdentity.KEY_HASH_SIZE_BYTES`), `peerId.value` is now decoded to bytes
once per `resolve()` call and compared against each candidate's `keyHash`
with a plain byte-prefix check instead.

**3. Reordered/reduced allocation in `handleScanResult()`'s UUID parsing.**
`result.scanRecord?.serviceUuids?.map { it.uuid.toString() }` previously ran
unconditionally on every scan result, formatting *every* advertised UUID
(including the fixed marker UUID, present in nearly every result) into a
string and building an intermediate list, before the meshHash pre-check ever
ran. Replaced with `firstNonMarkerServiceUuidString()` /
`firstNonMarkerServiceDataKeyString()`, which compare candidate `ParcelUuid`s
against a precomputed marker `ParcelUuid` directly (cheap UUID equality, no
string formatting) and only call `.uuid.toString()` on the one surviving
candidate. The `serviceData` fallback is now only even inspected when
`serviceUuids` didn't yield a candidate, instead of being unconditionally
mapped every time. `ScanCallback.onScanResult` is delivered on the main
looper with no way to redirect it to a background thread via the public API,
so this directly reduces main-thread work competing with connection-critical
BLE callback delivery.

**5/7. `GattNotifyClient` now retries a congested write in place instead of
tearing down the session.** Android's `BluetoothGatt.GATT_CONNECTION_CONGESTED`
(status 143) signals transient local transmit-queue backpressure, not a real
link failure -- but the client previously treated *any* non-success
`onCharacteristicWrite` status the same as a genuine per-chunk failure,
closing the whole GATT side-link session (forcing a full reconnect) even for
this recoverable case. `PendingGattWrite` now retains the chunk's bytes (not
just its completion `Deferred`), and `retryCongestedWriteIfPossible()`
reissues the exact same chunk in place, bounded by
`MAX_CONGESTION_RETRY_ATTEMPTS` (5), before falling back to the existing
close-the-session behavior once the retry budget is exhausted. Covered by
`writeRetriesInPlaceAfterGattConnectionCongestedInsteadOfClosingTheSession`
and `writeClosesTheSessionWhenCongestionRetriesAreExhausted` in
`GattNotifyClientTest`.

**8. Skipped redundant `PeerRegistry.upsertDiscovery()` calls for unchanged,
already-announced advertisements.** When a scan result's address/PSM/transport
mode exactly matches what's already recorded for a peer *and* presence was
already announced for it, `upsertDiscovery()`'s refresh path is a pure no-op
(same fields, no events emitted) -- but it still allocated a
`DiscoveredPeerDiscovery`, took the registry lock, and rebound the same
address→hint mapping in `PeerBindings` every single time. `handleScanResult()`
now recognizes this exact condition and reuses the already-resolved
`DiscoveredPeer` directly, skipping that no-op work for what is, in a stable
environment, the large majority of scan results for already-known peers.

## Documentation-only observations (not code changes)

**4. Engine "Support"/"Assembly" fragmentation.** The `engine/` package
(~40 files, ~11K lines) wires inbound/outbound dispatch through
constructor-injected lambda bundles (e.g. `MeshEngineInboundMessageCallbacks`,
`MeshEngineInboundTransport`). This is a deliberate testability trade-off
(many small, independently mockable closures), not a defect -- flagged for
awareness, not changed, since collapsing it would be a large, high-risk
refactor for a marginal, unmeasured allocation saving.

**6. Two concurrency idioms coexist by design.** Platform callback-thread
state (`PeerRegistry`, `PeerBindings`, `GattSideLinkCoordinator`) is guarded
with plain `synchronized` blocks, because it is genuinely mutated from real
Android callback threads (scan callback, GATT Binder thread, L2CAP accept
coroutine) that are not confined to a coroutine dispatcher. Coroutine-native
state (`RouteCoordinator`, `MeshEngineSessionRegistry`) uses `Mutex.withLock`
instead. Convention going forward: **callback-thread state uses
`synchronized`; coroutine-native state uses `Mutex`.** Do not put a
suspending call inside a `synchronized` block -- it will not compile as
written today, but if that ever changes (e.g. via a future Kotlin coroutines
feature), mixing the two would deadlock/misbehave.

## New issue found, not fixed here: a drain/completion race in `GattNotifyClient`

While adding test coverage for the congestion-retry fix (#5/7 above), writing
a test where *every* chunk of a payload resolves synchronously (completion
callback invoked directly within `writeChunk()`, rather than asynchronously
from a separate callback thread/dispatch) surfaced a pre-existing race:

`drainPendingWrites()` decides whether a payload succeeded by checking
whether `pendingWrites` still contains an entry to await
(`pendingWrites.firstOrNull() ?: break`, returning `true` once the queue is
empty). `completePendingWrite()` *removes* an entry from `pendingWrites` as
soon as its completion arrives, regardless of whether that completion was a
success or a failure. If every chunk's completion for a payload arrives (and
is removed) **before** `drainPendingWrites()` gets a chance to run --
possible in principle if completions are delivered fast enough relative to
the enqueuing coroutine resuming -- `drainPendingWrites()` sees an empty
queue and returns `true`, silently treating a failed transfer as successful.

This is masked in practice for two reasons: genuine Android GATT completions
are always delivered asynchronously (never synchronously within
`writeCharacteristic()`'s call stack), so the enqueuing coroutine reliably
gets to `drain()` before completions land; and the existing test suite's one
"failure" test (`writeClosesTheSessionWhenAPipelinedChunkCompletesWithAFailureStatus`)
happens to reach the correct answer anyway, but via the *drain timeout* path
rather than by directly observing the failed chunk's result (its failing
chunk resolves-and-is-removed before `drain()` runs, same as the race above,
but the *next* chunk in that same payload is left permanently unresolved by
that test's harness, so `drainPendingWrites()` blocks on it and times out --
producing the same "session closed" outcome by accident).

Properly closing this race would require `drainPendingWrites()` to track a
target count of completions for the current payload (rather than inferring
"done" from queue emptiness) so it can distinguish "everything drained
successfully" from "everything drained because it already failed and left."
That is a real fix to the write pipeline's synchronization design, not a
tuning change, and was judged out of scope for this pass -- recorded here so
it isn't lost, and so a future contributor writing GATT client tests knows
why synchronous (same-callstack) completions in a test harness can silently
hide a real assertion failure.

## Testing

- `./gradlew :meshlink:testAndroidHostTest --tests
  "ch.trancee.meshlink.platform.android.*"` -- covers all fixes above,
  including the new `GattNotifyClientTest` congestion-retry cases, the
  `L2capFrameBufferTest` fast-path/detailed-path parity test, and the
  `PeerRegistryTest`/`ScanResultSupportTest` coverage for the resolve/parsing
  changes.

## Related documentation

- [ble-connection-robustness.md](ble-connection-robustness.md) -- the prior
  BLE flakiness investigation (connection priority, PeerLost debounce,
  handshake timing) this pass is complementary to; that document covers
  correctness/robustness fixes, this one covers performance and the
  congestion-retry stability fix.
