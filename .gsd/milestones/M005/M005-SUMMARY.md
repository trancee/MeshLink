---
id: M005
title: "iOS Bootstrap Re-scope"
status: complete
completed_at: 2026-06-08T09:30:49.340Z
key_decisions:
  - Re-scoped mixed direct-guided live-proof out of the release campaign until a real iOS bootstrap seam is introduced.
key_files:
  - meshlink-reference/scripts/reference_fleet.py
  - meshlink-reference/scripts/tests/test_reference_fleet.py
  - meshlink-reference/scripts/tests/test_reference_release_campaign.py
  - meshlink-reference/scripts/tests/test_release_review_report.py
  - .gsd/milestones/M005/M005-VALIDATION.md
lessons_learned:
  - Route emission depends on a completed handshake, and the current iOS live-proof path has no bootstrap seam to get there.
  - Selection logic must be honest about unsupported platform capability rather than selecting a runnable-but-doomed mixed path.
---

# M005: iOS Bootstrap Re-scope

**The release campaign now falls back to Android-only direct-guided instead of repeatedly selecting unsupported mixed iOS live-proof.**

## What Happened

I traced the mixed-fleet live-proof failure through the release campaign and the shared engine and confirmed the blocker was architectural: iOS live-proof has no bootstrap factory, and the shared engine path does not consume a generic iOS bootstrap for route establishment. Rather than inventing a platform seam that the repo does not currently support, I re-scoped the release campaign selection logic so the Android-only direct-guided baseline is chosen whenever mixed iOS live-proof is not supported. The campaign-selection and report-rendering tests were updated and passed, so retained artifacts remain honest and offline-renderable without the unsupported mixed path.

## Milestone note

The release-review campaign is still allowed to discover a mixed fleet, but the current host and repository state do not provide a real iOS bootstrap seam for live mixed proof. Release review therefore records the mixed path as unsupported for now and keeps the Android-only fallback explicit in the retained manifest, docs, and milestone record.

## Success Criteria Results

- Mixed direct-guided live-proof is no longer selected for the release campaign when iOS bootstrap support is unavailable: verified by `test_reference_fleet.py` and `test_reference_release_campaign.py`.
- The campaign still produces retained artifacts and valid report-data.json using the Android-only direct-guided baseline: verified by `test_reference_release_campaign.py` and `test_release_review_report.py`.
- Tests cover the new selection behavior and keep the unsupported mixed path honest: verified by the updated reference-fleet tests.

## Definition of Done Results

- Selection logic prefers Android-only direct-guided when mixed iOS bootstrap support is unavailable: implemented in `meshlink-reference/scripts/reference_fleet.py`.
- Tests verify the mixed assignment is not chosen and Android-only remains runnable: implemented in `meshlink-reference/scripts/tests/test_reference_fleet.py`.
- Campaign execution still produces retained report artifacts without manual intervention: verified by the release-campaign and report-rendering tests.

## Requirement Outcomes

- R009 advanced by re-scoping the release-review path away from unsupported mixed live-proof so the retained proof set stays honest.
- The previous mixed-fleet blocker is now treated as deferred architectural work rather than a failing campaign state.

## Deviations

I did not implement an iOS bootstrap API because the repository does not contain a real iOS bootstrap source to thread through the engine. I re-scoped the campaign instead.

## Follow-ups

If mixed iOS live-proof is needed later, add a real iOS bootstrap API and thread it through the engine as a separate milestone.
