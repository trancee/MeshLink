# Android direct-proof fleet run summary

The latest rerun of the attached Android fleet completed six non-passing pairs and then stopped early after exceeding the configured failure threshold.

## Outcome

- **Passing pairs:** 0
- **Failing pairs:** 6
- **Pending pairs:** 24
- **Stop reason:** `failure count 6 exceeded max-failures=5; investigate the recorded failures before continuing`

## What changed

- The rerun bundle is now self-contained under `reports/android-direct-proof-fleet/runs/20260620T154539/`.
- The bundle includes the rendered matrix summary, machine-readable results, per-pair reports, and fleet inventory.
- The repository-root reports index now points at this run as the latest tracked direct-proof bundle.

## Evidence trail

- `matrix-report.md` — human-readable summary of the run
- `matrix-results.json` — structured pair outcomes
- `fleet.md` / `fleet.json` — device inventory and directed pair list
- `01_a065_nam_lx9_report.md` through `06_nam_lx9_a065_report.md` — per-pair evidence
