# About proof validation surfaces

This page explains why MeshLink keeps the reference app, the proof apps, and
retained benchmarks separate, and how to choose the right surface for the claim
you want to make.

It is an explanation page. Use the other docs when the job is different:

- product-like evaluation through the shared Android and iOS experience —
  [How to evaluate MeshLink with the reference app](../how-to/evaluate-meshlink-with-the-reference-app.md)
- the internal architecture of the reference app, proof apps, and automated tests —
  [Reference app and test architecture](reference-app-and-test-architecture.md)
- Android proof-host setup and launch steps —
  [How to run the Android proof app](../../meshlink-proof/android/README.md)
- iPhone proof-host setup and launch steps —
  [How to build and run the iOS proof app](../../meshlink-proof/ios/README.md)
- retained performance thresholds and latest evidence —
  [Benchmark and validation baselines](../../benchmarks/README.md)

## One repository, three different kinds of claim

The repository supports several kinds of validation, but they are not all meant
for the same conclusion.

| Surface | Best for | Supports claims about | Does not replace |
|---|---|---|---|
| Reference app | product-like evaluation | supported session flow, operator-facing diagnostics, retained history, and export behavior | benchmarking with proof fixtures and prototype transport investigation |
| Proof apps | physical proof fixtures | device-to-device proof runs, second-device proof peers, and proof-only transport experiments | the supported product-like evaluation surface |
| Benchmark runner and retained baselines | repeated performance evidence | latency, throughput, cold-start, and threshold-oriented retained evidence | a walkthrough-oriented host app or UX review |

That separation is deliberate. MeshLink needs to prove both that the SDK behaves
correctly on devices and that the supported evaluation experience stays coherent.
Trying to make one app do every job would blur those claims together.

The reference-app family now also includes a validated retained release-review campaign surface. It discovers the live fleet, persists `fleet-manifest.json`, `campaign-plan.json`, `campaign-state.json`, and `report-data.json`, and renders a self-contained offline `release-review-report.html` for reviewers who need a ship-or-no-ship decision without raw-log archaeology. On hosts where mixed iOS live proof is unsupported, that campaign explicitly falls back to Android-only direct-guided; the proof apps themselves remain separate evidence surfaces and do not carry the release-review selection contract.

## Why the proof apps remain separate

The proof apps are proof fixtures: dedicated transport-validation surfaces,
not smaller copies of the reference app.

That allows them to carry behavior that would be confusing in the supported
product-like surface, including:

- proof-only launch knobs
- benchmark-oriented auto-send behavior
- prototype transport modes such as `gatt` and `gatt-notify`
- device-side logging that is useful for retained evidence but not part of the
  main operator story

Keeping those behaviors separate means a reviewer can still say:

- "the reference app shows the supported evaluation path"
- "the proof apps show physical transport evidence"
- "the retained benchmarks show the current numeric posture"

without those statements stepping on each other.

## Why the proof hosts still look platform-specific

The proof apps are intentionally separate on Android and iPhone because the
host-level concerns are genuinely different:

- Android launch control is shaped around install tasks, `adb`, intent extras,
  and Bluetooth permission handling
- iOS launch control is shaped around Xcode signing, environment variables,
  simulator or device destinations, and retained console capture

That does not mean the hosts should become giant files.
The current proof-host refactors keep the platform shell thin while pushing the
real moving parts behind narrower helpers such as launch-config parsing,
permission policy, benchmark framing, runtime ownership, benchmark-mode
switching, and transport-log capture.

The goal is simple: let the host stay host-shaped, but do not let every proof
concern accumulate in one file.

## Prototype transport modes are deliberate, not alternate product paths

Both proof apps still expose `meshlink`, `gatt`, and `gatt-notify` launch modes,
but they do not all carry the same meaning.

- `meshlink` is the product-path runtime and is the right default when you need
  a tutorial proof peer, a manual proof peer, or a sanity check close to
  supported behavior
- `gatt` and `gatt-notify` are retained prototype modes for transport
  investigation and benchmark-oriented work

Those prototype modes are intentionally asymmetric across hosts.
For example:

- the current Android `gatt` prototype is a passive server-oriented proof fixture
- the current iPhone prototype modes are active benchmark modes rather than
  passive observation proof fixtures

That asymmetry is acceptable because the proof apps are evidence surfaces, not a
promise that every prototype behavior must look identical on both hosts.
If you are validating supported user-visible behavior, stay in `meshlink` mode
or move to the reference app.

## Choose the next doc by the claim you want to make

| If you want to... | Start here |
|---|---|
| evaluate MeshLink as one coherent Android and iOS experience | [How to evaluate MeshLink with the reference app](../how-to/evaluate-meshlink-with-the-reference-app.md) |
| use an Android device as a physical proof peer or proof fixture | [How to run the Android proof app](../../meshlink-proof/android/README.md) |
| use an iPhone as a physical proof peer or proof fixture | [How to build and run the iOS proof app](../../meshlink-proof/ios/README.md) |
| inspect the current retained performance posture | [Benchmark and validation baselines](../../benchmarks/README.md) |
| understand the larger repository split behind those surfaces | [About the repository architecture](about-the-repository-architecture.md) |

## Related docs

- [MeshLink documentation map](../README.md)
- [How to evaluate MeshLink with the reference app](../how-to/evaluate-meshlink-with-the-reference-app.md)
- [How to run the Android proof app](../../meshlink-proof/android/README.md)
- [How to build and run the iOS proof app](../../meshlink-proof/ios/README.md)
- [Benchmark and validation baselines](../../benchmarks/README.md)
- [About the repository architecture](about-the-repository-architecture.md)
