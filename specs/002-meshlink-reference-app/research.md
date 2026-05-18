# Research: MeshLink Reference App

## Decision 1: Package the app as one root `meshlink-reference` KMP module

- **Decision**: Create one new root-level `meshlink-reference/` Kotlin
  Multiplatform app module that owns the shared UI, app state, resources, and
  Android target, plus an iOS host project under `meshlink-reference/ios`.
- **Rationale**: The user explicitly wants the reference app saved in
  `meshlink-reference` at the repository root. A single KMP app module keeps
  Android and iOS behavior aligned, makes shared Compose UI practical, and
  avoids duplicating screen logic across two separate app codebases.
- **Alternatives considered**:
  - Separate Android and iOS app projects under `meshlink-reference/` — rejected
    because it would duplicate the main UI/state logic and weaken parity.
  - Extending `meshlink-proof` instead of creating a new app — rejected because
    the proof apps remain validation vehicles and should not absorb the new,
    user-friendly reference surface.

## Decision 2: Use Compose Multiplatform for the shared UI layer

- **Decision**: Build the reference experience with a shared Compose
  Multiplatform UI, shared lifecycle-aware view models/state holders, shared
  resources, and shared navigation definitions.
- **Rationale**: The feature requires the same experience on Android and iOS,
  and the user explicitly requested the Compose Multiplatform skill. Shared
  Compose UI gives the strongest parity for workflows, wording, diagnostics, and
  visual polish while still allowing platform glue only where needed.
- **Alternatives considered**:
  - Native Android UI + native SwiftUI — rejected because parity would be harder
    to maintain and every workflow change would be duplicated.
  - A web-based shell inside native wrappers — rejected because offline BLE
    flows and platform integration would become more complex for less value.

## Decision 3: Keep a three-surface information architecture

- **Decision**: Split the app into (1) a main guided reference experience,
  (2) a clearly separated advanced surface exposing the full public SDK
  configuration/runtime controls, and (3) a clearly labeled lab section for
  proof-only and benchmark-only behaviors.
- **Rationale**: This matches the clarified spec and balances usability with
  technical depth. First-time evaluators should not land in a wall of expert
  controls, but integrators still need access to the real SDK surface.
- **Alternatives considered**:
  - Guided-only app — rejected because it would hide too much of the library.
  - Advanced-first app — rejected because it would overwhelm evaluators.
  - Mixing lab behavior into main flows — rejected because it would blur the
    supported product surface.

## Decision 4: Provide a non-authoritative solo exploration mode

- **Decision**: Add a clearly labeled solo exploration mode for one-device use.
  It can expose screens, walkthrough steps, static configuration inspection, and
  explanatory content, but it must never pretend to be live peer discovery,
  live delivery, or authoritative diagnostics proof.
- **Rationale**: This improves onboarding, demos, and design review when a
  second device is unavailable, while preserving the credibility of live proof.
- **Alternatives considered**:
  - Live-peer-only experience — rejected because it raises friction for first
    contact and internal review.
  - Full simulation of live sessions — rejected because it would create
    ambiguity around what is real versus illustrative.

## Decision 5: Retain a bounded recent local session history

- **Decision**: Automatically retain the 20 most recent sessions in app-local
  storage, keep that history separate from the live session, and expose explicit
  clear/delete controls.
- **Rationale**: The spec now requires recent local history, but history must
  remain bounded to keep the UI fast and the storage model understandable.
  A count-based cap is simpler to test and explain than time-based expiry.
- **Alternatives considered**:
  - In-memory only history — rejected because it conflicts with the clarified
    retained-history requirement.
  - Unlimited local retention — rejected because it creates unclear storage and
    privacy growth over time.
  - Embedded database storage — rejected because the feature only needs bounded,
    inspectable session summaries and export files.

## Decision 6: Use JSON for retained session summaries and exports

- **Decision**: Store recent session summaries and exported session artifacts as
  app-local JSON documents. Retained history stores redacted data only;
  exported artifacts include payload metadata and redacted previews by default,
  with explicit opt-in for full payload inclusion.
- **Rationale**: JSON is easy to inspect, diff, share, and validate across both
  platforms. It also aligns well with the requirement that QA/support operators
  can quickly understand and export what happened.
- **Alternatives considered**:
  - Binary blobs or zipped archives — rejected because they reduce readability.
  - Plain-text logs only — rejected because they are weaker for structured
    filtering, validation, and future automation.

## Decision 7: Prefer direct iOS integration with a committed Xcode project

- **Decision**: Integrate the iOS host app directly with the KMP module using a
  committed Xcode project (generated from `project.yml`) and the standard
  `embedAndSignAppleFrameworkForXcode` build flow.
- **Rationale**: This repository is a mono-repo, does not need CocoaPods for
  this feature, and already uses direct integration successfully for the proof
  app. Reusing that pattern reduces setup surprise and keeps local device builds
  straightforward.
- **Alternatives considered**:
  - CocoaPods integration — rejected because it adds extra tooling and is not
    needed for this app.
  - SwiftPM export/import — rejected because local mono-repo development is the
    primary use case for the reference app.

## Decision 8: Validate mostly in shared tests, then prove parity with platform smoke tests

- **Decision**: Put most state, session, filtering, export-policy, and workflow
  logic under shared tests; add Compose UI tests for critical common flows; add
  Android and iOS smoke tests for startup, navigation, and platform-specific
  glue; rerun existing MeshLink tests and benchmarks whenever integration work
  touches the SDK.
- **Rationale**: The feature's value is parity and shared behavior, so the bulk
  of validation should live in common code. Platform tests still matter for app
  startup, permissions, and file/export glue.
- **Alternatives considered**:
  - Mostly manual validation — rejected because the constitutions require
    evidence-backed testing.
  - Platform-only UI tests — rejected because they would duplicate assertions
    and weaken the shared KMP benefit.

## Decision 9: Adopt an editorial-technical design language

- **Decision**: Use a high-contrast, editorial-technical visual direction with
  strong typography, tabular diagnostics, layered surfaces, progressive detail,
  and restrained motion that emphasizes understanding and confidence rather than
  consumer-chat aesthetics.
- **Rationale**: The user asked for a modern app with excellent UX/UI that is
  still technical enough to explain what is happening. An editorial/ops-console
  posture supports that goal better than either a bare benchmark shell or a
  consumer messenger look.
- **Alternatives considered**:
  - Minimal utility-only UI — rejected because it would undersell the reference
    experience.
  - Consumer chat styling — rejected because it would obscure the technical
    educational purpose.
