# Android direct-proof fleet run summary

The latest rerun of the attached Android fleet produced six failing pairs and then stopped early after exceeding the configured failure threshold.

## Outcome

- **Passing pairs:** 0
- **Failing pairs:** 6
- **Pending pairs:** 24
- **Stop reason:** `failure count 6 exceeded max-failures=5; investigate the recorded failures before continuing`

## What changed

- The direct-proof runner now launches sender and passive concurrently.
- The matrix now seeds the final pass from discovery evidence even when the initial run fails.
- The new bundle is self-contained under `reports/android-direct-proof-fleet/runs/20260620T164700/`.

## Evidence trail

- `matrix-report.md` — rendered summary of the run
- `matrix-results.json` — structured pair outcomes
- `fleet.md` / `fleet.json` — fleet inventory and directed pair list
- `01_a065_nam_lx9_report.md` through `06_nam_lx9_a065_report.md` — per-pair evidence
