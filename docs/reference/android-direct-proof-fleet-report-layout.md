# Android direct-proof fleet report layout

This page is a concise repository-tracked sample of the report bundle shape produced by the Android direct-proof fleet run.

## Sample bundle

```text
reports/android-direct-proof-fleet/runs/<timestamp>/
├── fleet.json
├── fleet.md
├── matrix-results.json
├── matrix-report.md
├── 01_a065_nam_lx9_report.md
├── 01_a065_nam_lx9_initial/
└── 01_a065_nam_lx9_final/
```

## Why this exists

Use this sample to understand the expected repository-local report bundle without hunting through generated output.
It complements [Android direct-proof fleet reports](android-direct-proof-fleet-reports.md), which explains the artifact contents and troubleshooting references,
[Reports index](../../reports/INDEX.md), which provides the repository-tracked entry point for generated report bundles,
and the tracked bundle guide at [reports/android-direct-proof-fleet/README.md](../../reports/android-direct-proof-fleet/README.md).
