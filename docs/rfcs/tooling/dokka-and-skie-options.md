# Dokka and SKIE posture for MeshLink

Status: Adopted for `:meshlink`

This note records the current repository posture for Dokka and SKIE after the
latest stable rollout.

## Current posture

The repository now uses both tools on the SDK module:

- **Dokka 2.2.0** on `:meshlink`
- **SKIE 0.10.12** on `:meshlink`

They are intentionally scoped to the library module that produces the Apple
framework and the public Kotlin API surface.

They are **not** applied to:

- `:meshlink-reference`
- `:benchmarks`
- proof apps

That keeps the tooling change focused on SDK docs and Swift consumer ergonomics.

## Why this shape

### Dokka

Dokka is used as **supplemental generated Kotlin reference output**.

It does not replace:

- the human-written Diátaxis documentation
- the generated BCV appendix
- contributor KDoc discipline

Current output task:

```bash
./gradlew :meshlink:dokkaGenerateHtml
```

Current output directory:

```text
meshlink/build/dokka/html/
```

### SKIE

SKIE is used to improve the Swift surface of the generated MeshLink framework.

Current stable benefits we rely on:

- suspend functions surface as Swift `async` APIs
- `Flow` and `StateFlow` surface as `AsyncSequence`
- global Kotlin functions such as `meshLinkConfig` no longer require `*Kt`
  entry-point names
- enums and sealed hierarchies are more natural in Swift

## Best-practice constraints used in this rollout

The current rollout intentionally keeps SKIE conservative:

- apply SKIE only to the framework-producing module (`:meshlink`)
- keep preview features off
- keep default-argument interop off
- do not add SKIE annotations until a real API need appears
- keep the Swift guide aligned with the stable SKIE surface

This means the current Swift surface still requires explicit `KotlinByteArray`
conversion for binary payloads. SKIE improves concurrency and naming, but it is
not being used here to opt into preview bridges or broader interop experiments.

## Practical implications for maintainers

If you change public KDoc in `:meshlink`, regenerate Dokka locally when you want
fresh generated reference output.

If you change Swift-facing API guidance, update at least:

- `docs/how-to/use-meshlink-from-swift.md`
- `docs/reference/meshlink-sdk-api.md`

If you consider enabling more SKIE features later, treat them as deliberate API
surface changes rather than passive tooling upgrades.

## Revisit triggers

Re-open this posture when any of these becomes true:

- Kotlin or SKIE compatibility changes enough to require a rollout review
- maintainers want preview SKIE features such as SwiftUI observing or Combine
  bridges
- maintainers want selective SKIE annotations such as default-argument interop
- the repository wants to publish Dokka output as a versioned hosted API site
