# Proof iOS Integration

This scaffold hosts the iOS proof app used by the MeshLink quickstart and
later physical-device validation tasks.

## Current Scope

- direct KMP integration notes are expected in this directory
- the SwiftUI entry point now includes a proof view model and control surface under `ProofApp/`
- the proof app installs an iOS `CryptoKit` bridge at startup via `ProofApp/MeshLinkCryptoBridge.swift`
- the shared library now contains a compile-verified CoreBluetooth L2CAP direct-transport path in `meshlink/src/iosMain/kotlin/ch/trancee/meshlink/platform/ios/IosBleTransport.kt`
- the Swift proof layer typechecks against the generated MeshLink framework, including start/stop/send actions and Flow collectors for state, peers, diagnostics, and messages
- benchmark source files will live under `ProofBenchmarks/`

## Current Validation

- `./gradlew :meshlink:compileKotlinIosSimulatorArm64`
- `./gradlew :meshlink:iosSimulatorArm64Test --tests '*IosL2capFrameBufferTest'`
- `xcrun swiftc -typecheck -target arm64-apple-ios15.0-simulator -sdk $(xcrun --show-sdk-path --sdk iphonesimulator) -F meshlink/build/bin/iosSimulatorArm64/debugFramework meshlink-sample/ios/ProofApp/*.swift`

Real-device validation is still pending because the attached iPhone 15 remains
offline/untrusted in local Xcode tooling.

## Next Steps

Later implementation tasks should wire the SwiftUI proof UI to the shared
MeshLink runtime, add real-device iOS proof validation, and add benchmark
instrumentation.
