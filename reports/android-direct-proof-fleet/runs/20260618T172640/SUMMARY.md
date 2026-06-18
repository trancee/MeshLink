# Android direct-proof fleet run summary

## Outcome

- Sweep mode: continue-on-failure
- Total directed pairs discovered: 30
- Completed pairs: 30
- Stopped early: no
- Passing pairs: 0
- Unsupported pairs: 0
- Remaining failure pairs: 30

## What happened

1. The rerun covered all 30 directed Android pairs in the attached fleet.
2. No device was excluded as unsupported; every pair stayed in scope.
3. The rerun still failed across the fleet, with the majority of outcomes classed as capture/route stalls and a small number of route-discovery or summary-missing failures.
4. This bundle therefore documents the exact supported-device failure shape that still needs a code-path bugfix.

## Failure breakdown

- Capture/route stall: 30 pairs

## Key evidence

- Fleet inventory: `fleet.md` and `fleet.json`
- Matrix summary: `matrix-report.md`
- Per-pair reports: `01_a065_nam_lx9_report.md` through `30_e940_cph2359_report.md`
- Matrix results: `matrix-results.json`

## Reader note

The exhaustive rerun confirms that all devices remain supported in the workflow. The next step is to fix the supported-device completion path that still prevents `proof.complete` from being recorded for every pair.
