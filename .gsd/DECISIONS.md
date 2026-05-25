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

## 2026-05-25 — Session transition execution owner

Chosen shape: design 2 — `SessionTransitionService` owns transition execution, while
`ReferenceSessionController` remains the session boundary owner and the technical
timeline becomes an evidence-state module.

Reason:
- removes production callers' need to reach into `TechnicalTimelineStore` to mutate session state
- keeps route application in navigation callbacks instead of moving route ownership into the service
- lets automation, timeline actions, and follow-up supported-session starts share one execution seam
- allows the technical timeline to focus on evidence projection, retention visibility, and export state
