# Dokka and SKIE posture for MeshLink

Status: Adopted for `:meshlink`; Dokka and SKIE also adopted for `:meshlink-reference`

This note records the current repository posture for Dokka and SKIE after a
codebase-wide pass over the Gradle modules.

## Current posture

The repository now uses the tools in these scopes:

- **Dokka 2.2.0** on `:meshlink`
- **Dokka 2.2.0** on `:meshlink-reference`
- **SKIE 0.10.12** on `:meshlink`
- **SKIE 0.10.12** on `:meshlink-reference`

They are intentionally **not** applied to:

- `:benchmarks`
- `:meshlink-proof:android`

A root convenience task now generates the useful Dokka outputs together:

```bash
./gradlew dokkaGenerateAllHtml
```

## Why this shape

### `:meshlink`

`:meshlink` is the actual SDK module:

- it enables `explicitApi()`
- it is BCV-tracked
- it has the public Kotlin API surface
- it produces the Apple framework consumed by host apps

Dokka is clearly useful here as supplemental generated Kotlin reference output.

### `:meshlink-reference`

`:meshlink-reference` is not a public SDK, but it is still a shared Kotlin
module that:

- produces the `MeshLinkReference` iOS framework
- exposes shared bridge types and entry points consumed by the native iOS host
- contains contributor-facing shared app contracts such as platform-services
  bridges, automation configuration, and root view-controller entry points

That makes Dokka helpful for maintainers and contributors working on the shared
reference-app layer, even though it is not a versioned public API commitment.

It also makes SKIE worthwhile there, because the native iOS host consumes those
entry points directly. Applying SKIE to `:meshlink-reference` removes needless
Swift-side `*Kt` calls and keeps the host app on the same modern Swift-facing
surface style already used for `:meshlink`.

### Why not `:benchmarks`

`:benchmarks` is an internal measurement harness rather than a reusable
library surface. It has no meaningful consumer API to browse, and generated
reference docs would mostly add noise.

### Why not the proof app

`:meshlink-proof:android` is an Android application
module used for retained proof workflows, not a reusable library module. Dokka
would not add meaningful value there.

## Dokka usage

Dokka remains **supplemental generated reference output**.

It does not replace:

- the human-written Diataxis documentation
- the generated BCV appendix
- contributor KDoc discipline

Useful commands:

```bash
./gradlew dokkaGenerateAllHtml
./gradlew :meshlink:dokkaGenerateHtml
./gradlew :meshlink-reference:dokkaGenerateHtml
```

Outputs:

```text
meshlink/build/dokka/html/
meshlink-reference/build/dokka/html/
```

## SKIE usage

SKIE is used to improve the Swift surface of the generated framework-producing
Kotlin modules that have native Swift consumers:

- the **MeshLink SDK framework** from `:meshlink`
- the **MeshLinkReference** framework from `:meshlink-reference`

Current stable benefits we rely on:

- suspend functions surface as Swift `async` APIs
- `Flow` and `StateFlow` surface as `AsyncSequence`
- global Kotlin functions such as `meshLinkConfig` no longer require `*Kt`
  entry-point names
- enums and sealed hierarchies are more natural in Swift

## Best-practice constraints used in this rollout

The current rollout intentionally keeps SKIE conservative:

- apply SKIE only to framework-producing modules with real Swift consumers
  (`:meshlink` and `:meshlink-reference`)
- keep preview features off
- keep default-argument interop off
- do not add SKIE annotations until a real API need appears
- keep the Swift guides and host apps aligned with the stable SKIE surface

For Dokka, the current constraint is different:

- apply it where generated API browsing is genuinely useful to contributors
- keep it off internal harness and proof-app modules where it would add noise
- keep the generated output supplemental rather than a replacement for the
  written docs

This means the current Swift surface still requires explicit `KotlinByteArray`
conversion for binary payloads and still relies on explicit native bridge
installations where MeshLink needs platform crypto or transport hooks. SKIE
improves concurrency and naming, but it is not being used here to opt into
preview bridges or broader interop experiments.

## Practical implications for maintainers

If you change public KDoc in `:meshlink`, regenerate Dokka locally when you want
fresh SDK reference output.

If you change shared reference-app bridge types or host-consumed entry points,
regenerate `:meshlink-reference` Dokka when you want fresh contributor-facing
shared-module reference output.

If you change Swift-facing SDK guidance, update at least:

- `docs/how-to/use-meshlink-from-swift.md`
- `docs/reference/meshlink-sdk-api.md`

If you change native iOS host usage of the shared reference-app framework,
prefer SKIE-visible global functions and async or `AsyncSequence` entry points
when those surfaces exist, instead of falling back to older `*Kt`, callback, or
manual `FlowCollector` patterns.

If you consider enabling more SKIE features later, treat them as deliberate API
surface changes rather than passive tooling upgrades.

## Revisit triggers

Re-open this posture when any of these becomes true:

- Kotlin or SKIE compatibility changes enough to require a rollout review
- maintainers want preview SKIE features such as SwiftUI observing or Combine
  bridges
- maintainers want selective SKIE annotations such as default-argument interop
- the repository wants to publish Dokka output as a versioned hosted API site
- the reference-app shared module becomes more strictly internal or more
  intentionally API-shaped, changing whether Dokka remains useful there
