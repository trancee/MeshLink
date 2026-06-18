# Android direct-proof fleet run summary

## Outcome

- Sweep mode: fail-fast
- Total directed pairs discovered: 30
- Completed pairs: 2
- Stopped early: yes
- Stop reason: `pair a065_xcover failed during capture`

## What happened

1. `a065_nam_lx9` passed end-to-end on GATT fallback.
2. `a065_xcover` failed during capture and the sweep stopped immediately.
3. The sweep did not reach `a065_mi_note3` in this run.

## Key evidence

- Fleet inventory: `fleet.md` and `fleet.json`
- Matrix summary: `matrix-report.md`
- Per-pair reports:
  - `01_a065_nam_lx9_report.md`
  - `02_a065_xcover_report.md`
- Xcover failure report:
  - initial summary: `02_a065_xcover_initial/summary.json`
  - initial HTML report: `02_a065_xcover_initial/summary.html`
  - initial sender logcat: `02_a065_xcover_initial/sender_logcat.log`
  - initial passive logcat: `02_a065_xcover_initial/passive_logcat.log`

## Observed failure mode

The xcover pair failed before retained completion.
The report shows the sender emitted `proof.failed` with `Error(GATT benchmark)` during the capture phase.

## Next fix to try

- Inspect the xcover sender and passive logcats for the GATT benchmark failure path.
- Check whether the passive SDK 28 GATT fallback is too brittle for this pair and needs a device-specific guard or a different transport selection.
- Keep the fail-fast sweep behavior unchanged so later failures are not masked.

## Related paths

- Run index: `INDEX.md`
- Run bundle root: `.`
- Top-level reports index: `../../../../INDEX.md`
