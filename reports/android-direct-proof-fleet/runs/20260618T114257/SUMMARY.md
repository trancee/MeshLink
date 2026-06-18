# Android direct-proof fleet run summary

## Outcome

- Sweep mode: fail-fast
- Total directed pairs discovered: 30
- Completed pairs: 3
- Stopped early: yes
- Stop reason: `pair a065_mi_note3 failed during capture`

## What happened

1. `a065_nam_lx9` passed end-to-end on GATT fallback.
2. `a065_xcover` passed end-to-end on GATT fallback.
3. `a065_mi_note3` failed during the capture phase and the sweep stopped immediately.

## Key evidence

- Fleet inventory: `fleet.md` and `fleet.json`
- Matrix summary: `matrix-report.md`
- Per-pair reports:
  - `01_a065_nam_lx9_report.md`
  - `02_a065_xcover_report.md`
  - `03_a065_mi_note3_report.md`
- Mi Note 3 failure report:
  - initial summary: `03_a065_mi_note3_initial/summary.json`
  - initial HTML report: `03_a065_mi_note3_initial/summary.html`
  - initial sender logcat: `03_a065_mi_note3_initial/sender_logcat.log`
  - initial passive logcat: `03_a065_mi_note3_initial/passive_logcat.log`

## Observed failure mode

The Mi Note 3 pair did not produce a retained completion during capture.
The run report shows the passive proof app started and emitted `gatt.benchmark.start() -> Started`, but the sender did not complete the capture path before the fail-fast stop.

## Next fix to try

- Inspect the Mi Note 3 sender and passive logcats for the missing completion marker.
- Check whether the GATT fallback path on SDK 28 needs a tighter startup/transport wait or a device-specific workaround.
- If the failure reproduces, add a dedicated Mi Note 3 regression note and keep the fail-fast sweep behavior unchanged.

## Related paths

- Run index: `INDEX.md`
- Run bundle root: `.`
- Top-level reports index: `../../../../INDEX.md`
