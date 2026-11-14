# Publish `meshlink` to Maven local and consume the XCFramework from SwiftPM

Status: done
Type: AFK
Blocked by: 01

## Behavior

`./gradlew :meshlink:publishToMavenLocal` publishes `meshlink` with a complete POM under The Unlicense, and the `MeshLinkKit` XCFramework is consumed by a SwiftPM package through a `binaryTarget` whose test passes on the iOS simulator through `tools/swiftpm-consumer/test.sh`. The repository gains its `LICENSE` file. There is no product behavior yet.

Scope cut (owner decisions, 2026-11-14):

- Kotlin/Native links no framework for a module without source, and a placeholder file would break Constitution Q8. `:meshlink` therefore gains a generated internal `BuildInfo` object that holds the library version.
- KGP writes no iOS klib for a module without source, so `:meshlink-core`, `:meshlink-crypto-sodium` and `:meshlink-testing` can't publish yet. The full `publishToMavenLocal`, the dependency-only internal artifacts, the generated Maven consumer and the ADR 0011 amendment move to [ticket 65](65-maven-consumer-proof.md), blocked by 07 and 08.

## Spec and ADR references

- [ADR 0011](../../../docs/adr/0011-modules-toggles-and-presets.md) decision 1 (published artifacts) and the 2026-11-13 amendment "coordinates and names" (groupId `ch.trancee.meshlink`, artifact IDs `meshlink` and `meshlink-testing`, XCFramework `baseName` `MeshLinkKit`, bundle ID `ch.trancee.meshlink.kit`).
- [ADR 0005](../../../docs/adr/0005-toolchain-and-dependency-baseline.md) "Version tuple" (SKIE 0.10.16, vanniktech maven-publish 0.37.0, iOS deployment target 15.0), "Tools" (SKIE analytics upload disabled; bundle ID set through `binaryOption("bundleId", …)`) and "Publishing" (`publishToMavenLocal` is the local proof).
- [ADR 0019](../../../docs/adr/0019-release-versioning-and-api-compatibility.md) decision 1 (one version for every artifact).
- [ADR 0006](../../../docs/adr/0006-documentation-architecture.md) decision 5 (Swift examples later live as XCTest files in the SwiftPM consumer test package).
- Owner decisions (2026-11-13): MeshLink is licensed under The Unlicense; the SwiftPM consumer runs through `xcodebuild test` on an iOS Simulator because `swift test` on macOS cannot load an iOS-only XCFramework.

## Acceptance criteria

- [x] vanniktech maven-publish 0.37.0 is configured in a `build-logic/` convention plugin. Signing and Central upload stay off (they belong to tickets 58 and 60).
- [x] Every MeshLink module has groupId `ch.trancee.meshlink` and the one version of the root `meshlink.version` property, so the `meshlink` POM names its project dependencies with their real coordinates. Consumer resolution of those dependencies is ticket 65.
- [x] The root `LICENSE` file holds the text of The Unlicense, copied unchanged from <https://unlicense.org>.
- [x] The published `meshlink` POM has name, description, URL, developer, SCM and license entries (license name `The Unlicense`, URL `https://unlicense.org`).
- [x] `:meshlink` holds a generated internal `BuildInfo` object whose version is `meshlink.version`; a `commonTest` test checks that it is a SemVer version. It adds no public ABI.
- [x] SKIE 0.10.16 is applied to `:meshlink` with analytics upload disabled.
- [x] `:meshlink` builds a release XCFramework named `MeshLinkKit` with `iosArm64` and `iosSimulatorArm64` slices, bundle ID `ch.trancee.meshlink.kit` and `minos 15.0` (checked with `vtool` or `otool` in the PR).
- [x] `tools/swiftpm-consumer/` is a SwiftPM package with a local-path `binaryTarget` for the built XCFramework and an XCTest target that imports `MeshLinkKit` and asserts that the framework bundle with ID `ch.trancee.meshlink.kit` is loaded. Tickets 09 and later extend it to call the public API.
- [x] `tools/swiftpm-consumer/test.sh` runs `xcodebuild test` for the consumer package on an available iOS Simulator; this ticket verifies the exact `-destination` value and documents it in the script. The script builds the XCFramework first or fails with a message naming the Gradle task to run; it never tests a stale framework silently. It exits non-zero on any test failure.
- [x] `./gradlew check` stays green, including the ABI dumps of every published module.

## Proof commands

```sh
./gradlew :meshlink:publishToMavenLocal
tools/swiftpm-consumer/test.sh
./gradlew check
python3 tools/check_adrs.py
npx --yes markdownlint-cli2@0.24.1
typos
lychee --offline .
```

## Docs to update

- `LICENSE`: created (The Unlicense).
- `PROJECT.md`: the project license (The Unlicense; `docs/reference/dependencies.md` records only dependency licenses), commands (`tools/swiftpm-consumer/test.sh`), layout (`tools/swiftpm-consumer/`), proof-matrix rows for `:meshlink:publishToMavenLocal` and the SwiftPM consumer (macOS, Xcode 27.2 locally; simulator runtime proof, with the verified destination).
- `docs/reference/dependencies.md`: confirm the SKIE and vanniktech rows.

## Devices needed

None.
