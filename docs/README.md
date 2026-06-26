# MeshLink documentation

Use this page to choose the right MeshLink document quickly.

The docs follow the Diataxis model, so start with your goal rather than with a
single "read everything" path.

| If you want to... | Start here |
|---|---|
| get one successful exchange running | [Tutorials](#tutorials) |
| complete an integration task | [How-to guides](#how-to-guides) |
| look up exact API or runtime facts | [Reference](#reference) |
| check official SDK or reference-app terms | [Reference](#reference) |
| understand design decisions and trade-offs | [Explanation](#explanation) |
| validate behavior on devices | [How-to guides](#how-to-guides), [Reference](#reference), and [Explanation](#explanation) |
| review repeated fleet-test history | [How-to guides](#how-to-guides) and [Reference](#reference) |
| understand when to use the reference app, proof apps, or retained benchmarks | [Explanation](#explanation) |
| check the current MeshLink release posture | [Reference](#reference) |
| understand where code lives and why the repository is split this way | [Reference](#reference) and [Explanation](#explanation) |
| review tooling posture or design proposals | [Tooling](#tooling) and [RFCs](#rfcs) |

## Quick reading paths

If you are not sure where to start, use one of these short paths.

### First success

1. [How to add MeshLink to your app](how-to/add-meshlink-to-your-app.md)
2. [Your first MeshLink exchange](tutorials/your-first-meshlink-exchange.md)
3. [How to run the Android proof app](../meshlink-proof/android/README.md) or [How to build and run the iOS proof app](../meshlink-proof/ios/README.md) for the second-device proof peer
4. [How to integrate MeshLink into a host app](how-to/integrate-meshlink-into-a-host-app.md)
5. [About how MeshLink works](explanation/about-how-meshlink-works.md)
6. [MeshLink runtime behavior reference](reference/meshlink-runtime-behavior.md)

### Production-shaped integration

1. [How to integrate MeshLink into a host app](how-to/integrate-meshlink-into-a-host-app.md)
2. [How to structure a robust MeshLink integration](how-to/structure-a-robust-meshlink-integration.md)
3. [About integrating MeshLink well](explanation/about-integrating-meshlink.md)
4. [The trust model](explanation/trust-model.md)
5. [The peer lifecycle model](explanation/peer-lifecycle.md)
6. [Power management](explanation/power-management.md)

### Physical evaluation

1. [How to evaluate MeshLink with the reference app](how-to/evaluate-meshlink-with-the-reference-app.md)
2. [How to run the reference-app physical integration scenarios](how-to/run-reference-app-physical-integration-scenarios.md)
3. [Android direct-proof matrix result](reference/android-direct-proof-matrix-result.md) — read the canonical 45s rerun summary before digging into raw direct-proof artifacts.
4. [Physical reference-app integration findings](explanation/reference-app-physical-integration-findings.md)
5. [Fleet test history report](../meshlink-reference/fleet-test-history/index.html)
6. [MeshLink runtime behavior reference](reference/meshlink-runtime-behavior.md)
6. [Benchmark and validation baselines](../benchmarks/README.md)

### Proof fixtures and retained benchmarks

1. [About proof validation surfaces](explanation/about-proof-validation-surfaces.md)
2. [How to run the Android proof app](../meshlink-proof/android/README.md)
3. [How to build and run the iOS proof app](../meshlink-proof/ios/README.md)
4. [Benchmark and validation baselines](../benchmarks/README.md)

### Debugging live behavior

1. [How to unblock MeshLink permissions on Android and iOS](how-to/unblock-meshlink-permissions.md)
2. [MeshLink runtime behavior reference](reference/meshlink-runtime-behavior.md)
3. [About how MeshLink works](explanation/about-how-meshlink-works.md)
4. [The trust model](explanation/trust-model.md)
5. [The peer lifecycle model](explanation/peer-lifecycle.md)
6. [About the L2CAP-preferred transport posture](explanation/why-l2cap-first.md)

### Contributor architecture

1. [Repository layout reference](reference/repository-layout.md)
2. [About the repository architecture](explanation/about-the-repository-architecture.md)
3. [Contributor build, test, and verification reference](reference/contributor-reference.md)
4. [MeshLink SDK API reference](reference/meshlink-sdk-api.md)
5. [MeshLink reference app overview](../meshlink-reference/README.md)

## Architecture index

Use this section when you want the strongest diagram-first docs without
guessing which page owns which model.

| Topic | Best starting doc | What the diagram or map gives you |
|---|---|---|
| repository and module ownership | [Repository layout reference](reference/repository-layout.md) | one module map covering `:meshlink`, `:meshlink-reference`, proof apps, benchmarks, and docs |
| why the repository is split this way | [About the repository architecture](explanation/about-the-repository-architecture.md) | the rationale behind the SDK, reference app, proof apps, and retained-evidence split |
| when to choose the reference app, proof apps, or retained benchmarks | [About proof validation surfaces](explanation/about-proof-validation-surfaces.md) | the boundary between product-like evaluation, proof fixtures, and retained performance evidence |
| SDK runtime model | [About how MeshLink works](explanation/about-how-meshlink-works.md) | the runtime services, lifecycle boundaries, discovery path, send pipeline, and persistence boundary |
| host-app integration shape | [How to integrate MeshLink into a host app](how-to/integrate-meshlink-into-a-host-app.md) | the default runtime bootstrap and ownership flow |
| production-shaped host integration | [How to structure a robust MeshLink integration](how-to/structure-a-robust-meshlink-integration.md) | the target steady-state integration model and stream ownership split |
| reference-app shell and evidence model | [MeshLink reference app overview](../meshlink-reference/README.md) | the shared shell wiring, surfaces, and evidence responsibilities |
| reference-app operator path | [How to evaluate MeshLink with the reference app](how-to/evaluate-meshlink-with-the-reference-app.md) | the supported surface path from first proof to retained redacted export |
| contributor workflow | [How to contribute to MeshLink](../CONTRIBUTING.md) | the diagram-first contributor path from checkout to review-ready verification |

## Tutorials

- [Your first MeshLink exchange](tutorials/your-first-meshlink-exchange.md) — build a minimal Android-side controller, discover a peer, and send a first message.

## How-to guides

### Integration and operation

- [How to add MeshLink to your app](how-to/add-meshlink-to-your-app.md) — add MeshLink from the current supported distribution path.
- [How to integrate MeshLink into a host app](how-to/integrate-meshlink-into-a-host-app.md) — bootstrap the runtime, manage lifecycle, collect streams, send payloads, and handle trust resets.
- [How to structure a robust MeshLink integration](how-to/structure-a-robust-meshlink-integration.md) — move from a working demo to an app-owned integration with clear lifecycle, diagnostics, and trust behavior.
- [How to unblock MeshLink permissions on Android and iOS](how-to/unblock-meshlink-permissions.md) — clear platform blockers before you debug discovery or delivery.
- [How to use MeshLink from Swift](how-to/use-meshlink-from-swift.md) — call the generated Apple framework safely from Swift.

### Validation and proof workflows

- [How to evaluate MeshLink with the reference app](how-to/evaluate-meshlink-with-the-reference-app.md) — walk through the shared Android and iOS reference app and export one retained artifact.
- [How to run the reference-app physical integration scenarios](how-to/run-reference-app-physical-integration-scenarios.md) — run the validated release-review campaign and retain direct proof, lifecycle, export, and offline review evidence on real devices; relay coverage is available through the separate relay harness. The campaign docs carry the retained selection vocabulary and the Android-only fallback when mixed live proof is unsupported, and they also document the Android direct-proof foreground wake-lock mitigation for doze-sensitive devices.
- [Physical proof checklist](how-to/run-reference-app-physical-integration-scenarios.md#direct-proof-checklist) — quick direct / relay / reporting checklist plus retained evidence excerpts for the reference-app release-review path.
- [How to run the Android proof app](../meshlink-proof/android/README.md) — retain Android-side physical proof evidence for proof-fixture work, not release-review campaign selection.
- [How to build and run the iOS proof app](../meshlink-proof/ios/README.md) — retain iPhone-side physical proof evidence for proof-fixture work, not release-review campaign selection.

## Reference

- [MeshLink SDK API reference](reference/meshlink-sdk-api.md) — public entry points, configuration, result types, diagnostics, exceptions, and Apple bridge APIs.
- [MeshLink runtime behavior reference](reference/meshlink-runtime-behavior.md) — lifecycle boundaries, stream semantics, delivery-path selection, trust-reset effects, persistence, and operational limits.
- [Glossary and acronym reference](reference/glossary.md) — quick definitions for recurring SDK, contributor, and reference-app terms.
- [Device test matrix reference](reference/device-test-matrix.md) — the attached Android device fleet, human-readable model names, Bluetooth version, crypto baseline, and update rules.
- [Android direct-proof fleet reports](reference/android-direct-proof-fleet-reports.md) — the repository-local report bundle layout, artifact references, and troubleshooting entry points for fleet sweeps.
- [Release status reference](reference/release-status.md) — the current distribution shape, intended first public release shape, and remaining release blockers.
- [Generated public API symbol tables](reference/generated-public-api.md) — the public API appendix rendered from the checked-in BCV dump.
- [Contributor build, test, and verification reference](reference/contributor-reference.md) — exact contributor commands, verification bundles, architecture landmarks, and repository rules.
- [Repository layout reference](reference/repository-layout.md) — module ownership, source-set boundaries, app hosts, scripts, and docs areas.
- [Benchmark and validation baselines](../benchmarks/README.md) — retained performance evidence and the current benchmark posture.
- [Feature specification](../specs/ble-mesh-sdk/spec.md) — normative product scope and success criteria.
- [Release decision](../specs/ble-mesh-sdk/release-decision.md) — current release framing and waiver history.

## Explanation

### Repository and contributor architecture

- [About the repository architecture](explanation/about-the-repository-architecture.md) — why the repository is split between the SDK, the reference app, the proof apps, and the retained evidence surfaces.
- [About proof validation surfaces](explanation/about-proof-validation-surfaces.md) — when to choose the reference app, the proof apps, or retained benchmarks.
- [Reference app and test architecture](explanation/reference-app-and-test-architecture.md) — how the reference app, proof apps, and automated tests fit together.

### Integration and lifecycle

- [About how MeshLink works](explanation/about-how-meshlink-works.md) — the runtime mental model, lifecycle side effects, delivery pipeline, and integration consequences.
- [About integrating MeshLink well](explanation/about-integrating-meshlink.md) — what good production-shaped integration looks like and why.
- [The trust model](explanation/trust-model.md) — how TOFU pinning and continuity checks work.
- [The peer lifecycle model](explanation/peer-lifecycle.md) — why peers move through connected, disconnected, and lost behavior.
- [Power management](explanation/power-management.md) — how MeshLink balances discovery, delivery, and battery use.

### Routing and transport

- [About the L2CAP-preferred transport posture](explanation/why-l2cap-first.md) — why MeshLink prefers L2CAP while keeping GATT available alongside it.
- [Cut-through relay](explanation/cut-through-relay.md) — why relays prefer forwarding without full reassembly.
- [Physical reference-app integration findings](explanation/reference-app-physical-integration-findings.md) — what recent real-device runs taught us.
- [Understanding Babel routing](explanation/understanding-babel-routing.md) — the routing ideas MeshLink borrows and adapts.
- [The MeshEngine coordinator pattern](explanation/meshengine-pattern.md) — why the public shell stays thin and coordination lives in the engine.

### Security, privacy, and engineering posture

- [Discovery identity hash and privacy trade-offs](explanation/privacy-pseudonyms.md) — what the current discovery identity shape gives up and preserves.
- [Regulatory compliance and region clamping](explanation/regulatory-compliance.md) — what MeshLink can and cannot control at the app layer.
- [Why MeshLink uses Noise XX only](explanation/why-noise-xx-only.md) — why one handshake pattern is the current posture.
- [Why pure-Kotlin FlatBuffers](explanation/why-pure-kotlin-flatbuffers.md) — why MeshLink owns its codec instead of relying on generated runtime code.
- [Why 100% coverage for a crypto protocol](explanation/why-full-coverage.md) — why the repository keeps a strict coverage bar.

## Tooling

- [AGP 9 migration plan for MeshLink build modules](tooling/agp-9-migration-plan.md) — the current maintenance summary for the post-migration build shape.
- [AGP 9 migration history for MeshLink build modules](tooling/agp-9-migration-plan-history.md) — the historical execution record.
- [Pre-release refactor roadmap for MeshLink](tooling/pre-release-refactor-roadmap.md) — retained execution record of the staged maintainability, efficiency, and documentation work that landed before the first release.
- [Dokka and SKIE posture for MeshLink](tooling/dokka-and-skie-options.md) — current documentation and Apple interop tooling posture.

## RFCs

- [M009 routing metadata privacy envelope and negotiation contract](rfcs/routing/m009-routing-metadata-privacy.md)
- [M010 PQ-hybrid candidate matrix and wire-shape feasibility](rfcs/crypto/m010-pq-hybrid-candidate-matrix.md)
- [M011 Android crypto fallback proof plan](rfcs/crypto/m011-android-crypto-fallback-proof.md) — shipped fallback plus retained validation posture
- [Crypto vector policy](rfcs/crypto/vector-policy.md)
