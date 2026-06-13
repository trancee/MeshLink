# MeshLink release status reference

This page states the current MeshLink distribution shape and the intended shape
for the first public release.

Use it when you need the repository's release posture without reading the full
specification history.

## Current state

MeshLink has not cut a public stable release yet.

The repository currently ships as a source checkout with:

- the shared `:meshlink` Kotlin Multiplatform SDK
- the `meshlink-reference` evaluation app
- Android and iOS proof apps
- retained benchmark and release-history evidence

The repository version currently comes from `VERSION_NAME` in
`gradle.properties` and remains on a snapshot value during active development.

## Current mobile SDK floor

| Surface | Android | iOS | Notes |
|---|---|---|---|
| `:meshlink` SDK | API 26+ | 14.0+ | shared runtime floor |
| `meshlink-reference` app | API 26+ | 14.0+ | evaluation surface stays in lockstep |
| `meshlink-proof` app | API 26+ | 14.0+ | proof and fixture surface stays in lockstep |

Why this floor:

- Android BLE/L2CAP transport is supported on API 26+.
- iOS app surfaces are shipped as SwiftUI app targets with a 14.0 deployment target.
- The algorithm contract does not change on supported versions: SHA-256, HMAC-SHA256, X25519, Ed25519, and ChaCha20-Poly1305 remain available through the platform bridge or fallback implementation.

Android crypto nuance:

- Android's official `KeyAgreement` support for `XDH` is API 33+, and official `Cipher` support for `ChaCha20-Poly1305` is API 28+.
- MeshLink therefore treats X25519/XDH and ChaCha20-Poly1305 on API 26-32 as runtime-capability features, not guaranteed platform APIs.
- Ed25519 already has an in-repo fallback on Android.

Fallback proof plan:

- add a host-test or probe harness that injects missing X25519/XDH and ChaCha20-Poly1305 capability reports and asserts the failure path is explicit;
- add a fallback implementation or adapter path for those primitives;
- run the same probes on API 26-32 and record which path is used before claiming a lower Android crypto floor.

## Current supported distribution paths

Today, supported integration paths are:

- direct Gradle source integration for Android or shared Kotlin code
- composite-build substitution for a separate Gradle host app
- a Gradle-built Apple framework linked by Xcode for iOS

There is not yet a published Maven Central artifact, Swift Package Manager
package, or CocoaPods pod.

## Intended first public release shape

The planned first public release shape is:

- publish `:meshlink` for Gradle consumers
- keep the generated Apple framework path for iOS Xcode hosts
- continue without Swift Package Manager for the first release
- continue without CocoaPods for the first release

## Release-readiness work already in place

The repository now has:

- a maintained CI workflow for the core MeshLink SDK verification bundle
- a `release-please` workflow and manifest configuration for cumulative release PRs, tags, and next-snapshot bumps
- a changelog for release-facing notes
- a security policy for private vulnerability reporting
- a maintainer release runbook

## Remaining blockers for a full public release

The first public release still needs:

- artifact publishing and signing configuration for `:meshlink`
- consumer smoke validation against the released distribution path
- a maintainer-reviewed first release PR before the initial `release-please` release is merged

## Related docs

- [How to add MeshLink to your app](../how-to/add-meshlink-to-your-app.md)
- [Changelog](../../CHANGELOG.md)
- [Security policy](../../SECURITY.md)
- [Release runbook](../../RELEASING.md)
- [Release decision](../../specs/ble-mesh-sdk/release-decision.md)
