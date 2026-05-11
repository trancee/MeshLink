# Proof iOS Integration

This app is the iOS reference host for the MeshLink quickstart.

## What the repo now contains

- a committed `ProofApp.xcodeproj` plus a matching `project.yml` XcodeGen spec
- a SwiftUI proof UI with Start / Stop / Send Hello controls, peer visibility, diagnostics, and inbound-message logging
- the Swift-installed `CryptoKit` bridge required by `IosCryptoProvider`
- direct KMP framework integration through a pre-build Gradle script that runs `:meshlink:embedAndSignAppleFrameworkForXcode`
- the default MeshLink `deliveryRetryDeadline` behavior; the proof app leaves the default 15-second deadline unchanged

## Build and run

### Regenerate the Xcode project (optional)

The committed Xcode project is ready to open directly. If the project spec changes,
regenerate it with:

```bash
cd meshlink-sample/ios
xcodegen --spec project.yml
```

### Build for the simulator

```bash
xcodebuild \
  -project meshlink-sample/ios/ProofApp.xcodeproj \
  -scheme ProofApp \
  -destination 'id=6C7DD73A-EC9C-46F9-B0B9-DD136F748621' \
  build
```

### Build for a physical iPhone

You need a local Apple development team for device signing. Either:

- open `ProofApp.xcodeproj` in Xcode and select a team under Signing & Capabilities, or
- pass `DEVELOPMENT_TEAM=<your-team-id>` to `xcodebuild`

Example CLI build:

```bash
xcodebuild \
  -project meshlink-sample/ios/ProofApp.xcodeproj \
  -scheme ProofApp \
  -destination 'id=<your-device-udid>' \
  -allowProvisioningUpdates \
  DEVELOPMENT_TEAM=<your-team-id> \
  build
```

### Install and launch on a physical iPhone

```bash
xcrun devicectl device install app \
  --device <your-device-udid> \
  ~/Library/Developer/Xcode/DerivedData/ProofApp-*/Build/Products/Debug-iphoneos/ProofApp.app

xcrun devicectl device process launch \
  --device <your-device-udid> \
  ch.trancee.meshlink.proof.ios
```

On the first local device run, iOS may refuse to launch the app until the
developer profile has been trusted on the phone.

## Current validation status

Verified in this repository state:

- `./gradlew :meshlink:compileKotlinIosSimulatorArm64`
- `./gradlew :meshlink:iosSimulatorArm64Test --tests '*IosL2capFrameBufferTest'`
- `xcodebuild -project meshlink-sample/ios/ProofApp.xcodeproj -scheme ProofApp -destination 'id=6C7DD73A-EC9C-46F9-B0B9-DD136F748621' build`
- `xcodebuild -project meshlink-sample/ios/ProofApp.xcodeproj -scheme ProofApp -destination 'id=00008120-00011DEE0105A01E' -allowProvisioningUpdates DEVELOPMENT_TEAM=<local-team-id> build`
- `xcrun devicectl device install app --device 00008120-00011DEE0105A01E <built-app-path>`

Shared harness evidence now also covers the common US2 runtime:

- three-node routed delivery across a relay hop
- route reconvergence after topology change
- 64 KiB routed transfer over a 512-byte per-delivery virtual link budget
- bounded, jittered exponential-backoff no-route retry expiry with `UNREACHABLE`
- immediate retry when a route appears before `deliveryRetryDeadline` expires

Current manual blocker:

- the installed app launch is still denied on the attached iPhone 15 until the
  developer profile is explicitly trusted on the device

## What to do once the phone trusts the build

1. Launch the app on the iPhone.
2. Tap **Start MeshLink**.
3. Wait for a nearby Android or iOS proof peer to appear.
4. Tap **Send Hello**.
5. Confirm the diagnostics and inbound-message log match the quickstart flow.
