# Dokka and SKIE Options for Future MeshLink Work

Status: Open decision note for future SDK and tooling work

## Reader and intended outcome

This note is for maintainers deciding whether future MeshLink work should adopt
Dokka, SKIE, both, or neither.

After reading it, a maintainer should be able to:

- understand what problem each tool would solve in this repository
- choose a low-risk next step without redoing the initial trade-off analysis
- know which conditions should trigger a fresh evaluation

## Current repository posture

MeshLink already has a strong documentation and compatibility baseline without
Dokka or SKIE:

- a human-written MeshLink SDK API reference for the public Kotlin contract
- a generated public API appendix derived from the binary compatibility dump
- documentation verification in the normal contributor workflow
- direct iOS framework integration for both the SDK and the reference app
- a Swift integration guide and an iOS proof app that document the current
  Kotlin/Native bridge shape

That means the repo is not blocked on either tool today.

The current Swift-facing integration is still the default Kotlin/Native
Objective-C bridge. In practice that means Swift callers still work with:

- completion-handler wrappers for suspend functions
- manual Flow collector objects instead of native `AsyncSequence`
- generated Kotlin entry-point names such as `MeshLinkConfigKt`
- explicit `KotlinByteArray` conversion helpers

## What each tool would actually improve

### Dokka

Dokka would improve generated Kotlin API reference output.

Best fit in this repository:

- generate a deeper Kotlin API reference for `:meshlink`
- supplement the existing human-written API and integration docs
- reuse the KDoc already required for public API changes

Dokka would **not** remove the need for:

- Diátaxis documentation
- the human-written Swift integration guide
- the generated binary-compatibility appendix
- public API change discipline around KDoc and BCV

In short: Dokka helps the Kotlin reference story, but it does not address the
main iOS consumer pain points.

### SKIE

SKIE would improve the Swift interop experience of the MeshLink KMP framework.

Best fit in this repository:

- apply it to `:meshlink`, which is the framework consumed from Swift
- use the iOS proof app as the first migration surface
- update the Swift integration guide once the new surface is stable

The expected benefits are meaningful:

- suspend functions can become real Swift `async` APIs
- Kotlin Flows can become `AsyncSequence`
- enums and sealed hierarchies become more natural to switch over in Swift
- global Kotlin functions and other bridged names become less awkward

That makes SKIE the more valuable tool if the goal is to improve the real
consumer experience of the SDK.

## Important constraint for SKIE

The repository currently uses a newer Kotlin version than the compatibility
window confirmed during the initial review of SKIE.

Until maintainers confirm a SKIE release that explicitly supports the current
repository Kotlin version, SKIE should be treated as a pilot candidate rather
than something to enable immediately.

This compatibility check is the first gate. Without it, do not adopt SKIE.

## Options

### Option 1: Stay as-is for now

Do not add Dokka or SKIE.

Choose this when:

- the active work is the reference app itself rather than SDK packaging or
  Swift ergonomics
- maintainers want zero toolchain churn
- there is no immediate pressure from SDK consumers

Pros:

- no migration work
- no compatibility risk
- no change to current contributor workflow

Cons:

- Swift interop remains more cumbersome than it could be
- Kotlin API reference stays partly hand-maintained

### Option 2: Add Dokka only

Add Dokka as a supplemental Kotlin API reference tool for `:meshlink`.

Choose this when:

- maintainers want a low-risk improvement to the documentation stack
- the priority is Kotlin API discoverability, not Swift interop
- the team wants to build on existing KDoc investment

Pros:

- low-risk tooling addition
- improves generated Kotlin API reference output
- does not change public runtime behavior

Cons:

- does not improve the Swift integration experience
- still requires the human-written Swift and operator-facing docs

### Option 3: Pilot SKIE only

Run a focused SKIE pilot on `:meshlink` after confirming version support.

Choose this when:

- the priority is better Swift ergonomics for real SDK consumers
- maintainers are willing to migrate the iOS proof app and Swift guide
- the current Kotlin version is confirmed to be supported by the chosen SKIE
  release

Pros:

- biggest developer-experience win for Swift consumers
- reduces Swift bridging boilerplate significantly
- aligns with the repository's direct iOS framework integration pattern

Cons:

- requires a compatibility check first
- requires Swift-source migration
- needs careful review of async and Flow behavior after migration

### Option 4: Phase both deliberately

Land no change during active feature delivery, then stage the tools in order:

1. add Dokka as a supplemental Kotlin reference tool
2. pilot SKIE later if compatibility and migration budget are both acceptable

Choose this when:

- maintainers want a safer sequence instead of a single larger tooling move
- the team wants documentation polish first and Swift interop upgrades second

Pros:

- lowest surprise rollout
- separates documentation work from interop migration work
- makes it easier to attribute any future problems to one change at a time

Cons:

- delays the Swift ergonomics win
- preserves some duplicated documentation effort for longer

## Recommended default posture

Unless a future task explicitly targets SDK publishing, Kotlin API site
improvement, or Swift ergonomics, keep the current posture.

If maintainers revisit this topic later:

1. prefer **SKIE over Dokka** when the real goal is Swift developer experience
2. prefer **Dokka over SKIE** when the real goal is low-risk reference polish
3. apply SKIE to `:meshlink`, not to `:meshlink-reference`, unless the
   reference app later grows significant native Swift surface area of its own

## Revisit triggers

Re-open this note when any of the following become true:

- Swift consumers complain about collector boilerplate, completion-handler APIs,
  or Kotlin-generated names
- maintainers want a generated Kotlin API site in addition to the current docs
- the Kotlin version or SKIE version changes enough to reopen the compatibility
  question
- the repository starts distributing the SDK in a more productized way and
  wants stronger generated reference output

## Working recommendation captured from the initial review

For the reference-app feature work itself, do not add either tool.

For future SDK-focused work:

- Dokka is a useful supplement, not a replacement for the current docs
- SKIE is the higher-value improvement, but only after an explicit
  compatibility-confirmed pilot
