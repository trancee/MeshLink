# Android direct-proof GATT code-change task list

Last verified: 2026-06-15

This task list breaks the GATT transport work into the smallest code changes that preserve current MeshLink-primary behavior while making the primary transport contract explicit.
The latest attached-fleet rerun showed that startup triage comes first: preflight/install and launch clusters are still hiding the transport signal, so the validation order is now triage, then transport contract work, then a targeted rerun.

## Tasks

- [x] **Task 1: Add an explicit primary transport contract to the proof app**
  - Add a `meshlink.primaryTransport` launch extra to `ProofLaunchConfig`.
  - Log the primary transport separately from `meshlink.benchmarkTransport` so the runtime emits one stable marker for the transport that should own the retained summary.
  - Keep the default as MeshLink so existing direct-proof runs behave the same.

- [x] **Task 2: Prefer the primary transport marker in direct-proof summary parsing**
  - Update `extract_transport_mode()` in `run_headless_reference_android_direct_proof.py` to look for the new primary-transport marker first.
  - Preserve the current fallback order so older logs still resolve from GATT/L2CAP evidence.
  - Keep the HTML report stable for current MeshLink-primary runs.

- [x] **Task 3: Verify both interpretations remain distinguishable**
  - Run a MeshLink-primary direct-proof case and confirm the summary still reports `L2CAP`.
  - Confirm a passive GATT fixture still logs its benchmark start separately from the primary transport marker.
  - Use the passing run and one failed run as regression evidence for report parsing.

## Code seams

- Proof app launch contract: `meshlink-proof/android/src/main/kotlin/ch/trancee/meshlink/proof/android/ProofLaunchConfig.kt`
- Proof runtime logging: `meshlink-proof/android/src/main/kotlin/ch/trancee/meshlink/proof/android/MeshLinkProofRuntime.kt`
- Direct-proof report parsing: `meshlink-reference/scripts/run_headless_reference_android_direct_proof.py`

## Expected outcome

The current behavior stays intact, but the app and report parser gain a durable contract for identifying the primary transport separately from the passive benchmark fixture.
