# Fleet matrix reports

This workflow keeps the Android fleet sweep reports inside the repository so they can be reviewed, diffed, and committed alongside the code that produced them.

## Default report location

When `meshlink-reference/scripts/run_headless_reference_android_direct_matrix.py` runs without `--run-root`, it writes into:

- `.gsd/workflows/bugfixes/260618-2-run-the-complete-android-fleet-with-all/runs/<timestamp>/`

That directory becomes the canonical report bundle for the sweep.

## Sample committed bundle layout

The example layout below is committed in the repository as the workflow report bundle sample.
It shows the shape the generated run directory should follow.

```text
.gsd/workflows/bugfixes/260618-2-run-the-complete-android-fleet-with-all/runs/<timestamp>/
├── fleet.json
├── fleet.md
├── matrix-results.json
├── matrix-report.md
├── 01_a065_nam_lx9_report.md
├── 01_a065_nam_lx9_initial/
└── 01_a065_nam_lx9_final/
```

## Report bundle contents

Each run produces:

- `fleet.json` — the enumerated device inventory before the first pair starts
- `fleet.md` — human-readable fleet inventory and directed pair list
- `matrix-results.json` — structured per-pair summary data
- `matrix-report.md` — end-of-run summary with pass/fail buckets, setup notes, and follow-up pointers
- `NN_pairlabel_report.md` — one Markdown report per pair, including:
  - setup details
  - transport selection
  - quirks and failure notes
  - Mermaid sequence diagram
  - artifact references
  - nested timing JSON
- `NN_pairlabel_initial/` and `NN_pairlabel_final/` — raw proof-run directories

## Raw evidence referenced by the per-pair report

The per-pair report points to the underlying proof artifacts so failures can be debugged without rerunning the sweep:

- `summary.json`
- `summary.html`
- `sender_logcat.log`
- `passive_logcat.log`
- `sender_start.txt`
- `passive_start.txt`
- `android_history.json`
- `android_export.json`

## Review expectation

The pair report should be sufficient to answer:

- which devices were in scope
- which transport path was used
- how far the run got before it failed
- which raw logs and evidence files should be inspected next
- what fix should be planned from the evidence
