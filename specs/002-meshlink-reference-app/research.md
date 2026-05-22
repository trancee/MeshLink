# Research: MeshLink reference app

This document records the key design decisions behind the reference app. It is
not a step-by-step implementation plan; it captures the choices the plan builds
on.

## Decision 1: Package the app as one root `meshlink-reference` KMP module

- **Decision**: Create one root-level `meshlink-reference/` Kotlin
  Multiplatform app module that owns the shared UI, app state, resources, and
  Android target, plus an iOS host project under `meshlink-reference/ios`.
- **Rationale**: The app must live at the repository root, keep Android and iOS
  aligned, and avoid duplicating the main UI/state logic.
- **Alternatives considered**:
  - Separate Android and iOS app projects — rejected because they would weaken
    parity and duplicate workflow code.
  - Extending `meshlink-proof` instead of creating a new app — rejected because
    the proof apps remain validation vehicles, not the primary reference
    surface.

## Decision 2: Use Compose Multiplatform for the shared UI layer

- **Decision**: Build the reference experience with shared Compose
  Multiplatform UI, shared lifecycle-aware state holders, shared resources, and
  shared navigation.
- **Rationale**: The app must offer the same experience on Android and iOS, and
  shared Compose gives the strongest workflow, wording, and visual parity.
- **Alternatives considered**:
  - Native Android UI + native SwiftUI — rejected because every workflow would
    need to be duplicated.
  - A web-based shell in native wrappers — rejected because offline BLE flows
    and platform integration would get harder for less value.

## Decision 3: Keep a three-surface information architecture

- **Decision**: Split the app into:
  1. a guided reference experience
  2. a clearly separated advanced surface
  3. a clearly labeled lab for proof-only and benchmark-only behavior
- **Rationale**: First-time evaluators should not land in a wall of expert
  controls, but integrators still need access to the real SDK surface.
- **Alternatives considered**:
  - Guided-only app — rejected because it would hide too much of the library.
  - Advanced-first app — rejected because it would overwhelm evaluators.
  - Mixing lab behavior into main flows — rejected because it would blur the
    supported product surface.

## Decision 4: Provide a non-authoritative solo exploration mode

- **Decision**: Add a clearly labeled solo mode for one-device use. It can
  expose screens, walkthrough steps, and explanatory content, but it must never
  pretend to be live discovery, live delivery, or authoritative diagnostics
  proof.
- **Rationale**: This lowers onboarding friction without weakening the meaning
  of real device-to-device proof.
- **Alternatives considered**:
  - Live-peer-only experience — rejected because it raises friction for first
    contact and internal review.
  - Full simulation of live sessions — rejected because it would blur the line
    between real and illustrative behavior.

## Decision 5: Retain a bounded recent local session history

- **Decision**: Automatically retain the 20 most recent sessions in app-local
  storage, keep that history separate from the live session, and expose clear
  delete controls.
- **Rationale**: The app needs recent history, but the retention model should
  stay bounded, fast, and easy to explain.
- **Alternatives considered**:
  - In-memory-only history — rejected because it conflicts with the retained
    history requirement.
  - Unlimited local retention — rejected because it creates unclear storage and
    privacy growth.
  - Embedded database storage — rejected because the feature only needs bounded,
    inspectable session summaries and exports.

## Decision 6: Use JSON for retained session summaries and exports

- **Decision**: Store recent session summaries and exported session artifacts as
  app-local JSON documents.
- **Rationale**: JSON is easy to inspect, diff, validate, and share across both
  platforms.
- **Alternatives considered**:
  - Binary blobs or zipped archives — rejected because they reduce readability.
  - Plain-text logs only — rejected because they are weaker for structured
    filtering, validation, and future automation.

## Decision 7: Prefer direct iOS integration with a committed Xcode project

- **Decision**: Integrate the iOS host app directly with the KMP module using a
  committed Xcode project generated from `project.yml` and the standard
  `embedAndSignAppleFrameworkForXcode` build flow.
- **Rationale**: The repository is a monorepo and already uses direct
  integration successfully for the proof app.
- **Alternatives considered**:
  - CocoaPods integration — rejected because it adds tooling that this app does
    not need.
  - SwiftPM export/import — rejected because local monorepo development is the
    primary use case.

## Decision 8: Validate mostly in shared tests, then prove parity with platform smoke tests

- **Decision**: Put most state, filtering, export-policy, and workflow logic
  under shared tests, then add Android and iOS smoke tests for startup,
  navigation, and platform glue.
- **Rationale**: The app's value is shared behavior and parity, so most
  validation should live in common code.
- **Alternatives considered**:
  - Mostly manual validation — rejected because the project requires
    evidence-backed testing.
  - Platform-only UI tests — rejected because they would duplicate assertions
    and weaken the shared KMP advantage.

## Decision 9: Adopt an editorial-technical design language

- **Decision**: Use a high-contrast, editorial-technical visual direction with
  strong typography, tabular diagnostics, progressive detail, and restrained
  motion.
- **Rationale**: The app should feel modern and polished while still teaching
  people what MeshLink is doing.
- **Alternatives considered**:
  - Minimal utility-only UI — rejected because it would undersell the reference
    experience.
  - Consumer-chat styling — rejected because it would obscure the technical,
    educational purpose.
