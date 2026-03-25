# Official sources for `android-kotlin-expert`

Use this file as a quick map to the official documentation that should anchor answers.

## Android Kotlin-first foundation

- `https://developer.android.com/kotlin/first`
  - Use for Android's Kotlin-first positioning and the high-level reasons Kotlin is the default path for Android development.

- `https://developer.android.com/kotlin/faq?hl=en`
  - Use for Android Studio support, Java interoperability, mixed Java/Kotlin projects, and quick factual answers about Kotlin on Android.

- `https://developer.android.com/kotlin/add-kotlin?hl=en`
  - Use for adding Kotlin to an existing Android app, Android Studio workflow, Gradle plugin setup, and source-set organization.

## Architecture and state

- `https://developer.android.com/topic/architecture/recommendations?hl=en`
  - Use for layered architecture, repository guidance, single source of truth, unidirectional data flow, lifecycle-aware collection, and strong Android app-architecture recommendations.

- `https://developer.android.com/topic/libraries/architecture/viewmodel?hl=en`
  - Use for what `ViewModel` is for, state persistence through configuration changes, business logic in the UI layer, and Compose-specific `ViewModel` guidance.
  - High-value fact: a composable is not a `ViewModelStoreOwner`, so a `ViewModel` should not be scoped directly to a composable.

## Coroutines and Flow

- `https://developer.android.com/kotlin/coroutines/coroutines-best-practices?hl=en`
  - Use for dispatcher injection, main-safe suspend functions, `ViewModel` coroutine ownership, immutable exposed state, and Android coroutine testability.

- `https://kotlinlang.org/docs/coroutines-guide.html`
  - Use for coroutine fundamentals, `launch`, `async`, cancellation, Flow, dispatchers, and the language/library boundary.
  - High-value fact: `async` and `await` are not Kotlin keywords and are not part of the standard library; they come from `kotlinx.coroutines`.
  - High-value dependency fact: coroutine examples need `kotlinx-coroutines-core`.

## Jetpack Compose

- `https://developer.android.com/jetpack/compose/architecture?hl=en`
  - Use for unidirectional data flow in Compose, state hoisting, immutable state, event callbacks, and separating UI rendering from state ownership.

## Gradle, setup, and build compatibility

- `https://kotlinlang.org/docs/gradle-configure-project.html`
  - Use for the Kotlin Gradle plugin, supported Gradle and AGP version ranges, literal plugin versions in `plugins {}`, JVM target validation, toolchains, and mixed Java/Kotlin source layout.
  - High-value JVM target fact: if `jvmTarget` is unset, Kotlin effectively targets `1.8`, while Java `targetCompatibility` follows the current Gradle JDK unless a toolchain is configured.
  - High-value validation fact: `kotlin.jvm.target.validation.mode` defaults to `error` on Gradle `8.0+` and `warning` on Gradle versions below `8.0`.
  - High-value toolchain fact: on Gradle `8.0.2+`, auto-download may need a resolver plugin such as `org.gradle.toolchains.foojay-resolver-convention` in `settings.gradle(.kts)`.

### KGP ↔ Gradle/AGP compatibility matrix (from JetBrains docs)

| KGP version       | Gradle min–max | AGP min–max      |
|-------------------|----------------|------------------|
| 2.3.10            | 7.6.3–9.0.0    | 8.2.2–9.0.0      |
| 2.3.0             | 7.6.3–9.0.0    | 8.2.2–8.13.0     |
| 2.2.20–2.2.21     | 7.6.3–8.14     | 7.3.1–8.11.1     |
| 2.2.0–2.2.10      | 7.6.3–8.14     | 7.3.1–8.10.0     |
| 2.1.20–2.1.21     | 7.6.3–8.12.1   | 7.3.1–8.7.2      |
| 2.1.0–2.1.10      | 7.6.3–8.10     | 7.3.1–8.7.2      |
| 2.0.20–2.0.21     | 6.8.3–8.8      | 7.1.3–8.5        |
| 2.0.0             | 6.8.3–8.5      | 7.1.3–8.3.1      |
| 1.9.20–1.9.25     | 6.8.3–8.1.1    | 4.2.2–8.1.0      |

Source: https://kotlinlang.org/docs/gradle-configure-project.html

### AGP minimum Gradle and JDK requirements (from Android docs)

| AGP version | Min Gradle | Default Gradle | Min JDK |
|-------------|-----------|----------------|---------|
| 9.1         | 9.3.1     | 9.3.1          | 17      |
| 9.0         | 8.11.1    | 8.11.1         | 21      |
| 8.9         | 8.10.2    | 8.10.2         | 17      |
| 8.7–8.8     | 8.9       | 8.9            | 17      |
| 8.4–8.6     | 8.6       | 8.6            | 17      |
| 8.2–8.3     | 8.2       | 8.2            | 17      |
| 8.0–8.1     | 8.0       | 8.0            | 17      |
| 7.4         | 7.5       | 7.5            | 11      |

Source: https://developer.android.com/build/releases/gradle-plugin

**How to use this table when diagnosing a build failure:**
1. Check the KGP row to get the KGP-side minimum Gradle and AGP bounds.
2. Check the AGP row to get the AGP-side minimum Gradle requirement.
3. The actual Gradle lower bound is the higher of the two minimums — identify which constraint is binding.
4. Only state version constraints that appear in these tables. Do not invent or interpolate version numbers.

## Testing

- `https://developer.android.com/training/testing/fundamentals?hl=en`
  - Use for local vs instrumented tests, test scope, why automated tests matter, and Android testing terminology.

## Kotlin language and style

- `https://kotlinlang.org/docs/coding-conventions.html`
  - Use for naming, file organization, style, and Kotlin-specific review/refactor guidance.

## How to apply these sources

- Start with Android documentation for app architecture, Compose, lifecycle, and Android-specific coroutine guidance.
- Use Kotlin docs for language behavior, coroutines as a library boundary, Gradle compatibility, and coding style.
- If the answer depends on a version, quote the version range precisely and separate official facts from your recommendation.
- When a user asks an Android question that overlaps with plain Kotlin, explicitly label what is Android-specific and what is language-specific.
