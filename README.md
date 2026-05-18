# MeshLink

MeshLink is an offline-first BLE mesh SDK for Android and iOS.

This repository contains:

- the shared `meshlink` Kotlin Multiplatform SDK
- runnable Android and iOS proof apps for physical validation
- retained benchmark and release-decision evidence
- explanation docs for the security, routing, transport, and power model

## Developer documentation

**Audience:** engineers integrating MeshLink into a host application.

**After reading these docs, you should be able to:** bootstrap MeshLink, manage its lifecycle, react to peers and messages, send payloads correctly, and choose the right supporting docs when you need more detail.

The documentation follows the Diátaxis model.

### Tutorials

- [Your first MeshLink exchange](docs/tutorials/your-first-meshlink-exchange.md) — a hands-on lesson that gets an Android host app talking to a proof peer.

### How-to guides

- [How to integrate MeshLink into a host app](docs/how-to/integrate-meshlink-into-a-host-app.md) — bootstrap the SDK on Android and iOS, collect events, send messages, and handle trust resets.
- [How to use MeshLink from Swift](docs/how-to/use-meshlink-from-swift.md) — the Swift-facing names, calling conventions, and collection patterns exposed by the generated Apple framework.

### Reference

- [MeshLink SDK API reference](docs/reference/meshlink-sdk-api.md) — the public API, result types, diagnostics, limits, iOS bridge entry points, and Swift naming notes.
- [Generated public API symbol tables](docs/reference/generated-public-api.md) — the checked-in public API surface rendered directly from the compatibility-tracked API dump.

### Explanation

- [About integrating MeshLink well](docs/explanation/about-integrating-meshlink.md) — integration best practices and the reasoning behind them.
- [The trust model](docs/explanation/trust-model.md)
- [The peer lifecycle model](docs/explanation/peer-lifecycle.md)
- [Power management](docs/explanation/power-management.md)
- [Why L2CAP-first](docs/explanation/why-l2cap-first.md)

## Samples and validation

- [Android proof app guide](meshlink-sample/android/README.md)
- [iOS proof app guide](meshlink-sample/ios/README.md)
- [Benchmarks and retained evidence](benchmarks/README.md)
- [Feature specification and release decision](specs/001-ble-mesh-sdk/spec.md)

## Source of truth

For behavior and compatibility, prefer these documents in order:

1. the public API and integration docs above for developer usage
2. the specification and release-decision docs for product requirements
3. the benchmark evidence for retained physical validation
