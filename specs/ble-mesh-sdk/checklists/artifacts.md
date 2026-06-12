# Cross-artifact requirements checklist: MeshLink offline BLE mesh SDK

Use this checklist to confirm that the MeshLink requirements stay complete,
clear, consistent, measurable, and review-ready across `spec.md`, `plan.md`,
and `tasks.md`.

**Created**: 2026-05-12
**Feature**: [spec.md](../spec.md)

**Note**: This checklist reviews the written artifact set, not implementation
correctness.

**Re-review update (2026-05-14):** After the follow-up artifact tightening,
release-decision record, and dedicated discovery contract were added, this
checklist was re-run against the current core artifacts and supporting docs. All
items below now pass.

## Requirement completeness

- [x] CHK001 Are offline-operation requirements fully represented across spec, plan constraints, and quickstart assumptions?
- [x] CHK002 Are trust lifecycle requirements complete across initial TOFU acceptance, identity-change rejection, explicit revocation, and restart recovery?
- [x] CHK003 Are large-transfer recovery requirements complete for chunking, selective acknowledgements, route changes, expiry handling, and restart boundaries?
- [x] CHK004 Are low-power requirements complete enough to cover scan duty, connection-interval expectations, connection limits, and chunk-budget behavior?

## Requirement clarity

- [x] CHK005 Is best-effort delivery in low-power mode clarified with explicit degradation and failure boundaries?
- [x] CHK006 Is the benchmark protocol for throughput and latency clear enough to measure `SC-004` consistently?
- [x] CHK007 Is artifact status wording clear across spec and plan?
- [x] CHK008 Is the iOS throughput shortfall framed clearly enough to tell reviewers whether it is an open risk, unmet release criterion, or accepted exception?

## Requirement consistency

- [x] CHK009 Are runtime dependency constraints consistent across the spec, plan, and setup tasks?
- [x] CHK010 Are parity requirements consistent between the public SDK contract, the spec’s Android/iOS parity language, and the implementation tasks?
- [x] CHK011 Are discovery and transport requirements consistent between the spec clarifications, dedicated discovery contract, quickstart guidance, and transport tasks?
- [x] CHK012 Is each success criterion traceably represented by at least one explicit validation or benchmark task?

## Acceptance-criteria quality

- [x] CHK013 Can the quickstart success criterion be reviewed objectively without hidden environmental assumptions?
- [x] CHK014 Are route-convergence requirements specific enough to distinguish control-plane convergence from end-to-end delivery recovery?
- [x] CHK015 Can parity claims such as “same meanings” and “equivalent workflows” be evaluated from the written artifacts alone?

## Scenario and edge-case coverage

- [x] CHK016 Are mixed-platform participation requirements defined beyond lifecycle parity, including discovery, transport fallback, and performance expectations?
- [x] CHK017 Are rollback or recovery expectations defined for partially successful validation runs that surface environmental blockers?
- [x] CHK018 Are restart-related requirements clear about what state is rebuilt, what delivery is resubmitted, and what trust survives?
- [x] CHK019 Are exception-flow requirements defined for missing permissions, unsupported transport capability, and incompatible protocol versions?

## Non-functional and security coverage

- [x] CHK020 Are persisted-data minimization and diagnostic-redaction requirements carried consistently from the spec into plan constraints and validation expectations?
- [x] CHK021 Are cryptographic requirements specific enough about which guarantees are normative and where they are validated?
- [x] CHK022 Are performance requirements complete for throughput, latency, memory, power, cold start, convergence, and wire-codec behavior?

## Dependencies and assumptions

- [x] CHK023 Are external dependency and hardware assumptions explicit enough for reviewers to distinguish product gaps from environmental blockers?
- [x] CHK024 Are host-app responsibilities clearly separated from SDK responsibilities for trust UX, recipient selection, and message resubmission after restart?
- [x] CHK025 Do any artifacts rely on vague review language such as “approved” or “complete” without making the blocking condition explicit?

## Artifact governance and source-of-truth boundaries

- [x] CHK026 Is it explicit which artifact is normative when `spec.md`, `plan.md`, and `tasks.md` differ?
- [x] CHK027 Are the roles of the three core artifacts clearly separated so a reviewer can distinguish requirements, design constraints, and execution history?
- [x] CHK028 Does the task ledger define append-only handling for newly discovered follow-up work after tasks are marked complete?
- [x] CHK029 Are rerun expectations documented clearly enough to preserve canonical artifacts while still allowing remediation work?
- [x] CHK030 Are task-completion markers and phase summaries clearly distinguishable from normative requirements?

## Reviewer decision framing

- [x] CHK031 Are known risks, unmet success criteria, and accepted documentation updates labeled clearly enough to show what still blocks release?
- [x] CHK032 Are benchmark baselines clearly separated from normative acceptance thresholds?
- [x] CHK033 Is the role of the proof integrations clear as reference implementation, quickstart aid, benchmark harness, and physical-validation vehicle?
- [x] CHK034 Are reviewer-facing exception paths defined for environmental blockers such as signing, device availability, and nearby-mesh interference?
- [x] CHK035 Are cross-artifact references stable enough that open risks, acceptance criteria, and follow-up obligations can be traced without relying on reviewer memory?

## Notes

- Audience: PR reviewer
- Depth: standard
- Focus: broad cross-artifact review of requirement completeness, consistency,
  measurability, scenario coverage, performance, security/privacy, and
  Android/iOS parity
- Scope: `spec.md` + `plan.md` + `tasks.md`, with supporting references from
  `quickstart.md` and `contracts/`
