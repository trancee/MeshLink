# Proof Android Integration

This scaffold hosts the Android proof app used by the MeshLink quickstart and
later physical-device validation tasks.

## Current Scope

- Android proof app wiring against the shared `:meshlink` module
- runtime Bluetooth permission handling for Android BLE proof runs
- process-scoped MeshLink proof runtime so OEM pairing flows do not tear down the test session
- start/stop controls, peer/discovery visibility, diagnostic log display, and a send-hello action in `MainActivity`
- deterministic initiator-side auto-send retries for two-device first-message validation
- shared `POWER_MODE_CHANGED` diagnostics that expose `tier`, `advertisementIntervalMillis`, `connectionIntervalMillis`, `scanDutyCyclePercent`, `maxConnections`, `chunkBudgetBytes`, and `region`
- proof discovery now follows the shared MeshLink contract: fixed `4d455348` discovery UUID + one 128-bit payload UUID in a single advertisement with no scan response dependency
- two proof-only native GATT benchmark modes outside the MeshLink product path: the older passive Android GATT server mode for `MESHLINK_BENCHMARK_TRANSPORT=gatt`, and the newer passive Android GATT client mode for `gatt-notify`

## Current Validation

The current proof app has been exercised on two attached Android phones:

- OPPO `CPH2689` (Android 16 / API 36)
- Samsung `SM-G970U1` (Android 12 / API 31)

Observed physical proof point:

- L2CAP advertisement PSM negotiation succeeds
- direct proof runs on the current no-pairing build do not require an OS pairing dialog
- Noise XX hop session establishment succeeds
- provider-backed encrypted direct-message delivery succeeds from OPPO to Samsung
- Samsung receives and logs the decrypted `hello mesh` payload
- the 64 KiB Android throughput proof run reached `1306.12 KB/s` from OPPO to Samsung

Shared harness evidence now also covers the common US2 runtime:

- three-node multi-hop routing succeeds across one relay hop
- route reconvergence succeeds after a topology change
- relays forward without surfacing end-to-end plaintext to app consumers
- 64 KiB routed transfer succeeds when the virtual network enforces a 512-byte per-delivery ceiling
- no-route retry uses bounded, jittered exponential backoff until `deliveryRetryDeadline` expires and then returns `UNREACHABLE`
- sends retry early when topology updates reveal a route before the deadline expires

## Additional proof-only GATT evidence

The newer passive Android GATT client mode (`gatt-notify`) now supports a
reverse-direction future-branch benchmark where iPhone hosts a peripheral GATT
service and streams the 64 KiB payload via notifications into Android.
Retained physical evidence on the attached Android peers shows that this branch
is promising but variable:

- OPPO retained runs: `73.65 KB/s`, `43.13 KB/s`, plus one unsubscribe /
  disconnect failure
- Samsung retained runs: `65.98 KB/s`, `53.87 KB/s`, and `68.52 KB/s`

These runs are proof-only investigative evidence for a future SC-004 closure
branch. They are not yet the normative MeshLink product path.

A first real MeshLink product-path mixed-bearer candidate then proved that
Android could decode genuine encrypted MeshLink frames on the GATT-notify side
link, and later Phase-22 remediations restored recipient-confirmed product-path
closure on both reference peers.

The latest current-head follow-up now closes the throughput side as well:

- Samsung retained headless current-head product-path reruns now reach
  `61.96-70.64 KB/s` with passive proof receipts retained on attempt 1 on the
  latest rebuilt/install headless path
- OPPO retained headless current-head product-path reruns now reach
  `77.48-80.10 KB/s` with passive proof receipts retained on attempt 1 on the
  latest rebuilt/install headless path

The decisive transport-side win came from suspending discovery during large
inline sends so the scored product path no longer pays concurrent scan /
advertise churn while draining the GATT side bearer. A later iPhone proof-app
route-recovery fix also removed a false-negative warmup-only rerun by
rescheduling benchmark auto-send after a direct `ROUTE_DISCOVERED` that follows
a transient `Peer lost`. A later runner / iOS inbound-link follow-up then
removed a stale-serial headless false hang and kept accepted inbound mixed-
bearer iOS L2CAP links bound to the resolved peer ID once discovery/GATT-side
identifiers converged.

## Next Steps

Later implementation tasks should extend this harness-verified US2 behavior to
broader physical-device proof evidence, keep benchmark automation durable, and
preserve the new mixed-bearer product-path throughput closure as the branch
continues toward any future release promotion decisions.
