# MeshLink reference app

Use this app when you want to evaluate MeshLink as a product-like experience,
not just as a proof harness.

It is designed for:

- SDK evaluators who want a guided first exchange
- integrators who want to inspect the live control surface
- QA and support engineers who want diagnostics, retained session history, and
  exportable evidence
- reviewers who need the same named experience on Android and iOS

## What the app shows

The reference app is organized into clearly separated surfaces:

- **Guided first exchange** — the fastest path to a first offline message proof
- **Solo exploration** — a non-authoritative walkthrough when only one device
  is available
- **Advanced controls** — the richer runtime control surface for technical
  reviewers
- **Technical timeline** — lifecycle, peer, diagnostic, message, and transfer
  events in one place
- **Recent history** — retained sessions kept separate from the live run
- **Lab** — proof-only and benchmark-only behavior isolated from the supported
  product path

## What it is not

This app is not:

- the normative proof benchmark harness
- a consumer chat product
- a replacement for the Android and iOS proof apps used for retained transport
  evidence

Use the proof apps and benchmark runner for transport-performance evidence. Use
this app to understand and demonstrate the library as a coherent reference
experience.

## Run it

For the stable walkthrough, start with:

- [How to evaluate MeshLink with the reference app](../docs/how-to/evaluate-meshlink-with-the-reference-app.md)

For surrounding context, use:

- [MeshLink documentation map](../docs/README.md)
- [How to unblock MeshLink permissions on Android and iOS](../docs/how-to/unblock-meshlink-permissions.md)
- [Android proof app guide](../meshlink-proof/android/README.md)
- [iOS proof app guide](../meshlink-proof/ios/README.md)
- [Benchmarks and retained evidence](../benchmarks/README.md)

## UI automation

The reference app includes deterministic Android and iOS workflow automation so
the operator surfaces can be validated without requiring a live BLE peer.

Android:

```bash
ANDROID_SERIAL=<your-android-device-serial> \
./gradlew :meshlink-reference:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=ch.trancee.meshlink.reference.ReferenceAppAndroidWorkflowTest \
  -Pandroid.testInstrumentationRunnerArguments.meshlink.reference.workflow=true
```

iOS:

```bash
xcodebuild \
  -project meshlink-reference/ios/ReferenceApp.xcodeproj \
  -scheme ReferenceApp \
  -destination 'platform=iOS Simulator,name=iPhone 16' \
  test
```

The automation targets launch the app in a deterministic scripted mode so they
can validate guided, advanced, timeline/history/export, lab, and blocked-start
surfaces while keeping the full two-device proof run separate.

## Physical live proof

Use the live-proof harness when you need retained evidence from a real Android ↔
physical iPhone guided exchange instead of scripted UI validation.

```bash
python3 meshlink-reference/scripts/run_headless_reference_live_proof.py \
  --android-serial <your-android-serial> \
  --ios-device <your-iphone-udid> \
  --run-dir /tmp/reference_live_proof_attempt
```

The harness:

- installs the Android debug build
- builds and reinstalls the physical iPhone app
- launches Android in passive live-proof mode
- launches the iPhone as the live sender
- waits for the guided exchange, retained history, and redacted export to finish
- writes retained evidence into the chosen run directory

Expected retained outputs:

- `summary.json` — Android passive proof completion, iPhone sender completion,
  and the retained export path
- `android_history.json` — retained session history evidence
- `android_export.json` — redacted export evidence
- `android_logcat.log` and `iphone_console.log` — raw device logs for the run

## Platform caveats

- If discovery stalls on Android or the first physical iPhone launch stops at a
  Bluetooth prompt, use [How to unblock MeshLink permissions on Android and iOS](../docs/how-to/unblock-meshlink-permissions.md) before debugging anything deeper.
- The default physical-launch path uses `devicectl`. The optional
  `--ios-launch-mode xcuitest` fallback exists for first-run permission
  handling. On free Apple development profiles, use
  `--cleanup-ios-dev-app-slots` if you need the runner to free old MeshLink dev
  apps before starting that fallback path.

## Expected outcome

After using the reference app, a reviewer should be able to:

1. complete a guided first exchange
2. explain why the last send succeeded or failed
3. inspect retained session history separately from the live run
4. open the export chooser and export a redacted session artifact
5. distinguish supported product behavior from lab-only behavior
