# Android direct-proof fleet reports

The Android fleet sweep produces repository-local report bundles so failures can be debugged and fixed without rerunning the same pairs blindly.

## Default report location

When `meshlink-reference/scripts/run_headless_reference_android_direct_matrix.py` runs without `--run-root`, it writes into:

`reports/android-direct-proof-fleet/runs/<timestamp>/`

That directory is the canonical report bundle for the sweep.

## Bundle contents

Each run should include:

- `fleet.json` — enumerated Android devices before pair execution starts
- `fleet.md` — human-readable fleet inventory and pair list
- `matrix-results.json` — structured results for every completed pair
- `matrix-report.md` — end-of-run summary, pass/fail buckets, and next-step notes
- `NN_pairlabel_report.md` — per-pair report with:
  - setup details
  - transport used
  - device quirks and known issues
  - Mermaid sequence diagram
  - references to raw evidence files
  - nested timing JSON
- `NN_pairlabel_initial/` and `NN_pairlabel_final/` — proof-run directories

## Raw evidence referenced by per-pair reports

Per-pair reports should point to the underlying proof artifacts instead of repeating everything inline:

- `summary.json`
- `summary.html`
- `sender_logcat.log`
- `passive_logcat.log`
- `sender_start.txt`
- `passive_start.txt`
- `android_history.json`
- `android_export.json`

## Sample committed bundle layout

A committed copy of the report bundle layout lives in
`reports/INDEX.md` and `reports/README.md`.
Those files are the repository-tracked examples of what the generated run directory should look like.
The first tracked run bundle is [reports/android-direct-proof-fleet/runs/20260620T154539/INDEX.md](../../reports/android-direct-proof-fleet/runs/20260620T154539/INDEX.md), with a concise failure summary at [SUMMARY.md](../../reports/android-direct-proof-fleet/runs/20260620T154539/SUMMARY.md).
For a shorter tree-only summary, see [Android direct-proof fleet report layout](android-direct-proof-fleet-report-layout.md).

## Troubleshooting intent

The report should make it easy to answer:

1. Which devices were in scope?
2. Which transport path was used?
3. How far did the run get before it failed?
4. Which raw logs and evidence files should be inspected next?
5. What fix should be planned from the evidence?

