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

## 2026-05-25 — Final transition architecture

Chosen shape: keep `SessionTransitionService` as the single transition execution module,
with `ReferenceSessionController` as the session boundary owner and
`TechnicalTimelineStore` as the evidence-state module. Do not introduce a separate
planner/executor seam or a second session-state-machine seam at this time.

Reason:
- keeps one real seam for transition execution instead of splitting the same logic across planner, executor, and callers
- preserves the current ownership split: navigation owns routes, the controller owns session boundaries, the service owns transition orchestration
- avoids shallow wrapper modules that would mostly forward to the same controller/export/retention calls
- keeps the deletion test strong: removing the service would re-spread the branching matrix into navigation, automation, and the evidence surface
- leaves room for future private helper extraction inside the service if one branch family becomes materially more complex, without committing to a second public seam too early

## 2026-05-25 — ReferenceSessionController internal state seam

Chosen shape: keep `ReferenceSessionController` as the single internal owner of
`currentKind`, `snapshotFlow`, and the supported/alternative session publication
rules. Do not extract a separate session-state store or session-boundary state
machine at this time.

Reason:
- the controller already has a small, cohesive interface centered on session boundaries
- `SupportedControllerRuntime` already hides the supported-runtime-specific lifecycle/binding concerns
- extracting `currentKind` + snapshot publication into another module now would mostly move a compact transition table into a shallow wrapper
- a second state-machine seam would overlap awkwardly with `SessionTransitionService`, weakening ownership clarity instead of improving it
- if the controller grows later, private helper extraction remains available without committing to another architectural module early
