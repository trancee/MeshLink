# Teach MeshLink with a tutorial and how-tos

Status: ready-for-agent
Type: AFK
Blocked by: 55, 56 (done); the testing how-to also by 80

## Behavior

A new Kotlin or Swift developer follows one tutorial from an empty app to two nodes exchanging a message, then finds a how-to for each common task. Every code block shows Kotlin and Swift side by side. Each block is copied from a marked region of a compiled and executed test, and `./gradlew check` fails when a block and its region differ. The background how-to says plainly that 0.1.0 does not verify background behavior. The repository's root `README.md` points readers to the tutorial, the how-tos and the API reference.

## Owner decisions (2026-11-28)

- Q1 A: `meshlink-testing` gets a public `VirtualMesh` facade in ticket 80; the testing how-to waits for it.
- Q2 A: three pull requests. (1) `checkDocRegions`, the tutorial, the root `README.md` and `docs/README.md`. (2) The how-tos except testing, and the `@sample` functions. (3) The testing how-to, after ticket 80.
- Q3 A: each step shows a Kotlin fenced block, then a Swift one. A line `<!-- region: <path>#<name> -->` directly before a fence ties it to the region between `// region <name>` and `// endregion` in a test source file. The check compares the region with its common indentation removed.

## Owner decisions for PR 2 (2026-11-28)

- Q1 A: the Android permission how-to asks with the Activity Result API in a Robolectric test (`RequestPermissionsAndroidTest`), adding the test-only dependency `androidx.activity:activity`.
- Q2 A: the Swift `blocked` and `export` regions run against the real framework on the simulator.
- Q3 A: the samples live in `meshlink-testing`'s `commonTest`, package `ch.trancee.meshlink.samples`; Dokka reads them through `samples.from`.
- Q4 A: per-preset background recommendations: `OfflineChat` and `FieldTeams` run in the background from a user action, `EmergencyBroadcast` for the emergency session, `ProximityDiscovery` and `LocalMultiplayer` foreground only.
- Export A: Swift 6 main-actor code can't await `diagnostics.export()`, so the overlay adds `@MainActor MeshLink.exportDiagnostics()` (ADR 0007 amendment of 2026-11-28).

## Spec and ADR references

- [ADR 0006](../../../docs/adr/0006-documentation-architecture.md#decision) decisions 1 (Diátaxis layout), 5 (Kotlin and Swift side by side; regions from compiled tests; no kotlinx-knit, DocC or SwiftPM `Snippets/`) and 6 (troubleshooting built on codes), and its "Risks": the region check needs its own tests.
- [ADR 0011](../../../docs/adr/0011-modules-toggles-and-presets.md#decision) decision 13: background modes belong to the host app, with per-preset recommendations in a how-to.
- [ADR 0010](../../../docs/adr/0010-discovery-and-advertising.md#amendments) and [ADR 0011](../../../docs/adr/0011-modules-toggles-and-presets.md#amendments) amendments (2026-11-13, deferred for 0.1.0): the Live Activity hook is deferred beyond 0.1.0, so no how-to documents it.
- [ADR 0007](../../../docs/adr/0007-public-api-shape.md#amendments), send-capacity amendment: every how-to that sends uses `awaitSendCapacity()` after `Rejected(QueueFull)` and never retries in a loop.
- [ADR 0015](../../../docs/adr/0015-message-size-and-compression.md#decision) decisions 1 and 4: the send how-to explains small and large messages and `TooLarge`, and warns that compressing secret data together with attacker-influenced data leaks it through ciphertext length. Ticket 50 put the same warning in the KDoc of `send` and `LimitsBuilder`.
- [ADR 0017](../../../docs/adr/0017-testing-architecture.md#amendments), 2026-11-13 amendment: 0.1.0 claims no background behavior, and the docs mark it unverified.
- Constitution O4, O5 and C8 in [`CONSTITUTION.md`](../../../CONSTITUTION.md).

## Acceptance criteria

- [x] `checkDocRegions` in `build-logic/` is wired into `check`. It fails when a fenced block tied to a region marker differs from that region in a Kotlin `commonTest` file or a Swift XCTest file. Its own tests prove a match passes and a changed line, a missing region and an orphan marker each fail.
- [x] `docs/tutorials/get-started.md` takes a reader from adding the dependency (Gradle and SwiftPM) through `meshLinkConfig`, `start()`, `peers`, `send`, `messages` and `close()`. Its Kotlin regions run in `:meshlink-testing`'s `commonTest` on `VirtualAir`. Its Swift regions run against `MeshLinkTesting` in the SwiftPM consumer tests.
- [ ] How-tos in `docs/how-to/`, each with Kotlin and Swift regions: choose and customize a preset; request permissions and leave `Blocked`; send with receipts and handle `QueueFull`; broadcast to the mesh; test an app with `meshlink-testing` and `MeshLinkTesting`; export diagnostics.
- [x] `docs/how-to/run-in-the-background.md` gives the per-preset recommendations of ADR 0011 decision 13. It states at the top that 0.1.0 has not verified background behavior on devices. No page documents a Live Activity setup.
- [x] `@sample` functions in `commonTest` cover `meshLinkConfig`, `send`, `broadcast` and `diagnostics.export()`, and Dokka renders them.
- [x] The troubleshooting pages from ticket 54 are linked from the tutorial's last step and from every how-to whose task can fail.
- [x] The troubleshooting pages (ticket 54e) link the export-diagnostics how-to from their "Collect the evidence" and "If the problem stays" sections.
- [x] A root `README.md` says what MeshLink is, its 0.1.0 status, the Maven and SwiftPM coordinates of ADR 0011's 2026-11-13 amendment, and links to `docs/README.md`, the tutorial and the API reference URL of ADR 0006.
- [x] `docs/README.md` is reorganized by reader task with "Learn", "Do a task", "Look up a contract" and "Understand the design". It no longer says that no library code exists.
- [ ] Kover stays at 100%. `./gradlew check`, `./gradlew iosSimulatorArm64Test`, `tools/swiftpm-consumer/test.sh` and every docs check pass.

PR 1 (branch `docs/tutorial-and-how-tos`) delivered the tutorial, the README files and `checkDocRegions`. PR 2 (branch `docs/how-tos`) delivers every how-to except testing, the background how-to, the `@sample`s and the troubleshooting links; the how-to item stays open for the testing how-to of PR 3. The Android-only region `platform-android` comes from a Robolectric test in `meshlink/src/androidHostTest`, and the Swift `create-ios` region runs against the real framework on the simulator (ADR 0006 amendment of 2026-11-28).

## Proof commands

```sh
./gradlew check
./gradlew iosSimulatorArm64Test
./gradlew dokkaGenerate
tools/swiftpm-consumer/test.sh
npx --yes markdownlint-cli2@0.24.1 "**/*.md"
typos
lychee --offline .
python3 tools/check_private.py
```

## Docs to update

- `docs/tutorials/get-started.md` and the `docs/how-to/` pages above (new).
- Root `README.md` (new) and [`docs/README.md`](../../../docs/README.md).
- `docs/agents/index.md` (regenerated by `./gradlew updateReference`).
- [`PROJECT.md`](../../../PROJECT.md): `checkDocRegions` in the commands.

## Devices needed

None. Samples run on the JVM, the iOS simulator and `VirtualAir`. Background behavior stays unverified.
