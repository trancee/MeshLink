# MeshLink

MeshLink is an offline-first BLE mesh SDK for Android and iOS.

This repository contains:

- the shared `meshlink` Kotlin Multiplatform SDK
- the shared `meshlink-reference` app for guided evaluation, diagnostics, retained session history, and exports
- runnable Android and iOS proof apps for physical validation
- retained benchmark and release-decision evidence
- tutorials, how-to guides, reference docs, and explanation docs

## Developer documentation

**Audience:** engineers integrating MeshLink into a host application.

**After reading these docs, you should be able to:** add MeshLink to an app,
bootstrap the runtime, react to peers and messages, send payloads correctly,
and find the right supporting document when you need more detail.

The documentation follows the Diataxis model. Start with the full
[documentation map](docs/README.md), which also includes recommended reading
orders for new integrators, production integrations, debugging, and technical
evaluation.

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
- [About how MeshLink works](docs/explanation/about-how-meshlink-works.md)
- [About integrating MeshLink well](docs/explanation/about-integrating-meshlink.md)

Maintainers can run `./gradlew verifyDocs` to check markdown links and the
rendered API appendix, and `./gradlew checkAgp9Invariants` to confirm the
post-migration AGP 9 module shape stays intact.

## Contributing

- [How to contribute to MeshLink](CONTRIBUTING.md)
- [Contributor build, test, and verification reference](docs/reference/contributor-reference.md)

## Reference app, proof apps, and validation

- [How to evaluate MeshLink with the reference app](docs/how-to/evaluate-meshlink-with-the-reference-app.md)
- [How to run the Android proof app](meshlink-proof/android/README.md)
- [How to build and run the iOS proof app](meshlink-proof/ios/README.md)
- [Benchmarks and retained evidence](benchmarks/README.md)
- [Feature specification and release decision](specs/001-ble-mesh-sdk/spec.md)
- [Spec alignment audit](specs/001-ble-mesh-sdk/alignment-audit.md)

## Source of truth

For day-to-day use, prefer these documents in order:

1. the public API and integration docs for developer-facing behavior
2. the specification and release-decision docs for product requirements
3. the benchmark evidence for retained performance and physical validation
