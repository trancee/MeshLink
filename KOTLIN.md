# Kotlin

Scope=Kotlin tasks; R/X/D/O from Constitution. Ported from cairn `guidance/kotlin.md` (2026-10-05 snapshot). Adopted pins: [ADR 0005](docs/adr/0005-toolchain-and-dependency-baseline.md) + [dependency register](docs/reference/dependencies.md) (verified 2026-11-01); those outrank versions below.

MeshLink constraints: local Xcode `27.2`, CI Xcode `26.4.1` on `macos-26` (ADR 0005 v4). Kotlin `2.4.21` proven with Xcode 16.4 and 27.2; move to 2.5.x only when stable + KGP table + SKIE support it. Gradle/AGP minor bump only once KGP's compatibility table lists it; patches allowed after smoke gates. iOS 15/16 devices: libimobiledevice only (CoreDevice/`devicectl` needs iOS 17+); record both host paths in the `PROJECT.md` proof matrix.
Select latest stable supported by complete Kotlin/Gradle/AGP/JDK/platform tuple; verify official release + compatibility before adoption/upgrade. Resolution != support; suffixless version != stable component. No stable compatible tool => report gap + explicit prerelease acceptance; holds R blocker + removal condition.

## Build

- Pin Wrapper; one Kotlin/compiler-plugin version owner. D version catalog; repeated policy => convention plugin when duplication warrants; included builds share version policy, X parallel owners.
- Official compatibility tables determine tuple. Declare daemon JDK separately from compile toolchain; align Java/Kotlin JVM toolchains/bytecode; target validation fail-closed.
- Typed `compilerOptions {}` at highest common scope; overrides only as required. `kotlinOptions {}` deprecated; X new legacy options/migration-warning suppression.
- Verify effective options/targets on supported CI hosts; resolution alone proves neither.
- Sources: [compatibility](https://kotlinlang.org/docs/gradle-configure-project.html), [compiler options](https://kotlinlang.org/docs/gradle-compiler-options.html).

## KMP proof

- [`PROJECT.md`](PROJECT.md) R `{module,target,host,prerequisites,task,proof,limitations}` matrix; proof=compilation|ABI|generated-consumer|runtime|coverage. X substitute proof kinds.
- Shared-code change => every affected supported target on authoritative host. JVM common tests prove JVM only; Native compile/ABI != Native runtime; inferred ABI != compiled; simulator != hardware.
- Unsupported local host => explicit unverified + owning CI host/task; X drop targets to pass.
- Separate fast module-local incremental/cache feedback from fresh clean/forced CI-equivalent completion gates.

## Tool reference

Snapshot=2026-10-05, refreshed 2026-11-01 where noted; review aid, not pins (pins=ADR 0005); re-check before adoption. Tool role below replaces a separate selection map.

### Dokka: API docs (Q1/E7/O1)

- [`org.jetbrains.dokka`](https://plugins.gradle.org/plugin/org.jetbrains.dokka): stable `2.3.0` (2026-11-05; fixes bundled-library CVEs). New builds=stable DGP v2 (default since `2.1.0`).
- Tasks: `dokkaGenerate` all formats; `dokkaGeneratePublicationHtml` HTML output for consumers. `dokkaHtml`/`dokkaHtmlMultiModule` legacy v1, not new-build guidance.
- Apply every documentable subproject; aggregator `dokka(project(":library"))`.
- Undocumented API release-blocking => `reportUndocumented=true` + publication `failOnWarning=true`; otherwise advisory.
- HTML recommended; re-check stability for durable external contract. Markdown=project choice, not universal replacement.
- Attach generated archive via existing Maven/Gradle publication; verify entry point + source links; README-only archive != generated API docs.
- Committed reference => profile owning task/output + CI regeneration detecting tracked AND new untracked files; X hand-edit.
- Internal-API formats/conventions => exact version alignment + explicit instability acceptance + generation/link checks on upgrade.

### Kover: coverage (Q1/T3/E7)

- [`org.jetbrains.kotlinx.kover`](https://github.com/Kotlin/kotlinx-kover): `0.9.11`, Beta; explicit pre-stable acceptance.
- Measures JVM/Android host-unit bytecode only; not JS/Native/Android instrumentation. No CI Kotlin/Native coverage tool exists (kotlinx-kover#293; `-Xcoverage` rejected by 2.4.21) => Constitution T3 (4.0.0) thin-adapter rule for iosMain. JVM commonMain execution does not measure other-platform actuals; X KMP-wide percentage claim.
- Profile R measured scope + unmet requirements + `{modules,source_sets,test_tasks,exclusions,engine,thresholds,unmeasured_targets}`. No universal Kotlin coverage tool prescribed.
- Tasks: `koverHtmlReport`, `koverXmlReport`, `koverBinaryReport`, `koverLog`, `koverVerify`.
- Engine=IntelliJ default|JaCoCo; same across merged dependencies, X mixed.
- Merger `kover(project(":moduleA"))` for every contributing code/test module.
- Seed uncovered branch => verify fails; meaningful test => passes. Excludes override includes; seed both to prove filter boundary.

### Spotless: formatting/lint (Q1/E1/E7)

- [`com.diffplug.spotless`](https://plugins.gradle.org/plugin/com.diffplug.spotless): `8.11.0` (2026-11-02) + ktlint `1.8.0`; verify selected Gradle/JDK + formatter compatibility.
- `spotlessCheck`=read-only CI; `spotlessApply`=developer mutation. One pinned `ktfmt`|`ktlint`; formatting != static analysis.
- Rollout=isolated repo-wide format commit|`ratchetFrom origin/main`; X `HEAD`.
- Apply twice => second zero diff; check passes. Exclude generated/build/vendor/byte-contractual fixtures.

### ABI: published library compatibility (Q5/E1/E7/G2)

- [KGP built-in](https://kotlinlang.org/api/kotlin-gradle-plugin/kotlin-gradle-plugin-api/org.jetbrains.kotlin.gradle.dsl.abi/-experimental-abi-validation/): `abiValidation {}`, `checkKotlinAbi`/`updateKotlinAbi`; `ExperimentalAbiValidation` has no compatibility guarantee.
- [Legacy](https://github.com/Kotlin/binary-compatibility-validator): `org.jetbrains.kotlinx.binary-compatibility-validator` `0.18.2`, Alpha; `apiCheck`/`apiDump`.
- New libraries D built-in iff accepted API/dump instability + sufficient target/artifact scope; existing legacy stays unless migration requested. Neither assumed stable/complete.
- Selected update -> full public-API baseline review -> commit dump+config -> matching check passes unchanged.
- Every diff => source declaration + published binary + release impact; removals/descriptors require MAJOR+migration; additions require review.
- Source visibility first; legacy exclusions only effectively-internal JVM-public declarations. Marker allowlists R BINARY/RUNTIME retention.
- Profile host inference policy; release R every target validated without inference on capable host. Strict completeness => fail unsupported host; inferred local dump != release proof.
- Transformed publication => final-artifact validation where supported; pre-transform classes != relocated/filtered contract proof.

### KSP: symbol processing

- Only for required code generation. Independent version track; verify Kotlin/AGP mapping, X mechanically reuse Kotlin version.
- Published processor R minimum supported KSP API + consumer JDK/bytecode contract + minimum/current consumer tests; processor compile != consumer compatibility.
- Integration tests compile generated consumers on affected targets; Gradle plugins R validation + TestKit consumers. JVM success != Android/Native proof.
- Generated sources R producer task-provider dependency + declared inputs/outputs + owning regeneration; X manual generation/missing producer wiring.

### SKIE: Apple-framework Swift interop (C2/C9/O3)

- Optional [`co.touchlab.skie`](https://skie.touchlab.co/intro); only framework-producing KMP modules with live Kotlin compatibility. `0.10.16` (tag 2026-10-27) supports Kotlin `2.4.21` (touchlab/SKIE#211); intro page still says `2.4.10` (stale) => verify via release/tag. X assume newer support. Disable analytics upload (`skie { analytics { disableUpload.set(true) } }`). Re-check intro/changelog on version/config change.
- Proof host=macOS+Xcode; compile actual Swift consumer + test enum/sealed switches, suspend/Flow cancellation, exported dependencies.
- Global Gradle defaults; annotations via `co.touchlab.skie:configuration-annotations:<VERSION>`.
- Existing migration: disable broad features -> enable narrow packages/declarations; X large-consumer all-at-once.
- Enum=>Swift enum (original `__Type`); suspend=>async/two-way cancel; Flow=>AsyncSequence; default arguments disabled.

### Power-assert: test diagnostics (T1/T4/T8)

- [`kotlin("plugin.power-assert")` / `org.jetbrains.kotlin.plugin.power-assert`](https://kotlinlang.org/docs/power-assert.html): stable release `2.4.20`, prerelease `2.5.0-Beta1`; plugin remains Experimental.
- Compiler/plugin exact version alignment + one owner; default=all test source sets. Main/custom/production/library reliance R explicit stability/runtime-impact acceptance.
- `functions`=exact fully-qualified callable names; omit `@PowerAssert` functions (auto-discovered). DSL may need narrowly accepted `ExperimentalKotlinGradlePluginApi` opt-in.
- Inline causal expression, not precomputed Boolean; retain domain message. Unconditional production invariant=>`require`/`check`; `assert` can be disabled.
- Assertion library: narrow `ExperimentalPowerAssert` opt-in + `@PowerAssert`; noisy/sensitive params=>`@PowerAssert.Ignore`; plugin-enabled AND fallback consumer tests.
- Seed failure=>capture exception/custom message/expression/intermediates; reverse=>suite passes.

### Detekt: static analysis (Q1/E7)

- [Compatibility](https://detekt.dev/docs/introduction/compatibility): stable `1.23.8` (`io.gitlab.arturbosch.detekt`, built Kotlin `2.0.21`); Alpha `2.0.0-alpha.6` (`dev.detekt`, built Kotlin `2.4.10`).
- Optional; select supported tuple, X silently exchange stability for newer compiler support. MeshLink: `2.0.0-alpha.6` under E2 hold (ADR 0005). KMP plain `detekt` task=NO-SOURCE => wire per-source-set tasks into `check`; type resolution reports false expect/actual errors.
- Project-owned rules/source scopes/type-resolution tasks + reviewed existing-debt baseline; X baseline hiding new findings.

### kotlinx-benchmark: performance evidence (P2/T11)

- Optional [`org.jetbrains.kotlinx.benchmark`](https://github.com/Kotlin/kotlinx-benchmark) `0.5.0`, Alpha; only measured requirement + explicit pre-stable acceptance. Wasm experimental.
- Runtime deps only benchmark source sets; correctness tests + smoke profile + raw results/target/runtime/host metadata.
- Controlled comparisons; single run != improvement proof; noisy shared CI X hard regression gate.
