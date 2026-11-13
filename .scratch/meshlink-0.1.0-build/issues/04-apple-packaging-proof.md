# Publish to Maven local and consume the XCFramework from SwiftPM

Status: ready-for-agent
Type: AFK
Blocked by: 01

## Behavior

`./gradlew publishToMavenLocal` publishes every Maven artifact with a complete POM under The Unlicense, and the `MeshLinkKit` XCFramework is consumed by a SwiftPM package through a `binaryTarget` whose test passes on the iOS simulator through `tools/swiftpm-consumer/test.sh`. The repository gains its `LICENSE` file. There is no product behavior yet.

## Spec and ADR references

- [ADR 0011](../../../docs/adr/0011-modules-toggles-and-presets.md) decision 1 (published artifacts; this ticket amends it), decision 2 (`:meshlink-core` and `:meshlink-crypto-sodium` "are not published on their own") and the 2026-11-13 amendment "coordinates and names" (groupId `ch.trancee.meshlink`, artifact IDs `meshlink` and `meshlink-testing`, XCFramework `baseName` `MeshLinkKit`, bundle ID `ch.trancee.meshlink.kit`).
- [ADR 0005](../../../docs/adr/0005-toolchain-and-dependency-baseline.md) "Version tuple" (SKIE 0.10.16, vanniktech maven-publish 0.37.0, iOS deployment target 15.0), "Tools" (SKIE analytics upload disabled; bundle ID set through `binaryOption("bundleId", …)`) and "Publishing" (`publishToMavenLocal` is the local proof).
- [ADR 0019](../../../docs/adr/0019-release-versioning-and-api-compatibility.md) decision 1 (one version for every artifact).
- [ADR 0006](../../../docs/adr/0006-documentation-architecture.md) decision 5 (Swift examples later live as XCTest files in the SwiftPM consumer test package).
- Owner decisions (2026-11-13): MeshLink is licensed under The Unlicense; the internal modules publish as dependency-only artifacts; the SwiftPM consumer runs through `xcodebuild test` on an iOS Simulator because `swift test` on macOS cannot load an iOS-only XCFramework.

## Acceptance criteria

- [ ] vanniktech maven-publish 0.37.0 is configured in a `build-logic/` convention plugin. Signing and Central upload stay off (they belong to tickets 58 and 60).
- [ ] Consumer resolution: a Gradle consumer of `ch.trancee.meshlink:meshlink` from Maven local alone resolves every transitive project module. KGP cannot merge klibs of several modules into one artifact, so `:meshlink-core` and `:meshlink-crypto-sodium` publish as dependency-only Maven artifacts. Their POM descriptions and the docs state they are internal: no API guarantee, outside the ABI and SemVer promise of ADR 0019.
- [ ] A dated amendment to ADR 0011 decision 1 (version bump) records the dependency-only internal artifacts and their exclusion from the ABI and SemVer promise; `python3 tools/check_adrs.py` passes.
- [ ] The root `LICENSE` file holds the text of The Unlicense, copied unchanged from <https://unlicense.org>.
- [ ] Every published POM has name, description, URL, developer, SCM and license entries (license name `The Unlicense`, URL `https://unlicense.org`); all artifacts share one version.
- [ ] SKIE 0.10.16 is applied to `:meshlink` with analytics upload disabled.
- [ ] `:meshlink` builds a release XCFramework named `MeshLinkKit` with `iosArm64` and `iosSimulatorArm64` slices, bundle ID `ch.trancee.meshlink.kit` and `minos 15.0` (checked with `vtool` or `otool` in the PR).
- [ ] `tools/swiftpm-consumer/` is a SwiftPM package with a local-path `binaryTarget` for the built XCFramework and an XCTest target that imports `MeshLinkKit` and asserts that the framework bundle with ID `ch.trancee.meshlink.kit` is loaded. Tickets 09 and later extend it to call the public API.
- [ ] `tools/swiftpm-consumer/test.sh` runs `xcodebuild test` for the consumer package on an available iOS Simulator; this ticket verifies the exact `-destination` value and documents it in the script. The script builds the XCFramework first or fails with a message naming the Gradle task to run; it never tests a stale framework silently. It exits non-zero on any test failure.
- [ ] `./gradlew check` stays green, including the ABI dumps of every published module.

## Proof commands

```sh
./gradlew publishToMavenLocal
tools/swiftpm-consumer/test.sh
./gradlew check
python3 tools/check_adrs.py
npx --yes markdownlint-cli2@0.24.1
typos
lychee --offline .
```

## Docs to update

- `docs/adr/0011-modules-toggles-and-presets.md`: dated amendment to decision 1 on the dependency-only internal artifacts (version bump).
- `LICENSE`: created (The Unlicense).
- `PROJECT.md`: the project license (The Unlicense; `docs/reference/dependencies.md` records only dependency licenses), commands (`tools/swiftpm-consumer/test.sh`), layout (`tools/swiftpm-consumer/`), proof-matrix rows for `publishToMavenLocal` (generated-consumer proof) and the SwiftPM consumer (macOS, Xcode 27.2 locally; simulator runtime proof, with the verified destination).
- `docs/reference/dependencies.md`: confirm the SKIE and vanniktech rows.

## Devices needed

None.
