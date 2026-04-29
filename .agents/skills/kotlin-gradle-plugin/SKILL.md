---
name: kotlin-gradle-plugin
description: Kotlin Gradle Plugin (KGP) reference for configuring Kotlin compilation in Gradle projects. Covers applying the plugin, JVM toolchains, compiler options, dependencies, serialization/all-open/KSP plugins, incremental compilation, build caching, the Kotlin daemon, and best practices. Use when configuring a Kotlin project's Gradle build, setting compiler options, migrating from kotlinOptions to compilerOptions, setting up JVM toolchains, optimizing Kotlin build performance, or asked about KGP features like "how to set jvmTarget", "how to configure compiler options", "kotlin incremental compilation", or "migrate kotlinOptions".
---

<essential_principles>

The **Kotlin Gradle Plugin (KGP)** integrates Kotlin compilation into Gradle builds. It shares its version number with Kotlin itself (e.g., KGP 2.3.21 = Kotlin 2.3.21).

### Core Rules an Agent Must Know

- **Apply with `kotlin("jvm")` or `kotlin("multiplatform")`** in the `plugins {}` block. Use version catalogs for consistency.
- **Use `jvmToolchain(17)` in the `kotlin {}` block** to set the JDK for both Kotlin and Java compilation. This is the recommended approach — avoids jvmTarget/targetCompatibility mismatches.
- **Configure compiler options at extension level** via `kotlin { compilerOptions {} }`. Use typed values (`JvmTarget.JVM_17`, `KotlinVersion.KOTLIN_2_3`), not strings.
- **`kotlinOptions {}` is removed.** Use `compilerOptions {}` instead. Key migration: `jvmTarget = "17"` → `jvmTarget.set(JvmTarget.JVM_17)`, `freeCompilerArgs +=` → `freeCompilerArgs.add()`.
- **kotlin-stdlib is added automatically** by KGP since 1.4. Don't declare it manually.
- **Prefer KSP over kapt** for annotation processing — faster, no Java stubs.
- **Incremental compilation is on by default.** Gradle build cache, configuration cache, and parallel execution should all be enabled.
- **Add `.kotlin` to `.gitignore`** — KGP stores per-project data there.
- **Check KGP/Gradle/AGP version compatibility** before upgrading any of the three.

</essential_principles>

<routing>

Based on what you need, read the appropriate reference:

| Topic | Reference |
|-------|-----------|
| Applying the plugin, version compat, JVM target, toolchains, dependencies, serialization/all-open/KSP | `references/configuration.md` |
| Compiler options DSL (extension/target/task level), common + JVM options, `kotlinOptions` migration | `references/compiler-options.md` |
| Incremental compilation, build cache, configuration cache, Kotlin daemon, best practices, build reports | `references/compilation-and-caching.md` |

For initial project setup, start with `references/configuration.md`. For build speed issues, start with `references/compilation-and-caching.md`.

</routing>

<reference_index>

All domain knowledge in `references/`:

**Configuration:** configuration.md — applying KGP, version catalog, KGP/Gradle/AGP compatibility table, JVM targeting, Java toolchains, dependencies (stdlib auto-added, kotlin-test), serialization plugin, all-open/spring plugin, no-arg/JPA plugin, KSP
**Compiler Options:** compiler-options.md — three-level DSL (extension/target/task), common options table, JVM-specific options, `kotlinOptions` → `compilerOptions` migration guide (typed values, Android, freeCompilerArgs)
**Compilation & Caching:** compilation-and-caching.md — incremental compilation (classpath snapshots, fine/coarse-grained), build cache, remote cache, configuration cache, Kotlin daemon JVM args (5-level precedence), best practices checklist, build reports

</reference_index>
