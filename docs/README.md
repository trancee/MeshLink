# MeshLink documentation

Use this page to find the right MeshLink document for the job.

The docs follow the Diataxis model, so the fastest way to the right page is to
start with your goal:

| If you want to... | Start here |
|---|---|
| learn MeshLink by doing | [Tutorials](#tutorials) |
| complete an integration task | [How-to guides](#how-to-guides) |
| look up exact API facts | [Reference](#reference) |
| understand design decisions and trade-offs | [Explanation](#explanation) |
| run proof flows or inspect retained evidence | [How-to guides](#how-to-guides) and [Reference](#reference) |

## Recommended reading order

If you are not sure where to start, use one of these short reading paths.
They deliberately move from task-oriented documents into explanation and
reference only when that extra depth becomes useful.

### New integrator: first success, then understanding

1. [Your first MeshLink exchange](tutorials/your-first-meshlink-exchange.md)
2. [How to add MeshLink to your app](how-to/add-meshlink-to-your-app.md)
3. [How to integrate MeshLink into a host app](how-to/integrate-meshlink-into-a-host-app.md)
4. [About how MeshLink works](explanation/about-how-meshlink-works.md)
5. [MeshLink runtime behavior reference](reference/meshlink-runtime-behavior.md)
6. [MeshLink SDK API reference](reference/meshlink-sdk-api.md)

### Production-shaped integration: architecture and guardrails

1. [How to integrate MeshLink into a host app](how-to/integrate-meshlink-into-a-host-app.md)
2. [How to structure a robust MeshLink integration](how-to/structure-a-robust-meshlink-integration.md)
3. [About integrating MeshLink well](explanation/about-integrating-meshlink.md)
4. [The trust model](explanation/trust-model.md)
5. [The peer lifecycle model](explanation/peer-lifecycle.md)
6. [Power management](explanation/power-management.md)
7. [MeshLink runtime behavior reference](reference/meshlink-runtime-behavior.md)

### Debugging live behavior: from symptoms to machinery

1. [How to unblock MeshLink permissions on Android and iOS](how-to/unblock-meshlink-permissions.md)
2. [MeshLink runtime behavior reference](reference/meshlink-runtime-behavior.md)
3. [About how MeshLink works](explanation/about-how-meshlink-works.md)
4. [The trust model](explanation/trust-model.md)
5. [The peer lifecycle model](explanation/peer-lifecycle.md)
6. [Power management](explanation/power-management.md)
7. [About the L2CAP-first transport posture](explanation/why-l2cap-first.md)

### Technical evaluation: reference app first, deeper review second

1. [How to evaluate MeshLink with the reference app](how-to/evaluate-meshlink-with-the-reference-app.md)
2. [About how MeshLink works](explanation/about-how-meshlink-works.md)
3. [MeshLink runtime behavior reference](reference/meshlink-runtime-behavior.md)
4. [Physical reference-app integration findings](explanation/reference-app-physical-integration-findings.md)
5. [Benchmark and validation baselines](../benchmarks/README.md)

## Tutorials

- [Your first MeshLink exchange](tutorials/your-first-meshlink-exchange.md) — build a minimal Android-side controller, discover a proof peer, and send a first message.

## How-to guides

### Integration and operation

- [How to add MeshLink to your app](how-to/add-meshlink-to-your-app.md) — wire the current source-distributed SDK into an Android Gradle app or iOS Xcode app.
- [How to integrate MeshLink into a host app](how-to/integrate-meshlink-into-a-host-app.md) — bootstrap the runtime, manage lifecycle, collect streams, send payloads, and handle trust resets.
- [How to structure a robust MeshLink integration](how-to/structure-a-robust-meshlink-integration.md) — move from a working demo to an app-owned integration with explicit lifecycle, diagnostics, trust-reset, and delivery semantics.
- [How to unblock MeshLink permissions on Android and iOS](how-to/unblock-meshlink-permissions.md) — clear Android and iPhone permission blockers before you debug discovery or delivery.
- [How to use MeshLink from Swift](how-to/use-meshlink-from-swift.md) — call the generated Apple framework safely from Swift and interpret the Swift-facing API surface.

### Validation and proof workflows

- [How to evaluate MeshLink with the reference app](how-to/evaluate-meshlink-with-the-reference-app.md) — run the shared Android and iOS reference app, complete a guided exchange, and export a retained artifact.
- [How to run the reference-app physical integration scenarios](how-to/run-reference-app-physical-integration-scenarios.md) — retain direct, relay, and permission-recovery physical proofs with scenario-specific analysis artifacts.
- [How to run the Android proof app](../meshlink-proof/android/README.md) — retain Android-side physical proof evidence.
- [How to build and run the iOS proof app](../meshlink-proof/ios/README.md) — retain iPhone-side physical proof evidence.

## Reference

- [MeshLink SDK API reference](reference/meshlink-sdk-api.md) — public entry points, configuration, result types, diagnostics, exceptions, and iOS bridge APIs.
- [MeshLink runtime behavior reference](reference/meshlink-runtime-behavior.md) — lifecycle boundaries, stream semantics, delivery-path selection, trust-reset effects, persistence, and operational limits.
- [Glossary and acronym reference](reference/glossary.md) — quick definitions for recurring project terms such as AGP 9, BCV, SKIE, TOFU, GATT, and L2CAP.
- [Generated public API symbol tables](reference/generated-public-api.md) — the public API appendix rendered from the checked-in Binary Compatibility Validator (BCV) dump.
- [Contributor build, test, and verification reference](reference/contributor-reference.md) — exact contributor commands, verification bundles, architecture landmarks, and repository rules.
- [Benchmark and validation baselines](../benchmarks/README.md) — retained performance evidence and the current benchmark posture.
- [Feature specification](../specs/001-ble-mesh-sdk/spec.md) — normative product scope and success criteria.
- [Release decision](../specs/001-ble-mesh-sdk/release-decision.md) — current release framing and waiver history.

## Explanation

### Integration and lifecycle

- [About how MeshLink works](explanation/about-how-meshlink-works.md) — the runtime mental model, lifecycle side effects, delivery pipeline, and integration consequences.
- [About integrating MeshLink well](explanation/about-integrating-meshlink.md)
- [The trust model](explanation/trust-model.md)
- [The peer lifecycle model](explanation/peer-lifecycle.md)
- [Power management](explanation/power-management.md)

### Routing and transport

- [About the L2CAP-first transport posture](explanation/why-l2cap-first.md)
- [Cut-through relay](explanation/cut-through-relay.md)
- [Physical reference-app integration findings](explanation/reference-app-physical-integration-findings.md)
- [Understanding Babel routing](explanation/understanding-babel-routing.md)
- [The MeshEngine pattern](explanation/meshengine-pattern.md)

### Security, privacy, and engineering posture

- [Privacy and pseudonyms](explanation/privacy-pseudonyms.md)
- [Regulatory compliance](explanation/regulatory-compliance.md)
- [Why MeshLink uses Noise XX only](explanation/why-noise-xx-only.md)
- [Why MeshLink keeps FlatBuffers in pure Kotlin](explanation/why-pure-kotlin-flatbuffers.md)
- [Why MeshLink requires full coverage](explanation/why-full-coverage.md)

