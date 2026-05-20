# MeshLink documentation

Use this page to find the right MeshLink document for the job.

The docs follow the Diátaxis model, so the quickest way to the right page is to
start with your need:

| If you need to... | Start here |
|---|---|
| learn MeshLink by doing | [Tutorials](#tutorials) |
| complete an integration task | [How-to guides](#how-to-guides) |
| look up exact API facts | [Reference](#reference) |
| understand design and trade-offs | [Explanation](#explanation) |
| run the proof apps or inspect retained evidence | [Proof and validation guides](#proof-and-validation-guides) |

## Tutorials

- [Your first MeshLink exchange](tutorials/your-first-meshlink-exchange.md) — build a minimal Android-side controller, discover a proof peer, and send a first message.

## How-to guides

- [How to add MeshLink to your app](how-to/add-meshlink-to-your-app.md) — add the current source-distributed SDK to an Android Gradle app or iOS Xcode app and understand the supported install paths.
- [How to integrate MeshLink into a host app](how-to/integrate-meshlink-into-a-host-app.md) — bootstrap the SDK on Android and iOS, manage lifecycle, collect streams, send payloads, and handle trust resets.
- [How to unblock MeshLink permissions on Android and iOS](how-to/unblock-meshlink-permissions.md) — clear Android Nearby devices / Location blockers and the first-run iPhone Bluetooth prompt before you debug discovery.
- [How to use MeshLink from Swift](how-to/use-meshlink-from-swift.md) — call the generated Apple framework safely from Swift, retain collectors correctly, and interpret Swift-facing result types.

## Reference

- [MeshLink SDK API reference](reference/meshlink-sdk-api.md) — public entry points, configuration, result types, diagnostics, exceptions, and iOS bridge APIs.
- [Generated public API symbol tables](reference/generated-public-api.md) — the binary-compatibility-tracked public surface rendered from the checked-in API dump.
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
- [Understanding Babel routing](explanation/understanding-babel-routing.md)
- [The MeshEngine pattern](explanation/meshengine-pattern.md)

### Security, privacy, and engineering posture

- [Privacy and pseudonyms](explanation/privacy-pseudonyms.md)
- [Regulatory compliance](explanation/regulatory-compliance.md)
- [Why MeshLink does not use Noise XX only](explanation/why-noise-xx-only.md)
- [Why MeshLink keeps FlatBuffers in pure Kotlin](explanation/why-pure-kotlin-flatbuffers.md)
- [Why MeshLink requires full coverage](explanation/why-full-coverage.md)

## Proof and validation guides

- [How to use the MeshLink reference app](../meshlink-reference/README.md) — guided reference workflows, deterministic UI automation, and the retained Android ↔ iPhone live-proof harness.
- [How to run the Android proof app](../meshlink-proof/android/README.md)
- [How to build and run the iOS proof app](../meshlink-proof/ios/README.md)
- [Benchmark and validation baselines](../benchmarks/README.md)
