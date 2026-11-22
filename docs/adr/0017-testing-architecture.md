# 0017: Testing architecture

Status: accepted (2026-11-08; ticket "What is the testing architecture?"). Version: 13 (amended 2026-11-13, release device evidence; 2026-11-13, errata; 2026-11-14, test timeouts; 2026-11-14, sans-IO enforcement; 2026-11-14, system clock; 2026-11-14, Android adapter tests; 2026-11-15, iOS adapter tests; 2026-11-15, lab tools; 2026-11-15, vector runner; 2026-11-16, production randomness; 2026-11-18, fuzzing; 2026-11-21, self-hosted runner).

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
   - Robolectric (version pinned by ADR 0005) tests only the Android BLE adapter, on SDK 26 and 37.
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
      - hpke-pq (`draft-ietf-hpke-pq-05` Appendix A.5, ADR 0004);
      - our own: `XXhfs`, `XXhfspsk3`, `IKhfs`, the certificate, the X-Wing seal and the wire codecs.
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
    - CI never retries a test automatically. One Robolectric SDK 37 run is known to have hung.
    - Every test task has a 10-minute timeout, so a hung test fails CI instead of stalling it. Per-test timeouts come from the framework where it has one: `runTest` (60 s default) in coroutine tests, and a JUnit `Timeout` rule in JVM-only and Robolectric test classes.
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
- **Manual device runs** can be skipped under time pressure. The release checklist (0.1.0 backlog ticket 64) requires the evidence record.

## Consequences

- **[ADR 0005](0005-toolchain-and-dependency-baseline.md):** gains the host libsodium build, Jazzer and the CI tiers.
- **[ADR 0011](0011-modules-toggles-and-presets.md):** `meshlink-testing` holds `VirtualAir` and runs real nodes over it.
- **[Dependency register](../reference/dependencies.md):** gains Jazzer and the host libsodium build.
- **The release ticket (0.1.0 backlog ticket 64)** makes the decision 8 evidence record a release gate; backlog ticket 62 produces the record.

## Amendments

2026-11-13, release device evidence (0.1.0 map ticket "What device evidence does the 0.1.0 release need?"):

- **Release phones (decision 8):** 8 phones, by lab alias. Android: `p10lite` (8.0, the oldest; 1M only, so it never gets PHY commands), `xcover4` (9, 32-bit `armeabi-v7a` only), `nothing2` (16, 2M) and `pixel6` (17, the newest). iOS: `se2016` (15.8, the oldest), `ipx` (16.7), `ip15` (17.6) and `ip12mini` (27, the newest). iOS 15 and 16 are installed through libimobiledevice ([lessons](../agents/lessons.md#device-lab)).
- **Pairwise scenarios (decision 8):** on every platform pairing and both roles: discovery, handshake, direct unicast with delivery and read receipts, and a 128 KiB message over GATT and, where both phones support it, over L2CAP CoC. Direct unicast runs under all three profiles (`unsecured`, `classical`, `post-quantum`); the other scenarios run under `classical`.
- **Mesh scenarios (decision 8):** the 8 phones form a mixed-platform line of at least 3 hops, forced by an internal topology filter that only the lab app enables and that is not public API (ADR 0007). Scenarios: routed unicast, signed broadcast, store-and-forward (a relay goes offline and returns) and link churn.
- **Fleet smoke run (decision 8):** every reachable lab phone, at least 15, carries traffic for 10 minutes with no crash and full delivery. The density bands stay simulator evidence and are labeled as such (T9). 0.1.0 claims no background behavior; the docs mark it unverified.
- **Pass criteria (decision 8):** every message is delivered within its lifetime, exactly once and intact; receipts match the messages; no crash or ANR. Any miss blocks the release unless the record root-causes it to an external fault (for example, a phone locking itself). Latency, throughput and connect time are recorded: 0.1.0 sets the baseline, and later releases fail on a regression above 10% (P4).
- **Lab app (decision 7):** one unpublished app per platform in `labapp/` (Compose on Android, a thin SwiftUI shell on iOS), installed as `ch.trancee.lab.host` (ADR 0005) and driven only through launch arguments and result files, as in the bake-off.
- **Who runs it (decision 7):** an agent runs `tools/lab/` from a dedicated worktree; the owner fixes physical lab faults. The record has every T9 field, and Bluetooth addresses are pseudonymized ([lessons](../agents/lessons.md#device-lab)).

2026-11-13, errata (0.1.0 backlog review):

- **Profile name (2026-11-13 release amendment):** the first profile is `unsecured`, not `none` (ADR 0007 decision 5).
- **Ticket numbers (Risks, Consequences):** "ticket 27" referred to the retired v1 map. The 0.1.0 backlog's release ticket is 64, and its release evidence run is ticket 62.
- **Vector sets (decision 10):** the list now names the hpke-pq vectors that ADR 0004 makes a conformance gate, and `XXhfspsk3`, which the spec's [tier 2 vectors](../spec/vectors/README.md#tier-2-pending) require.

2026-11-14, test timeouts (0.1.0 backlog ticket "Gate the build with Kover, detekt, Spotless, ABI validation and Dokka"):

- **Timeouts (decision 13):** "every test has a timeout" can't be enforced per test across the toolchain. JUnit 4 has no default per-test timeout, `commonTest` can't use JUnit rules, Kotlin/Native tests have no per-test timeout, and Robolectric runs only on JUnit 4, so a JUnit 5 default timeout wouldn't cover the run that hung. Decision: a 10-minute timeout on every Gradle test task (`meshlink.kmp-library` sets it on every `AbstractTestTask`), plus the framework per-test timeouts named in decision 13. Rejected: JUnit 5 for JVM host tests (new dependencies that still leave Robolectric and iOS uncovered) and a custom check for `Timeout` rules (can't cover `commonTest`).

2026-11-14, sans-IO enforcement (0.1.0 backlog ticket "Run a sans-IO core on `VirtualAir` in virtual time"):

- **Enforcement (decision 1):** detekt's `ForbiddenImport` and `ForbiddenMethodCall`, limited to `commonMain` in [`config/detekt/detekt.yml`](../../config/detekt/detekt.yml), fail `check` on ambient time (`kotlin.time.Clock`, `TimeSource`, `measureTime` and kin) and ambient randomness (`kotlin.random` outside `RandomSource.kt`, `Random.Default`, `shuffled`, `random()`, `Uuid.random`). The method rule needs type resolution, so `check` also runs `detektMainAndroid`, which analyzes `commonMain` with `androidMain`. Platform radio APIs need no rule: `commonMain` can't reach them. The seeded source is `SeededRandom`, which wraps `kotlin.random.Random(seed)`; its sequence is fixed for one Kotlin version, so a replay across a Kotlin upgrade may differ.

2026-11-14, system clock (0.1.0 backlog ticket "Discover an `Unsecured` peer on `VirtualAir`"):

- **Production clock (decision 1):** a platform injects `SystemClock` from `:meshlink-core` unless it needs its own: wall time from `kotlin.time.Clock.System`, monotonic time from `TimeSource.Monotonic`. `Clock.kt` is the second `commonMain` file that both detekt rules exempt, as `RandomSource.kt` is for `SeededRandom`. Production code reaches ambient time only through this injected object, so tests still run in virtual time. A platform adapter whose timers must run while the device sleeps supplies a sleep-aware `Clock` instead.

2026-11-14, Android adapter tests (0.1.0 backlog ticket "Scan, advertise and carry links over GATT on Android"):

- **Own shadows (decision 5):** `RadioShadows.kt` in `:meshlink`'s `androidHostTest` shadows the advertiser, the GATT server and client, the remote device and `BluetoothManager.openGattServer`. They script failing registrations, null server opens, busy and refused notifies and writes, write and descriptor statuses, and MTU and connect outcomes, which Robolectric 4.18's own shadows don't.
- **Main-looper tests (decision 13):** a JUnit `Timeout` rule runs the test on another thread, where Robolectric can't build an activity or idle the main looper. A class that needs either uses `@Test(timeout = …)`, which Robolectric enforces on the test's own thread.
- **API levels between the two SDKs (decision 5):** the permission table for API 29 and 30 (fine location on 29 and 30, coarse or fine below) is tested by calling it with each API level, not on an SDK 30 runtime. Behavior that needs the framework at those levels stays unproven until device runs (ticket 17).

2026-11-15, iOS adapter tests (0.1.0 backlog ticket "Scan, advertise and carry links over GATT on iOS"):

- **Where the logic lives (decision 6):** the iOS adapter logic sits in `:meshlink` `commonMain` behind the internal `CoreBluetooth` port, and its fakes are in `commonTest`, not `iosTest` (owner decision). Why: Kover measures only JVM and Android tests, so logic in `commonMain` falls under the 100% gate, and the same tests still run on the simulator. The port mirrors CoreBluetooth: one call interface each for the central manager, a remote peripheral and the peripheral manager, and one callback sink per delegate.
- **Thin layer (decision 6):** `iosArm64Main` holds only the CoreBluetooth calls and delegates, the serial-queue dispatcher and the platform factory; `config/thin-adapters.txt` lists every file, and `checkThinAdapters` fails `check` unless that list names exactly the Kotlin/Native main source files.
- **Requirements flow:** `PlatformPort.requirements` is a `Flow`, not a `StateFlow`. A radio that can't tell yet emits nothing, so `start()` waits in `Starting`, and collecting the flow is what creates the CoreBluetooth managers.

2026-11-15, lab tools (0.1.0 backlog ticket "Drive the lab apps from `tools/lab/`"):

- **Layout (decision 7):** `labapp/shared` (`:labapp-shared`) holds the scenarios once, in Kotlin, against the public API plus the internal link-role hook (ADR 0007, 2026-11-15 lab link roles); it builds the static `LabKit` framework for iOS. `labapp/android` (`:labapp-android`) is the Compose shell, `labapp/ios` the SwiftUI shell, generated by XcodeGen, with a UI test that accepts the Bluetooth alert by exact button label (owner decisions). None is published, and all stay out of the Kover, ABI and Dokka scopes; detekt and Spotless check them.
- **Results:** each phone writes `<run>.jsonl` in its app container: one JSON object per record, starting with `t_ms` (monotonic, injected by the platform shell, because `commonMain` reads no ambient time) and `event`, and carrying the role the node linked in. A run ends with a `done` record whose `result` is `pass` or `fail`.
- **Lab configuration:** aliases resolve through `~/.config/meshlink-lab/devices.json`, which `MESHLINK_LAB_CONFIG` can relocate; the pseudonym key sits beside it in `pseudonym.key`. Neither is ever committed (owner decision). `tools/lab/README.md` gives the format.
- **Profile names (2026-11-13 errata):** already `unsecured`, `classical` and `post-quantum`; until tickets 24 and 25, the lab apps stop a `classical` or `post-quantum` run with a message naming the ticket.

2026-11-15, vector runner (0.1.0 backlog ticket "Run every vector set from one runner in `commonTest`"):

- **Location (decision 10):** the runner lives in `:meshlink-crypto-sodium` `commonTest`, not `:meshlink-core` (owner decision). Derivation, Wycheproof and hpke-pq handlers need the real `CryptoProvider`, which `:meshlink-core` can't depend on, and one runner must own every set. The core codec tests keep only focused cases.
- **Embedding:** the `meshlink.spec-vectors` plugin embeds each JSON file unchanged as minified text chunks in generated test sources, and a test-only JSON reader parses them at run time (owner decision), so `commonTest` reads no files on either target and no JSON library is added. The typed `LayoutVector` and `DerivationVector` generation is deleted.
- **Vendored sets:** external sets sit unchanged in `third_party/vectors/<set>/` with `SOURCE.md` and `SHA256SUMS`; the build fails when a checksum differs (owner decision). hpke-pq `test-vectors.json` (`draft-ietf-hpke-pq-05`) replaces the hand-copied X-Wing A.5 vectors of ticket 18; its other KEMs are recorded as not applicable (ADR 0004).
- **No silent gaps:** a test without a handler fails the run unless a skip names the owning ticket or the reason MeshLink never runs it; a skip that matches no test, or overlaps a handler, also fails. Wycheproof's two ML-KEM-768 `InvalidDecapsulationKey` tests are not applicable: MeshLink decapsulates only with keys it generated, and the FIPS 203 section 7.3 input check is for supplied keys (owner decision).

2026-11-16, production randomness (0.1.0 backlog ticket "Rotate advertisements and recognize rotated peers"):

- **Production randomness (decision 1):** a platform injects `SystemRandom` from `:meshlink-core` through `PlatformPort.random`, as it injects `SystemClock`. It wraps `kotlin.random.Random.Default` for non-cryptographic draws such as a node's rotation phase; key material still comes from `CryptoProvider`. `RandomSource.kt` now holds both `SeededRandom` and `SystemRandom`, and both detekt rules exempt it. A `VirtualPlatform` injects its air's `SeededRandom`, so one seed still replays one run.

2026-11-18, fuzzing (0.1.0 backlog ticket "Fuzz every decoder on a schedule with Jazzer"):

- **One JVM per target (decision 9):** the standalone driver fuzzes one `--target_class` per process, so `:meshlink-core:fuzz` is one `JavaExec` whose main class, `FuzzLauncher` in `androidHostTest`, starts `com.code_intelligence.jazzer.Jazzer` once per target on the same classpath: the `jazzer` configuration plus the Android host-test runtime classpath. Each target runs for `-Pfuzz.seconds` (30 by default) with a 5 s per-input timeout and a 1 GiB heap, so a throw, a hang or an unbounded allocation is a finding; any finding fails the task.
- **Targets:** one per top-level decoder, in `meshlink-core/src/androidHostTest/kotlin/ch/trancee/meshlink/fuzz/`, outside the Kover-measured sources. Each checks that a decoded value re-encodes to its input, the invariant the deterministic tier already holds. `FuzzTargetsTest` in the default suite reads the main classes and fails when a public or internal method that takes bytes and returns `Decoded` belongs to no target, and runs every target over its committed corpus.
- **Corpus:** committed in `meshlink-core/src/androidHostTest/fuzz/<target>/`. `tools/fuzz/seed_corpus.py` writes every `encoded` value of the matching vector file as a seed, and `-Pfuzz.seeds=DIR` replaces each target's corpus with the libFuzzer merge of the seeds and the old corpus. A fuzz run reads the committed corpus and writes new inputs only under `build/fuzz/`.
- **CI:** `.github/workflows/fuzz.yml` runs weekly with 300 s per target and on pull requests that change the targets, the corpus or the task, with 15 s per target; it uploads any crash input. It is not a required check.

2026-11-21, self-hosted runner (owner request: use the self-hosted Mac mini for macOS workflows):

- **CI runner (decision 7):** decision 7's "no self-hosted CI runner" no longer holds for CI. `build.yml`'s Apple phases run on a self-hosted Mac mini on the lab's home network, under a dedicated `gh-runner` account. The rejected alternative "a self-hosted runner for the device lab" stays rejected: no workflow drives the lab's phones.
- **Fork guard:** the repository is public, so a fork's pull request never runs on the Mac mini. The job's `runs-on` picks GitHub's `macos-26` image when `github.event.pull_request.head.repo.fork` is true. `tools/ci/tests/test_require_apple_jobs.py` fails if the guard goes. Only code from a branch of this repository, written by someone with write access, reaches the runner.
- **Exposure accepted (owner decision):** code on the runner can reach the home network and whatever the `gh-runner` account can read. The account reads the owner's JDK 25 and Android SDK; the owner's `Library` folder grants it traverse only (an ACL entry), not listing. `build.yml` uses no secrets and a read-only `GITHUB_TOKEN`.
- **Runner isolation:** the runner runs as a launchd service under `gh-runner`, never from the owner's terminal session. A runner started from a terminal passes that shell's environment, including API keys, to every job. Its `.env` holds only `LANG`, `JAVA_HOME_21_arm64`, `JAVA_HOME_25_arm64` and `ANDROID_HOME`. No workflow step prints the environment.
- **Shared toolchains:** the NDK ships some binaries that only their owner may execute (`-rwxr--r--`). The owner added the group execute bit (`chmod g+x`) for the `staff` group, which includes `gh-runner`. Repeat this after every NDK install or update.
- **Persistent homes:** the runner keeps `~/.gradle` and `~/.konan` between jobs, so its Apple phases skip the Actions cache for both (`runner.environment == 'self-hosted'`). The libsodium cache stays: it lives in the checkout's `build/` directory, which checkout cleans.
- **Gatekeeper:** a launchd service lacks the Developer Tools permission, so Gatekeeper blocks the unsigned test programs that libsodium's `configure` runs, and the job hangs. `gh-runner` grants Developer Tools to `/bin/bash`, which runs the service script. The setup steps are in [the runner how-to](../how-to/set-up-self-hosted-runner.md).
