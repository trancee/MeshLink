# 0017: Testing architecture

Status: accepted (2026-11-08; ticket "What is the testing architecture?"). Version: 1.

## Context

The Constitution sets the bar:

- default CI is deterministic and needs no hardware (T2);
- 100% line and branch coverage wherever a coverage tool exists (T3, amended 4.0.0 for iOS adapters);
- multi-node tests use a deterministic virtual environment (T8);
- hardware claims need an evidence record (T9);
- malformed, truncated, boundary, replayed, incompatible and adversarial inputs are tested (T10).

Earlier decisions already fix parts of the answer:

- **Toolchain ([ADR 0005](0005-toolchain-and-dependency-baseline.md)):**
  - There is no `jvm()` target. Android host tests therefore measure `commonMain`, with Kover at 100%.
  - Kover has no Kotlin/Native support.
  - Robolectric 4.17 runs on SDK 26 and 37.
  - The iOS simulator slice runs non-radio tests.
  - Every radio test runs on hardware.
- **Crypto ([ADR 0004](0004-crypto-provider-libsodium.md)):** libsodium behind our own JNI and cinterop shims.
- **Vectors ([ADR 0006](0006-documentation-architecture.md)):** Wycheproof-style JSON in `docs/spec/vectors/`, also read by the Rust reference.
- **Testing artifacts ([ADR 0007](0007-public-api-shape.md), [ADR 0011](0011-modules-toggles-and-presets.md)):** `meshlink-testing` for Kotlin and `MeshLinkTesting` for Swift. Both run the five reference scenarios.
- **Decoders ([ADR 0012](0012-wire-format-and-codec.md)):** hand-written, so they need truncation, over-long and fuzz tests.

Inputs:

- **Cairn**, the sibling project, has a similar shape:
  - a sans-IO core with an in-memory "air" between nodes;
  - Robolectric with its own shadows, because `ShadowBluetoothGatt.writeCharacteristic` always reports status 0;
  - a device fleet run by hand outside CI;
  - cargo-fuzz with a committed, minimized corpus.

  Its lesson: "CI green, simulator green and device green are three different claims."
- **The device lab ([lessons](../agents/lessons.md#device-lab)):** the bake-off host scripts already build, install, launch and pull results on remote-adb Android phones and on iPhones through `devicectl` and libimobiledevice.
- **Fuzzers:**
  - Jazzer 0.30.0: Apache-2.0, released 2026-02-24, active. Its `jazzer` jar is a standalone driver (`Main-Class: com.code_intelligence.jazzer.Jazzer`) with bundled macOS and Linux natives. Only `jazzer-junit` pulls in JUnit 5.
  - kotlinx.fuzz 1.0.0 (2025-04, last pushed 2025-11) requires JDK 8 and custom Maven repositories.

## Decision

1. **Sans-IO core.** The protocol core reads no clock, draws no randomness and touches no radio of its own. A `Clock`, a seeded `Random` and a `RadioPort` are injected. Platform adapters implement `RadioPort` and contain no protocol logic.
2. **Multi-node tests in virtual time.** Multi-node tests run in `commonTest` under kotlinx-coroutines-test virtual time (T8). They therefore run on the JVM, through the Android host tests, and on the iOS simulator.
3. **One virtual radio, `VirtualAir`.**
   - **Where it lives:** in `:meshlink-testing`. The default suite and apps' own tests use this same fake, so there is never a second one. Real MeshLink nodes run over it.
   - **Where the tests live:** multi-node tests sit in `:meshlink-testing`'s `commonTest`, which avoids a project cycle. Kover merges `:meshlink` and `:meshlink-testing`.
   - **What it models**, all seeded:
     - per-link MTU, latency and loss;
     - link caps per platform (iOS 3, ADR 0009);
     - advertising and scan windows;
     - disconnects.
   - **What it reuses from the routing simulator:** the calibration numbers, as data. Its code is throwaway and is not reused.
4. **Real crypto on the host.**
   - libsodium and our JNI shim are also built for the host (macOS arm64, Linux x64). They come from the same verified tarball and flags, as a test-only artifact that is never published.
   - Host tests therefore run the real crypto, including the vectors and the shim.
   - Fake crypto appears only in unit tests of `unsecured`-profile code.
5. **Robolectric scope.**
   - Robolectric 4.17 tests only the Android BLE adapter, on SDK 26 and 37.
   - The android-all jars are pinned and load offline.
   - Where Robolectric's BLE shadows fall short, our own shadows fill the gaps, for example GATT write status and failing registrations.
   - The library has no UI tests. A Compose UI test exists only if a sample app does, and then runs under Robolectric.
6. **iOS.**
   - CI runs `commonTest` and `iosTest` on the simulator, including the crypto vectors and the libsodium binding.
   - The CoreBluetooth adapter sits behind a port, with fakes in `iosTest`.
   - The CoreBluetooth calls themselves stay thin. They are listed in a CI-named file and exercised by device runs (T3, Constitution 4.0.0).
7. **Device lab.**
   - Host scripts live in `tools/lab/`, modeled on the bake-off's `bakeoff.py`, `matrix.py` and `summarize.py`. They build, install, launch, pull results and summarize.
   - They run before each release and on demand. Each run writes a T9 evidence record to `docs/evidence/`.
   - v1 has no self-hosted CI runner: the lab is on a home network, and the repository may become public.
8. **Release device set.** Every release's evidence record covers:
   - Android↔Android, Android↔iOS and iOS↔iOS;
   - central and peripheral roles on both sides;
   - the oldest supported OS on each side (Android 8, iOS 15) and the newest.

   Energy is re-measured with the [energy method](../evidence/2026-11-energy/README.md) only when the radio duty cycle changes.
9. **Fuzzing in two tiers.**
   - **Default suite:** every decoder gets a deterministic mutation test: every prefix length, over-long inputs and a fixed number of seeded mutations (ADR 0012).
   - **Scheduled:** coverage-guided Jazzer 0.30.0 runs the decoders.
     - Integration: the standalone `jazzer` driver with `fuzzerTestOneInput(ByteArray)` targets, launched by a Gradle `JavaExec` task on the Android host-test runtime classpath. No JUnit 5 and no `jazzer-junit`.
     - The minimized corpus is committed.
     - Every crash becomes a regression input in the default suite.
10. **One vector runner.**
    - A single JSON vector runner lives in `commonTest`, so it runs on both the JVM and the iOS simulator. The Rust reference reads the same files.
    - Sets:
      - Wycheproof, at a pinned commit;
      - cacophony;
      - our own: `XXhfs`, `IKhfs`, the certificate, the X-Wing seal and the wire codecs.
11. **Density tests.**
    - **Pull requests:** small seeded `VirtualAir` meshes of 2–12 nodes cover routing, receipts, store-and-forward and link churn.
    - **Scheduled:** 50–200-node meshes at low, medium and high density. They assert delivery and airtime bands taken from the [routing-simulator evidence](../evidence/2026-11-routing-simulation/README.md).

    A failure is a regression, not radio truth (T9).
12. **CI tiers.**
    - **Pull requests**, targeting at most 15 min:
      - docs gates, detekt and ABI checks;
      - JVM and Robolectric tests with Kover at 100%, on ubuntu;
      - iOS simulator tests, on macOS.
    - **Scheduled:** Jazzer, the large density meshes, the Rust reference cross-check and a dependency freshness check.
    - **Devices:** manual only (decision 7).
13. **No flaky tolerance.**
    - CI never retries a test automatically, and every test has a timeout. One Robolectric SDK 37 run is known to have hung.
    - A flaky test is a bug. It is fixed, or removed with a ticket that tracks restoring it. It is never silently disabled.

## Alternatives

- **Fake crypto on the host, real crypto only on the simulator and devices:** weakens T3/T10 on the JVM, the only target Kover measures. Rejected.
- **A separate internal fake radio besides `meshlink-testing`:** two fakes drift apart. Rejected.
- **Reusing the routing simulator's code:** it is throwaway prototype code with its own model. Only its numbers carry over.
- **A self-hosted runner for the device lab:** it exposes the home network to workflow code, and a public repository makes that worse. Rejected for v1.
- **A device farm on every pull request:** costly and slow. Cairn rejected it for the same reasons.
- **kotlinx.fuzz:** requires JDK 8 and custom Maven repositories, and is barely active. Rejected.
- **`jazzer-junit`:** adds JUnit 5 to a JUnit 4 test stack. The standalone driver needs neither.
- **Only the deterministic mutation fuzzer:** misses deep paths that coverage guidance finds. Kept as the per-PR tier, not as the only tier.
- **Automatic retries:** they hide races, which are exactly the bugs a mesh has. Rejected.

## Risks

- **Fake fidelity:** `VirtualAir` can pass behavior that real radios break. Device evidence (decisions 7 and 8) is the only radio proof, and failures found on devices become `VirtualAir` cases.
- **Swift fake drift:** `MeshLinkTesting` stays hand-written (ADR 0011). Both fakes run the same reference scenarios.
- **Host native build:** a second libsodium build adds CI time and a platform split. It is cached by the tarball's sha256, as the iOS build is.
- **Jazzer on the Android host classpath:** unproven until the first decoder exists. If the wiring fails, the choice returns to the owner.
- **Manual device runs** can be skipped under time pressure. The release checklist (ticket 27) requires the evidence record.

## Consequences

- **[ADR 0005](0005-toolchain-and-dependency-baseline.md):** gains the host libsodium build, Jazzer and the CI tiers.
- **[ADR 0011](0011-modules-toggles-and-presets.md):** `meshlink-testing` holds `VirtualAir` and runs real nodes over it.
- **[Dependency register](../reference/dependencies.md):** gains Jazzer and the host libsodium build.
- **The release ticket (27)** makes the decision 8 evidence record a release gate.
