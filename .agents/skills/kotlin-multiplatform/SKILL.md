---
name: kotlin-multiplatform
description: Work with Kotlin Multiplatform (KMP) projects — set up modules, write expect/actual declarations, configure targets and source sets, add dependencies, and navigate the source set hierarchy. Use when asked to "add a KMP module", "share code across platforms", "write expect/actual", "add a target", "add a multiplatform dependency", or when working in a Kotlin Multiplatform codebase.
---

<essential_principles>

**Kotlin Multiplatform (KMP)** shares code across Android, iOS, desktop, web, and server using a single Kotlin codebase. Code is organized into **source sets** that compile to declared **targets**.

### Source Set Hierarchy

- `commonMain` compiles to **all** declared targets — no platform-specific APIs allowed here
- Platform source sets (`androidMain`, `iosMain`, `jvmMain`) compile to one target or target group
- Intermediate source sets (`appleMain`, `nativeMain`, `iosMain`) share code among a subset of targets
- Each `*Main` source set has a matching `*Test` source set
- Platform source sets can access `commonMain` declarations, but not vice versa

### Expect/Actual Mechanism

The `expect`/`actual` mechanism is how common code accesses platform-specific APIs:
- `expect` in `commonMain` — declares the API contract, no implementation
- `actual` in each platform source set — provides the platform-specific implementation
- Both must be in the **same package**

**Prefer interfaces + factory functions** over expect classes. They're more flexible, easier to test, and don't require Beta flags.

### Dependencies

- KMP libraries go in `commonMain` using the **base artifact name** — Gradle resolves platform variants
- Platform-specific libraries go in the matching platform source set
- You **cannot** add platform-only libraries to `commonMain`
- Use `libs.versions.toml` for version management

### Naming

- Platform prefix for actual classes: `AndroidBleTransport`, `IosCryptoProvider`
- Use `Ios` prefix, not `IOS` or `iOS`
- Source set directories match target names: `androidMain/`, `iosMain/`

</essential_principles>

<routing>

Based on the task, read the appropriate workflow:

| Task | Workflow |
|------|----------|
| Set up a new KMP module or project | `workflows/setup-module.md` |
| Write expect/actual declarations for platform APIs | `workflows/add-expect-actual.md` |
| Add a library dependency | `workflows/add-dependency.md` |
| Add or configure a compilation target | `workflows/add-target.md` |
| Understand source set hierarchy or project structure | Read `references/project-structure.md` directly |
| Understand Gradle configuration patterns | Read `references/gradle-config.md` directly |

If the task involves multiple areas (e.g., "add iOS support" requires both a target and expect/actual implementations), combine the relevant workflows in sequence.

</routing>

<reference_index>

All domain knowledge in `references/`:

**Structure:** project-structure.md — source sets, targets, hierarchy, directory layout
**Expect/Actual:** expect-actual.md — patterns, rules, naming, type aliases
**Dependencies:** dependencies.md — common deps, platform deps, version catalogs, popular libraries
**Gradle:** gradle-config.md — plugin setup, target declaration, compiler options, iOS framework export

</reference_index>

<workflows_index>

| Workflow | Purpose |
|----------|---------|
| setup-module.md | Create a new KMP module from scratch |
| add-expect-actual.md | Define platform abstractions with expect/actual |
| add-dependency.md | Add multiplatform or platform-specific dependencies |
| add-target.md | Declare a new compilation target and wire it up |

</workflows_index>
