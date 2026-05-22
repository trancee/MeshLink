# How to evaluate MeshLink with the reference app

Use this guide when you want to evaluate MeshLink as an SDK and runtime
experience, not just as a transport proof harness.

This guide helps you:

- install the Android and iOS reference app builds
- complete one guided first exchange
- inspect the advanced controls and technical timeline
- retain one session and export one redacted artifact
- decide when to switch to the proof apps or the live-proof harness

If you need the app overview itself, use
[MeshLink reference app](../../meshlink-reference/README.md).

## Before you start

You need:

- this repository checked out locally
- Xcode for the iOS host project
- an Android device running API 29+
- an iPhone running iOS 15+
- Bluetooth enabled on both devices
- an Apple development team available locally if you want to run on a physical iPhone

If one device is not ready yet, you can still use the app's clearly labelled
solo exploration mode for a non-authoritative walkthrough.

If discovery stalls because Android or iOS is still blocked on permissions or
the first Bluetooth prompt, fix that first with
[How to unblock MeshLink permissions on Android and iOS](unblock-meshlink-permissions.md).

## 1. Install the Android reference app

```bash
./gradlew :meshlink-reference:installDebug
```

Launch the installed app on the Android device after the install completes.

## 2. Build and launch the iOS reference app

For the simulator:

```bash
xcodebuild \
  -project meshlink-reference/ios/ReferenceApp.xcodeproj \
  -scheme ReferenceApp \
  -destination 'generic/platform=iOS Simulator' \
  build
```

For a physical iPhone, pass your team at build time instead of storing it in repo
files:

```bash
xcodebuild \
  -project meshlink-reference/ios/ReferenceApp.xcodeproj \
  -scheme ReferenceApp \
  -destination 'id=<your-device-udid>' \
  DEVELOPMENT_TEAM=<your-team-id> \
  build
```

After the build succeeds, launch the `ReferenceApp` scheme from Xcode on the chosen
simulator or iPhone.

If iOS shows the first Bluetooth prompt on a physical device, allow it before you
continue.

## 3. Open the guided first-exchange flow on both devices

On both platforms, start in the guided first-exchange surface.

The expected flow is the same on Android and iOS:

1. readiness check
2. MeshLink start action
3. peer wait or peer selection
4. send proof action
5. timeline evidence review

If the flow names differ between platforms, stop and fix that before using the
app as reference evidence.

## 4. Complete one guided first exchange

Use the guided surface to:

1. start MeshLink on both devices
2. wait for peer discovery
3. select the discovered peer
4. send the default first message
5. confirm that trust and delivery evidence appear in the timeline

A successful evaluation pass should show:

- a discovered peer on both devices
- a trust establishment event
- a delivery or inbound-message event
- a final guided outcome that clearly indicates success

## 5. Inspect the advanced controls

Open the advanced controls surface and confirm that it exposes the runtime
details a host-app integrator would care about:

- lifecycle controls
- the visible power mode
- the current configuration snapshot
- peer and trust state
- send controls with priority selection
- trust reset behavior

Use this surface to answer technical questions about why the last send succeeded or
failed.

## 6. Inspect the technical timeline

Open the technical timeline and verify that it gives you one operator-facing
place to inspect:

- lifecycle events
- peer events
- diagnostics
- inbound and outbound message evidence
- retained-session state and export actions

Use the filter controls to narrow the view when you want to isolate one kind of event.

## 7. Retain one session and export one redacted artifact

After the guided exchange succeeds:

1. retain the current session
2. open retained history
3. verify the retained session appears separately from the live session
4. export a redacted artifact

The default export should keep payload previews redacted and should not
silently switch to full-payload export.

## 8. Use the right validation path for the job

Use the reference app when you need:

- a guided first proof for SDK evaluation
- a product-like operator surface
- technical evidence that is easy to walk through with another engineer

Switch to the proof apps or the live-proof harness when you need:

- retained transport validation evidence
- physical Android ↔ iPhone proof runs
- benchmark-only or proof-only behavior that the app keeps isolated from the main
  reference surfaces

For proof-specific workflows, use:

- [How to run the Android proof app](../../meshlink-proof/android/README.md)
- [How to build and run the iOS proof app](../../meshlink-proof/ios/README.md)
- [Benchmarks and retained evidence](../../benchmarks/README.md)

## 9. Optional: retain one headless live-proof run

Once the manual walkthrough works, retain one repeatable physical proof run
with this harness:

```bash
python3 meshlink-reference/scripts/run_headless_reference_live_proof.py \
  --android-serial <your-android-serial> \
  --ios-device <your-iphone-udid> \
  --run-dir /tmp/reference_live_proof_attempt
```

The harness installs the Android debug build, rebuilds the iPhone app, runs the
physical guided exchange, and writes retained evidence into the chosen run directory.

## Expected outcome

After following this guide, you should be able to:

1. complete a guided first exchange
2. explain the last trust or delivery outcome from the timeline
3. inspect retained session history separately from the live run
4. export a redacted session artifact
5. distinguish supported reference behavior from proof-only or benchmark-only behavior
