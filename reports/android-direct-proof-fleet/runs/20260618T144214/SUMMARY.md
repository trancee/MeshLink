# Android direct-proof fleet run summary

## Outcome

- Sweep mode: continue-on-failure
- Total directed pairs discovered: 30
- Completed pairs: 30
- Stopped early: no
- Passing pairs: 0
- Dominant failure mode: capture/route stall

## What happened

1. The rerun covered all 30 directed Android pairs in the attached fleet.
2. The low-API sender contract was corrected: passive `gatt` fallback runs now keep the sender on `meshlink` instead of switching it to `gatt-notify`.
3. The repaired low-API xcover path now shows `benchmarkTransport=meshlink` on the sender and `start() with l2capPsm=227` in the retained transport logs, but the overall matrix still times out in capture before `proof.complete`.
4. Every pair in this rerun failed with capture/route stall, so the bundle is evidence of the contract fix and of the remaining capture-window issue.

## Key evidence

- Fleet inventory: `fleet.md` and `fleet.json`
- Matrix summary: `matrix-report.md`
- Per-pair reports: `01_a065_nam_lx9_report.md` through `30_e940_cph2359_report.md`
- Low-API xcover sender logcat: `02_a065_xcover_initial/sender_logcat.log`
- Low-API xcover summary JSON: `02_a065_xcover_initial/summary.json`
- Low-API xcover passive logcat: `02_a065_xcover_initial/passive_logcat.log`
- Low-API xcover matrix report: `02_a065_xcover_report.md`

## Reader note

The rerun bundle now documents the sender-side transport-selection fix, but it also makes clear that a separate capture/route-stall problem remains for this fleet and needs follow-up before the matrix can pass end-to-end.
