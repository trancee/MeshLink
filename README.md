# MeshLink

MeshLink is an offline-first BLE mesh SDK for Android and iOS.

This repository contains:

- the shared `meshlink` Kotlin Multiplatform SDK
- the shared `meshlink-reference` app for guided evaluation, diagnostics, retained session history, and exports
- runnable Android and iOS proof apps for physical validation
- retained benchmark and release-decision evidence
- tutorials, how-to guides, reference docs, and explanation docs

## Release status

MeshLink has not cut a public stable release yet.

Today, the repository is still source-distributed from a local checkout. The
intended first public release shape is a published `:meshlink` artifact for
Gradle consumers while iOS can use the generated Apple framework path or the
root-level SwiftPM package.

For the current distribution shape and remaining release blockers, use the
[release status reference](docs/reference/release-status.md).

Current mobile support floor: Android API 26+, iOS 14.0+. On Android, X25519/XDH
and ChaCha20-Poly1305 are runtime-capability features on API 26-32 rather than
blanket platform guarantees; the detailed crypto note and fallback-proof plan
live in the release status reference and in [M011 Android crypto fallback proof
plan](docs/rfcs/crypto/m011-android-crypto-fallback-proof.md).

Release-facing entry points:

- [Release status reference](docs/reference/release-status.md)
- [Changelog](CHANGELOG.md)
- [Security policy](SECURITY.md)
- [Release runbook](RELEASING.md)

## Developer documentation

**Audience:** engineers integrating MeshLink into a host application.

**After reading these docs, you should be able to:** add MeshLink to an app,
bootstrap the runtime, react to peers and messages, send payloads correctly,
and find the right supporting document when you need more detail.

The documentation follows the Diataxis model. Start with the full
[documentation map](docs/README.md), which also includes recommended reading
orders for new integrators, production integrations, debugging, technical
evaluation, and a small architecture index for diagram-first reading.

Quick entry points:

- [How to add MeshLink to your app](docs/how-to/add-meshlink-to-your-app.md)
- [Your first MeshLink exchange](docs/tutorials/your-first-meshlink-exchange.md)
- [How to integrate MeshLink into a host app](docs/how-to/integrate-meshlink-into-a-host-app.md)
- [How to structure a robust MeshLink integration](docs/how-to/structure-a-robust-meshlink-integration.md)
- [How to unblock MeshLink permissions on Android and iOS](docs/how-to/unblock-meshlink-permissions.md)
- [How to use MeshLink from Swift](docs/how-to/use-meshlink-from-swift.md)
- [MeshLink SDK API reference](docs/reference/meshlink-sdk-api.md)
- [MeshLink runtime behavior reference](docs/reference/meshlink-runtime-behavior.md)
- [Glossary and acronym reference](docs/reference/glossary.md)
- [Release status reference](docs/reference/release-status.md)
- [Repository layout reference](docs/reference/repository-layout.md)
- [About how MeshLink works](docs/explanation/about-how-meshlink-works.md)
- [About the repository architecture](docs/explanation/about-the-repository-architecture.md)
- [About integrating MeshLink well](docs/explanation/about-integrating-meshlink.md)

Maintainers can run `./gradlew verifyDocs` to check markdown links and the
rendered API appendix, and `./gradlew checkAgp9Invariants` to confirm the
post-migration AGP 9 module shape stays intact.

## Contributing

- [How to contribute to MeshLink](CONTRIBUTING.md)
- [Contributor build, test, and verification reference](docs/reference/contributor-reference.md)
- [Release runbook](RELEASING.md)
- [Repository layout reference](docs/reference/repository-layout.md)
- [About the repository architecture](docs/explanation/about-the-repository-architecture.md)

## Reference app, proof apps, and validation

- [How to evaluate MeshLink with the reference app](docs/how-to/evaluate-meshlink-with-the-reference-app.md)
- [How to run the Android proof app](meshlink-proof/android/README.md)
- [How to build and run the iOS proof app](meshlink-proof/ios/README.md)
- [Benchmarks and retained evidence](meshlink-benchmark/README.md)
- [Fleet test history report](meshlink-reference/fleet-test-history/index.html) — concise HTML history of repeated fleet tests with device, result, and comparison context.
- [Feature specification and release decision](specs/ble-mesh-sdk/spec.md)
- [Spec alignment audit](specs/ble-mesh-sdk/alignment-audit.md)

## Source of truth

For day-to-day use, prefer these documents in order:

1. the public API and integration docs for developer-facing behavior
2. the specification and release-decision docs for product requirements
3. the benchmark evidence for retained performance and physical validation
