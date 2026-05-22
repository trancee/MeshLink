# Quickstart: MeshLink reference app

## Goal

Install the reference app on Android and iOS, complete the guided first
exchange, inspect the technical timeline, and export one redacted session
artifact.

## Prerequisites

You need:

- this repository checked out locally
- Xcode for the iOS host project
- an Android device running API 29+
- an iPhone running iOS 15+
- Bluetooth enabled on both devices
- an Apple development team available locally if you want a physical iPhone
  build

If you have only one prepared device, you can still use the clearly labeled
solo exploration mode for a non-authoritative walkthrough.

If peer discovery stalls because one platform is still blocked on permissions or
first-run prompts, use [How to unblock MeshLink permissions on Android and iOS](../../docs/how-to/unblock-meshlink-permissions.md)
before continuing.

## 1. Build and install the Android app

```bash
./gradlew :meshlink-reference:installDebug
```

## 2. Build the iOS host app

For the simulator:

```bash
xcodebuild \
  -project meshlink-reference/ios/ReferenceApp.xcodeproj \
  -scheme ReferenceApp \
  -destination 'generic/platform=iOS Simulator' \
  build
```

For a physical iPhone:

```bash
xcodebuild \
  -project meshlink-reference/ios/ReferenceApp.xcodeproj \
  -scheme ReferenceApp \
  -destination 'id=<your-device-udid>' \
  DEVELOPMENT_TEAM=<your-team-id> \
  build
```

After the build succeeds, launch the `ReferenceApp` scheme from Xcode on the
chosen simulator or device.

## 3. Open the guided first-exchange flow on both devices

The first visible path should be the same named guided workflow on both
platforms.

Expected early states:

- readiness check
- MeshLink start action
- peer wait or peer selection
- send proof
- timeline evidence review

## 4. Complete the first exchange

Use the guided flow to:

1. start the mesh on both devices
2. wait for peer discovery
3. select the discovered peer
4. send the default first message
5. confirm that discovery, trust, and delivery evidence appear in the timeline

## 5. Inspect the advanced surface

Use the advanced area to confirm that the app exposes:

- lifecycle controls
- the visible power mode
- trust reset
- the current configuration snapshot relevant to the app

## 6. Confirm retained history and export behavior

After ending the session:

- open recent local session history
- verify the ended session appears separately from any live session
- open the export chooser and export a redacted session artifact
- confirm the export includes payload metadata and redacted previews by default
- confirm retained history does not offer full-payload export because retained
  sessions keep only redacted payload evidence

## 7. Keep proof-only behavior in the lab

The lab may expose proof-only or benchmark-only behavior, but it must stay
clearly separated from the main guided and advanced product-reference surfaces.

## 8. Optional: retain a headless two-device proof run

Once the manual flow works, you can retain one repeatable physical proof run
with the live-proof harness:

```bash
python3 meshlink-reference/scripts/run_headless_reference_live_proof.py \
  --android-serial <your-android-serial> \
  --ios-device <your-iphone-udid> \
  --run-dir /tmp/reference_live_proof_attempt
```

The runner:

- installs the Android debug app
- builds and reinstalls the physical iPhone app
- launches Android as the passive peer and the iPhone as the sender
- waits for the live guided exchange to complete
- retains proof artifacts in the chosen run directory

Expected retained outputs:

- `summary.json`
- `android_history.json`
- `android_export.json`

## Expected first proof point

A reviewer should be able to complete the guided first exchange on Android and
an iPhone, then point to:

- a discovered peer
- a successful trust and delivery timeline sequence
- a retained session entry in recent history
- one exported redacted session artifact
