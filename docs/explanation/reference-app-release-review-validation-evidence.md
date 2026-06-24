# Offline release-review validation evidence

This note records the retained browser/runtime proof for the offline release-review
surface. It exists so a cold reader can trust the review path from disk-backed
artifacts, not from raw log archaeology.

## What this proof supports

The proof supports the active validation-surface requirement **R010** by showing
that the retained release-review report is inspectable offline, that the report
renders honest verdict math from retained campaign data, and that the reviewer
can follow the evidence pointers back to the retained campaign run root.

## Retained artifacts to inspect first

Start with the campaign artifacts in this order:

1. `campaign-plan.json` — planned scenario order, selected baseline, and artifact locations; selection status stays in the retained `selected` / `skipped` / `invalid-environment` vocabulary.
2. `campaign-state.json` — actual scenario statuses, happy-path gate state, and failure provenance; execution stays in the retained `pass` / `fail` / `skipped` / `invalid-environment` vocabulary.
3. `report-data.json` — retained verdict counts, gate math, and scenario inspection pointers.
4. `release-review-report.html` — the offline reviewer surface rendered only from retained report data.

The retained browser proof used the run root:

- `.gsd/uat-s05-run`

## Browser/runtime check performed

The retained report was opened directly from disk with the browser at:

- `file:///home/phil/Projects/MeshLink/.gsd/uat-s05-run/release-review-report.html`

The browser-visible surfaces that were checked were:

- the **Inputs** panel, which shows the run root and the source campaign files
- the **Verdict counts** section
- the **Gate math** section, including the retained percentage values
- the offline boundary note in the footer: `Rendered from retained report-data.json only`
- the retained relative evidence links for `campaign-plan.json` and `campaign-state.json`

The browser network log showed only the local document request for the HTML file.
No external assets were requested.

## Observed result

The report rendered successfully and stayed self-contained. The page showed the
expected input provenance, honest verdict counts, gate math, scenario summaries,
and the offline-only boundary text. The retained links in the report remained
relative to the run root, which keeps the proof inspectable alongside the archived
campaign artifacts.

## Why this matters for validation

This proof closes the gap between “the campaign produced data” and “a reviewer can
re-open that data later and still understand the outcome.” If the offline review
surface regresses, the failure will show up as a missing or broken retained
artifact, not as a hidden assumption in a live log stream.

## Quick reviewer checklist

- confirm the campaign run root exists and contains the retained JSON artifacts
- open `release-review-report.html` from the run root, not from a live service
- verify the Inputs panel points at the retained plan/state files
- verify the verdict totals and gate math are visible in the HTML
- verify the footer still states that the report was rendered from retained `report-data.json` only

## Relationship to the review flow

The retained report is the reviewer-facing surface. The campaign artifacts are the
source of truth, and the HTML report is the offline rendering of that truth. This
note exists to make that chain explicit and to give reviewers a stable first stop
for the validation-surface proof.
