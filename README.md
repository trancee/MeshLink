# MeshLink

A **Kotlin Multiplatform (KMP)** BLE mesh messaging library for **Android**, **iOS**, and **macOS**. MeshLink enables secure, multi-hop peer-to-peer communication over Bluetooth Low Energy with ~85% shared code across platforms.

## Features

- **Multi-hop mesh routing** — Enhanced DSDV protocol with gossip-based route advertisement, composite cost metrics, and loop prevention
- **Two-layer encryption** — Hop-by-hop (Noise XX) and end-to-end (Noise K) using `Noise_XX_25519_ChaChaPoly_SHA256`
- **Zero external crypto dependencies** — Pure Kotlin Ed25519/X25519, platform-native SHA-256 and ChaCha20-Poly1305
- **Large message transfer** — Chunking, selective ACKs (SACK), AIMD congestion control, byte-offset resume
- **Power-aware operation** — Automatic tuning of scan duty cycle, advertising interval, and chunk sizes based on battery level
- **L2CAP support** — High-throughput data plane with automatic GATT fallback
- **Real-time diagnostics** — 19 diagnostic event codes, health snapshots, and Flow-based event streams
- **Identity rotation** — Rotate keys with signed gossip announcements and configurable grace periods

## Platform Support

| Target | Min Version | Crypto Provider |
|--------|-------------|-----------------|
| Android | API 26 | Pure Kotlin (API 26–32), JCA (API 33+) |
| iOS | arm64, simulatorArm64 | Pure Kotlin |
| macOS | arm64, x64 | Pure Kotlin |
| JVM | Java 21 | Java native JCA |

## Quick Start

### Gradle (Android / JVM)

```kotlin
// settings.gradle.kts
include(":meshlink")

// app/build.gradle.kts
dependencies {
    implementation(project(":meshlink"))
}
```

### Swift Package Manager (iOS)

Add the repository URL and depend on the `MeshLink` target. See [Package.swift](Package.swift) for details.

### Basic Usage

```kotlin
import io.meshlink.MeshLink
import io.meshlink.config.meshLinkConfig

// Create with a configuration preset
val mesh = MeshLink(
    transport = bleTransport,
    config = meshLinkConfig {
        maxMessageSize = 50_000
        gossipIntervalMs = 10_000L
    },
)

// Start the mesh
mesh.start()

// Listen for peers and messages
launch {
    mesh.peers.collect { event ->
        when (event) {
            is PeerEvent.Discovered -> println("Peer: ${event.peerId.toHex()}")
            is PeerEvent.Lost -> println("Lost: ${event.peerId.toHex()}")
        }
    }
}

launch {
    mesh.messages.collect { msg ->
        println("${msg.senderId.toHex()}: ${msg.payload.decodeToString()}")
    }
}

// Send a message
mesh.send(recipientPeerId, "Hello mesh!".encodeToByteArray())
```

## Configuration Presets

| Preset | Max Message | Buffer | Use Case |
|--------|-------------|--------|----------|
| `chatOptimized` | 10 KB | 512 KB | Text chat, small payloads |
| `fileTransferOptimized` | 100 KB | 2 MB | Images, files, large data |
| `powerOptimized` | 10 KB | 256 KB | IoT, wearables, sensors |
| `sensorOptimized` | 1 KB | 128 KB | Telemetry, beacons |

```kotlin
val config = MeshLinkConfig.chatOptimized()
```

## Security

MeshLink uses a two-layer encryption model when a `CryptoProvider` is supplied:

| Layer | Protocol | Scope |
|-------|----------|-------|
| Hop-by-hop | Noise XX | Per BLE link — mutual authentication, forward secrecy |
| End-to-end | Noise K | Per message — sender-authenticated, ephemeral keys |

All cryptographic primitives are validated against RFC test vectors (RFC 7748, 8032, 8439, 5869, 6234).

## Project Structure

```
MeshLink/
├── meshlink/                    # Core KMP library
│   └── src/
│       ├── commonMain/          # ~85% shared code
│       ├── appleMain/           # Shared Apple (CoreBluetooth, Keychain)
│       ├── iosMain/             # iOS-specific (UIKit battery)
│       ├── macosMain/           # macOS-specific (battery stub)
│       ├── androidMain/         # Android BLE, storage, services
│       ├── jvmMain/             # JVM crypto provider
│       └── commonTest/          # 891 tests
├── meshlink-sample/
│   ├── android/                 # Jetpack Compose reference app
│   └── ios/                     # SwiftUI reference app
├── docs/                        # Architecture, API, wire format spec
├── Package.swift                # SPM manifest (iOS + macOS)
└── UBIQUITOUS_LANGUAGE.md       # Domain glossary
```

## Documentation

- [Integration Guide](docs/integration-guide.md) — Setup, configuration, and usage patterns
- [API Reference](docs/api-reference.md) — Complete public API surface
- [Architecture](docs/architecture.md) — Module structure and data flow
- [Wire Format Spec](docs/wire-format-spec.md) — Binary protocol specification
- [Design](docs/design.md) — Design decisions and rationale

## Building

```bash
# Run all JVM tests
./gradlew :meshlink:jvmTest

# Compile Android release
./gradlew :meshlink:compileReleaseKotlinAndroid

# Run iOS simulator tests
./gradlew :meshlink:iosSimulatorArm64Test

# Run macOS tests
./gradlew :meshlink:macosArm64Test

# Build XCFramework for SPM (iOS + macOS)
./gradlew :meshlink:assembleMeshLinkXCFramework
```

## CI

GitHub Actions runs on every push and PR:
- JVM tests (Ubuntu)
- Android compilation (Ubuntu)
- iOS simulator tests (macOS, Apple Silicon)

## License

[Unlicense](LICENSE) — Public domain.
