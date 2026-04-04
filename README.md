# MeshLink

A **Kotlin Multiplatform (KMP)** BLE mesh messaging library for **Android**, **iOS**, **macOS**, **Linux**, and **JVM**. MeshLink enables secure, multi-hop peer-to-peer communication over Bluetooth Low Energy with ~85% shared code across platforms.

## Features

- **Multi-hop mesh routing** — Babel-based routing (RFC 8966 adapted for BLE) with proactive route propagation, composite cost metrics, and loop-free convergence
- **Two-layer encryption** — Hop-by-hop (Noise XX) and end-to-end (Noise K) using `Noise_XX_25519_ChaChaPoly_SHA256`
- **Zero external crypto dependencies** — Pure Kotlin Ed25519/X25519, platform-native SHA-256 and ChaCha20-Poly1305
- **Large message transfer** — Chunking, selective ACKs (SACK), AIMD congestion control, byte-offset resume
- **Power-aware operation** — Automatic tuning of scan duty cycle, advertising interval, and chunk sizes based on battery level
- **L2CAP support** — High-throughput data plane with automatic GATT fallback
- **Real-time diagnostics** — 20 diagnostic event codes, health snapshots, and Flow-based event streams
- **Identity rotation** — Rotate keys with signed broadcast announcements and configurable grace periods

## Platform Support

| Target | Min Version | Details |
|--------|-------------|---------|
| Android | API 26 | Pure Kotlin crypto (API 26–32), JCA (API 33+) |
| iOS | arm64, simulatorArm64 | CoreBluetooth, Keychain |
| macOS | arm64 | CoreBluetooth, Keychain |
| Linux | arm64, x64 | Raw HCI/L2CAP sockets via BlueZ kernel API |
| JVM | Java 21 | Java native JCA crypto |

## Quick Start

```kotlin
val mesh = MeshLink(
    transport = bleTransport,
    config = meshLinkConfig { maxMessageSize = 50_000 },
    crypto = CryptoProvider(),
    cryptoDispatcher = Dispatchers.Default, // offload crypto to background threads
)
mesh.start()
```

See the [Integration Guide](docs/integration-guide.md) for complete setup, configuration presets, and platform-specific examples.

## Configuration Presets

MeshLink ships with four presets — `smallPayloadLowLatency`, `largePayloadHighThroughput`,
`minimalResourceUsage`, and `minimalOverhead` — see the
[API Reference](docs/api-reference.md#presets) for details.

## Security

MeshLink uses two-layer Noise protocol encryption — Noise XX
(`Noise_XX_25519_ChaChaPoly_SHA256`) for hop-by-hop confidentiality between
neighbors and Noise K (`Noise_K_25519_ChaChaPoly_SHA256`) for end-to-end
authentication between sender and recipient. Identity is Ed25519 with TOFU
(Trust-On-First-Discover) key pinning. See the
[Threat Model](docs/threat-model.md) and
[Integration Guide § Encryption & Trust](docs/integration-guide.md#encryption--trust)
for details.

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
│       └── commonTest/          # 1,210+ tests
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

[Integration Guide](docs/integration-guide.md) · [Design](docs/design.md) · [Wire Format Spec](docs/wire-format-spec.md) · [API Reference](docs/api-reference.md) · [Threat Model](docs/threat-model.md) · [Diagrams](docs/diagrams.md)

## Building

### Prerequisites

- **Java 21** (e.g., Zulu, Temurin)
- **Gradle 9.4+** (wrapper included)
- **Android SDK** with `compileSdk 36` (for Android target)
- **Xcode 15+** (for iOS/macOS targets, macOS only)

### Library

```bash
# Run all JVM tests
./gradlew :meshlink:jvmTest

# Run integration tests only (41 end-to-end scenarios)
./gradlew :meshlink:jvmTest --tests "io.meshlink.MeshIntegrationTest" --parallel

# Compile Android AAR
./gradlew :meshlink:compileAndroidMain

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

**With Android Studio:**

1. Open the **root `MeshLink/` directory** in Android Studio (File → Open)
2. Wait for Gradle sync to complete
3. In the **Run Configurations** dropdown, select `meshlink-sample.android`
4. Select a device or emulator (API 26+)
5. Click ▶ Run
6. Grant **Bluetooth** and **Location** permissions when prompted

> **Tip:** If the run configuration doesn't appear, go to
> Run → Edit Configurations → + → Android App, set module to
> `MeshLink.meshlink-sample.android.main` and launch activity to
> `io.meshlink.sample.MainActivity`.

**From the command line:**

```bash
# Build debug APK
./gradlew :meshlink-sample:android:assembleDebug

# APK location:
# meshlink-sample/android/build/outputs/apk/debug/android-debug.apk

# Install on connected device
adb install meshlink-sample/android/build/outputs/apk/debug/android-debug.apk
```

The app has four screens: **Chat** (start/stop mesh, send messages),
**Mesh Visualizer** (peer topology graph), **Diagnostics** (filterable event log),
and **Settings** (config presets, MTU).

The sample uses the real `AndroidBleTransport` — it will advertise, scan,
and connect to other MeshLink devices over BLE. Runtime permissions
(Bluetooth Scan/Connect/Advertise + Location) are requested on launch.

#### UI Tests (Maestro)

The Android sample app includes [Maestro](https://maestro.mobile.dev/) UI
automation flows covering navigation, lifecycle, messaging, settings, and
diagnostics.

**Prerequisites:**

- Maestro CLI installed (`curl -fsSL "https://get.maestro.mobile.dev" | bash`)
- Android emulator running or device connected
- Debug APK installed:
  ```bash
  ./gradlew :meshlink-sample:android:assembleDebug
  adb install meshlink-sample/android/build/outputs/apk/debug/android-debug.apk
  ```

**Running:**

```bash
# All UI tests
maestro test maestro/flows/android/

# Smoke tests only
maestro test maestro/flows/android/ --include-tags=smoke

# Single flow
maestro test maestro/flows/android/01-app-launch-navigation.yaml

# Continuous mode (re-runs on every YAML save — great for development)
maestro test maestro/flows/android/01-app-launch-navigation.yaml -c

# Generate JUnit report
maestro test maestro/flows/android/ --format=JUNIT --output=report.xml
```

**Flow structure:**

```
maestro/
├── config.yaml                        # Workspace config
└── flows/
    ├── android/                       # 10 Android end-to-end flows
    │   ├── 01-app-launch-navigation   # Tab navigation (smoke)
    │   ├── 02-start-stop-mesh         # Mesh lifecycle (smoke)
    │   ├── 03-broadcast-message       # Broadcast messaging (smoke)
    │   ├── 04-settings-presets        # Config preset switching
    │   ├── 05-settings-mtu            # MTU & config details
    │   ├── 06-settings-mesh-status    # Running/Stopped state sync
    │   ├── 07-diagnostics             # Severity filters & events
    │   ├── 08-mesh-visualizer         # Canvas graph & legend
    │   ├── 09-health-card             # Health snapshot fields
    │   └── 10-send-validation         # Button enable/disable
    ├── ios/                           # 10 iOS end-to-end flows
    │   └── (mirrors android/ with SwiftUI-specific selectors)
    └── subflows/                      # Shared building blocks
        ├── grant-permissions.yaml     # BLE + Location (Android & iOS)
        ├── navigate-to-tab.yaml       # Parameterized tab navigation
        ├── start-mesh.yaml            # Start + permissions + verify
        └── stop-mesh.yaml             # Stop + verify
```

#### iOS (SwiftUI)

```bash
./gradlew :meshlink:assembleMeshLinkXCFramework
open meshlink-sample/ios/MeshLinkSample.xcodeproj
```

Build the XCFramework, open the pre-configured Xcode project, and hit ⌘R.
See [meshlink-sample/ios/README.md](meshlink-sample/ios/README.md) for details.

#### iOS UI Tests (Maestro)

The iOS sample app has its own set of Maestro flows under `maestro/flows/ios/`,
tailored to the SwiftUI interface (different labels, segmented picker, icon-only
buttons, etc.).

**Prerequisites:**

- macOS with Xcode 15+ and Simulator
- Maestro CLI installed (`curl -fsSL "https://get.maestro.mobile.dev" | bash`)
- iOS sample app running on a Simulator:
  ```bash
  ./gradlew :meshlink:assembleMeshLinkXCFramework
  open meshlink-sample/ios/MeshLinkSample.xcodeproj
  # Build & Run on Simulator (⌘R) in Xcode
  ```

**Running:**

```bash
# All iOS flows
maestro --platform=ios test maestro/flows/ios/

# Smoke tests only
maestro --platform=ios test maestro/flows/ios/ --include-tags=smoke

# Single flow
maestro --platform=ios test maestro/flows/ios/01-app-launch-navigation.yaml

# Continuous mode
maestro --platform=ios test maestro/flows/ios/01-app-launch-navigation.yaml -c

# Target a specific simulator
maestro --device="iPhone 16" --platform=ios test maestro/flows/ios/
```

**iOS flow structure:**

```
maestro/flows/ios/
├── 01-app-launch-navigation     # Tab navigation (smoke)
├── 02-start-stop-mesh           # Mesh lifecycle (smoke)
├── 03-broadcast-message         # Broadcast via icon button (smoke)
├── 04-settings-presets          # Segmented picker presets
├── 05-settings-mtu              # MTU & current configuration
├── 06-settings-mesh-status      # Running/Stopped in Form section
├── 07-diagnostics               # Severity filters, clear button
├── 08-mesh-visualizer           # Canvas graph, status badges, legend
├── 09-health-card               # Health snapshot fields
└── 10-clear-log                 # Clear Log toolbar button
```

> **Note:** BLE does not work on the iOS Simulator. Flows that start the mesh
> will run but won't discover real peers. Use a physical device for full BLE
> testing.

#### macOS (SwiftUI)

```bash
./gradlew :meshlink:assembleMeshLinkXCFramework
open meshlink-sample/macos/MeshLinkSample.xcodeproj
```

Build the XCFramework, open the pre-configured Xcode project, and hit ⌘R.
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

GitHub Actions runs on every push and PR (when relevant files change):
- JVM tests (Ubuntu)
- Android compilation (Ubuntu)
- iOS simulator tests (macOS, Apple Silicon)
- macOS native tests (macOS, Apple Silicon)
- Linux native compilation (Ubuntu)

## License

[Unlicense](LICENSE) — Public domain.
