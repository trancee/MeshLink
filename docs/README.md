# MeshLink documentation

Use this page to find the right MeshLink document for the job.

The docs follow the Diátaxis model, so the fastest way to the right page is to
start with your goal:

| If you want to... | Start here |
|---|---|
| learn MeshLink by doing | [Tutorials](#tutorials) |
| complete an integration task | [How-to guides](#how-to-guides) |
| look up exact API facts | [Reference](#reference) |
| understand design decisions and trade-offs | [Explanation](#explanation) |
| run proof flows or inspect retained evidence | [Proof and validation guides](#proof-and-validation-guides) |

## Tutorials

- [Your first MeshLink exchange](tutorials/your-first-meshlink-exchange.md) — build a minimal Android-side controller, discover a proof peer, and send a first message.

## How-to guides

- [How to add MeshLink to your app](how-to/add-meshlink-to-your-app.md) — wire the current source-distributed SDK into an Android Gradle app or iOS Xcode app.
- [How to integrate MeshLink into a host app](how-to/integrate-meshlink-into-a-host-app.md) — bootstrap the runtime, manage lifecycle, collect streams, send payloads, and handle trust resets.
- [How to structure a robust MeshLink integration](how-to/structure-a-robust-meshlink-integration.md) — move from a working demo to an app-owned integration with explicit lifecycle, diagnostics, trust-reset, and delivery semantics.
- [How to unblock MeshLink permissions on Android and iOS](how-to/unblock-meshlink-permissions.md) — clear Android and iPhone permission blockers before you debug discovery or delivery.
- [How to evaluate MeshLink with the reference app](how-to/evaluate-meshlink-with-the-reference-app.md) — run the shared Android and iOS reference app, complete a guided exchange, and export a retained artifact.
- [How to run the reference-app physical integration scenarios](how-to/run-reference-app-physical-integration-scenarios.md) — retain direct, relay, and permission-recovery physical proofs with scenario-specific analysis artifacts.
- [How to use MeshLink from Swift](how-to/use-meshlink-from-swift.md) — call the generated Apple framework safely from Swift and interpret the Swift-facing API surface.

## Reference

- [MeshLink SDK API reference](reference/meshlink-sdk-api.md) — public entry points, configuration, result types, diagnostics, exceptions, and iOS bridge APIs.
- [Generated public API symbol tables](reference/generated-public-api.md) — the BCV-tracked public surface rendered from the checked-in API dump.
- [Benchmark and validation baselines](../benchmarks/README.md) — retained performance evidence and the current benchmark posture.
- [Feature specification](../specs/001-ble-mesh-sdk/spec.md) — normative product scope and success criteria.
- [Release decision](../specs/001-ble-mesh-sdk/release-decision.md) — current release framing and waiver history.

## Explanation

### Integration and lifecycle

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

## Proof and validation guides

- [How to evaluate MeshLink with the reference app](how-to/evaluate-meshlink-with-the-reference-app.md) — guided reference workflows, retained history, exports, and the handoff point to the proof harnesses.
- [How to run the reference-app physical integration scenarios](how-to/run-reference-app-physical-integration-scenarios.md) — direct proof, relay proof, and the retained physical matrix.
- [How to run the Android proof app](../meshlink-proof/android/README.md)
- [How to build and run the iOS proof app](../meshlink-proof/ios/README.md)
- [Benchmark and validation baselines](../benchmarks/README.md)
