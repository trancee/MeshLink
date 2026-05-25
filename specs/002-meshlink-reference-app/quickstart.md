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

## Evidence to record

- guided first-exchange start time
- guided first-exchange proof time
- timeline entry count used for filter/search/export validation
- redacted export start time
- redacted export completion time
- whether the session used live or retained history

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

For SC-001 evidence:

- record the guided first-exchange start time when the live flow begins
- record the proof time when matching delivery and diagnostic evidence are visible
- confirm the live proof path completes within 5 minutes

## 5. Run the scripted advanced evaluation

Use the advanced area on both platforms to complete the SC-002 flow without
consulting source code:

1. confirm the same peer-selection controls are visible and select the
   discovered peer
2. use lifecycle controls to pause and resume mesh participation, then confirm
   the resulting state change is visible
3. send a short payload from the composer and confirm the delivery outcome is
   visible
4. start the large transfer action and confirm progress plus the final outcome
   are visible
5. trigger trust reset for the selected peer and confirm the trust status
   changes in the UI
6. inspect the technical timeline or diagnostics surface and confirm it shows
   lifecycle, send, transfer, and trust-related evidence for the actions above

Also confirm that the advanced surface still exposes:

- the visible power mode
- the current configuration snapshot relevant to the app

## 6. Confirm retained history and export behavior

After you choose **End session** from the technical timeline:

- open recent local session history
- verify the eligible ended session appears separately from any live session
- if you are collecting SC-004 timing evidence, use a live or retained session containing 2,000 timeline entries for the filter/search/export check
- record that the session contains 2,000 timeline entries before exporting
- record the redacted export start time
- open the export chooser and export a redacted session artifact
- record the redacted export completion time
- confirm the export includes payload metadata and redacted previews by default
- confirm redacted export completes within 60 seconds
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

If you only need to verify the physical iPhone sender XCTest path, add:

```bash
  --ios-launch-mode xcuitest \
  --skip-android-completion-wait
```

That mode still launches the passive Android peer so the iPhone can discover a
real device, but it stops after the sender UI test passes instead of waiting
for a retained Android passive export.

Expected retained outputs:

- `summary.json`
- `android_history.json`
- `android_export.json`

If you need the broader retained physical campaign instead of a single direct
proof, continue with
[`docs/how-to/run-reference-app-physical-integration-scenarios.md`](../../docs/how-to/run-reference-app-physical-integration-scenarios.md).
That guide adds the constrained relay proof, the optional XCTest
permission-recovery path, and per-run `analysis.json` / `analysis.md` artifacts.

## Expected first proof point

A reviewer should be able to complete the guided first exchange on Android and
an iPhone, then point to:

- a discovered peer
- a successful trust and delivery timeline sequence
- a retained session entry in recent history
- one exported redacted session artifact
