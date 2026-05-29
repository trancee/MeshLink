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

- the final public-distribution license beyond the conservative pre-release rights notice in `LICENSE`
- artifact publishing and signing configuration for `:meshlink`
- consumer smoke validation against the released distribution path

## Related docs

- [How to add MeshLink to your app](../how-to/add-meshlink-to-your-app.md)
- [Changelog](../../CHANGELOG.md)
- [Security policy](../../SECURITY.md)
- [Release runbook](../../RELEASING.md)
- [Release decision](../../specs/001-ble-mesh-sdk/release-decision.md)
