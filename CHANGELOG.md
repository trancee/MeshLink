# Changelog

All notable changes to MeshLink are recorded here, in the [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) style. Versions follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html) as [ADR 0019](docs/adr/0019-release-versioning-and-api-compatibility.md) defines them. Every PR adds its entry under "Unreleased" and states its SemVer impact; `./gradlew check` fails when an ABI dump changes without a change here.

## [Unreleased]

### Added

- Committed ABI dumps for `meshlink` and `meshlink-testing`. Both modules are still empty, so the dumps are empty. SemVer impact: none.
- Maven publishing of `meshlink` with a complete POM under The Unlicense, and the `MeshLinkKit` XCFramework for iOS. The `meshlink` klib dump gains its header and still declares nothing public. SemVer impact: none.
- `meshlink-testing` now depends on `meshlink` and `kotlinx-coroutines-test` and holds the internal `VirtualAir` test radio. Its klib dump gains its header and still declares nothing public. SemVer impact: none.
- The node lifecycle API in `ch.trancee.meshlink`: `meshLinkConfig(preset, securityProfile) { meshId = … }.build()` with mesh ID validation and `describe()`, `MeshLink.create(config, platform)`, suspending idempotent `start()` and `stop()`, final `close()`, and `state` (`Stopped`, `Starting`, `Running`, `Blocked(requirements)`, `Stopping`). `SecurityProfile` holds only `Unsecured`; `Preset` names the five built-in scenarios. One node runs per platform radio; another reports `Blocked(RadioInUse)`. On first start a node creates its Ed25519 installation identity, kept in memory for now. `MeshPlatform.ios()` reports `Blocked(Unsupported)` until the Core Bluetooth radio lands. SemVer impact: minor (new API, pre-1.0).

### Fixed

- `MeshLinkKit` exported none of SKIE's Swift extensions on its Objective-C classes, because their mangled names compress the module name, so a Swift app calling `start()` failed to link. SemVer impact: none.
