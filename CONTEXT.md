# MeshLink Reference App

The MeshLink reference app is the operator-facing evaluation surface for the MeshLink SDK. It helps reviewers prove live behavior, inspect retained evidence, and separate supported product-reference workflows from non-normative lab behavior.

## Language

**Surface**:
A named operator-facing area of the reference app with a stable purpose and audience. Surfaces include the guided flow, advanced controls, technical timeline, recent history, lab, and solo exploration.
_Avoid_: Screen, tab, mode

**Advanced controls**:
The supported surface for deliberate operator inspection and runtime actions beyond the guided flow. It exposes a curated set of evaluation-relevant controls without becoming a general-purpose SDK console.
_Avoid_: Debug shell, expert mode, full SDK console

**Evaluation-relevant capability**:
A supported MeshLink capability that materially changes what an operator can demonstrate, compare, or explain in the reference app. Evaluation-relevant capabilities may belong in advanced controls; low-level SDK knobs that do not improve evaluation do not.
_Avoid_: Every supported option, internal knob, full SDK parity

**Configuration snapshot**:
The operator-visible set of supported configuration values that defines one evaluated session. A configuration snapshot stays fixed for the session being reviewed or exported rather than drifting with later app changes.
_Avoid_: Current config, latest values, live config

**Session boundary**:
The point where the reference app stops treating evidence as part of one evaluated session and starts a new one. A new session boundary is required when an evaluation-relevant capability changes the conditions being assessed.
_Avoid_: Continue same session, silent drift, mixed evidence

**Session rollover**:
The explicit, operator-confirmed transition from one live session to the next after a boundary-changing action. For a supported live session, session rollover includes a direct pre-rollover export path before the old session becomes retained history.
_Avoid_: Auto-restart, silent session reset

**Pre-rollover export**:
The last export opportunity for a supported live session before session rollover closes the full-payload path. It may include full payload only when the operator explicitly chooses it.
_Avoid_: After-the-fact full export, warning only

**Pre-end export**:
The last export opportunity for a supported live session before the operator ends that session explicitly. It may include full payload only when the operator explicitly chooses it.
_Avoid_: After-the-fact full export, warning only

**Live session**:
The current active session open in the reference app before it becomes retained history. A live session may belong to the supported path or to a non-authoritative or non-normative path.
_Avoid_: Current history, retained session, archived runtime

**Session end**:
The point where a live session stops accumulating new evidence. Session end is separate from retention, so an ended session may be retained or omitted from recent history depending on its path and reviewable evidence.
_Avoid_: Same as retention, always retained, retained-only end

**End session action**:
The explicit operator action that closes the current evidence window for a session. It is separate from lifecycle controls such as start, pause, resume, and stop, and for a supported live session it includes a direct pre-end export path before full-payload export closes.
_Avoid_: Stop mesh, implicit end, session stop

**Ended session view**:
The read-only presentation of the most recently ended session before the operator starts a new live session. It keeps the closed evidence window visible without silently creating a replacement session, even if the same ended session has already been added to recent history.
_Avoid_: Auto-start next session, hidden reset, live session

**Review-only ended session view**:
The ended-session view with inspection and export affordances but no runtime controls, send actions, peer actions, or trust actions. It exists to review a closed evidence window, not to mutate it.
_Avoid_: Closed live session, editable ended session, post-end controls

**Supported live session**:
A live session on the supported guided or advanced path rather than the solo or lab path. Only a supported live session may use full-payload export or automatic retention.
_Avoid_: Any live session, solo session, lab session

**Retained session**:
A previously captured local session snapshot kept separate from the live session for review. Retained sessions are created automatically when a live session ends or rolls over if it produced reviewable evidence, preserve redacted evidence, and do not keep full payload content.
_Avoid_: Saved live session, archived runtime

**Recent history**:
The surface that lists retained sessions that can be reopened later for review. Recent history is for retained sessions only, not for the immediate ended-session view.
_Avoid_: Ended session view, live session list, export index

**History state**:
Where a session currently lives in the reference app’s evidence model. History state is about whether a session is live or retained, not about whether it has been exported.
_Avoid_: Export status, artifact state, file status

**History deletion**:
The explicit removal of a retained session from recent history. History deletion removes the retained entry instead of placing the session into a separate persistent deleted state.
_Avoid_: Deleted session state, retained tombstone, soft delete

**Automatic retention**:
The default rule that a finished live session with reviewable evidence is added to recent history immediately without requiring a separate operator action. Automatic retention preserves evidence before the operator moves on to a new session, but it does not apply to solo sessions or lab sessions.
_Avoid_: Optional save, manual retain only

**Reviewable evidence**:
Operator-visible startup blockers, lifecycle changes, peer changes, sends, trust changes, or diagnostic timeline activity that make a finished session worth reopening. A trivial session with no meaningful evidence is not retained.
_Avoid_: Successful proof only, empty session, incidental open

**Startup-blocked session**:
A live session whose main evidence is one or more unresolved startup blockers before mesh participation begins. A startup-blocked session still counts as reviewable evidence and may be retained for later review.
_Avoid_: Not a real session, discardable attempt

**Session checkpoint**:
A partial capture of an ongoing live session. Session checkpoints are not part of the current reference-app evidence model, so operators do not create separate mid-session retained entries.
_Avoid_: Retain live session, manual retain, partial retained session

**Session artifact**:
An exported evidence document for one session. A session artifact summarizes the scenario, peer state, timeline, and export redaction policy, and a single session may produce multiple session artifacts.
_Avoid_: Dump, log file, report

**Artifact instance**:
One unique session artifact created by one export action. Different artifact instances may share the same source session while differing by export time or payload policy, and each artifact instance has its own identifier and storage path.
_Avoid_: Canonical export file, overwritten export, one true artifact

**Export action**:
An operator request that creates a new session artifact instance from a session without changing that session’s history state. Export actions and history state are separate concerns, and repeated export actions may produce multiple artifacts for the same session.
_Avoid_: Exported session state, promote to exported, replace retained state

**Export independence**:
The rule that deleting, clearing, or pruning retained-session history does not delete session artifact instances that were already exported. Export cleanup, if offered at all, is a separate explicit action.
_Avoid_: History delete removes exports, hidden evidence cleanup

**Export index**:
A browsable in-app archive of exported artifact instances. An export index is not part of the current reference-app evidence model; exported artifacts remain external file outputs rather than a second retained-history system.
_Avoid_: Export history surface, in-app artifact archive, second recent-history list

**History pruning**:
The automatic removal of the oldest retained-session entries when recent history exceeds its bounded limit. History pruning affects retained-session history only and does not remove exported artifact instances.
_Avoid_: Export cleanup, evidence deletion, unbounded history

**Timestamp**:
A conceptual point in time used in the reference-app documentation. In conceptual docs, `Timestamp` does not imply a concrete storage type or library type.
_Avoid_: Instant, datetime object

**Export timestamp**:
A timestamp written into a session artifact for interchange with readers outside the live runtime. Export timestamps are canonical UTC ISO 8601 strings in `YYYY-MM-DDTHH:MM:SS.SSSZ` form.
_Avoid_: Epoch text, local timestamp

**Internal timestamp**:
A timestamp stored inside the live reference-app model for runtime logic and retention bookkeeping. Internal timestamps are stored as epoch-millis values even when the exported artifact presents them differently.
_Avoid_: Export timestamp, display timestamp

**Retained-history store**:
The app-local JSON store that keeps retained session state for the reference app itself. The retained-history store is internal data, not a session artifact, and it stays on internal epoch-millis storage.
_Avoid_: Export file, evidence artifact

**Redacted export**:
A session artifact that includes payload metadata and redacted previews without full payload content. This is the default export path and the only export mode available for retained, solo, and lab sessions.
_Avoid_: Safe export, normal export

**Full-payload export**:
A session artifact that includes payload content after an explicit operator choice. Full-payload export is available only from a supported live session.
_Avoid_: Raw export, complete export

**Lab**:
The explicitly non-normative surface for proof-only or benchmark-only behavior. The lab does not replace the supported product-reference path.
_Avoid_: Advanced mode, expert mode

**Lab session**:
A session produced on the lab surface. A lab session remains non-normative, is not added to recent history automatically, and may be exported only through an explicit redacted export.
_Avoid_: Retained reference proof, supported-history entry, normal recent-history entry

**Solo exploration**:
The one-device, non-authoritative walkthrough surface. It allows inspection and orientation without claiming live peer proof.
_Avoid_: Demo mode, offline proof

**Solo session**:
A session produced on the solo exploration path. A solo session remains non-authoritative, is not added to recent history automatically, and may be exported only through an explicit redacted export.
_Avoid_: Retained live proof, authoritative session, normal recent-history entry

## Example dialogue

Developer: “The live session ended, recent history kept it automatically, and the operator reopened it as a retained session before exporting a redacted artifact.”

Domain expert: “Good — that means the session had reviewable evidence, they reviewed a retained session rather than the live one, and the export stayed on the default redacted path.”

Developer: “If they need payload content, they must return to the live session and choose the full-payload export explicitly.”

Domain expert: “Exactly. And if they want proof-only experiments, they should move into the lab, not the guided or advanced surfaces.”
