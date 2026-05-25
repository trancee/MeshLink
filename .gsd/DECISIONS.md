# Decisions

## 2026-05-25 — Session transition seam

Chosen shape: an internal `SessionTransitionService` with use-case-shaped operations,
plus a pure `chooseSessionSurfaceChoice(...)` helper for the decision table.

Reason:
- keeps `ReferenceSessionController` as the session boundary owner
- keeps navigation as the route owner via `applySurfaceSelection`
- hides the branching matrix for supported live / supported ended / solo / lab
- centralizes transition ordering for boundary confirmation and follow-up supported-session starts
- avoids introducing a broader coordinator module that would compete with existing ownership
