---
name: kotlin-power-assert
description: Kotlin Power-assert compiler plugin reference for enhanced test assertion diagnostics. Covers applying the plugin (Gradle/Maven), configuring transformed functions and source sets, supported function signatures, best practices for maximizing diagnostic output, soft assertions pattern, custom assertion functions, and the `@PowerAssert` annotation/`CallExplanation` runtime API for library authors. Use when setting up Power-assert, asking about "power-assert plugin", "detailed assertion messages", "assert intermediate values", "soft assertions in Kotlin", "powerAssert block configuration", "@PowerAssert annotation", "CallExplanation", or any Kotlin Power-assert topic.
---

<essential_principles>

**Power-assert** is a Kotlin compiler plugin (Experimental) that transforms assertion calls to show every sub-expression's value on failure. No special assertion library needed.

### What an Agent Must Know

- **Plugin ID:** `kotlin("plugin.power-assert")` — version must match the Kotlin version (current: 2.4.0).
- **Default:** only `kotlin.assert()` is transformed. Add others via `powerAssert { functions = listOf(...) }`.
- **Default scope:** all test source sets. Narrow with `includedSourceSets`.
- **`@OptIn(ExperimentalKotlinGradlePluginApi::class)`** before `powerAssert {}` suppresses experimental warnings.
- **Inline expressions** in the assertion call for maximum diagnostic value. Variables hide intermediate values.
- **Any function** whose last parameter is `String` or `() -> String` can be transformed — `require()`, `check()`, `assertTrue()`, custom functions.
- **Custom functions** must be registered by fully-qualified name (e.g., `com.example.AssertScope.assert`) — **or** annotated `@PowerAssert` so callers get transformation automatically with no registration at all (library-author feature, via the `kotlin-power-assert-runtime` library).
- **`@PowerAssert`-annotated functions** read `PowerAssert.explanation` (a nullable `CallExplanation`) to build custom messages via `.toDefaultMessage()` or by walking `.expressions`/`.arguments`/`.source`; `@PowerAssert.Ignore` excludes a parameter (e.g. `message`) from the rendered output.
- **Soft assertions** collect all failures before reporting — useful for data-driven tests.

</essential_principles>

<routing>

| Topic | Reference |
|-------|-----------|
| All topics: setup (Gradle/Maven), configuration options, supported functions, best practices, soft assertions, custom functions, `@PowerAssert` annotation and `CallExplanation` runtime API for library authors | `references/power-assert.md` |

This is a single-reference skill. Read `references/power-assert.md` for any Power-assert question.

</routing>

<reference_index>

**power-assert.md** — overview and output example, Gradle setup (Kotlin DSL + Groovy DSL), `powerAssert {}` configuration block (`functions` list, `includedSourceSets`), `@OptIn` annotation for experimental warnings, supported functions table (assert/require/check/assertTrue/assertEquals/assertNull), custom function registration (fully-qualified names, signature requirement), best practices (inline expressions for max diagnostics, message lambdas, source set scoping), soft assertions pattern (AssertScope interface, assertSoftly wrapper, registration in `powerAssert.functions`), Maven setup (kotlin-maven-plugin + kotlin-maven-power-assert dependency, pluginOptions), library-author support via the `@PowerAssert` annotation and `CallExplanation`/`kotlin-power-assert-runtime` (auto-transformation without caller-side `functions` registration, `@PowerAssert.Ignore`, `PowerAssert.explanation`, `toDefaultMessage()`, `EqualityExpression`, when to use this vs. manual registration)

</reference_index>
