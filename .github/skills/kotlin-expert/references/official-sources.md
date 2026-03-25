# Official sources for `kotlin-expert`

Use this file as a quick map to the official documentation that should anchor answers.

## Core Kotlin language

- `https://kotlinlang.org/docs/basic-syntax.html`
  - Use for entry points, variables, classes, loops, `when`, ranges, collections, and other baseline language constructs.

- `https://kotlinlang.org/docs/null-safety.html`
  - Use for nullable types, safe calls, Elvis, smart casts, `!!`, and Kotlin/Java nullability pitfalls.

- `https://kotlinlang.org/docs/coding-conventions.html`
  - Use for naming, file organization, source layout, and style guidance when reviewing or refactoring code.
  - Important multiplatform detail: platform-specific files with top-level declarations should use source-set suffixes such as `Platform.jvm.kt` or `Platform.android.kt` to avoid duplicate JVM file facades like `myPackage.PlatformKt`.

## Coroutines

- `https://kotlinlang.org/docs/coroutines-guide.html`
  - Use for structured concurrency, `launch`, `async`, cancellation, dispatchers, Flow, channels, exceptions, and coroutine context.
  - Important boundary detail: `async` and `await` are not Kotlin keywords and are not part of the standard library; they come from `kotlinx.coroutines`.
  - Important dependency detail: coroutine examples require the `kotlinx-coroutines-core` dependency.

## Gradle and setup

- `https://kotlinlang.org/docs/get-started-with-jvm-gradle-project.html`
  - Use for JVM project bootstrapping, `build.gradle.kts` basics, toolchain setup, and standard project layout.

- `https://kotlinlang.org/docs/gradle-configure-project.html`
  - Use for plugin IDs, Kotlin/Gradle compatibility, JVM target concerns, source set configuration, and build setup details.
  - High-value compatibility anchor: Kotlin Gradle plugin `2.3.10` is fully supported with Gradle `7.6.3–9.0.0` and AGP `8.2.2–9.0.0`.
  - High-value plugin detail: the Kotlin plugin `version` in the `plugins {}` block must be a literal.
  - High-value JVM target detail: if `jvmTarget` is unset, Kotlin treats it as `1.8`, while `targetCompatibility` tracks the current Gradle JDK unless you configure a toolchain.
  - High-value metadata detail: on JDK `17` with the default setup, published metadata can wrongly declare `org.gradle.jvm.version=17` even though Kotlin bytecode still targets `1.8`.
  - High-value validation detail: `kotlin.jvm.target.validation.mode` defaults to `error` on Gradle `8.0+` and `warning` on Gradle versions lower than `8.0`.
  - High-value toolchain detail: on Gradle `8.0.2+`, auto-downloaded toolchains may require a resolver plugin such as `org.gradle.toolchains.foojay-resolver-convention` in `settings.gradle(.kts)`.

## Testing

- `https://kotlinlang.org/docs/jvm-test-using-junit.html`
  - Use for Kotlin/JVM testing basics, `kotlin("test")`, JUnit integration, and mixed Java/Kotlin test projects.

## Android

- `https://developer.android.com/kotlin/coroutines/coroutines-best-practices?hl=en`
  - Use for dispatcher injection, main-safe suspend functions, `ViewModel` coroutine ownership, immutable exposed state, and coroutine testability in Android apps.

## Ktor

- `https://ktor.io/docs/welcome.html`
  - Use as the Ktor documentation entry point for version-specific server, routing, plugin, serialization, client, and testing guidance.

## How to apply these sources

- Start with Kotlin language docs for syntax, type-system, refactoring, and style questions.
- Layer in ecosystem docs only when the question is clearly about Android, Ktor, build tooling, or testing.
- If a recommendation depends on a specific version, say so explicitly instead of implying the guidance is universal.
- When the user is debugging build or framework issues, include the exact doc links that match the topic instead of citing Kotlin docs in the abstract.

## High-value factual anchors

Use these when the user needs a precise, doc-grounded answer rather than a general explanation:

- **KMP / duplicate JVM classes from top-level declarations**
  - If `commonMain` and a JVM-specific source set use the same package and same filename for top-level declarations, the JVM compiler can generate duplicate file facades such as `myPackage.PlatformKt`.
  - Recommended fix: rename the platform-specific file with a source-set suffix such as `Platform.jvm.kt`.

- **KGP / Gradle / AGP compatibility**
  - For Kotlin Gradle plugin `2.3.10`, fully supported Gradle versions are `7.6.3–9.0.0`.
  - For Kotlin Gradle plugin `2.3.10`, fully supported AGP versions are `8.2.2–9.0.0`.
  - If a build uses Gradle `7.5` with KGP `2.3.10`, the smallest direct fix is to upgrade Gradle to at least `7.6.3` or downgrade KGP to a version compatible with the current wrapper.

- **JVM target mismatch / metadata trap**
  - With the Kotlin JVM plugin in a default setup, `jvmTarget` is effectively `1.8` when unset.
  - `targetCompatibility` follows the Gradle JDK unless a toolchain is configured.
  - On JDK `17`, that combination can produce wrong published compatibility metadata such as `org.gradle.jvm.version=17` even when Kotlin bytecode is still `1.8`.
  - Preferred fix: configure a Java/Kotlin toolchain instead of manually living with the mismatch.

- **JVM target validation mode**
  - `kotlin.jvm.target.validation.mode=error` is the default on Gradle `8.0+`.
  - `kotlin.jvm.target.validation.mode=warning` is the default on Gradle versions lower than `8.0`.
  - Temporary mitigation: set the property to `warning` in `gradle.properties` or configure task-level warning behavior.

- **Toolchain resolver plugin**
  - On Gradle `8.0.2+`, automatic JDK download may require a toolchain resolver plugin.
  - The Kotlin docs show `org.gradle.toolchains.foojay-resolver-convention` version `0.9.0` in `settings.gradle(.kts)` as an example.
  - The docs also say to check that the resolver version matches the Gradle version.

- **Coroutine boundary**
  - `async` and `await` are library features from `kotlinx.coroutines`, not Kotlin language keywords.
  - A minimal JVM coroutine example usually needs `kotlinx-coroutines-core` in dependencies and `runBlocking`/`async` in code.
