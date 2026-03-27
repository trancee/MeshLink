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

| Target | Min Version | Details |
|--------|-------------|---------|
| Android | API 26 | Pure Kotlin crypto (API 26–32), JCA (API 33+) |
| iOS | arm64, simulatorArm64 | CoreBluetooth, Keychain |
| macOS | arm64 | CoreBluetooth, Keychain |
| Linux | arm64, x64 | Raw HCI/L2CAP sockets via BlueZ kernel API |
| JVM | Java 21 | Java native JCA crypto |

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
│       ├── linuxMain/           # Linux-specific (POSIX time, battery stub)
│       ├── androidMain/         # Android BLE, storage, services
│       ├── jvmMain/             # JVM crypto provider
│       └── commonTest/          # 891 tests
├── meshlink-sample/
│   ├── android/                 # Jetpack Compose reference app
│   ├── ios/                     # SwiftUI reference app
│   ├── macos/                   # SwiftUI macOS reference app
│   ├── jvm/                     # JVM console app
│   └── linux/                   # Linux console app (arm64, x64)
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

### Prerequisites

- **Java 21** (e.g., Zulu, Temurin)
- **Gradle 9.4+** (wrapper included)
- **Android SDK** with `compileSdk 35` (for Android target)
- **Xcode 15+** (for iOS/macOS targets, macOS only)

### Library

```bash
# Run all JVM tests (891 tests)
./gradlew :meshlink:jvmTest

# Compile Android AAR
./gradlew :meshlink:compileReleaseKotlinAndroid

# Run iOS simulator tests (requires macOS + Xcode)
./gradlew :meshlink:iosSimulatorArm64Test

# Run macOS tests
./gradlew :meshlink:macosArm64Test

# Compile Linux targets (cross-compiles from macOS)
./gradlew :meshlink:compileKotlinLinuxX64
./gradlew :meshlink:compileKotlinLinuxArm64

# Build XCFramework for Swift Package Manager (iOS + macOS)
./gradlew :meshlink:assembleMeshLinkXCFramework
```

### Sample Apps

#### JVM (console)

```bash
./gradlew :meshlink-sample:jvm:run
```

#### Android (Jetpack Compose)

```bash
# Build debug APK
./gradlew :meshlink-sample:android:assembleDebug

# APK location:
# meshlink-sample/android/build/outputs/apk/debug/android-debug.apk
```

Install with `adb install` and grant Bluetooth + Location permissions.

#### iOS (SwiftUI)

```bash
# 1. Build the XCFramework
./gradlew :meshlink:assembleMeshLinkXCFramework

# 2. Open in Xcode
#    - Create a new iOS App project
#    - Add the MeshLink SPM package (File → Add Package Dependencies → local repo)
#    - Copy files from meshlink-sample/ios/MeshLinkSample/ into your project
#    - Run on simulator or device
```

See [meshlink-sample/ios/README.md](meshlink-sample/ios/README.md) for detailed setup.

#### macOS (SwiftUI)

```bash
# 1. Build the XCFramework (same as iOS — includes macOS slice)
./gradlew :meshlink:assembleMeshLinkXCFramework

# 2. Open in Xcode
#    - Create a new macOS App project
#    - Add the MeshLink SPM package (local repo)
#    - Copy files from meshlink-sample/macos/MeshLinkSample/ into your project
#    - Run (⌘R)
```

See [meshlink-sample/macos/README.md](meshlink-sample/macos/README.md) for details.

#### Linux (console, native)

```bash
# Cross-compile from macOS
./gradlew :meshlink-sample:linux:linkReleaseExecutableLinuxX64
./gradlew :meshlink-sample:linux:linkReleaseExecutableLinuxArm64

# Executable locations:
# meshlink-sample/linux/build/bin/linuxX64/releaseExecutable/linux.kexe
# meshlink-sample/linux/build/bin/linuxArm64/releaseExecutable/linux.kexe

# Copy to target Linux machine and run:
# ./linux.kexe
```

To use real BLE hardware on Linux, edit `Main.kt` to use `LinuxBleTransport` instead of `DemoTransport`. Requires `CAP_NET_RAW` or root.

## CI

GitHub Actions runs on every push and PR:
- JVM tests (Ubuntu)
- Android compilation (Ubuntu)
- iOS simulator tests (macOS, Apple Silicon)

## License

[Unlicense](LICENSE) — Public domain.
