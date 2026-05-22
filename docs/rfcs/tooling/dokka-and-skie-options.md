# Dokka and SKIE options for future MeshLink work

Status: Open decision note for future SDK and tooling work

This note helps maintainers decide whether future work should adopt Dokka,
SKIE, both, or neither.

## Reader outcome

After reading this note, a maintainer should be able to:

- understand what problem each tool would solve in this repository
- choose a low-risk next step without redoing the trade-off analysis
- know which conditions should trigger a fresh evaluation

## Current repository posture

MeshLink already has a strong baseline without either tool:

- a human-written SDK API reference
- a generated public API appendix derived from the BCV dump
- documentation verification in the normal contributor workflow
- direct iOS framework integration for the SDK and reference app
- a Swift integration guide that documents the current Kotlin/Native bridge

That means the repository is not blocked on Dokka or SKIE today.

The current Swift-facing integration still uses the default Kotlin/Native
Objective-C bridge, so Swift callers still deal with:

- completion-handler wrappers for suspend functions
- manual Flow collector objects instead of native `AsyncSequence`
- generated Kotlin entry-point names such as `MeshLinkConfigKt`
- explicit `KotlinByteArray` conversion helpers

## What each tool would improve

### Dokka

Dokka improves generated Kotlin API reference output.

Best fit here:

- generate deeper Kotlin API reference for `:meshlink`
- supplement the existing human-written API and integration docs
- reuse the KDoc already required for public API changes

Dokka would **not** replace:

- Diátaxis documentation
- the human-written Swift guide
- the generated BCV appendix
- public API discipline around KDoc and BCV

In short: Dokka helps the Kotlin reference story, but it does not address the
main iOS consumer pain points.

### SKIE

SKIE improves the Swift interop experience of the MeshLink KMP framework.

Best fit here:

- apply it to `:meshlink`
- use the iOS proof app as the first migration surface
- update the Swift integration guide once the new surface is stable

Expected benefits:

- suspend functions become real Swift `async` APIs
- Kotlin Flows can become `AsyncSequence`
- enums and sealed hierarchies become more natural in Swift
- generated bridge names become less awkward

If the goal is better Swift consumer ergonomics, SKIE is the higher-value tool.

## Important constraint for SKIE

The repository currently uses a newer Kotlin version than the compatibility
window confirmed during the initial SKIE review.

Until maintainers confirm a SKIE release that explicitly supports the current
repository Kotlin version, SKIE should be treated as a pilot candidate, not as
a default tooling change.

## Options

### Option 1 — stay as-is

Choose this when:

- active work is focused on the reference app rather than SDK packaging or Swift
  ergonomics
- maintainers want zero toolchain churn
- there is no immediate pressure from SDK consumers

### Option 2 — add Dokka only

Choose this when:

- the priority is Kotlin API discoverability
- maintainers want a low-risk documentation improvement
- the team wants to build on existing KDoc investment

### Option 3 — pilot SKIE only

Choose this when:

- the priority is better Swift ergonomics for real SDK consumers
- maintainers are willing to migrate the iOS proof app and Swift guide
- version support is explicitly confirmed first

### Option 4 — phase both deliberately

A safer staged sequence is:

1. add Dokka as supplemental Kotlin reference output
2. pilot SKIE later if compatibility and migration budget both look good

## Recommended default posture

Unless a future task explicitly targets SDK publishing, Kotlin API-site
improvement, or Swift ergonomics, keep the current posture.

If maintainers revisit this topic later:

1. prefer **SKIE over Dokka** when the real goal is Swift developer experience
2. prefer **Dokka over SKIE** when the real goal is low-risk reference polish
3. apply SKIE to `:meshlink`, not `:meshlink-reference`, unless the reference
   app later grows a meaningful native Swift surface of its own

## Revisit triggers

Re-open this note when any of these becomes true:

- Swift consumers complain about collector boilerplate, completion-handler APIs,
  or Kotlin-generated names
- maintainers want a generated Kotlin API site in addition to the current docs
- the Kotlin version or SKIE version changes enough to reopen compatibility
- the repository starts distributing the SDK in a more productized way and wants
  stronger generated reference output
