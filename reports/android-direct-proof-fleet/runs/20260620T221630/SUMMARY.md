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
- The bundle is self-contained under `reports/android-direct-proof-fleet/runs/20260620T221630/`.

## Evidence trail

- `matrix-report.md` — rendered summary of the run
- `matrix-results.json` — structured pair outcomes
- `fleet.md` — inventory and pair mapping for the run
- `fleet.json` — structured inventory and execution metadata
- `01_a065_nam_lx9_report.md` through `06_nam_lx9_a065_report.md` — pair-level evidence

## Follow-up

- Investigate the remaining capture-stage failures as open defects.
- Use the pair reports and the `matrix-results.json` details to separate launch-gate, route-stall, and hop-session behavior in the next investigation step.
