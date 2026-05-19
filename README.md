# MeshLink

MeshLink is an offline-first BLE mesh SDK for Android and iOS.

This repository contains:

- the shared `meshlink` Kotlin Multiplatform SDK
- a shared Android/iOS `meshlink-reference` app for guided evaluation, logs, diagnostics, retained session history, and exports
- runnable Android and iOS proof apps for physical validation
- retained benchmark and release-decision evidence
- explanation docs for the security, routing, transport, and power model

## Developer documentation

**Audience:** engineers integrating MeshLink into a host application.

**After reading these docs, you should be able to:** bootstrap MeshLink, manage its lifecycle, react to peers and messages, send payloads correctly, and choose the right supporting docs when you need more detail.

The documentation follows the Diátaxis model. Start with the full
[documentation map](docs/README.md).

Quick entry points:

- [Your first MeshLink exchange](docs/tutorials/your-first-meshlink-exchange.md)
- [How to integrate MeshLink into a host app](docs/how-to/integrate-meshlink-into-a-host-app.md)
- [How to unblock MeshLink permissions on Android and iOS](docs/how-to/unblock-meshlink-permissions.md)
- [How to use MeshLink from Swift](docs/how-to/use-meshlink-from-swift.md)
- [MeshLink SDK API reference](docs/reference/meshlink-sdk-api.md)
- [About integrating MeshLink well](docs/explanation/about-integrating-meshlink.md)

## Reference app, proof apps, and validation

- [How to use the MeshLink reference app](meshlink-reference/README.md)
- [How to run the Android proof app](meshlink-proof/android/README.md)
- [How to build and run the iOS proof app](meshlink-proof/ios/README.md)
- [Benchmarks and retained evidence](benchmarks/README.md)
- [Feature specification and release decision](specs/001-ble-mesh-sdk/spec.md)
- [Spec alignment audit](specs/001-ble-mesh-sdk/alignment-audit.md)

## Source of truth

For behavior and compatibility, prefer these documents in order:

1. the public API and integration docs above for developer usage
2. the specification and release-decision docs for product requirements
3. the benchmark evidence for retained physical validation
