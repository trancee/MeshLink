# Changelog

All notable changes to MeshLink are recorded here, in the [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) style. Versions follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html) as [ADR 0019](docs/adr/0019-release-versioning-and-api-compatibility.md) defines them. Every PR adds its entry under "Unreleased" and states its SemVer impact; `./gradlew check` fails when an ABI dump changes without a change here.

## [Unreleased]

### Added

- Committed ABI dumps for `meshlink` and `meshlink-testing`. Both modules are still empty, so the dumps are empty. SemVer impact: none.
- Maven publishing of `meshlink` with a complete POM under The Unlicense, and the `MeshLinkKit` XCFramework for iOS. The `meshlink` klib dump gains its header and still declares nothing public. SemVer impact: none.
- `meshlink-testing` now depends on `meshlink` and `kotlinx-coroutines-test` and holds the internal `VirtualAir` test radio. Its klib dump gains its header and still declares nothing public. SemVer impact: none.
- The node lifecycle API in `ch.trancee.meshlink`: `meshLinkConfig(preset, securityProfile) { meshId = … }.build()` with mesh ID validation and `describe()`, `MeshLink.create(config, platform)`, suspending idempotent `start()` and `stop()`, final `close()`, and `state` (`Stopped`, `Starting`, `Running`, `Blocked(requirements)`, `Stopping`). `SecurityProfile` holds only `Unsecured`; `Preset` names the five built-in scenarios. One node runs per platform radio; another reports `Blocked(RadioInUse)`. On first start a node creates its Ed25519 installation identity, kept in memory for now. `MeshPlatform.ios()` reports `Blocked(Unsupported)` until the Core Bluetooth radio lands. SemVer impact: minor (new API, pre-1.0).
- Discovery: a running node advertises the 16-byte layout `001` payload with its mesh tag and scans for other members of its mesh, keeping those whose mesh tag matches epoch e − 1, e or e + 1 as link candidates. `peers` stays empty until links land. For `meshlink-testing` only, `MeshLink.linkCandidates()` and `radioFailure()` are `@InternalMeshLinkApi` and hidden from Objective-C, and the internal `PlatformPort` gains `radio` and `clock`. SemVer impact: none (no new public behavior; the additions require the internal opt-in).
- Unsecured links: a running node opens a GATT link to each link candidate, selects the highest common protocol version from the peer's capability record and exchanges `PLAIN_HELLO` in clear records. A peer of another mesh gets `CLOSE` `WRONG_MESH`; two links to one neighbor collapse to the one whose central has the lower NodeId; `stop()` sends `CLOSE` `SHUTTING_DOWN` on every link. New public API: `MeshLink.peers: StateFlow<List<Peer>>`, ordered by NodeId; `Peer` with `nodeId`, `reachability`, `secured` and `lastSeen` (`kotlin.time.Instant`); `Reachability.Direct`; and `NodeId`, which prints as lowercase hex and returns its 32 bytes with `toByteArray()`. A peer leaves `peers` when its last link closes. SemVer impact: minor (new API, pre-1.0).

### Fixed

- `MeshLinkKit` exported none of SKIE's Swift extensions on its Objective-C classes, because their mangled names compress the module name, so a Swift app calling `start()` failed to link. SemVer impact: none.
