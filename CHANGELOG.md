# Changelog

All notable changes to MeshLink are recorded here, in the [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) style. Versions follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html) as [ADR 0019](docs/adr/0019-release-versioning-and-api-compatibility.md) defines them. Every PR adds its entry under "Unreleased" and states its SemVer impact; `./gradlew check` fails when an ABI dump changes without a change here.

## [Unreleased]

### Added

- Committed ABI dumps for `meshlink` and `meshlink-testing`. Both modules are still empty, so the dumps are empty. SemVer impact: none.
