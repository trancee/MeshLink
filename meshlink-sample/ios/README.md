# Proof iOS Integration

This scaffold hosts the iOS proof app used by the MeshLink quickstart and
later physical-device validation tasks.

## Current Scope

- direct KMP integration notes are expected in this directory
- the SwiftUI entry point is scaffolded under `ProofApp/`
- the proof app now installs an iOS `CryptoKit` bridge at startup via `ProofApp/MeshLinkCryptoBridge.swift`
- the shared library now contains a compile-verified CoreBluetooth L2CAP direct-transport path in `meshlink/src/iosMain/kotlin/ch/trancee/meshlink/platform/ios/IosBleTransport.kt`
- benchmark source files will live under `ProofBenchmarks/`

## Current Validation

- `./gradlew :meshlink:compileKotlinIosSimulatorArm64`
- `./gradlew :meshlink:iosSimulatorArm64Test --tests '*IosL2capFrameBufferTest'`

Real-device validation is still pending because the attached iPhone 15 remains
offline/untrusted in local Xcode tooling.

## Next Steps

Later implementation tasks should wire the SwiftUI proof UI to the shared
MeshLink runtime, add real-device iOS proof validation, and add benchmark
instrumentation.
