# MeshLink 0.1.0 implementation backlog

The ordered build of MeshLink 0.1.0, from an empty Gradle build to the published release. The [0.1.0 wayfinder map](../meshlink-0.1.0/map.md) decided its shape; this file is the index, and each ticket in `issues/` holds its own detail.

## Authority

This backlog decides only order and slicing. Behavior comes from:

- the [protocol spec](../../docs/spec/README.md), with its data in `docs/spec/data/` and vectors in `docs/spec/vectors/`;
- the [ADRs](../../docs/adr/), which decide everything else;
- [`CONSTITUTION.md`](../../CONSTITUTION.md), [`AGENTS.md`](../../AGENTS.md) and [`KOTLIN.md`](../../KOTLIN.md).

A ticket that disagrees with the spec or an ADR is wrong: fix the ticket, or change the ADR first through its own amendment.

## Slicing rules

Decided in [build order and slices](../meshlink-0.1.0/issues/07-build-order-and-slices.md#answer):

1. **One ticket = one PR = one agent session.** It delivers one observable behavior (through the public API or a spec section) or one piece of infrastructure.
2. **Every required gate stays green**, including Kover at 100%, the ABI dump and the docs checks (ADR 0021).
3. **No layer ahead of its slice.** A frame codec, its spec vectors and its deterministic mutation test (ADR 0017 decision 9) land with the first slice that sends that frame.
4. **Too big means split.** A claimed ticket found bigger than one session goes back to `needs-triage` and is split. It never grows mid-PR.
5. **Radio slices name a minimal device pair** under "Devices needed". The full 8-phone set runs only at release.
6. **Lanes.** Tickets whose `Blocked by:` lines allow it may run in parallel, for example the M3 crypto lane beside the M2 radio lane. The owner merges one PR at a time.

## Proof commands

These names are fixed here so every ticket can cite them. The ticket that creates a command (in brackets) may rename it only if it updates this file and every ticket that cites it in the same PR (Constitution O1).

| Command | Proves | Created by |
|---|---|---|
| `./gradlew check` | Android host tests (Robolectric), Kover 100%, detekt, Spotless, ABI check, module direction check; later also `checkChangelog`, `checkAgentDocs` and `checkDependencyRegister` | 01, 02, 56 |
| `./gradlew iosSimulatorArm64Test` | `commonTest` and `iosTest` on the iOS simulator | 01 |
| `./gradlew :<module>:testAndroidHostTest --tests '<pattern>'` | One focused host test while iterating; the build has no `jvm()` target (ADR 0017), and ticket 01 verifies the task name | 01 |
| `./gradlew dokkaGenerate` | API docs build without warnings | 02 |
| `./gradlew publishToMavenLocal` | Every artifact publishes with its POM | 04 |
| `tools/swiftpm-consumer/test.sh` | The XCFramework works from a SwiftPM `binaryTarget`; runs `xcodebuild test` on an iOS simulator, because `swift test` can't link an iOS-only XCFramework on macOS | 04 |
| `python3 tools/lab/lab.py run --scenario <name> --devices <alias>,<alias> [--profile <p>]` | One device scenario; writes results under `build/lab/` | 16 |
| `python3 tools/lab/lab.py evidence --run <dir>` | Turns a run into a T9 evidence record in `docs/evidence/` | 16 |
| `python3 -m unittest discover -s tools/lab/tests` | The lab tools' own unit tests | 16 |
| `cargo test --manifest-path tools/reference/Cargo.toml` | The Rust reference cross-checks the tier 2 vectors | 20 |
| `./gradlew :meshlink-core:fuzz` | Jazzer fuzzing of the decoders (scheduled) | 34 |
| `./gradlew :meshlink-testing:densityTest` | 50–200-node density meshes against the simulator bands (scheduled) | 42 |
| `./gradlew updateReference` | Regenerates the generated reference pages | 53 |
| `python3 tools/check_freshness.py` | Dependency freshness report | 56 |
| `swift/test.sh` | The Swift overlay's tests on an iOS simulator | 55 |
| `actionlint` | Workflow files are valid | 59, 60 |
| `python3 tools/release/verify_release.py --version <X.Y.Z>`, `python3 -m unittest discover tools/release` | A published release matches its build; the verifier's own tests | 60 |
| `gh workflow run release.yml --ref main -f dry_run=true` | A release dry run | 60 |
| Docs checks: `npx --yes markdownlint-cli2@0.24.1`, `typos`, `lychee --offline .`, `python3 tools/check_adrs.py`, `python3 tools/check_private.py`, `python3 tools/spec/check_spec.py` | Docs, links, privacy and spec gates | exist |

Ticket 05 adds the required status checks, since the first build PRs (01–04) create the jobs they name (ADR 0021 decision 6). Ticket 02 adds `CHANGELOG.md` and `checkChangelog` (ADR 0019 decision 6).

## Scheduled jobs

Non-required jobs, each added by the ticket shown:

| Job | Ticket |
|---|---|
| `xcode-27` preview build and iOS tests | 05 |
| Rust reference cross-check of the tier 2 vectors | 20 |
| Jazzer coverage-guided fuzzing of the decoders | 34 |
| 50–200-node density meshes against the simulator bands | 42 |
| Dependency freshness report | 56 |
| External link check | exists (`docs.yml`) |

## Milestones

Each milestone ends when all its tickets are merged and its exit criterion holds on `main`.

### M0 Scaffold

Exit: an empty but fully gated build. Every required CI check runs on pull requests and is required on `main`. No behavior.

| # | Ticket | Type | Blocked by |
|---|---|---|---|
| 01 | [Build the four modules with `build-logic` and the dependency-direction check](issues/01-gradle-scaffold.md) | AFK | |
| 02 | [Gate the build with Kover, detekt, Spotless, ABI validation and Dokka](issues/02-quality-gates.md) | AFK | 01 |
| 03 | [Prove Robolectric 4.18 on SDK 26 and 37](issues/03-robolectric-proof.md) | AFK | 01 |
| 04 | [Publish to Maven local and consume the XCFramework from SwiftPM](issues/04-apple-packaging-proof.md) | AFK | 01 |
| 05 | [Run the build in CI and make its jobs required on `main`](issues/05-ci-required-checks.md) | HITL | 02, 03, 04 |

### M1 Tracer bullet

Exit: two `Unsecured` nodes on `VirtualAir` discover each other and exchange one direct message through the public API, in Android host tests and on the iOS simulator, with real libsodium hashing.

| # | Ticket | Type | Blocked by |
|---|---|---|---|
| 06 | [Build libsodium from verified source for the host, Android and iOS](issues/06-libsodium-native-build.md) | AFK | 05 |
| 07 | [Hash, generate identities and draw randomness through a minimal `CryptoProvider`](issues/07-crypto-provider-minimal.md) | AFK | 06 |
| 08 | [Run a sans-IO core on `VirtualAir` in virtual time](issues/08-sans-io-core-virtual-air.md) | AFK | 05 |
| 09 | [Create, start, stop and close a node through the public API](issues/09-lifecycle-api.md) | AFK | 07, 08 |
| 10 | [Discover an `Unsecured` peer on `VirtualAir`](issues/10-discovery-unsecured.md) | AFK | 09 |
| 11 | [Open an `Unsecured` link with `PLAIN_HELLO`](issues/11-unsecured-link.md) | AFK | 10 |
| 12 | [Send and receive a direct message between two nodes](issues/12-direct-message-tracer.md) | AFK | 11 |

### M2 Radio tracer

Exit: the M1 scenario passes over GATT on real phones for Android↔Android, Android↔iOS and iOS↔iOS.

| # | Ticket | Type | Blocked by |
|---|---|---|---|
| 13 | [Scan, advertise and carry links over GATT on Android](issues/13-android-gatt-adapter.md) | AFK | 12 |
| 14 | [Scan, advertise and carry links over GATT on iOS](issues/14-ios-gatt-adapter.md) | AFK | 12 |
| 15 | [Keep the installation identity in the platform keystore](issues/15-platform-keystore-identity.md) | AFK | 12 |
| 16 | [Drive the lab apps from `tools/lab/`](issues/16-labapp-and-lab-tools.md) | AFK | 13, 14, 15 |
| 17 | [Prove the radio tracer on real phone pairs](issues/17-radio-tracer-device-proof.md) | HITL | 16 |

### M3 Security

Exit: `Classical` and `PostQuantum` links come up on `VirtualAir` and on a real phone pair; every crypto vector set passes in Android host tests and on the iOS simulator, and the Rust reference agrees.

| # | Ticket | Type | Blocked by |
|---|---|---|---|
| 18 | [Complete `CryptoProvider` with every profile's primitives](issues/18-crypto-provider-full.md) | AFK | 07 |
| 19 | [Run every vector set from one runner in `commonTest`](issues/19-vector-runner.md) | AFK | 18 |
| 20 | [Cross-check tier 2 vectors with the Rust reference](issues/20-rust-reference-vectors.md) | AFK | 19 |
| 21 | [Issue and validate identity certificates](issues/21-identity-certificate.md) | AFK | 20 |
| 22 | [Complete Noise `XX` and `XXpsk3` handshakes](issues/22-noise-xx.md) | AFK | 19 |
| 23 | [Complete Noise `XXhfs` and `XXhfspsk3` handshakes](issues/23-noise-xxhfs.md) | AFK | 20, 22 |
| 24 | [Open `Classical` links with a certified handshake](issues/24-classical-profile-link.md) | AFK | 12, 21, 22 |
| 25 | [Open `PostQuantum` links](issues/25-post-quantum-profile-link.md) | AFK | 23, 24, 28 |
| 26 | [Prove the secured profiles and crypto speed on real phones](issues/26-crypto-device-proof.md) | HITL | 17, 25 |

### M4 Links

Exit: links rotate, fragment, flow-control, rekey and upgrade to L2CAP CoC where both phones support it, on `VirtualAir` and a real phone pair.

| # | Ticket | Type | Blocked by |
|---|---|---|---|
| 27 | [Rotate advertisements and recognize rotated peers](issues/27-resolvable-tag-rotation.md) | AFK | 24 |
| 28 | [Fragment link records and answer busy](issues/28-fragments-busy.md) | AFK | 24 |
| 29 | [Enforce link limits, flow control and connect retries](issues/29-link-limits-flow-control.md) | AFK | 28 |
| 30 | [Rekey long-lived links](issues/30-rekey.md) | AFK | 28 |
| 31 | [Upgrade links to L2CAP CoC with GATT fallback](issues/31-coc-bearer-switch.md) | AFK | 17, 29 |
| 32 | [Apply the PHY policy, advertising and scanning rules](issues/32-phy-advertising-scanning.md) | AFK | 17, 27 |
| 33 | [Prove the link layer on real phone pairs](issues/33-link-device-proof.md) | HITL | 30, 31, 32 |
| 34 | [Fuzz every decoder on a schedule with Jazzer](issues/34-jazzer-fuzzing.md) | AFK | 28 |

### M5 Routing

Exit: routed unicast and broadcast (signed in secured profiles) cross multi-hop meshes of 2–12 nodes on pull requests and 50–200 nodes on schedule, within the simulator bands, and a forced 3-hop line works on real phones.

| # | Ticket | Type | Blocked by |
|---|---|---|---|
| 35 | [Keep active and passive neighbor views with shuffles](issues/35-neighbor-views-shuffle.md) | AFK | 27 |
| 36 | [Adapt to low, medium and high local density](issues/36-density-classes.md) | AFK | 35 |
| 37 | [Forward unicast messages over multiple hops](issues/37-routed-unicast.md) | AFK | 36 |
| 38 | [Discover routes on demand](issues/38-route-discovery.md) | AFK | 37 |
| 39 | [Flood and sign mesh broadcasts](issues/39-flood-broadcast.md) | AFK | 21, 36 |
| 40 | [Spread broadcasts with Plumtree](issues/40-plumtree.md) | AFK | 39 |
| 41 | [Catch up missed broadcasts after reconnecting](issues/41-catch-up.md) | AFK | 40 |
| 42 | [Test routing at every density](issues/42-density-test-suites.md) | AFK | 38, 41 |
| 43 | [Prove a forced 3-hop line on real phones](issues/43-mesh-line-device-proof.md) | HITL | 33, 42 |

### M6 End-to-end

Exit: end-to-end sealed unicast with delivery and read receipts works in every profile on `VirtualAir`; certificates are found by lookup.

| # | Ticket | Type | Blocked by |
|---|---|---|---|
| 44 | [Find a peer's certificate by lookup](issues/44-certificate-lookup.md) | AFK | 21, 37 |
| 45 | [Seal first messages with HPKE](issues/45-hpke-first-messages.md) | AFK | 20, 44 |
| 46 | [Keep end-to-end sessions with `IK` and `IKhfs`](issues/46-e2e-sessions.md) | AFK | 45, 47 |
| 47 | [Report delivery with delivery receipts](issues/47-delivery-receipts.md) | AFK | 45 |
| 48 | [Report reading with read receipts](issues/48-read-receipts.md) | AFK | 46, 47 |

### M7 Store-and-forward and size

Exit: messages survive an absent recipient within their lifetime, large messages arrive intact under the size caps, and deleted messages leave tombstones.

| # | Ticket | Type | Blocked by |
|---|---|---|---|
| 49 | [Hold messages for absent recipients](issues/49-store-and-forward.md) | AFK | 47 |
| 50 | [Send large messages within their size classes](issues/50-size-classes.md) | AFK | 28, 37 |
| 51 | [Delete messages with tombstones](issues/51-tombstones.md) | AFK | 49 |
| 52 | [Rotate static keys and drop expired certificates](issues/52-static-key-rotation.md) | AFK | 44, 49 |

### M8 Product surface

Exit: every preset and toggle works and explains itself, every diagnostic is cataloged, Swift reads naturally, and the tutorial and how-tos run as written in Kotlin and Swift.

| # | Ticket | Type | Blocked by |
|---|---|---|---|
| 53 | [Configure nodes with presets and toggles](issues/53-presets-toggles.md) | AFK | 32, 39, 46, 48, 50, 52 |
| 54 | [Emit and export cataloged diagnostics](issues/54-diagnostics-catalog.md) | AFK | 53 |
| 55 | [Use MeshLink naturally from Swift](issues/55-swift-overlay.md) | AFK | 54 |
| 56 | [Generate the agent docs and check the dependency register](issues/56-agent-docs-generators.md) | AFK | 54 |
| 57 | [Teach MeshLink with a tutorial and how-tos](issues/57-tutorial-and-how-tos.md) | AFK | 55, 56 |

### M9 Release

Exit: 0.1.0 is on Maven Central and in `meshlink-swift`, its docs are on Pages, and its evidence record passes.

| # | Ticket | Type | Blocked by |
|---|---|---|---|
| 58 | [Set up the release accounts, secrets and `meshlink-swift`](issues/58-release-infrastructure.md) | HITL | 05 |
| 59 | [Publish the docs site on GitHub Pages](issues/59-pages-workflow.md) | AFK | 05 |
| 60 | [Release through a workflow, dry run first](issues/60-release-workflow.md) | AFK | 57, 58 |
| 61 | [Fix the lab faults](issues/61-lab-faults.md) | HITL | 16 |
| 62 | [Pass the 8-phone release evidence run](issues/62-release-evidence-run.md) | HITL | 43, 57, 61 |
| 63 | [Pass the 15-phone fleet smoke run](issues/63-fleet-smoke-run.md) | HITL | 62 |
| 64 | [Release 0.1.0](issues/64-release-0-1-0.md) | HITL | 59, 60, 63 |

## Not in this backlog

- Offering HPKE base mode to libsodium upstream (ADR 0004): it waits for our HPKE to pass, and posting needs the owner's approval.
- Background behavior claims: 0.1.0 marks them unverified (ADR 0017 amendment).
- Kotlin 2.5 and other toolchain moves: ADR 0005 holds them until their conditions are met.
- Extended advertising and the iOS Live Activity hook: deferred beyond 0.1.0 (ADR 0010 and ADR 0011 amendments).
