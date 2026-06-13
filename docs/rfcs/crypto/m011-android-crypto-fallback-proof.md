# M011 Android crypto fallback proof plan

Status: draft design artifact

This note turns the Android crypto question into an implementation plan.
The transport/runtime floor is already API 26+ for the app shells, but Android
still does not officially guarantee the full MeshLink crypto set at that floor.
The open work is to prove or add in-repo fallback support for X25519/XDH and
ChaCha20-Poly1305 so MeshLink can justify a lower Android crypto floor without
overclaiming the platform contract.

## Background

MeshLink's current mobile app floor is Android API 26+ and iOS 14.0+.
That floor is correct for the transport/runtime shells, but Android's official
crypto API guarantees arrive later than that floor:

- `KeyAgreement` support for `XDH` is officially listed at API 33+
- `Cipher` support for `ChaCha20-Poly1305` is officially listed at API 28+

Current runtime behavior on Android is:

- Ed25519 has an in-repo fallback implementation
- X25519/XDH and ChaCha20-Poly1305 are treated as runtime-capability features
  on API 26-32
- if either primitive is missing, `JcaCryptoProviderFactory.create()` fails
  fast rather than silently weakening the crypto contract

## Current evidence

The repository already proves some of the boundary behavior we care about:

- `CryptoRuntimeCapabilityTest` covers the explicit failure path when
  `supportsChaCha20Poly1305` is false and when `supportsMeshLinkRuntime` is not
  satisfied
- the device matrix records the attached Android fleet and now includes crypto
  coverage gaps plus explicit future emulator targets for API 26 and API 28
- the release-status docs already say the Android crypto story is a runtime
  capability story on API 26-32 rather than a blanket platform guarantee

## Problem

We should not claim that older Android devices fully support the MeshLink crypto
contract until one of the following is true:

1. MeshLink has a deterministic in-repo fallback or adapter path for both
   X25519/XDH and ChaCha20-Poly1305, or
2. the supported device/provider baseline is proven to expose those primitives
   reliably on the Android versions we want to support.

Right now, only Ed25519 satisfies option 1.

## Implementation plan

### Task 1 — Make the runtime boundary explicit in tests

Strengthen the Android host tests so the crypto boundary is clear and repeatable.

Files to touch:

- `meshlink/src/androidHostTest/kotlin/ch/trancee/meshlink/platform/android/CryptoRuntimeCapabilityTest.kt`
- `meshlink/src/androidHostTest/kotlin/ch/trancee/meshlink/platform/android/CryptoProviderFactoryTest.kt`
- `meshlink/src/androidHostTest/kotlin/ch/trancee/meshlink/platform/android/Ed25519FallbackTest.kt`

Test goals:

- verify that `supportsMeshLinkRuntime` requires both X25519/XDH and
  ChaCha20-Poly1305
- verify that missing ChaCha20-Poly1305 fails explicitly
- keep the Ed25519 fallback behavior unchanged and documented

Verification:

- `./gradlew :meshlink:testAndroidHostTest --tests 'ch.trancee.meshlink.platform.android.CryptoRuntimeCapabilityTest'`
- `./gradlew :meshlink:check`

### Task 2 — Add the actual fallback or adapter path

Implement the in-repo fallback or adapter path for the missing Android crypto
primitives.

Files to touch:

- `meshlink/src/androidMain/kotlin/ch/trancee/meshlink/platform/android/JcaCryptoProviderFactory.kt`
- `meshlink/src/androidMain/kotlin/ch/trancee/meshlink/platform/android/JcaCapabilityProbe.kt`
- any new Android crypto adapter or fallback implementation files needed to keep
  the contract explicit

Implementation goals:

- provide a deterministic path for X25519/XDH when the platform provider is
  missing it
- provide a deterministic path for ChaCha20-Poly1305 when the platform provider
  is missing it
- keep the failure mode explicit if either primitive still cannot be satisfied
- do not silently substitute a different primitive or weaken the handshake

Verification:

- `./gradlew :meshlink:check :meshlink-reference:check`
- focused Android host tests covering the fallback path

### Task 3 — Validate on real hardware and emulator targets

Use the device matrix to prove the runtime path across the supported and target
Android tiers.

Files to touch:

- `docs/reference/device-test-matrix.md`

Validation targets:

- API 26 emulator target: lowest supported transport floor and fallback path
- API 28 emulator target: first official `ChaCha20-Poly1305` floor
- API 30 attached device: runtime-capability path coverage
- API 33+ attached devices: official `XDH` + `ChaCha20-Poly1305` support floor

Verification:

- `adb devices -l`
- `adb shell getprop ro.build.version.sdk`
- `adb shell getprop ro.build.version.release`
- `./gradlew verifyDocs`

### Task 4 — Keep the docs aligned with the proven state

Update the release-status and landing docs only after the runtime story is
proven, so the docs stay faithful to the code.

Files to touch:

- `docs/reference/release-status.md`
- `docs/how-to/add-meshlink-to-your-app.md`
- `docs/how-to/evaluate-meshlink-with-the-reference-app.md`
- `README.md` if the root landing page should summarize the same proven floor

## Acceptance criteria

This RFC is satisfied when:

- the code has explicit tests for the missing-primitive failure path
- the code either implements a fallback or documents that the lower floor still
  depends on runtime capability on API 26-32
- the device matrix records at least one device or emulator target for each
  relevant crypto tier
- the release-status docs can state the Android crypto story without ambiguity

## Non-goals

- lowering the Android app floor below API 26
- changing the iOS floor
- changing the mesh wire format or transport semantics just to work around the
  crypto boundary
- claiming hardware-backed crypto support where the platform only offers a
  runtime-capability path

## Open questions

- Should X25519/XDH and ChaCha20-Poly1305 be implemented via pure Kotlin, a
  dedicated provider abstraction, or a platform bridge with a software fallback?
- Should the fallback work be split into two RFCs, one per primitive, if the
  implementation paths diverge?
- Do we want to keep API 26-32 as a supported transport floor even if the full
  crypto fallback is not yet proven?
