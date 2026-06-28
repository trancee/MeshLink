# Android direct-proof GATT path and retained transport behavior

Last verified: 2026-06-18

This note records what the Android direct-proof runs showed about the passive GATT benchmark fixture, why the retained summary sometimes still reports L2CAP, and when the retained transport can now legitimately report GATT.

## Short version

Launching the passive proof app with `meshlink.benchmarkTransport=gatt` starts the proof-app GATT server. The latest attached-fleet rerun also confirmed the sender contract fix: for passive GATT fallback runs, the sender stays on `meshlink` instead of being switched to `gatt-notify`, and the low-API xcover bundle now shows `benchmarkTransport=meshlink` on the sender plus `start() with l2capPsm=227` in the retained transport path. On the older low-API fallback pairs, that GATT path can now be the retained primary transport and the summary can report `timings.transportMode = GATT`.


In the successful retained run on CPH2359:
- the passive proof app logged `gatt.server.start` / `gatt.server.started`
- the same run later logged `start() with l2capPsm=201`
- the summary still reported `timings.transportMode = L2CAP`

So the current behavior is split:
rrent behavior is:
- GATT is active as a passive benchmark/control surface
- MeshLink/L2CAP still carries the direct-proof transport that the summary reports
- the latest attached-fleet rerun split into clear preflight/install and launch clusters, but still produced no pass on either transport path
- the proof app now fails fast when Bluetooth is off or the manager/adapter is unavailable, and that failure is surfaced in the UI state as well as logs, so the GATT launch signal is now explicit instead of an opaque null-server crash

## Evidence from the passing run

Passing run: `a065_cph2359`
- sender: `1f1dad34` / A065 / Android 16
- passive: `EQUGS85LJNEIO7Z5` / CPH2359 / Android 14
- status: `PASS`
- retained transport: `L2CAP`
- total time: `21.9s`
- capture timeout: `45.0s`

The rendered HTML summary exposed:

- **Transport mode**: `L2CAP`
- **Transport evidence**: sender `gatt.notify.start() -> Started`, passive `start() with l2capPsm=201`
- **Startup timing**: sender startup wait, passive startup wait, passive transport wait, and the configured wait budgets

The raw passive log makes the split explicit:

```text
REFERENCE_AUTOMATION gatt.server.start role=PASSIVE benchmarkTransport=gatt
REFERENCE_AUTOMATION gatt.server.started role=PASSIVE benchmarkTransport=gatt
start() with l2capPsm=201
REFERENCE_AUTOMATION proof.complete role=passive inbound=true ...
```

## Failure-path evidence

Targeted replay: `nam_lx9_gatt_replay`
- sender: `09071JEC215801` / Pixel 4a / Android 13
- passive: `2ASVB21B09005117` / NAM-LX9 / Android 12
- status: `FAILED`
- retained startup state: `bluetooth-disabled`
- passive log marker: `Bluetooth preflight blocked; startup-state=bluetooth-disabled; Bluetooth is turned off`

That means the retained summary is correctly describing the actual carried transport when the run succeeds, and the failure path now makes the Bluetooth-off startup boundary explicit instead of collapsing into a generic GATT crash.

## Sender/reference-app trace

The relevant sender/reference-app path is split across the proof app launch config and the runtime bootstrap, and the runner must keep the sender on MeshLink for passive `gatt` fallback runs:

### 1. Launch config reads the benchmark transport extra

`ProofLaunchConfig.fromIntent()` reads:
- `meshlink.appId`
- `meshlink.disableAutoSend`
- `meshlink.benchmarkTransport`

So the app receives the requested benchmark mode directly from the launch intent.

### 2. MainActivity initializes the proof runtime and starts it after permissions

`meshlink-proof/android/.../MainActivity.kt` does three important things:

1. calls `MeshLinkProofRuntime.initialize(context, launchConfig)`
2. renders the snapshot and collects runtime updates
3. calls `MeshLinkProofRuntime.start()` once permissions are granted

That means the activity itself is just the shell; the transport choice is really owned by `MeshLinkProofRuntime`.

### 3. MeshLinkProofRuntime chooses between MeshLink and GATT

In `MeshLinkProofRuntime.initialize()`:
- if `benchmarkTransport == MeshLink`, it constructs the MeshLink controller
- it also clears any previous GATT benchmark fixtures

In `MeshLinkProofRuntime.start()`:
- `GattPrototype` enters a GATT benchmark branch
- that branch requires `meshlink.disableAutoSend=true`
- it opens `ProofGattBenchmarkServer`
- it logs `gatt.benchmark.start() -> Started`
- but it does **not** convert the direct-proof summary into a GATT-carried transport

The important implication is that the GATT branch is currently a benchmark fixture layered beside the ordinary proof runtime, not a replacement for the transport path the summary records.

## Why the summary still reports L2CAP

The summary builder currently prefers the transport evidence that comes from the retained run timings.

In the passing run:
- the passive app started the GATT benchmark server
- the proof runtime still brought up the MeshLink/L2CAP path
- the retained summary therefore recorded `transportMode = L2CAP`

That is consistent with the code and the logs.

## What would need to change for GATT to become the primary retained transport

The current `benchmarkTransport=gatt` setting is not enough. To make GATT the primary retained transport, the app-side launch/config path needs a stronger split:

1. **Separate GATT benchmark mode from MeshLink bootstrap mode**
   - when the passive fixture is GATT-only, do not also initialize the MeshLink transport path for the same run
   - make the app’s transport selection explicit rather than implicit

2. **Record GATT as the authoritative transport in the retained summary**
   - summary generation should prefer the passive proof-app’s GATT completion markers when the run is in GATT-primary mode
   - if the run still starts MeshLink/L2CAP, the summary should continue to say L2CAP

3. **Make sender and passive roles explicit in the launch contract**
   - the sender should know whether it is participating in a mesh transport run or a GATT control run
   - the passive app should not infer the primary transport from one extra alone

4. **Keep the logs auditable**
   - retain `gatt.server.start` / `gatt.server.started`
   - retain `start() with l2capPsm=...` when MeshLink remains active
   - add a distinct marker for a true GATT-primary run so the summary parser can distinguish the modes

## Practical interpretation for this branch

For the current branch, the safe reading is:

- `benchmarkTransport=gatt` means "start the proof-app GATT fixture"
- it does **not** mean "the retained direct-proof transport is now GATT"
- the current summary remains authoritative when it says `L2CAP`

## Related artifacts

- Passing run summary: `/tmp/meshlink-direct-proof.fan2g1/summary.html`
- Passing run JSON: `/tmp/meshlink-direct-proof.fan2g1/summary.json`
- Fleet report guide: [Android direct-proof fleet reports](../reference/android-direct-proof-fleet-reports.md)
- Bundle layout guide: [Android direct-proof fleet report layout](../reference/android-direct-proof-fleet-report-layout.md)
- Repository report index: [Reports index](../../reports/INDEX.md)
- Failed run summary: `/tmp/meshlink-direct-proof.QgIbA1/summary.html`
- Failed run JSON: `/tmp/meshlink-direct-proof.QgIbA1/summary.json`

## Reader takeaway

If you are debugging direct-proof transport behavior, treat `meshlink.benchmarkTransport=gatt` as a fixture selector, not as proof that the carried transport switched away from L2CAP. The sender-side contract fix is already in the runner, but the fleet bundle still shows capture stalls in later phases, so making GATT the primary transport still requires a deliberate app/startup contract change rather than a runner-only flag.
