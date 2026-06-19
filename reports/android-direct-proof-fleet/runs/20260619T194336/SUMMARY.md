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
3. The rerun still failed across the fleet, with the entire remaining failure shape now collapsed into capture-stage direct-proof stalls.
4. This bundle documents the supported-device failure shape that still needs a follow-up code-path fix.

## Failure breakdown

- Capture/route stall: 30 pairs

## Key evidence

- Fleet inventory: `fleet.md` and `fleet.json`
- Matrix report: `matrix-report.md`
- Per-pair reports: `NN_<pair_label>_report.md`
