# M011 Android crypto fallback proof plan

Status: implementation shipped; retained validation follow-up still open

This note started as the Android crypto fallback plan and now records the
shipped fallback plus the remaining validation posture. The transport/runtime
floor is already API 26+ for the app shells, but Android still does not
officially guarantee the full MeshLink crypto set at that floor. The in-repo
fallback now covers X25519/XDH and ChaCha20-Poly1305, so the remaining work is
retained runtime proof on the lowest Android tiers without overclaiming the
platform contract.

## Validation update (2026-06-13)

- Attached Android 9 / SDK 28 hardware (`SM-G390F`) passed
  `connectedAndroidDeviceTest` against `CryptoRuntimeValidationDeviceTest`.
- That runtime probe reported `x25519=false`, `ed25519=false`,
  `chacha=false`, `meshRuntime=false`, and selected
  `AndroidFallbackCryptoProvider`, which confirms the fallback path on a real
  API 28-class device.
- Provisioned `meshlink-api26` and `meshlink-api28` AVDs both crashed before
  `sys.boot_completed` in the current headless host environment, so API 26
  emulator proof remains blocked by local emulator stability rather than by the
  MeshLink runtime or test build.

## Performance optimization update (2026-07-07)

The device matrix (`docs/reference/device-test-matrix.md`) showed the fallback
paths were the slowest entries in the fleet, most visibly on
`AndroidFallbackCryptoProvider` devices such as the Samsung Galaxy XCover 4.
The following optimizations were made to the pure-Kotlin fallback
implementations without changing their external behavior or algorithm
contract; each round was validated against `Ed25519FallbackTest`,
`WycheproofRegressionTest` (which exercises `AndroidFallbackCryptoProvider`
directly on `androidHostTest`), the full `:meshlink:build
checkAgp9Invariants` gate, and a real-device fleet re-benchmark before/after.

Findings and techniques, in `meshlink/src/androidMain/kotlin/ch/trancee/meshlink/platform/android/Ed25519Fallback.kt`:

- Dedicated fast `square()` and `double()` field-arithmetic routines replace
  generic multiply-based squaring/doubling, avoiding redundant partial-product
  computation.
- A precomputed radix-16 fixed-base comb table (`baseCombTable`) speeds up
  `scalarBase()` (used once per Ed25519 key generation and once per sign),
  trading static table size for far fewer point additions per scalar
  multiplication.
- `windowedScalarMultiplyPublic()` adds windowed scalar multiplication for
  signature verification's public-point multiply.
- `invert()`/`power2523()` (field inversion and the sqrt exponent used when
  decoding points) previously used TweetNaCl's naive one-bit-at-a-time Fermat
  exponentiation — 254 squarings + 251 multiplications for inversion
  (`p-2`), 250 squarings + 249 multiplications for the sqrt exponent
  (`(p-5)/8`). Both were replaced with the standard ref10/curve25519-donna
  addition-chain algorithm, which builds up `2^k-1` runs (`z2`, `z9`, `z11`,
  `z2_5_0`, `z2_10_0`, `z2_20_0`, `z2_50_0`, `z2_100_0`) to reach the same
  exponent with only 11 and 9 multiplications respectively — the squaring
  count is unchanged, but the multiplication count (equally expensive per
  limb-pair) drops by more than 20x for these hot paths, which run on every
  Ed25519 keygen, sign, and verify.

In `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/crypto/PureX25519.kt`:

- Added a fast `squareInto()` alongside the existing generic multiply, and
  applied the same addition-chain technique to `invert()`, used once per
  X25519 keygen and once per agreement finalize.

In `meshlink/src/androidMain/kotlin/ch/trancee/meshlink/platform/android/AndroidFallbackCryptoProvider.kt`:

- The Poly1305 MAC previously used `java.math.BigInteger` arithmetic, which is
  both slow (arbitrary-precision allocation per block) and not constant-time.
  It was replaced with a constant-time 5×26-bit-limb implementation
  (poly1305-donna style, per RFC 8439 §2.5), removing the BigInteger-based
  helpers (`poly1305Mac`, `clampPoly1305R`, `littleEndianToBigInteger`,
  `bigIntegerToLittleEndian`, the modulus constants, and the `BigInteger`
  import) entirely.

Real-device fleet re-benchmarks after these changes confirmed measurable
speedups on fallback-crypto devices with no change on native `JcaCryptoProvider`
devices (control), e.g. Samsung Galaxy XCover 4 `ed25519KeyGen` improved from
15.4ms to 11.9ms. See `docs/reference/device-test-matrix.md` for the full
per-provider benchmark history, including trend markers for each run.

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

> Note: these tests live under `androidHostTest`, which is the host-side Android KMP test source set; `androidTest` remains reserved for instrumented app/device tests.

### Task 1 — Make the runtime boundary explicit in tests

Strengthen the host-side Android tests so the crypto boundary is clear and repeatable.

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
