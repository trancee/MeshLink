# Android direct-proof fleet run summary

This rerun preserves the attached fleet evidence after the launch-floor and route-failure detection fixes were applied; the verified rerun pairs still fail at the same route boundary rather than at launch.

## Outcome

- **Passing pairs:** 0
- **Failing pairs:** 6
- **Pending pairs:** 24
- **Stop reason:** `failure count 6 exceeded max-failures=5; investigate the recorded failures before continuing`

## What changed

- The direct-proof runner now launches sender and passive concurrently.
- The matrix now seeds the final pass from discovery evidence even when the initial run fails.
- The bundle is self-contained under `reports/android-direct-proof-fleet/runs/20260621T153830/`.

## Evidence trail

- `matrix-report.md` — rendered summary of the fleet rerun.
- `matrix-results.json` — machine-readable pair statuses and failure stages.
- `fleet.md` / `fleet.json` — fleet inventory and directed pair map used for the sweep.
- `NN_<pair_label>_report.md` — per-pair evidence with the latest route-stage markers.

## Interpretation

The rerun confirms that the fixes changed the orchestration behavior without introducing a launch regression. The remaining failures are still recorded as route-stage failures in the pair reports rather than ambiguous capture-only stalls.
