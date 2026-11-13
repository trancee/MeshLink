# Toolchain refresh, 2026-11-13

Status: research findings only. This note does not record a decision. It answers the ticket "Is the October toolchain still the newest stable, and can Xcode 27.2 replace Xcode 16.4?" for the decision ticket that may amend [ADR 0005](../adr/0005-toolchain-and-dependency-baseline.md).

## Question

1. Is every row of ADR 0005's version tuple still the newest stable release, and what do the vendors' compatibility statements say now? Is detekt 2.0 stable, is Kover still Beta, and is KGP's built-in ABI validation still Experimental?
2. Which Xcode versions does the newest Kotlin support? Can Xcode 27.2 build Kotlin/Native frameworks for an iOS 15.0 deployment target, and can it install and debug apps on the lab's iOS 15 and 16 phones?
3. Which Xcode versions do GitHub's hosted macOS runner images ship, which is the default, and what is being deprecated?
4. Which tool can enforce the Gradle module dependency direction of [ADR 0011](../adr/0011-modules-toggles-and-presets.md) decision 2 in a KMP build, and how does each candidate fit E3?
5. Has anything else changed since [the 2026-10-31 toolchain note](2026-10-31-kmp-toolchain-2026.md) that affects the baseline?

## Method and sources

Every fact below was checked on 2026-11-13 against a primary source:

- Maven Central and Google Maven `maven-metadata.xml`, with POM `Last-Modified` headers for publish dates
- `services.gradle.org/versions`, `api.adoptium.net`, and Google's SDK repository manifest (`dl.google.com/android/repository/repository2-3.xml`)
- GitHub releases, tags, issues and repository source, read with `gh`
- kotlinlang.org, developer.android.com, docs.gradle.org, detekt.dev, developer.apple.com, blog.cocoapods.org, and the JetBrains YouTrack API
- Read-only checks on the lab Mac: `xcodebuild -version`, `xcrun --show-sdk-version`, each SDK's `SDKSettings.json`, and the Xcode bundles' `DeviceSupport` folders, selected per command with `DEVELOPER_DIR`. Nothing was built, installed, or run on a device.

Apple's Xcode support table rendered this time, so the Xcode facts no longer depend on KT-87187 quoting Apple.

## Findings: version tuple

"Newer?" compares the newest stable release with ADR 0005's pin.

| Component | ADR 0005 | Newest stable (date) | Newer? | Compatibility and notes | Source |
|---|---|---|---|---|---|
| Kotlin (KGP, compiler, stdlib) | 2.4.21 | **2.4.21** (2026-10-08) | No | 2.5.0-RC was published 2026-11-11 (pre-release). kotlinlang plans 2.5.0 for December 2026 and 2.5.20 for March 2027. KGP 2.4.20–2.4.21 fully supports Gradle 7.6.3–9.7.0, AGP 8.5.2–9.3.1 and Xcode 26.4 | Maven Central; GH release v2.5.0-RC; kotlinlang releases, gradle-configure-project, KMP compatibility guide |
| Gradle | 9.7.1 | **9.9.1** (build 2026-11-10); 9.8.2 (2026-10-19) | Yes, outside KGP's table | KGP 2.4.21 lists 9.7.0 as the maximum. Gradle's own table says it was tested with Kotlin up to 2.5.0-Beta1 | services.gradle.org; Gradle compatibility matrix |
| AGP + `com.android.kotlin.multiplatform.library` | 9.3.3 | **9.5.1** (2026-11-05); 9.4.1; 9.3.3 is still the newest 9.3 patch | Yes, outside KGP's table | AGP 9.5 needs Gradle ≥ 9.6.0 and JDK 17, supports API 37, and its default NDK is 28.2.13676358. The 9.5.1 notes list no public fixes. 9.6.0-alpha03 is a pre-release | Google Maven; AGP 9.5 release notes |
| Gradle daemon JDK / toolchain / test runtime | 25 / 21 / 21 | **25** is the newest LTS; 27 is the newest feature release | No | Gradle runs on JDK 25 from 9.1.0 and on JDK 27 from 9.8.0. Kotlin 2.5.0-RC reports an error for compiler runtimes below JDK 17 (opt-out flag `-Xallow-pre-17-runtime-jdk`) | api.adoptium.net; Gradle compatibility matrix; Kotlin what's new 2.5.0-RC |
| compileSdk / minSdk | 37 / 26 | API **37**, with minor releases `android-37.1` and `android-37.2` | Minor only | No API 38 platform is published; `android-canary-*` platforms are previews. Whether to target the 37.2 minor SDK is a separate choice | Google SDK repository manifest |
| Android NDK | 30.0.16248370 | **r30 = 30.0.16248370** (2026-09-08) | No | GitHub's runner images default to NDK 27 (see Q3) | android/ndk release r30; SDK manifest |
| kotlinx-coroutines | 1.11.0 | **1.11.0** (2026-05-08) | No | No newer release or RC | Maven Central; GH releases |
| SKIE | 0.10.16 | **0.10.16** (Maven 2026-10-29; GitHub release 2026-11-10) | No | `versionSupport.kotlin` on `main` ends at `2.4.20(2.4.21)`. No Kotlin 2.5 support yet; touchlab/SKIE#213 "Kotlin 2.5 support?" is open (2026-11-03) | Maven Central; SKIE `common-gradle/gradle.properties`; touchlab/SKIE#213 |
| Kover | 0.9.11 | **0.9.11** (2026-09-29) | No | README still carries the **Beta** badge. Native coverage (kotlinx-kover#293) is still open | Maven Central; Kover README; issue #293 |
| Spotless + ktlint | 8.10.4 + 1.8.0 | **Spotless 8.11.0** (2026-11-02) + **ktlint 1.8.0** | Spotless only | Spotless 8.11.0 changes only the Java formatter (`google-java-format` 2.0.0 default, `cleanup(false)`, `maxLineLength`); it still needs JRE 17+ and Gradle 7.3+. ktlint 2.0.0-ALPHA-4 is a pre-release | Plugin Portal; GH release gradle/8.11.0; ktlint releases |
| detekt | 2.0.0-alpha.6 | **1.23.8** (stable, built against Kotlin 2.0.21); newest 2.x is still **2.0.0-alpha.6** (2026-08-04) | No | **detekt 2.0 is not stable.** The `2.0.0` milestone has 32 open issues and no due date. `main` builds against Kotlin 2.4.21 with Analysis API 2.4.10 | Maven Central (`dev.detekt`, `io.gitlab.arturbosch.detekt`); detekt.dev compatibility table; GH milestones |
| Dokka | 2.2.0 | **2.3.0** (2026-11-05) | **Yes** | Stable. Analysis is built on Kotlin `2.5.0-dev-3229` (2.2.0 used 2.3.20-dev). Removes K1 analysis, adds Project Isolation support in DGP v2, and **updates bundled FreeMarker, jsoup and Jackson to fix published CVEs, one rated critical** | GH release v2.3.0; Dokka `libs.versions.toml`@v2.3.0 |
| vanniktech maven-publish | 0.37.0 | **0.37.0** (2026-06-21) | No | `main` now builds with Gradle 9.9.1, AGP 9.5.1 and Kotlin 2.4.21, but that is unreleased | GH releases; `main` `libs.versions.toml` |
| Robolectric | 4.17 | **4.18** (GitHub release 2026-11-13 18:14 UTC; Maven 15:36 UTC) | **Yes** | Published on the day of this check. Removes SDK 23 support, a newer API 37 `android-all-instrumented` jar (`17-robolectric-16653203-i7`) is published, enables native access on JDK 24+, lowers `ShadowBluetoothAdapter`'s minSdk for LE PHY APIs, and adds Linux arm64 native libraries. Whether the two JVM flags of ADR 0005 are still needed is unverified | GH release robolectric-4.18; Maven Central |
| JUnit 4 / androidx.test core | 4.13.2 / 1.7.0 | **4.13.2 / 1.7.0** | No | androidx.test core 1.8.0-alpha04 is a pre-release | Maven Central; Google Maven |
| libsodium | 1.0.22 | **1.0.22** (2026-04-09) | No | The `1.0.22-stable` branch and tarball moved on 2026-11-12, including "Explicitly enable fuse-aes on aarch64" and AEGIS speedups. Those touch AES/AEGIS paths that the ADR 0005 invariant keeps out of the shim; ADR 0005 pins the release tarball, not `-stable` | GH releases; libsodium `ChangeLog`; download.libsodium.org |
| Rust (test-only reference) | 1.99.0 | **1.100.0** (2026-11-12); 1.99.1 (2026-10-16) | **Yes** | 1.99.1 fixes an x86_64 FFI `bool` miscompile and macOS `rust-lld` rpath | GH releases rust-lang/rust |
| KGP built-in ABI validation | Experimental | — | — | **Still Experimental**: the docs still require `@OptIn(ExperimentalAbiValidation::class)`. The 2.5.0-RC notes announce no stabilization | kotlinlang gradle-binary-compatibility-validation; whatsnew-eap |

## Findings: Xcode

### What Kotlin supports

| Kotlin | Xcode in the compatibility table | `XcodeVersion.maxTested` in source | Notes |
|---|---|---|---|
| 2.4.21 (stable) | 26.4 | 26.4 | `minVersion.ios = 15.0` in `konan.properties` |
| 2.5.0-RC (pre-release) | not yet listed | **27.0** | `konan.properties` pins Xcode 27.0 (27A266a); `minVersion.ios = 15.0` |

- When the selected Xcode is newer than `maxTested`, KGP reports the `XcodeVersionTooHighWarning` diagnostic ("Stability in such configuration hasn't been tested"). It is a warning, not an error, and `kotlin.apple.xcodeCompatibility.nowarn=true` suppresses it. Xcode 27.2 is newer than both 26.4 and 27.0, so both Kotlin versions would warn.
- Xcode 27 work shipped in two steps:
  - Kotlin 2.4.20-Beta2 fixed KT-87187 (Apple deployment targets raised to match Xcode 27: iOS 15.0) and KT-87196 (the linker override broke under Xcode 27).
  - The Kotlin/Native Xcode 27 meta issue KT-87869 is fixed in 2.5.0-Beta2, not in 2.4.x.
- So no stable Kotlin officially supports any Xcode 27. The first one is expected to be 2.5.0 (planned for December 2026), whose source is tested against 27.0.

### What Apple supports

From Apple's Xcode support table and the Xcode 27 / 27.2 release notes:

| Xcode | Runs on | iOS deployment targets | On-device debugging | Simulator |
|---|---|---|---|---|
| 16.4 | macOS Sequoia 15.3 – Tahoe 26.1.x | 15–18 | iOS 15+ | iOS 15+ |
| 26.4.1, 26.5, 26.6 | macOS Tahoe 26.2 – 26.x | 15–26.x | iOS 15+ | iOS 15+ |
| 27.0, 27.1, **27.2**, 27.3 beta | macOS Tahoe 26.6 or later | **15–27** | **iOS 17+** | iOS 17+ |

- The Xcode 27 and 27.2 release notes repeat: "supports on-device debugging in iOS 17 and later" and "requires a Mac running macOS Tahoe 26.6 or later". Under Deprecations, Xcode 27 adds: "Instruments now requires target iOS … devices with versions of at least iOS 17".
- **No Xcode is both supported on macOS 27 and able to debug iOS 15/16.** Xcode 16.4 and 26.x list macOS Tahoe 26.x as their maximum. Xcode 27.x supports macOS 27 but debugs only iOS 17+.

### Local checks (lab Mac, macOS 27.0.1)

| Check | Xcode 16.4 (16F6) | Xcode 27.2 (27B52) |
|---|---|---|
| `xcrun --sdk iphoneos --show-sdk-version` | 18.5 | 27.2 |
| iPhoneOS SDK `MinimumDeploymentTarget` (`SDKSettings.json`) | 12.0 | **15.0** |
| `Platforms/iPhoneOS.platform/DeviceSupport` | 15.0 – 16.4 developer disk images | **absent** |
| `LSMinimumSystemVersion` | 15.3 | 26.6 |

- The Xcode 27.2 SDK accepts an iOS 15.0 deployment target, which matches Apple's table and Kotlin's `minVersion.ios`.
- Xcode 16.4 carried the developer disk images that debugging an iOS 15/16 phone needs. Xcode 27.2 ships none, consistent with "debugging on iOS 17+".

### Answer to question 2

- **Build for iOS 15.0:** Apple supports it (deployment targets 15–27), and the SDK allows it. Kotlin 2.4.21 does not list Xcode 27 and warns. Whether 2.4.21 builds, links and runs a MeshLink-shaped XCFramework with Xcode 27.2 is **unverified**: no smoke build was run, because this ticket allowed only read-only checks.
- **Debug on iOS 15/16:** **no.** Apple supports device debugging only on iOS 17+, Xcode 27.2 has no iOS 15/16 disk images, and Instruments requires iOS 17+.
- **Install on iOS 15/16:** Apple documents no install path separately from "Device Support". The lab already installs and launches on iOS 15/16 with libimobiledevice (`idevicedebug`, `afcclient`; see `docs/agents/lessons.md`), which does not depend on Xcode. Installing an Xcode-27.2-signed app with an iOS 15.0 deployment target that way is plausible but **unverified**; no device was touched.
- **Keeping Xcode 16.4 for iOS 15/16:** it is no longer an officially supported pairing on macOS 27 (Apple lists Tahoe 26.1.x as its maximum), even though it builds and runs there today (ADR 0005 smoke builds).

## Findings: GitHub-hosted macOS runners

From the actions/runner-images README and image readmes:

| Label | OS (image version) | Xcode installed | Default | Status |
|---|---|---|---|---|
| `macos-15`, `macos-15-xlarge` (arm64) | macOS 15.8.2 (20261104) | 16.0–16.4, 26.0.1–26.3 | **16.4** | GA |
| `macos-26`, `macos-latest`, `macos-26-xlarge` (arm64) | macOS 26.7.2 (20261102) | 26.0.1–26.6 | **26.6** | GA; `macos-latest` moved to macOS 26 in June 2026 (#14167) |
| `xcode-27`, `xcode-27-xlarge` (arm64) | **macOS 27.0.1** (20261104) | 27.0, 27.1, 27.2 | **27.1** | **Preview** since 2026-07-16 (#14404); based on macOS 27 since 2026-09-16 |
| `macos-14` | — | — | — | Retired on GitHub Actions 2026-11-02 (#13518, #15226) |

- Policy (README): one Xcode major per macOS version, all its minors installed, and a new patch replaces the previous patch.
- No deprecation date is announced for `macos-15` or `macos-26`. There is no GA `macos-27` label yet.
- Every image has JDK 11, 17, 21 (default) and 25.
- Every image readme lists NDK 27.3 (default), 28.2 and 29.0. Announcement #14745 swaps NDK 28 for **30** "from 2026-10-01 onward", but the current readmes do not show it yet. ADR 0005's NDK 30.0.16248370 therefore needs an explicit `sdkmanager` install in CI either way.
- `macos-26` and `xcode-27` will switch the default OpenSSL from 3 to 4 (#14856).
- Consequence: **no hosted image pairs Xcode 27 with GA status**. CI on Xcode 26.6 (`macos-26`) is GA but outside Kotlin 2.4.21's tested 26.4 (a patch-level gap: `macos-26` also ships 26.4.1). CI on Xcode 16.4 (`macos-15`) is GA and matches ADR 0005.

## Findings: dependency-direction check (ADR 0011 decision 2)

The rule to enforce is four modules with a fixed allow-list: `:meshlink-core` → none; `:meshlink-crypto-sodium` → `:meshlink-core`; `:meshlink` → both; `:meshlink-testing` → `:meshlink`.

| Option | License | Maintenance (2026-11-13) | KMP support | What it checks | E3 / D1 fit |
|---|---|---|---|---|---|
| **Custom check in `build-logic/`** (no dependency) | Project's own | Owned by MeshLink | Full: reads every configuration, including `commonMainImplementation`, `androidMainApi`, `iosMainImplementation` and test configurations | Each module's `ProjectDependency.path` (stable since Gradle 8.11) against an allow-list map | **Best.** No new dependency or E3 row. About 40–60 lines: a convention plugin applied per module, which keeps it Project-Isolation-safe, plus a `check`-wired task and one failing-case test (T1) |
| Gradle built-in | Apache-2.0 | — | — | Gradle has no declarative rule for allowed project dependencies; it offers only the APIs the custom check uses | Not a standalone option |
| `com.jraska.module.graph.assertion` (modules-graph-assert) 2.9.1 | Apache-2.0 | Release 2026-04-12; last push 2026-11-09; 644 stars | Partial: by default it reads only `api`/`implementation`; KMP needs `configurations += setOf("commonMainImplementation", "commonMainApi")` and points to issue #249 for caveats. Platform source-set configurations must be listed by hand | `allowed`/`restricted` regex rules, `maxHeight`, wired into `check` | Works, but adds a build dependency and an E3 row for about 4 rules, and its KMP coverage depends on listing configurations correctly |
| dependency-analysis-gradle-plugin 3.20.2 | Apache-2.0 | Active (release 2026-11-08) | Only the `jvm` and `androidLibrary` targets of KMP modules; "Module Advice for Kotlin Multiplatform" (#751) open since 2022 | Unused/misdeclared dependencies; its `structure {}` block configures bundles, not direction rules | Does not enforce direction. Rejected for this need |
| Konsist 1.0.0-beta.1 | Apache-2.0 | Active (2026-11-04) | Kotlin source files | Package/import architecture rules written as tests | Checks imports, not the Gradle graph. The newest release is a beta (E2). Overlaps with Kotlin's `internal` visibility |
| ArchUnit 1.5.1 | Apache-2.0 | Active (2026-09-25) | JVM bytecode only: no common or native code | Class/package dependency rules on bytecode | Wrong layer for Gradle modules; misses iOS. Rejected |

## Findings: other changes since 2026-10-31

- **Kotlin 2.5.0-RC** (2026-11-11), with 2.5.0 planned for December 2026:
  - Swift export is promoted to **Beta** with a new `export { swift { … } }` DSL. The RC notes still show it configured for Xcode integration; nothing says it can produce an XCFramework for SwiftPM, so SKIE remains needed.
  - Cross-module klib inlining is on by default.
  - A Kotlin 2.5 upgrade is gated on a SKIE release (#213 open).
- **Dokka 2.3.0** is the first stable release since March and fixes CVEs in its bundled libraries (build-time only; Dokka output is not a runtime artifact, E5).
- **Robolectric 4.18** and **Rust 1.100.0** were released in the last two days.
- **AGP 9.5.1** and **Gradle 9.9.x** widen the gap to KGP 2.4.21's documented range.
- **CocoaPods trunk:** the read-only plan is unchanged. The test run was scheduled for 2026-11-02 to 11-06, and trunk stops accepting new podspecs permanently on **2026-12-02**. MeshLink ships through SwiftPM, so this does not affect it.
- **No 2.4.x patch** after 2.4.21, and no new kotlinx-coroutines, SKIE, Kover, ktlint, detekt or vanniktech release.

## Recommendation for the decision ticket

These are options with their tradeoffs. The decision ticket chooses.

### A. Version tuple

1. **Patch-level and stable minor bumps inside the rules of ADR 0005:**
   - **Dokka 2.2.0 → 2.3.0:** stable, fixes CVEs, and its analysis is built on Kotlin 2.5.0-dev, so it suits 2.4.21 better than 2.2.0's 2.3.20-dev. Needs the ADR 0005 smoke gate (Dokka run).
   - **Spotless 8.10.4 → 8.11.0:** low value (Java-only changes) but harmless.
   - **Rust 1.99.0 → 1.99.1 or 1.100.0:** test-only reference; 1.99.1 is the smallest move.
   - **Robolectric 4.17 → 4.18:** less than a day old at the time of this check. Wait for one patch cycle or rerun the SDK 26/37 smoke tests, including whether the two JVM flags can go.
2. **Hold Kotlin, Gradle and AGP** at 2.4.21 / 9.7.1 / 9.3.3. Gradle 9.8–9.9 and AGP 9.4–9.5 remain outside KGP 2.4.21's table (ADR 0005 version rule). Revisit when Kotlin 2.5.0 is stable and SKIE supports it.
3. **Keep the E2 holds unchanged:** detekt 2.0 is not stable, Kover is still Beta, and KGP ABI validation is still Experimental.

### B. Xcode

| Option | Builds iOS 15.0 | Debug iOS 15/16 | Officially supported on macOS 27 | Kotlin 2.4.21 listed | Cost / risk |
|---|---|---|---|---|---|
| **B1. Keep Xcode 16.4** as the build Xcode; keep 27.2 for Instruments on iOS 17+ | Yes (proven 2026-11-01) | Yes | No (Apple max: Tahoe 26.1.x) | No (26.4 listed) | Status quo; relies on an unsupported OS pairing that already works locally |
| **B2. Xcode 27.2 for builds; 16.4 kept only for iOS 15/16 debugging** | Apple: yes; Kotlin: warns, unverified | Only with 16.4 or libimobiledevice | Yes for 27.2 | No (warns) | Needs a smoke build first. Enables CryptoKit iOS 26 SDK APIs at compile time (ADR 0004), though the iOS 15 target still requires a runtime guard (C9) |
| **B3. Wait for Kotlin 2.5.0 + SKIE**, then move builds to Xcode 27.x | Yes | Same as B2 | Yes | Expected (2.5.0-RC tests 27.0) | Delays the move by weeks; a Kotlin minor bump also needs SKIE, Gradle/AGP re-evaluation |

- CI either way: `macos-15` with Xcode 16.4 is GA. `xcode-27` is a preview image, so B2 and B3 in CI would rest on a preview image until GitHub promotes a macOS 27 image.
- Whatever is chosen, a smoke build of Kotlin 2.4.21 (or 2.5.0) with Xcode 27.2 is the missing evidence: `iosSimulatorArm64Test`, a release XCFramework with `minos 15.0` (`vtool -show-build`), the SwiftPM `binaryTarget` test, and an iOS 15 device install and launch through libimobiledevice.

### C. Dependency-direction tool

- **Recommended for evaluation first: the custom `build-logic/` check.** It adds no dependency, so no E3 row is needed; it covers every KMP configuration, and is small enough to test.
- **Fallback: modules-graph-assert 2.9.1**, if the team prefers a maintained plugin over owning the check. It needs an E3 row and explicit KMP configuration lists.
- dependency-analysis-gradle-plugin, Konsist and ArchUnit do not enforce Gradle module direction for this build.

## Limitations

- No build was run. Every "unverified" above, including Kotlin 2.4.21 with Xcode 27.2 and Robolectric 4.18 without the JVM flags, is documentation-level.
- No device was touched, so installing on iOS 15/16 without Xcode's disk images is unverified.
- Release dates come from Maven POM `Last-Modified` headers, the Gradle versions API, or GitHub `publishedAt`, and may differ by a day from announcements.
- GitHub runner readmes are point-in-time snapshots (image versions 20261102–20261104); the weekly rollout may change installed software.
- The Kotlin 2.5.0-RC compatibility table row (Gradle, AGP, Xcode) is not published yet; the Xcode 27.0 figure comes from source (`Xcode.kt`, `konan.properties` at tag v2.5.0-RC).

## Sources

All checked 2026-11-13.

- KGP ↔ Gradle ↔ AGP: <https://kotlinlang.org/docs/gradle-configure-project.html>
- KMP ↔ Xcode: <https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html>
- Kotlin release schedule: <https://kotlinlang.org/docs/releases.html>
- Kotlin 2.5.0-RC: <https://github.com/JetBrains/kotlin/releases/tag/v2.5.0-RC>, <https://kotlinlang.org/docs/whatsnew-eap.html>
- Kotlin source, max tested Xcode: <https://github.com/JetBrains/kotlin/blob/v2.4.21/native/utils/src/org/jetbrains/kotlin/konan/target/Xcode.kt>, <https://github.com/JetBrains/kotlin/blob/v2.5.0-RC/native/utils/src/org/jetbrains/kotlin/konan/target/Xcode.kt>
- Kotlin source, Xcode warning: <https://github.com/JetBrains/kotlin/blob/v2.4.21/libraries/tools/kotlin-gradle-plugin/src/common/kotlin/org/jetbrains/kotlin/gradle/plugin/diagnostics/KotlinToolingDiagnostics.kt>
- Kotlin/Native properties: <https://github.com/JetBrains/kotlin/blob/v2.5.0-RC/kotlin-native/konan/konan.properties>
- YouTrack: <https://youtrack.jetbrains.com/issue/KT-87187>, <https://youtrack.jetbrains.com/issue/KT-87196>, <https://youtrack.jetbrains.com/issue/KT-87869>
- KGP ABI validation: <https://kotlinlang.org/docs/gradle-binary-compatibility-validation.html>
- Gradle versions and Java matrix: <https://services.gradle.org/versions/all>, <https://docs.gradle.org/current/userguide/compatibility.html>
- Gradle `ProjectDependency.getPath()`: <https://docs.gradle.org/current/javadoc/org/gradle/api/artifacts/ProjectDependency.html>
- AGP 9.5: <https://developer.android.com/build/releases/agp-9-5-0-release-notes>
- AGP versions: <https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/maven-metadata.xml>
- Android SDK manifest: <https://dl.google.com/android/repository/repository2-3.xml>
- NDK r30: <https://github.com/android/ndk/releases/tag/r30>
- JDK releases: <https://api.adoptium.net/v3/info/available_releases>
- SKIE: <https://github.com/touchlab/SKIE/blob/main/common-gradle/gradle.properties>, <https://github.com/touchlab/SKIE/issues/213>
- Kover: <https://github.com/Kotlin/kotlinx-kover>, <https://github.com/Kotlin/kotlinx-kover/issues/293>
- detekt: <https://detekt.dev/docs/introduction/compatibility/>, <https://github.com/detekt/detekt/milestones>
- Dokka 2.3.0: <https://github.com/Kotlin/dokka/releases/tag/v2.3.0>
- Spotless 8.11.0: <https://github.com/diffplug/spotless/releases/tag/gradle/8.11.0>
- ktlint: <https://github.com/pinterest/ktlint/releases>
- vanniktech: <https://github.com/vanniktech/gradle-maven-publish-plugin/releases/tag/0.37.0>
- Robolectric 4.18: <https://github.com/robolectric/robolectric/releases/tag/robolectric-4.18>
- libsodium: <https://github.com/jedisct1/libsodium/releases/tag/1.0.22-RELEASE>, <https://github.com/jedisct1/libsodium/blob/stable/ChangeLog>
- Rust: <https://github.com/rust-lang/rust/releases/tag/1.100.0>, <https://github.com/rust-lang/rust/releases/tag/1.99.1>
- Apple Xcode support table: <https://developer.apple.com/support/xcode/>
- Xcode 27 / 27.2 release notes: <https://developer.apple.com/documentation/xcode-release-notes/xcode-27-release-notes>, <https://developer.apple.com/documentation/xcode-release-notes/xcode-27_2-release-notes>
- Runner images: <https://github.com/actions/runner-images>, image readmes under <https://github.com/actions/runner-images/tree/main/images/macos>
- Runner announcements: <https://github.com/actions/runner-images/issues/14404>, <https://github.com/actions/runner-images/issues/14167>, <https://github.com/actions/runner-images/issues/13518>, <https://github.com/actions/runner-images/issues/15226>, <https://github.com/actions/runner-images/issues/14745>, <https://github.com/actions/runner-images/issues/14856>
- modules-graph-assert: <https://github.com/jraska/modules-graph-assert>
- dependency-analysis-gradle-plugin: <https://github.com/autonomousapps/dependency-analysis-gradle-plugin>, <https://github.com/autonomousapps/dependency-analysis-gradle-plugin/issues/751>
- Konsist: <https://github.com/LemonAppDev/konsist>
- ArchUnit: <https://github.com/TNG/ArchUnit>
- CocoaPods trunk: <https://blog.cocoapods.org/CocoaPods-Specs-Repo/>
