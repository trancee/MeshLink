# Feature Specification: MeshLink Reference App

**Feature Branch**: `002-meshlink-reference-app`  
**Created**: 2026-05-18  
**Status**: Draft  
**Input**: User description: "Create a modern, user-friendly MeshLink reference app that showcases the full library, explains what is happening, exposes logs and diagnostics, and offers the same experience on Android and iOS."

## Clarifications

### Session 2026-05-18

- Q: How should proof-only and benchmark-only features appear in the reference app? → A: Supported product capabilities stay in the main reference experience; proof-only and benchmark-only behaviors appear only in a clearly labeled separate lab section.
- Q: How much control surface should the reference app expose? → A: Use a guided default experience plus a clearly separated advanced area exposing the full public SDK configuration and runtime controls.
- Q: Should the reference app include a no-peer exploration mode? → A: Include a clearly labeled solo exploration mode for non-authoritative walkthroughs and UI inspection, while reserving authoritative delivery and diagnostics proof for live device-to-device sessions.
- Q: How should the app retain technical session history locally? → A: Automatically retain a clearly separated recent local session history, with explicit clear and delete controls.
- Q: What payload detail should exported session artifacts include? → A: Export metadata and redacted previews by default, with an explicit operator opt-in to include full payload content.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Complete a guided first exchange (Priority: P1)

As a developer evaluating MeshLink, I want to install the reference app on two
mobile devices and complete a first offline exchange quickly while the app
explains each step and proves what happened.

**Why this priority**: If the app cannot make the first successful exchange
clear and approachable, it fails its main purpose as a reference experience.

**Independent Test**: Can be fully tested by installing the reference app on
two supported devices, keeping them offline, following the default guided flow,
and confirming that one device discovers the other, sends a first message, and
shows matching delivery evidence on both sides.

**Acceptance Scenarios**:

1. **Given** the app is opened for the first time on a supported device,
   **When** the operator follows the default getting-started flow,
   **Then** the app shows readiness checks, explains the required conditions,
   starts the mesh, surfaces nearby peers, and guides the operator to a first
   successful message exchange.
2. **Given** the device is missing a prerequisite such as Bluetooth
   availability, required permission, or a compatible peer, **When** the
   operator attempts to start the first exchange flow, **Then** the app blocks
   the flow with explicit recovery guidance and does not imply that the mesh is
   already running correctly.
3. **Given** the operator has only one prepared device, **When** they enter the
   solo exploration mode, **Then** the app allows them to explore workflows,
   screens, and explanatory content without presenting simulated results as
   authoritative proof of live MeshLink delivery or diagnostics behavior.

---

### User Story 2 - Explore MeshLink capabilities from one place (Priority: P2)

As an integrator or technical reviewer, I want to exercise MeshLink lifecycle,
peer, trust, delivery, transfer, and power behaviors from one app so I can
understand how the SDK behaves without reading source code or wiring a custom
demo.

**Why this priority**: A reference app must demonstrate more than a single
message send; it needs to make the library's major capabilities inspectable and
demonstrable.

**Independent Test**: Can be fully tested on two supported devices by using the
reference app to start and stop participation, select a peer, send both short
and large payloads, observe delivery progress, reset trust, and inspect the
resulting state changes.

**Acceptance Scenarios**:

1. **Given** the mesh is running and a peer is available, **When** the
   operator changes runtime controls such as start, pause, resume, stop, peer
   selection, or delivery priority, **Then** the app shows the resulting
   library state and the latest peer and delivery outcomes without requiring
   hidden setup.
2. **Given** a trusted peer is selected, **When** the operator sends a short
   payload, initiates a large transfer, or resets trust for that peer,
   **Then** the app surfaces progress, completion or failure outcomes, and the
   trust state transition in a way that is understandable to both newcomers and
   technical reviewers.
3. **Given** a first-time evaluator is using the app, **When** they stay in the
   default reference flow, **Then** they see only the guided controls needed
   for the current scenario, and **When** they choose the advanced area,
   **Then** the full public SDK configuration and runtime controls become
   available without replacing the guided experience.

---

### User Story 3 - Inspect and share a technical session timeline (Priority: P3)

As a QA, support, or solutions engineer, I want a live technical timeline with
logs and diagnostics correlated to peers and user actions so I can understand
why a session behaved the way it did and retain useful evidence.

**Why this priority**: The app should help people debug and explain MeshLink,
not just make it look polished.

**Independent Test**: Can be fully tested by running a session with real
lifecycle changes, peer discovery, message sends, and failures, then filtering
the timeline and exporting a redacted session summary.

**Acceptance Scenarios**:

1. **Given** a session is active, **When** user actions and library events
   occur, **Then** the app records them in one chronological timeline that
   includes category, severity, peer context, and outcome context.
2. **Given** the session produces many events, **When** the operator filters by
   peer, severity, event family, or search text, **Then** the app narrows the
   visible timeline without hiding that additional session data still exists.
3. **Given** the operator wants to share evidence from the session,
   **When** they export a session artifact, **Then** the artifact includes the
   relevant configuration and timeline context plus metadata and redacted
   payload previews by default, and only includes full payload content after an
   explicit operator opt-in.
4. **Given** a session has already ended, **When** the operator opens recent
   local history, **Then** the app shows retained recent sessions separately
   from the live session and provides explicit controls to clear or delete that
   retained history.

---

### User Story 4 - Compare the same reference flows on Android and iOS (Priority: P4)

As a product reviewer, I want the reference experience to work the same way on
Android and iOS so I can evaluate the SDK once and trust that the workflow is
consistent across both supported platforms.

**Why this priority**: Cross-platform consistency is part of the value of
MeshLink itself, so the reference app must reinforce that promise.

**Independent Test**: Can be fully tested by running the same named workflows on
supported Android and iOS devices and confirming that the same tasks, labels,
explanations, and outcome categories are present on both platforms except where
operating-system rules force a clearly explained difference.

**Acceptance Scenarios**:

1. **Given** supported Android and iOS devices are prepared for the same
   reference scenario, **When** a reviewer opens the same screen or workflow on
   each platform, **Then** both platforms expose the same task sequence,
   terminology, and diagnostic meaning for that scenario.
2. **Given** a platform-specific prerequisite or limitation blocks one platform
   temporarily, **When** the reviewer reaches that workflow, **Then** the app
   explains the reason explicitly instead of silently renaming, hiding, or
   changing the feature behavior.

### Edge Cases

- The app must explain blocked startup clearly when Bluetooth is off, required
  permissions are denied, or the device is otherwise not ready to participate.
- The app must keep the first-run flow useful when no peer is discovered within
  the expected range or time window, including directing the operator into the
  clearly labeled solo exploration mode when a second device is unavailable.
- The app must show what changed when a selected peer disappears or becomes
  unreachable during a send or large transfer.
- The app must explain identity-change and trust-reset outcomes clearly for a
  previously known peer.
- The app must reject and explain payloads that exceed the supported transfer
  limit before the operator starts a misleading demo flow.
- The technical timeline must remain understandable during long or noisy
  sessions with a high volume of events.
- The app must explain platform- or device-specific blockers without making the
  Android and iOS experiences appear inconsistent or incomplete.
- The export flow must prevent accidental oversharing by redacting sensitive
  details unless the operator explicitly opts into including them.
- Exported session artifacts must include payload metadata and redacted
  previews by default and require an explicit opt-in before including full
  payload content.
- Recent local session history must stay clearly separated from the live
  session and be removable through explicit clear or delete controls.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST provide a dedicated reference experience for the
  currently supported MeshLink SDK capabilities and MUST NOT require internet
  connectivity, backend services, or user accounts for its core workflows.
- **FR-002**: The system MUST guide the operator through first-run readiness,
  including device prerequisites, offline expectations, and the steps required
  to complete a first successful message exchange.
- **FR-002a**: The system MUST provide a clearly labeled solo exploration mode
  for operators with only one prepared device. That mode MAY support guided
  walkthroughs, navigation, and static inspection, but it MUST NOT present
  simulated or placeholder behavior as authoritative proof of live peer
  discovery, delivery, or diagnostics outcomes.
- **FR-003**: The system MUST surface the current mesh runtime state, peer
  availability, peer selection, last message outcomes, and the operator's next
  recommended action from the main reference flow.
- **FR-004**: The system MUST allow the operator to start, pause, resume, and
  stop mesh participation and MUST show the resulting outcome clearly.
- **FR-005**: The system MUST allow the operator to send supported payloads to a
  selected peer and MUST show delivery progress, transfer progress, priority,
  and final outcome for both short messages and large transfers.
- **FR-006**: The system MUST surface peer discovery, peer loss, connection
  state, and trust state, and MUST allow the operator to forget a peer so the
  trust flow can be demonstrated again.
- **FR-007**: The system MUST expose power-related behavior in a way that lets
  the operator understand the active power mode, the inputs affecting that
  mode, and the resulting behavior changes relevant to MeshLink usage.
- **FR-008**: The system MUST present a live technical timeline that combines
  user actions, lifecycle changes, peer events, diagnostic events, message
  activity, and transfer activity in chronological order.
- **FR-009**: The system MUST let the operator filter, search, and narrow the
  visible technical timeline by peer, severity, event family, time range, and
  searchable event text.
- **FR-010**: The system MUST use progressive disclosure so the default view is
  approachable for first-time evaluators while a clearly separated advanced
  area exposes the full public SDK configuration, runtime controls, and deeper
  technical detail for users who want it.
- **FR-011**: The system MUST let the operator capture and export a session
  artifact containing the active scenario, current configuration, peer summary,
  technical timeline, delivery outcomes, and payload metadata, with sensitive
  details and payload content redacted by default.
- **FR-011b**: Exported session artifacts MUST include redacted payload
  previews by default and MUST require an explicit operator opt-in before full
  payload content is included.
- **FR-011a**: The system MUST automatically retain a recent local session
  history that is clearly separated from the live session view and provides
  explicit clear and delete controls for the operator.
- **FR-012**: The system MUST provide actionable recovery guidance for blocked
  or failed states, including no peer available, unreachable route, trust
  failure, oversized payload, paused runtime, and missing prerequisites.
- **FR-013**: The system MUST preserve the same information architecture,
  workflow names, capability coverage, and diagnostic terminology on Android
  and iOS, except where operating-system rules require a clearly explained
  difference.
- **FR-014**: The system MUST keep the main reference experience focused on
  currently supported MeshLink SDK capabilities. Proof-only, benchmark-only, or
  otherwise non-normative behaviors MAY be shown only in a clearly labeled
  separate lab section so adopters are not misled about the product surface.
- **FR-015**: The system MUST support named reference scenarios that make it
  easy to demonstrate first exchange, lifecycle control, trust reset, power
  behavior, successful delivery, and bounded failure behavior without relying
  on hidden operator knowledge.
- **FR-016**: The system MUST remain useful offline after installation and MUST
  keep retained session history and exported evidence separate from live
  runtime state so operators can tell whether they are viewing current
  behavior or retained artifacts.

### Key Entities *(include if feature involves data)*

- **Reference Scenario**: A named walkthrough or exercise such as first
  exchange, large transfer, trust reset, or failure handling, with clear
  prerequisites, steps, and expected success indicators.
- **Peer Snapshot**: The current reference view of one peer, including
  availability, trust status, connection status, latest delivery outcome, and
  actions the operator can take next.
- **Timeline Entry**: A time-ordered record of a user action, runtime state
  change, message event, or diagnostic event, including category, severity,
  peer context, and explanatory detail.
- **Session Artifact**: A retained summary of one reference-app session,
  including scenario context, configuration snapshot, peer summary, timeline,
  payload metadata, and selected evidence intended for debugging, review, or
  sharing.
- **Recent Session History**: A clearly separated local record of recent
  sessions that can be reopened for review and removed through explicit clear
  or delete controls.

## Constitutional Alignment *(mandatory)*

- **Code Quality & API Surface**: The reference app and any supporting project
  changes MUST still satisfy the repository's formatting, static-analysis,
  comment-quality, TODO, and dependency-pinning rules. This feature assumes the
  MeshLink public API remains unchanged where possible; if new public SDK
  surface is needed to support the reference experience, BCV, explicit API, and
  public-documentation requirements apply in the same change set.
- **Testing & Benchmarking**: The feature MUST add independently testable
  reference flows for first exchange, lifecycle control, trust reset, large
  transfer visibility, and timeline filtering on both mobile platforms. The
  canonical virtual harness and existing benchmark suites remain the normative
  validation source for protocol correctness and performance; the reference app
  may surface evidence but MUST NOT weaken or replace those gates.
- **Cross-Platform Consistency**: The reference app MUST reinforce MeshLink's
  Android/iOS parity by presenting the same workflows, terms, event meanings,
  and explanatory content on both platforms. Any platform-specific difference
  MUST be explicitly explained rather than silently diverging.
- **Performance & Technical Constraints**: The feature MUST preserve offline
  operation, minimum supported mobile platforms, redaction expectations,
  transport and wire-compatibility guarantees, and the library's existing
  runtime-dependency budget. Any dependencies or UI capabilities added for the
  reference app MUST NOT leak into the shipped MeshLink runtime artifact or
  alter the library's cryptographic or wire-format posture.

## Out of Scope *(mandatory)*

- Building a production consumer chat application with accounts, cloud sync,
  push notifications, or multi-device history.
- Replacing the proof-only validation apps or the retained benchmark harnesses
  as the repository's source of normative performance evidence.
- Desktop, web, or non-mobile reference clients in this feature.
- Exposing experimental or proof-only transport behaviors as if they were
  supported product features, including mixing them into the main guided
  experience.
- Adding a backend service, relay server, or any requirement for internet
  access.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A first-time evaluator can complete a two-device offline first
  exchange and locate the corresponding peer, delivery, and diagnostic evidence
  within 5 minutes on either supported mobile platform using only the app and
  its in-app guidance.
- **SC-002**: In a scripted evaluation covering lifecycle control, peer
  selection, a short send, a large transfer, a trust reset, and diagnostics
  inspection, at least 90% of evaluators complete every step without needing
  source-code help.
- **SC-003**: Android and iOS each expose 100% of the named primary reference
  workflows, terminology, and diagnostic categories, excluding only
  operating-system setup differences that are explicitly explained in the app.
- **SC-004**: A QA or support operator can filter the current session to a
  specific peer or failure class and export a redacted session artifact with
  payload metadata and redacted previews in under 60 seconds.
- **SC-005**: After a 10-minute guided exploration, at least 80% of reviewers
  can correctly answer the current mesh state, whether the selected peer is
  trusted, and why the last send succeeded or failed.

## Assumptions

- The primary users are SDK evaluators, application integrators, QA engineers,
  support engineers, and technical stakeholders rather than consumer end users.
- The first release focuses on supported Android and iOS mobile devices and
  demonstrates currently supported MeshLink capabilities; proof-only or
  benchmark-only behaviors remain secondary context, not primary workflows.
- The reference app ships as a dedicated repository artifact separate from the
  proof-only validation apps and benchmark harnesses.
- The app automatically retains a bounded recent local session history for
  review, while explicit export remains a separate operator action.
- Existing MeshLink documentation, diagnostics, and public behavior remain the
  source of truth for capability definitions; any gaps discovered while shaping
  the reference experience will be resolved during planning rather than assumed
  silently in this specification.
