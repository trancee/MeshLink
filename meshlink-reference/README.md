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

Use the physical runners when you need retained evidence from real devices
instead of scripted UI validation.

### Direct guided proof

```bash
python3 meshlink-reference/scripts/run_headless_reference_live_proof.py \
  --android-serial <your-android-serial> \
  --ios-device <your-iphone-udid> \
  --run-dir /tmp/reference_live_proof_attempt
```

This is the stable one-hop baseline:

- Android runs as the passive peer
- the physical iPhone runs as the sender
- the scenario waits for retained history and a redacted export

### Constrained relay proof

```bash
python3 meshlink-reference/scripts/run_headless_reference_relay_proof.py \
  --ios-device <your-iphone-udid> \
  --relay-android-serial <samsung-serial> \
  --passive-android-serial <oppo-serial> \
  --run-dir /tmp/reference_relay_proof_attempt
```

This is the honest routed proof:

- `A = iPhone 15` sender
- `B = Samsung` relay
- `C = OPPO` passive recipient
- sender-side success only counts when the routed delivery reaches
  `routeIsDirect=false` and the passive side also retains/exports the session

### Physical matrix

```bash
python3 meshlink-reference/scripts/run_headless_reference_physical_matrix.py \
  --ios-device <your-iphone-udid> \
  --passive-android-serial <oppo-serial> \
  --relay-android-serial <samsung-serial>
```

The matrix keeps the physical campaign repeatable. The direct phase can cover:

- `direct-guided`
- `direct-pause-resume`
- `direct-full-export`
- `direct-trust-reset-recovery`
- `direct-large-transfer`

and the relay phase can cover:

- `relay-constrained`

Each scenario writes:

- `summary.json` — scenario-specific completion lines and retained export path
- `analysis.json` — machine-readable pass/fail analysis
- `analysis.md` — reviewer-friendly findings and next debugging direction
- raw device logs and retained artifacts for the scenario

For the scenario list, hard pass criteria, and the reasoning behind the matrix,
use [How to run the reference-app physical integration scenarios](../docs/how-to/run-reference-app-physical-integration-scenarios.md).

## Platform caveats

- If discovery stalls on Android or the first physical iPhone launch stops at a
  Bluetooth prompt, use [How to unblock MeshLink permissions on Android and iOS](../docs/how-to/unblock-meshlink-permissions.md) before debugging anything deeper.
- The default physical-launch path uses `devicectl`. The optional
  `--ios-launch-mode xcuitest` fallback exists for first-run permission
  handling. When you only need to verify the physical iPhone sender XCTest path,
  add `--skip-android-completion-wait` so the run stops after the sender test
  passes instead of waiting for a retained Android passive export. On free Apple
  development profiles, use `--cleanup-ios-dev-app-slots` if you need the
  runner to free old MeshLink dev apps before starting that fallback path.
- Keep the relay proof topology constrained and honest: `A = iPhone 15`,
  `B = Samsung`, `C = OPPO`, with both Android devices reachable over wireless
  ADB and `routeIsDirect=false` required on the sender side.

## Expected outcome

After using the reference app, a reviewer should be able to:

1. complete a guided first exchange
2. explain why the last send succeeded or failed
3. inspect retained session history separately from the live run
4. open the export chooser and export a redacted session artifact
5. distinguish supported product behavior from lab-only behavior
