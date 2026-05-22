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

**Live session**:
The current active reference-app session backed by the running MeshLink runtime. A live session is the only session that can be retained again or exported with full payload content.
_Avoid_: Current history, active artifact

**Retained session**:
A previously captured local session snapshot kept separate from the live session for review. Retained sessions preserve redacted evidence and do not keep full payload content.
_Avoid_: Saved live session, archived runtime

**Session artifact**:
An exported evidence document for one session. A session artifact summarizes the scenario, peer state, timeline, and export redaction policy.
_Avoid_: Dump, log file, report

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
A session artifact that includes payload metadata and redacted previews without full payload content. This is the default export path.
_Avoid_: Safe export, normal export

**Full-payload export**:
A session artifact that includes payload content after an explicit operator choice. This export is only available from the live session.
_Avoid_: Raw export, complete export

**Lab**:
The explicitly non-normative surface for proof-only or benchmark-only behavior. The lab does not replace the supported product-reference path.
_Avoid_: Advanced mode, expert mode

**Solo exploration**:
The one-device, non-authoritative walkthrough surface. It allows inspection and orientation without claiming live peer proof.
_Avoid_: Demo mode, offline proof

## Example dialogue

Developer: “The operator retained the live session, reopened it from recent history, and exported a redacted artifact.”

Domain expert: “Good — that means they reviewed a retained session, not the live session, and the export stayed on the default redacted path.”

Developer: “If they need payload content, they must return to the live session and choose the full-payload export explicitly.”

Domain expert: “Exactly. And if they want proof-only experiments, they should move into the lab, not the guided or advanced surfaces.”
