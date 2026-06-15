# Android direct-proof GATT transport refactor plan

Last verified: 2026-06-15

This plan turns the observed transport split into an implementation sequence.
It is intentionally app-side first: the runner already passes `meshlink.benchmarkTransport`, but that extra only starts the passive proof-app GATT fixture. It does not make GATT the primary transport carried by the retained direct-proof summary.

## Goal

Make the transport choice explicit enough that the passive proof app can run in one of two clear modes:

1. **MeshLink-primary direct proof**
   - current behavior
   - retained summary should continue to report `L2CAP`

2. **GATT-primary direct proof**
   - new behavior
   - retained summary should report GATT as the primary transport only when the app really runs that mode

## Current sender-side flow

The relevant sender/reference-app flow is split across these files:

### `ProofLaunchConfig.kt`

- `ProofLaunchConfig.fromIntent()` reads `meshlink.appId`, `meshlink.disableAutoSend`, and `meshlink.benchmarkTransport`.
- This means the transport choice is already coming from the launch intent.
- The app has enough information to distinguish a mesh run from a GATT benchmark run, but it does not yet use that distinction as the authoritative summary contract.

Relevant lines:
- `ProofLaunchConfig.kt:31-50`

### `MainActivity.kt`

- `onCreate()` calls `MeshLinkProofRuntime.initialize(context, ProofLaunchConfig.fromIntent(intent))`.
- After the runtime snapshot is rendered, permission approval calls `MeshLinkProofRuntime.start()`.
- The activity is a shell; it does not decide transport semantics itself.

Relevant lines:
- `MainActivity.kt:26-33`
- `MainActivity.kt:50-66`

### `MeshLinkProofRuntime.kt`

This is the key seam.

#### Initialize path

- `initialize()` normalizes the launch config.
- It stops any previous GATT fixtures.
- It constructs the MeshLink runtime only when `benchmarkTransport == MeshLink`.
- That means the runtime already has a transport split, but the split is still operational rather than contractual.

Relevant lines:
- `MeshLinkProofRuntime.kt:80-131`

#### Start path

- `start()` branches on `benchmarkTransport`.
- `GattPrototype` enters the GATT benchmark branch.
- That branch requires `disableAutoSend=true`.
- It opens `ProofGattBenchmarkServer` and logs `gatt.benchmark.start() -> Started`.
- The MeshLink path is still present in the runtime and still produces the retained transport evidence when it is active.

Relevant lines:
- `MeshLinkProofRuntime.kt:156-239`

### `ProofGattBenchmarkServer.kt`

- `start()` opens `BluetoothGattServer` and advertises the GATT benchmark service.
- It is a real passive GATT server fixture.
- Its existence does not, by itself, make the direct-proof summary GATT-primary.

Relevant lines:
- `ProofGattBenchmarkServer.kt:167-178`

## Why the retained summary still says L2CAP

The passing CPH2359 run showed both of these at once:

- passive app: `gatt.server.start` / `gatt.server.started`
- transport evidence: `start() with l2capPsm=201`

That is the important distinction:

- GATT was active as a benchmark fixture
- MeshLink/L2CAP still carried the proof flow that the retained summary reports

The report generator in `run_headless_reference_android_direct_proof.py` then surfaces the transport evidence it was given. As long as the underlying runtime still brings up L2CAP, the HTML summary should continue to report `L2CAP`.

## Implementation plan

### Step 1: make primary transport explicit in the app contract

Add a launch-level notion of the **primary** transport rather than only a passive benchmark fixture.

Desired outcome:
- `MeshLink` remains the default primary mode.
- `GATT-primary` becomes a first-class app mode instead of a side effect of `benchmarkTransport=gatt`.

Likely touch points:
- `ProofLaunchConfig.kt`
- `MeshLinkProofRuntime.kt`
- any launcher or intent builder that currently sets only `meshlink.benchmarkTransport`

### Step 2: separate GATT-primary from MeshLink bootstrap

Make the runtime avoid initializing the MeshLink transport path when the launch contract explicitly says the run is GATT-primary.

Desired outcome:
- a GATT-primary run does not also create the L2CAP path that later dominates the retained summary
- the app emits an unambiguous marker that the selected primary transport is GATT

### Step 3: teach summary generation to prefer the primary transport marker

Update the direct-proof summary generation to distinguish:
- passive GATT fixture started
- GATT-primary run carried the proof
- MeshLink/L2CAP run remained the transport actually used

Desired outcome:
- the summary can report GATT only when the app really ran in GATT-primary mode
- the current L2CAP result remains correct for current MeshLink-primary runs

Likely touch points:
- `meshlink-reference/scripts/run_headless_reference_android_direct_proof.py`
- any retained summary producer that currently reads only the generic transport evidence

### Step 4: preserve auditable markers for both modes

Keep the logs explicit so future runs are easy to interpret.

For MeshLink-primary runs, retain markers like:
- `start() with l2capPsm=...`
- the current retained transport evidence

For GATT-primary runs, add markers like:
- `gatt.primary.start() -> Started`
- a single authoritative transport label for the summary parser

### Step 5: verify with two concrete runs

After the refactor, verify both paths separately:

1. MeshLink-primary direct proof
   - should continue to report `L2CAP`
2. GATT-primary direct proof
   - should report GATT as the retained transport

## Patch plan

1. **Wire a primary-transport field into the proof launch contract.**
   - Update `ProofLaunchConfig.fromIntent()` to distinguish the current benchmark fixture from the primary transport mode.
   - Preserve the current default so existing runs keep behaving as MeshLink-primary.

2. **Update `MeshLinkProofRuntime.initialize()` to follow the primary transport contract.**
   - If the run is MeshLink-primary, keep the current bootstrap path.
   - If the run is GATT-primary, avoid constructing the MeshLink runtime that later yields `l2capPsm` evidence.

3. **Split `MeshLinkProofRuntime.start()` into primary-mode branches.**
   - MeshLink-primary should keep the current MeshLink startup flow.
   - GATT-primary should start the GATT server as the authoritative transport, not as a sidecar fixture.

4. **Update the summary parser to prefer the primary transport marker.**
   - If a GATT-primary marker exists, use it for the retained transport result.
   - Otherwise continue deriving transport from the existing timings/evidence.

5. **Add a regression check for both modes.**
   - One run should still yield `L2CAP`.
   - One run should yield GATT.
   - The report must make the difference obvious in both JSON and HTML.

## Why this order

This sequence keeps the current behavior stable while carving out the new mode explicitly.
It avoids the trap the recent runs exposed: adding `meshlink.benchmarkTransport=gatt` alone is not enough to switch the retained transport.

## Cross-reference

- Companion note: `docs/explanation/android-direct-proof-gatt-path.md`
- Matrix result summary: `docs/reference/android-direct-proof-matrix-result.md`
