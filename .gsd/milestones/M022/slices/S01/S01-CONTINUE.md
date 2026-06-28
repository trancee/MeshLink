---
milestone: M022
slice: S01
task: none
step: 1
total_steps: 1
status: ready_to_resume
saved_at: 2026-06-27T18:22:18Z
branch: docs/direct-proof-handoff
head_commit: 0174b240
---

## Completed Work
- Restored the proof startup baseline to 30s for the Android direct-proof rerun.
- Reran the proof app twice on `1f1dad34` (sender) ↔ `R5CT83ACSJX` (passive) using the default library-owned MeshLink launch path.
- Updated `reports/android-direct-proof-fleet/2026-06-27-fleet-report.md` with the rerun artifacts and log analysis.

## Current Finding
- The passive app reaches `android.factory.begin`, `android.meshlink.factory.begin`, `android.meshlink.bootstrap.begin`, and `android.meshlink.controller.begin`.
- After that, the 30s run only emitted ambient GATT scan acceptances on other peers and repeated `GATT side-link already active` / `connectIfNeeded skipped: no PSM` lines.
- No sender-facing route, peer discovery, or send/completion markers were reached for this pair.
- The 60s diagnostic retry showed the same missing-route boundary, so the issue is discovery/route setup rather than the timeout budget alone.

## Key Artifacts
- Report: `reports/android-direct-proof-fleet/2026-06-27-fleet-report.md`
- Rerun summaries:
  - `/tmp/reference_android_direct_proof_20260627T200612/summary.json`
  - `/tmp/reference_android_direct_proof_20260627T200846/summary.json`
- Passive logs:
  - `/tmp/reference_android_direct_proof_20260627T200612/passive_logcat.log`
  - `/tmp/reference_android_direct_proof_20260627T200846/passive_logcat.log`

## Resume Here
1. Inspect the sender-side log for the same reruns if you want the paired view of the missing-route boundary.
2. Try a different attached sender/passive pair if you want to separate pair-specific behavior from the current transport setup.
3. Keep the proof-app docs aligned with the library-owned transport contract; do not reintroduce proof-app transport override plumbing.
