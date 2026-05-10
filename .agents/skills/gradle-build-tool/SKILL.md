---
name: gradle-build-tool
description: Gradle build tool reference for configuring builds, managing dependencies, writing tasks, and optimizing build performance. Covers Kotlin DSL, build scripts, settings files, version catalogs, multi-project builds, plugins, the build lifecycle, CLI commands, and caching. Use when writing or editing Gradle build files, debugging build failures, adding dependencies, configuring plugins, setting up multi-project builds, or asked about Gradle features like "how do I add a dependency", "what's the build lifecycle", "how do multi-project builds work", or "how to speed up Gradle builds".
---

<essential_principles>

**Gradle** automates building, testing, and deployment using build scripts written in Kotlin DSL (`.gradle.kts`) or Groovy DSL (`.gradle`). Prefer Kotlin DSL for type safety and IDE support.

### Core Rules an Agent Must Know

- **Always use the wrapper** (`./gradlew`, not `gradle`). It ensures consistent Gradle version across environments.
- **Three build phases:** Initialization (find projects) → Configuration (evaluate build scripts, build task graph) → Execution (run tasks). Code in `doLast {}` / `doFirst {}` runs in execution; code directly in task config runs in configuration.
- **`implementation` by default.** Use `api` only when a dependency's types appear in your library's public API. Use `compileOnly` for annotation processors and compile-time-only deps.
- **Version catalogs** (`gradle/libs.versions.toml`) centralize dependency versions. Reference via `libs.some.library` in build scripts.
- **Incremental builds** — Gradle skips tasks whose inputs/outputs haven't changed (`UP-TO-DATE`). Always declare task inputs/outputs.
- **`plugins {}` block** is the preferred way to apply plugins. Use `alias(libs.plugins.x)` with version catalogs.
- **Multi-project builds** use `include()` in `settings.gradle.kts`. Cross-project deps via `project(":subproject")`.
- **Convention plugins** (in `buildSrc/` or `build-logic/`) share build logic across subprojects — don't repeat configuration.
- **Enable caching and parallelism** in `gradle.properties`: `org.gradle.parallel=true`, `org.gradle.caching=true`, `org.gradle.configuration-cache=true`.

</essential_principles>

<routing>

Based on what you need, read the appropriate reference:

| Topic | Reference |
|-------|-----------|
| Project structure, settings file, build file, wrapper, `gradle.properties` | `references/core-concepts.md` |
| Build lifecycle (init/config/exec), tasks, custom tasks, incremental builds | `references/lifecycle-and-tasks.md` |
| Dependencies, configurations, version catalog, repositories, constraints | `references/dependencies.md` |
| Plugins, Kotlin DSL tips, multi-project builds, convention plugins, composite builds | `references/plugins-and-config.md` |
| CLI commands, flags, performance tuning, caching, daemon, troubleshooting | `references/cli-and-performance.md` |

For general Gradle tasks, read `references/core-concepts.md` first — it covers the foundational structure. Load additional references as needed.

</routing>

<reference_index>

All domain knowledge in `references/`:

**Core:** core-concepts.md — project structure, settings file, build file, wrapper, gradle.properties, core concepts table
**Lifecycle:** lifecycle-and-tasks.md — three build phases, running tasks, common tasks, registering custom tasks, inputs/outputs, up-to-date checks
**Dependencies:** dependencies.md — declaring deps, configurations table, version catalog (libs.versions.toml), repositories, inspecting deps, constraints, conflict resolution
**Plugins:** plugins-and-config.md — applying plugins, common plugins table, Kotlin DSL tips, multi-project builds, convention plugins, composite builds
**CLI:** cli-and-performance.md — essential commands, useful flags, task abbreviation, build cache, config cache, parallel execution, daemon, troubleshooting

</reference_index>
