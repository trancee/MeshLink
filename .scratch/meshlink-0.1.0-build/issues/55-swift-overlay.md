# Use MeshLink naturally from Swift

Status: ready-for-agent
Type: AFK
Blocked by: 54

## Behavior

A Swift app imports the `MeshLink` package and calls MeshLink in idiomatic Swift. It gets `Data` instead of `KotlinByteArray`, Swift default arguments on the options types, a static `MeshLink.create`, and typed errors through `error.meshLink`. `MeshLink` conforms to a Swift protocol, so an app's tests can swap in `MeshLinkTesting`, a pure Swift fake that runs the same reference scenarios as the Kotlin `meshlink-testing`. The overlay holds idioms only, no logic.

## Spec and ADR references

- [ADR 0007](../../../docs/adr/0007-public-api-shape.md#decision) decisions 11 (no `kotlin.Result`, value classes or nullable primitives), 13 (threading; collect on `@MainActor` under Swift 6) and 14 (the overlay of about 60 lines), plus the [amendments](../../../docs/adr/0007-public-api-shape.md#amendments): the Swift protocol, `MeshLinkTesting`, the one-copy `Data` conversion with its guarding benchmark, and `awaitSendCapacity()`.
- [ADR 0011](../../../docs/adr/0011-modules-toggles-and-presets.md#decision) decisions 4 and 5: `MeshLinkTesting` is pure Swift and never linked into an app target; development uses a path-based `Package.swift` under `swift/`. Its 2026-11-13 [amendment](../../../docs/adr/0011-modules-toggles-and-presets.md#amendments) names the package `MeshLink`, the products `MeshLink` (re-exports `MeshLinkKit`) and `MeshLinkTesting`.
- [ADR 0017](../../../docs/adr/0017-testing-architecture.md#decision) decision 6 and its "Swift fake drift" risk: both fakes run the same reference scenarios.
- [ADR 0019](../../../docs/adr/0019-release-versioning-and-api-compatibility.md#decision) decisions 4 and 5: the overlay's `swift-api-digester` dump and Swift deprecation. The dump job lives in `meshlink-swift` CI and is written by ticket 60.
- [`KOTLIN.md`](../../../KOTLIN.md), SKIE section: compile a real Swift consumer and test sealed switches, suspend and Flow cancellation.

## Acceptance criteria

- [ ] `swift/Package.swift` (path-based, development only) defines package `MeshLink` with products `MeshLink` and `MeshLinkTesting`. Its binary target is the local `MeshLinkKit` XCFramework that ticket 04's build produces.
- [ ] The `MeshLink` target re-exports `MeshLinkKit` and adds only ADR 0007 decision 14's items plus the Swift protocol: `Data` ↔ `KotlinByteArray` with one bulk copy, Swift default arguments on the options types, a static `MeshLink.create`, and `error.meshLink`. It has no protocol or mesh logic (T3) and stays near 60 lines. A larger overlay needs a reason in the PR.
- [ ] `MeshLinkTesting` is a pure Swift target with a protocol-based fake. It contains no Kotlin of its own, conforms to the overlay's protocol, and is never linked into an app target.
- [ ] XCTest suites in `swift/Tests/` run the reference scenarios that `meshlink-testing` runs, once against `MeshLinkTesting` and once against the real framework where the simulator allows it (MeshLink reports `Blocked(Unsupported)` there).
- [ ] Swift tests cover exhaustive `onEnum(of:)` switches over `MeshState`, `SendResult` and `DeliveryStatus`, `try await` with `Task` cancellation reaching Kotlin, a full queue answered with `awaitSendCapacity()`, `error.meshLink` on a declared exception, and collection from `@MainActor`.
- [ ] A conversion test round-trips empty, 1 B and 128 KiB payloads unchanged. A benchmark test converts 16 KB and fails above a budget written in the test as P1 `{workload, environment, method, threshold}`, derived from the [SE 2016 evidence](../../../docs/evidence/2026-11-se2016-bench/README.md).
- [ ] `tools/swiftpm-consumer` depends on `swift/` by path and builds against the `MeshLink` product, not `MeshLinkKit` directly.
- [ ] `swift/test.sh` runs `xcodebuild test` for the `swift/` package on an iOS Simulator, builds the XCFramework first or fails naming the Gradle task, and exits non-zero on any failure.
- [ ] Every overlay declaration has a Swift doc comment. `./gradlew check` and `./gradlew iosSimulatorArm64Test` still pass.

## Proof commands

```sh
./gradlew iosSimulatorArm64Test
./gradlew check
tools/swiftpm-consumer/test.sh
swift/test.sh
```

`swift/test.sh` is new: this ticket creates it and adds it to the spine's proof-command table. Like `tools/swiftpm-consumer/test.sh` from ticket 04, it runs `xcodebuild test` for the `swift/` package on an available iOS Simulator with the destination ticket 04 verified, because `swift test` on macOS cannot load the iOS-only XCFramework.

## Docs to update

- [`docs/README.md`](../../../docs/README.md): add a "Swift overlay" row to "Look up a contract", pointing to `swift/`.
- [`PROJECT.md`](../../../PROJECT.md): the `swift/` package in the layout table, `swift/test.sh` in the commands, and the proof matrix.
- [Spine](../spec.md#proof-commands): `swift/test.sh` row, created by 55.
- The how-tos and the tutorial use the overlay from ticket 57 on.

## Devices needed

None. Swift tests run on the iOS simulator; radio behavior is not claimed here.
