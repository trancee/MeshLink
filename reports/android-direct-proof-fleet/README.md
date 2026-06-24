# Android direct-proof fleet outputs

Canonical artifacts for a matrix run live under `runs/<timestamp>/`.

## Read these first

- `matrix-report.md` — fleet overview, foreign-scan summary, and run setup notes.
- `matrix-results.json` — machine-readable per-pair results.
- `fleet.json` — fleet inventory plus `foreignScanSummary` for downstream tooling.
- `progress.json` — resumable checkpoint payload; includes `results` and `foreignScanSummary`.

## Pair reports

Each pair run writes `01_<pair>_report.md` with the per-run foreign-scan breakdown and the pair-specific explanation block.
