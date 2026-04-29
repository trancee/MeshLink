# MeshLink

[![CI](https://github.com/nicegram/MeshLink/actions/workflows/ci.yml/badge.svg)](https://github.com/nicegram/MeshLink/actions/workflows/ci.yml)
[![Coverage](https://img.shields.io/badge/coverage-100%25-brightgreen)](docs/explanation/why-full-coverage.md)
[![License](https://img.shields.io/badge/license-Unlicense-blue.svg)](LICENSE)

**Encrypted, serverless mesh networking over Bluetooth Low Energy.**

MeshLink is a Kotlin Multiplatform library that enables offline, peer-to-peer encrypted messaging over BLE mesh networks. No internet, no servers, no user accounts.

```kotlin
val config = meshLinkConfig("com.example.myapp") {
    security { trustMode = TrustMode.PROMPT }
}
val mesh = MeshLink.createAndroid(context, config)
mesh.start()

// Send an E2E encrypted message to a peer discovered over BLE
mesh.send(peerId, "Hello from the mesh".encodeToByteArray())
```

## Features

- **Zero infrastructure** — works without internet, servers, or accounts
- **End-to-end encrypted** — Noise XX mutual authentication + Noise K payload sealing
- **Multi-hop routing** — Babel (RFC 8966) distance-vector routing across the mesh
- **L2CAP-first** — 3–10× throughput over GATT, with automatic fallback
- **Privacy by design** — rotating pseudonyms prevent BLE tracking
- **Battery-aware** — three power tiers adapt radio usage to battery state
- **Cross-platform** — Android + iOS from a single Kotlin Multiplatform codebase

## Installation

### Android (Gradle)

```kotlin
implementation("ch.trancee:meshlink:0.1.0")
```

### iOS (Swift Package Manager)

```swift
dependencies: [
    .package(url: "https://github.com/nicegram/MeshLink", from: "0.1.0")
]
```

## Quick Start

- [**Android Tutorial**](docs/tutorials/first-android-integration.md) — 15 minutes to first message
- [**iOS Tutorial**](docs/tutorials/first-ios-integration.md) — 15 minutes to first message
- [**3-Node Test Mesh**](docs/tutorials/three-node-test-mesh.md) — simulate a mesh in a unit test

## Documentation

Full documentation at [`docs/`](docs/README.md), organized by the [Diataxis](https://diataxis.fr) framework:

| Type | Purpose |
|------|---------|
| [**Tutorials**](docs/README.md#tutorials-learning-oriented) | Hands-on lessons for getting started |
| [**How-to Guides**](docs/README.md#how-to-guides-goal-oriented) | Step-by-step solutions to specific goals |
| [**Reference**](docs/README.md#reference-information-oriented) | API, wire protocol, configuration, architecture |
| [**Explanation**](docs/README.md#explanation-understanding-oriented) | Design decisions and the "why" behind the system |

## Architecture

```
App Layer          MeshLinkApi (public)
                        │
Coordinator        MeshEngine (wires all subsystems)
                        │
        ┌───────────────┼───────────────┐
        │               │               │
Protocol        Routing          Transfer
Noise XX        Babel RFC 8966   SACK + Cut-through
        │               │               │
        └───────────────┼───────────────┘
                        │
Transport          BleTransport (L2CAP / GATT)
                        │
Platform           Android BLE / CoreBluetooth
```

See [Architecture Reference](docs/reference/architecture.md) for the full component map.

## Building

```bash
# Run all tests + coverage + API check + lint
./gradlew :meshlink:jvmTest :meshlink:koverVerify :meshlink:apiCheck :meshlink:detekt :meshlink:ktfmtCheck
```

See [How to Run Verification](docs/how-to/run-verification.md) for details.

## Requirements

- Kotlin 2.3.20
- Android API 29+ (Android 10)
- iOS 14+ / Xcode 14.3+
- Gradle 9.4.1+

## Specification

For AI agents rebuilding this project from scratch: see [`SPEC.md`](SPEC.md) — a complete greenfield build specification covering architecture, crypto, protocol, transport, API, and build system.

## License

[Unlicense](LICENSE) — public domain.
