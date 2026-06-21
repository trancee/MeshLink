# Android direct-proof fleet run 20260621T124016

This bundle captures the republished rerun of the attached Android fleet after the direct-proof launch-floor and route-failure detection fixes.

## Run summary

| Metric | Value |
|---|---|
| Completed pairs | 6 |
| Passing pairs | 0 |
| Failing pairs | 6 |
| Pending pairs | 24 |
| Fail-fast | disabled |
| Stopped early | yes |
| Stop reason | failure count 6 exceeded max-failures=5; investigate the recorded failures before continuing |

## Primary artifacts

- [SUMMARY.md](SUMMARY.md)
- [matrix-report.md](matrix-report.md)
- [matrix-results.json](matrix-results.json)
- [fleet.md](fleet.md)
- [fleet.json](fleet.json)

## Pair reports

| # | Pair | Final outcome | Report |
|---|---|---|---|
| 01 | `a065_nam_lx9` | failed capture | [01_a065_nam_lx9_report.md](01_a065_nam_lx9_report.md) |
| 02 | `a065_xcover` | failed capture | [02_a065_xcover_report.md](02_a065_xcover_report.md) |
| 03 | `a065_mi_note3` | failed capture | [03_a065_mi_note3_report.md](03_a065_mi_note3_report.md) |
| 04 | `a065_cph2359` | failed capture | [04_a065_cph2359_report.md](04_a065_cph2359_report.md) |
| 05 | `a065_e940` | failed capture | [05_a065_e940_report.md](05_a065_e940_report.md) |
| 06 | `nam_lx9_a065` | failed capture | [06_nam_lx9_a065_report.md](06_nam_lx9_a065_report.md) |

## Notes

- This bundle is the latest tracked evidence for the fleet rerun.
- The captured failures are open investigation items; none are being treated as unsupported for this milestone.
