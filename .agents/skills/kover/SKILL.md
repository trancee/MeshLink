---
name: kover
description: Kover Gradle Plugin reference for Kotlin/JVM code coverage measurement and reporting. Covers applying the plugin, single/multi-module JVM and KMP projects, Android build variant support, merged reports across modules (manual `kover()` dependencies and the `merge {}` auto-merge shortcut), verification rules (line/branch/instruction coverage with min/max bounds, warning-instead-of-failure, per-rule disable), HTML/XML/binary/log reports, report filtering (by class, package, project, annotation, inheritance, Android-generated shortcut), source set exclusion, instrumentation configuration (include/exclude classes), and JaCoCo integration. Use when configuring code coverage for a Kotlin project, writing coverage verification rules, asking about "koverVerify", "koverHtmlReport", "how to set minimum coverage", "exclude class from coverage", "merge coverage across modules", or any Kover Gradle Plugin topic.
---

<essential_principles>

**Kover** is the official Kotlin code coverage tool. The Gradle plugin instruments JVM bytecode during test execution and generates coverage reports. Version 0.9.8.

### Core Rules an Agent Must Know

- **Apply with `id("org.jetbrains.kotlinx.kover") version "0.9.8"`** in `plugins {}`. In multi-module builds, apply in root (version inherited by submodules).
- **JVM-only coverage.** JS and Native targets are not measured. KMP projects only get coverage for common + JVM source sets.
- **Android: local unit tests only.** On-device instrumented tests are NOT supported.
- **Report tasks auto-trigger tests.** Running `koverHtmlReport` runs all relevant test tasks first.
- **Multi-module merging** uses `kover(project(":other"))` dependencies in a merging module (typically root), or the `merge { subprojects() }` shortcut for the same effect — but `merge {}` breaks project isolation and the configuration cache. Use full task paths like `:koverVerify`.
- **Android build variants** create matching report variants. Append variant name to tasks: `koverVerifyDebug`.
- **Verification rules** check coverage bounds. Default: `COVERED_PERCENTAGE` of `LINE` grouped by `APPLICATION`. Use `minBound(100)` for 100% line coverage. Set `warningInsteadOfFailure = true` to log instead of fail; set a rule's `disabled = true` to keep it defined but skipped.
- **`verify {}` replaces rules; `verifyAppend {}` adds.** Same for `filters {}` vs `filtersAppend {}`.
- **Filters: excludes beat includes.** Use `classes(...)` for fully-qualified names and `packages(...)` for a package plus subpackages, both with `*`/`?` wildcards. `annotatedBy` and `inheritedFrom` are Kover-only (not JaCoCo). `androidGeneratedClasses()` is a one-call shortcut excluding typical Android-generated classes.
- **Instrumentation issues** (`VerifyError`, `No instrumentation registered!`) — exclude the class from instrumentation. Side effect: 0% coverage for that class. `excludedClasses` always wins over `includedClasses` where both match.
- **`require()` with string interpolation** creates uncoverable bytecode branches. Use explicit `if (...) throw` instead.
- **The `test` source set is excluded by default.** Use `sources { excludedSourceSets }` for additional exclusions, or `excludeJava = true` to drop Java-compiled classes in mixed Java/Kotlin projects.
- **`kover { disable() }`** turns off Kover entirely for a project — useful for isolating whether Kover itself caused a build/test problem.

</essential_principles>

<routing>

Based on what you need, read the appropriate reference:

| Topic | Reference |
|-------|-----------|
| Applying the plugin, project types (single/multi-module, JVM/KMP/Android), merging modules, `kover()` dependencies, the `merge {}` auto-merge shortcut, custom report variants (`createVariant`/`copyVariant`/`providedVariant`/`totalVariant`), disabling Kover entirely, Gradle tasks | `references/setup.md` |
| Verification rules, coverage units (LINE/BRANCH/INSTRUCTION), aggregation types, grouping (APPLICATION/CLASS/PACKAGE), rule scoping (total/named/all variants), verify vs verifyAppend, warning-instead-of-failure, per-rule disable, concise bound shortcuts | `references/verification.md` |
| Report configuration (HTML/XML/binary/log), report colors, filtering (classes/packages/annotatedBy/inheritedFrom/projects, `androidGeneratedClasses()` shortcut), wildcard syntax, filter scoping and priority, source set exclusion (including `excludeJava`), instrumentation config and troubleshooting (`excludedClasses`/`includedClasses`), JaCoCo integration | `references/reports-and-filtering.md` |

For initial setup, start with `references/setup.md`. For "how to require 100% coverage", go to `references/verification.md`.

</routing>

<reference_index>

All domain knowledge in `references/`:

**Setup:** setup.md — applying plugin (plugins DSL, legacy, submodule inheritance), project types (single-module JVM, single-module KMP, multi-module JVM, multi-module KMP, single-module Android, multi-module Android, mixed KMP Android+JVM), merging module pattern with `kover()` dependencies, the `merge {}` auto-merge shortcut (`subprojects()`/`allProjects()`/`projects(...)`, per-project `sources`/`instrumentation`/`createVariant`, project-isolation and config-cache caveat), custom report variants (`createVariant`, `copyVariant`, `providedVariant`, `totalVariant`), disabling Kover entirely (`disable()`), report variant naming (total vs named), Gradle tasks table (koverHtmlReport, koverXmlReport, koverVerify, koverBinaryReport, koverLog), named variant task naming convention
**Verification:** verification.md — coverage units (LINE, INSTRUCTION, BRANCH), aggregation types (COVERED_PERCENTAGE, MISSED_PERCENTAGE, COVERED_COUNT, MISSED_COUNT), grouping entity types (APPLICATION, CLASS, PACKAGE), writing rules (minBound, bound block with minValue/maxValue/coverageUnits/aggregationForGroup), rule scoping (all variants, total only, named variant only), verify vs verifyAppend, warning-instead-of-failure, per-rule `disabled`, concise `minBound`/`maxBound`/`bound` shortcuts with inline units/aggregation
**Reports & Filtering:** reports-and-filtering.md — report task configuration (html, xml, log, binary with title/onCheck/file paths/format), HTML report colors (green/red/yellow), binary reports (IC format), additional binary reports, filtering (classes/packages/annotatedBy/inheritedFrom/projects, `androidGeneratedClasses()` shortcut, wildcards `*`/`?`, excludes priority over includes), per-variant filter overriding, filters vs filtersAppend, source set exclusion (excludedSourceSets/includedSourceSets/excludeJava), instrumentation (excludedClasses, includedClasses, disabledForTestTasks, disabledForAll, error messages), JaCoCo integration (useJacoco, limitations)

</reference_index>
