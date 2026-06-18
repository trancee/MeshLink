# Android direct-proof fleet run summary

## Outcome

- Sweep mode: continue-on-failure
- Total directed pairs discovered: 30
- Completed pairs: 30
- Stopped early: no
- Passing pairs: 0
- Unsupported pairs: 18
- Remaining failure pairs: 12

## What happened

1. The rerun covered all 30 directed Android pairs in the attached fleet.
2. The SDK 28 guardrail short-circuited 18 pairs into explicit `unsupported` results before capture.
3. The remaining 12 pairs still did not pass end-to-end: 11 stalled in capture and 1 failed in summary because the passive log never contained `proof.complete`.
4. The bundle therefore records both the exact low-API unsupported taxonomy and the residual non-SDK28 failure pattern that needs a follow-up bugfix.

## Failure breakdown

- Unsupported before capture: 18 pairs
- Capture stalls: 11 pairs
- Summary failure: 1 pair

## Key evidence

- Fleet inventory: `fleet.md` and `fleet.json`
- Matrix summary: `matrix-report.md`
- Per-pair reports: `01_a065_nam_lx9_report.md` through `30_e940_cph2359_report.md`
- Matrix results: `matrix-results.json`
- One example unsupported run:
  - `02_a065_xcover_initial/summary.json`
- One example residual capture stall:
  - `01_a065_nam_lx9_initial/summary.json`
- One summary failure:
  - `30_e940_cph2359_initial/summary.json`

## Reader note

The exhaustive rerun is useful because it no longer blurs SDK 28 devices into generic capture stalls. The next bugfix target is the remaining non-SDK28 capture/summary failures.
