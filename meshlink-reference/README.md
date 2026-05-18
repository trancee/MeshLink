# MeshLink reference app

Use this app when you want to evaluate MeshLink as a product-like experience
instead of a proof harness.

It is designed for:

- SDK evaluators who want a guided first exchange
- integrators who want to inspect the live control surface
- QA and support engineers who want logs, diagnostics, retained session history,
  and exportable evidence
- reviewers who need the same named experience on Android and iOS

## What the app shows

The reference app is organized into clearly separated surfaces:

- **Guided first exchange** — the fastest path to a first offline message proof
- **Solo exploration** — a non-authoritative walkthrough when only one device is
  available
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

For a fresh-reader walkthrough, start with the feature quickstart:

- [Reference app quickstart](../specs/002-meshlink-reference-app/quickstart.md)

For surrounding context, use:

- [MeshLink documentation map](../docs/README.md)
- [Android proof app guide](../meshlink-proof/android/README.md)
- [iOS proof app guide](../meshlink-proof/ios/README.md)
- [Benchmarks and retained evidence](../benchmarks/README.md)

## iOS UI automation

The iOS host project now includes a simulator-driven UI automation path for the
reference workflows. Run it with:

```bash
xcodebuild \
  -project meshlink-reference/ios/ReferenceApp.xcodeproj \
  -scheme ReferenceApp \
  -destination 'platform=iOS Simulator,name=iPhone 16' \
  test
```

The UI test target launches the app in a deterministic automation mode so it
can validate guided, advanced, timeline/history/export, lab, and blocked-start
surfaces without requiring a physical BLE peer.

## Expected outcome

After using the reference app, a reviewer should be able to:

1. complete a guided first exchange
2. explain why the last send succeeded or failed
3. inspect retained session history separately from the live run
4. export a redacted session artifact
5. distinguish supported product behavior from lab-only behavior
