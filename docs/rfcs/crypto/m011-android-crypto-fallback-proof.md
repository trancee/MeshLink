# M011 Android crypto fallback proof plan

Status: draft design artifact

This note captures the remaining work needed to prove or add in-repo fallback
support for Android X25519/XDH and ChaCha20-Poly1305 so MeshLink can justify a
lower Android crypto floor than the platform guarantees today.

## Context

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

The current device matrix also shows a coverage gap for API 26 and API 28
hardware, so the remaining proof work needs both implementation and validation
coverage.

## Problem

We should not claim that older Android devices fully support the MeshLink crypto
contract until one of the following is true:

1. MeshLink has a deterministic in-repo fallback or adapter path for both
   X25519/XDH and ChaCha20-Poly1305, or
2. the supported device/provider baseline is proven to expose those primitives
   reliably on the Android versions we want to support.

Right now, only Ed25519 satisfies option 1.

## Proposed work

### 1. Clarify the runtime contract in code and tests

- keep the existing capability probe for X25519/XDH and ChaCha20-Poly1305
- add tests that explicitly cover the failure path when each primitive is absent
- add tests that document the capability boundary rather than implying a hidden
  fallback exists

### 2. Prove or implement fallback support

- add an in-repo implementation or adapter path for X25519/XDH
- add an in-repo implementation or adapter path for ChaCha20-Poly1305
- keep Ed25519 fallback behavior unchanged
- do not weaken the API contract by silently substituting a different primitive

### 3. Validate against real devices

- use the device test matrix to record which attached Android devices exercise
  the runtime-capability path and which ones exercise official platform support
- add API 26 and API 28 coverage when suitable hardware or emulator images are
  available
- record which path was used for each validation run

## Acceptance criteria

This RFC is satisfied when:

- the code has explicit tests for the missing-primitive failure path
- the code either implements a fallback or documents that the lower floor still
  depends on runtime capability on API 26-32
- the device matrix records at least one device for each relevant crypto tier
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
