# MeshLink Documentation

> **Audience:** AI coding agents and developers integrating or contributing to MeshLink.

This documentation follows the [Diataxis](https://diataxis.fr) framework — four distinct types serving four different needs.

---

## Tutorials (Learning-oriented)

Hands-on lessons that take you through building something real. Follow them in order.

| Tutorial | What you'll learn |
|----------|-------------------|
| [Your First Android Integration](tutorials/first-android-integration.md) | Add MeshLink to an Android app, discover a peer, send a message |
| [Your First iOS Integration](tutorials/first-ios-integration.md) | Add MeshLink to an iOS app via SPM, discover a peer, send a message |
| [Building a 3-Node Test Mesh](tutorials/three-node-test-mesh.md) | Use VirtualMeshTransport to simulate a multi-hop mesh in a unit test |

---

## How-to Guides (Goal-oriented)

Directions for achieving specific real-world goals. Assumes you already know the basics.

| Guide | Goal |
|-------|------|
| [How to Add a New Wire Message Type](how-to/add-wire-message-type.md) | Extend the protocol with a new FlatBuffers message |
| [How to Consume Diagnostic Events](how-to/consume-diagnostics.md) | Wire up a DiagnosticSink consumer for monitoring |
| [How to Handle Key Rotation](how-to/handle-key-rotation.md) | Respond to identity key changes in TOFU Prompt mode |
| [How to Configure Power Tiers](how-to/configure-power-tiers.md) | Tune scan/connection behavior for battery life |
| [How to Publish a Release](how-to/publish-release.md) | Tag, sign, and ship to Maven Central + SPM |
| [How to Run the Full Verification Suite](how-to/run-verification.md) | Execute all quality gates locally |
| [How to Write a Protocol Integration Test](how-to/write-integration-test.md) | Use MeshTestHarness for N-node scenarios |
| [How to Add a Platform Transport](how-to/add-platform-transport.md) | Implement BleTransport for a new platform |

---

## Reference (Information-oriented)

Precise, complete descriptions of the machinery. Look things up here.

| Reference | Content |
|-----------|---------|
| [API Reference](reference/api.md) | MeshLinkApi methods, MeshLinkConfig, events, state machine |
| [Wire Protocol](reference/wire-protocol.md) | 12 message types, binary encoding, L2CAP framing |
| [Diagnostic Codes](reference/diagnostic-codes.md) | 27 codes with severity, payload type, and emission context |
| [Configuration](reference/configuration.md) | All config options, sub-configs, presets, regulatory regions |
| [Build System](reference/build-system.md) | Toolchain versions, plugins, quality gates, CI pipeline |
| [Architecture Map](reference/architecture.md) | Package layout, component responsibilities, data flow |
| [Crypto Primitives](reference/crypto.md) | CryptoProvider contract, key formats, Noise protocols |
| [BLE Transport Contract](reference/ble-transport.md) | BleTransport interface, advertisement format, connection model |

---

## Explanation (Understanding-oriented)

Background, context, and the "why" behind design decisions. Read these to understand the system deeply.

| Topic | Question answered |
|-------|-------------------|
| [Why Noise XX Only](explanation/why-noise-xx-only.md) | Why we removed Noise IK and what we gained |
| [Why Pure-Kotlin FlatBuffers](explanation/why-pure-kotlin-flatbuffers.md) | Why not flatc codegen or flatbuffers-java |
| [Why L2CAP-First](explanation/why-l2cap-first.md) | The connection model and why GATT is fallback |
| [Understanding Babel Routing](explanation/understanding-babel-routing.md) | How RFC 8966 adapts to a BLE mesh |
| [The Peer Lifecycle Model](explanation/peer-lifecycle.md) | Why three states, two sweeps, and what "Gone" means |
| [Why 100% Coverage for Crypto](explanation/why-full-coverage.md) | How coverage serves correctness in a protocol library |
| [The MeshEngine Coordinator Pattern](explanation/meshengine-pattern.md) | Why MeshLink is thin and MeshEngine owns all logic |
| [Cut-Through vs Store-and-Forward](explanation/cut-through-relay.md) | Why relay nodes pipeline chunks instead of reassembling |
