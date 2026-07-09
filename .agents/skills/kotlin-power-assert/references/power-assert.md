# Kotlin Power-Assert Compiler Plugin

<overview>
## What It Does

The Power-assert compiler plugin transforms assertion function calls to produce detailed failure messages showing intermediate values of every sub-expression. **Experimental** status (as of Kotlin 2.4.0).

Example output when `assert(hello.length == world.substring(1, 4).length)` fails:
```
Incorrect length
assert(hello.length == world.substring(1, 4).length) { "Incorrect length" }
       |     |      |  |     |               |
       |     |      |  |     |               3
       |     |      |  |     orl
       |     |      |  world!
       |     |      false
       |     5
       Hello
```
No special assertion library needed — plain `assert()` gives rich diagnostics.
</overview>

<setup>
## Gradle Setup (Kotlin DSL)

```kotlin
// build.gradle.kts
plugins {
    kotlin("multiplatform") version "2.4.0"        // or kotlin("jvm")
    kotlin("plugin.power-assert") version "2.4.0"
}
```

### Configuration
```kotlin
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

@OptIn(ExperimentalKotlinGradlePluginApi::class)
powerAssert {
    // Which functions to transform (default: only kotlin.assert)
    functions = listOf(
        "kotlin.assert",
        "kotlin.require",
        "kotlin.check",
        "kotlin.test.assertTrue",
        "kotlin.test.assertEquals",
        "kotlin.test.assertNull"
    )

    // Which source sets to transform (default: all test source sets)
    includedSourceSets = listOf("commonTest", "jvmTest")
}
```

### Configuration Options

| Option | Default | Description |
|--------|---------|-------------|
| `functions` | `listOf("kotlin.assert")` | Fully-qualified function paths to transform |
| `includedSourceSets` | All test source sets | Gradle source sets where transformation applies |

### `@OptIn` annotation
The plugin is Experimental — without `@OptIn(ExperimentalKotlinGradlePluginApi::class)` before the `powerAssert {}` block, you get warnings on every build.

### Groovy DSL
```groovy
// build.gradle
plugins {
    id 'org.jetbrains.kotlin.multiplatform' version '2.4.0'
    id 'org.jetbrains.kotlin.plugin.power-assert' version '2.4.0'
}

powerAssert {
    functions = ["kotlin.assert", "kotlin.require", "kotlin.test.assertTrue"]
    includedSourceSets = ["commonTest", "jvmTest"]
}
```
</setup>

<supported_functions>
## Supported Functions

Power-assert transforms any function whose **last parameter** is `String` or `() -> String` (the message/lambda). This includes:

| Function | Fully-qualified name |
|----------|---------------------|
| `assert()` | `kotlin.assert` |
| `require()` | `kotlin.require` |
| `check()` | `kotlin.check` |
| `assertTrue()` | `kotlin.test.assertTrue` |
| `assertEquals()` | `kotlin.test.assertEquals` |
| `assertNull()` | `kotlin.test.assertNull` |

**You must add each function to the `functions` list** in `powerAssert {}` — only `kotlin.assert` is transformed by default.

### Custom functions
Any function matching the signature pattern works, including your own:
```kotlin
// Must be added as "com.example.MyScope.myAssert" in the functions list
fun myAssert(condition: Boolean, message: () -> String = { "Assertion failed" }) {
    if (!condition) throw AssertionError(message())
}
```
Use the fully-qualified name including the class for member/extension functions.

**Alternative: `@PowerAssert` annotation (no build-file registration needed).** Since the `kotlin-power-assert-runtime` library, annotating a function with `@PowerAssert` makes the compiler plugin transform calls to it automatically — callers don't need to add it to `functions` in `powerAssert {}` at all. See `<library_authors>` in this file for the full annotation/runtime API.
</supported_functions>

<best_practices>
## Best Practices

### Inline expressions for maximum diagnostic value
**Bad** — variables hide intermediate values:
```kotlin
val isValidName = person.name.startsWith("A") && person.name.length > 3
val isValidAge = person.age in 21..28
assert(isValidName && isValidAge)
// Output: only shows isValidName=true, isValidAge=false
```

**Good** — inline the full expression:
```kotlin
assert(
    person.name.startsWith("A") &&
    person.name.length > 3 &&
    person.age > 20 &&
    person.age < 29
)
// Output: shows every sub-expression value including person.age=10
```

### Use message lambdas for context
```kotlin
assert(user.email.contains("@")) { "Invalid email for user ${user.id}" }
```

### Avoid `require()` with string interpolation in production code
When Power-assert is limited to test source sets (default), `require()` in production code won't be transformed. The `functions` list only affects `includedSourceSets`.
</best_practices>

<soft_assertions>
## Soft Assertions

Collect all failures before reporting (instead of failing on the first):

### 1. Define the soft-assert infrastructure
```kotlin
fun <R> assertSoftly(block: AssertScope.() -> R): R {
    val scope = AssertScopeImpl()
    val result = scope.block()
    if (scope.errors.isNotEmpty()) {
        throw AssertionError(scope.errors.joinToString("\n"))
    }
    return result
}

interface AssertScope {
    fun assert(assertion: Boolean, message: (() -> String)? = null)
}

class AssertScopeImpl : AssertScope {
    val errors = mutableListOf<String>()
    override fun assert(assertion: Boolean, message: (() -> String)?) {
        if (!assertion) {
            errors.add(message?.invoke() ?: "Assertion failed")
        }
    }
}
```

### 2. Register with Power-assert
```kotlin
@OptIn(ExperimentalKotlinGradlePluginApi::class)
powerAssert {
    functions = listOf(
        "kotlin.assert",
        "com.example.AssertScope.assert"  // fully-qualified!
    )
}
```

### 3. Use in tests
```kotlin
@Test
fun `test employees data`() {
    val employees = listOf(
        Employee("Alice", 30, 60000),
        Employee("Bob", 45, 80000),
        Employee("Charlie", 55, 40000),
        Employee("Dave", 150, 70000)
    )

    assertSoftly {
        for (employee in employees) {
            assert(employee.age < 100) { "${employee.name} has invalid age" }
            assert(employee.salary > 50000) { "${employee.name} has invalid salary" }
        }
    }
    // Reports ALL failures, not just the first
}
```
</soft_assertions>

<maven>
## Maven Setup

```xml
<build>
  <plugins>
    <plugin>
      <artifactId>kotlin-maven-plugin</artifactId>
      <groupId>org.jetbrains.kotlin</groupId>
      <version>2.4.0</version>
      <configuration>
        <compilerPlugins>
          <plugin>power-assert</plugin>
        </compilerPlugins>
        <pluginOptions>
          <option>power-assert:function=kotlin.assert</option>
          <option>power-assert:function=kotlin.test.assertTrue</option>
        </pluginOptions>
      </configuration>
      <dependencies>
        <dependency>
          <groupId>org.jetbrains.kotlin</groupId>
          <artifactId>kotlin-maven-power-assert</artifactId>
          <version>2.4.0</version>
        </dependency>
      </dependencies>
    </plugin>
  </plugins>
</build>
```
</maven>

<library_authors>
## Adding Power-assert Support to a Library (`@PowerAssert` Annotation & `CallExplanation`)

Library authors can make their own assertion functions Power-assert-capable **without requiring callers to register them** in `functions = listOf(...)`. This is a separate, newer mechanism from the manual `functions` list registration above — both still work, and can be mixed.

### Setup
- Apply the Power-assert compiler plugin as usual.
- **Gradle** automatically adds the `kotlin-power-assert-runtime` dependency alongside the compiler plugin.
- **Maven** requires adding it explicitly:
  ```xml
  <dependency>
      <groupId>org.jetbrains.kotlin</groupId>
      <artifactId>kotlin-power-assert-runtime</artifactId>
      <version>2.4.0</version>
  </dependency>
  ```

### `@PowerAssert` annotation
Annotate the assertion function itself. Any caller with the Power-assert compiler plugin enabled gets automatic transformation — no entry in `powerAssert { functions = ... }` needed:

```kotlin
import kotlin.powerassert.PowerAssert
import kotlin.powerassert.toDefaultMessage
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

@OptIn(ExperimentalContracts::class)
@PowerAssert
fun powerAssert(condition: Boolean, @PowerAssert.Ignore message: String? = null) {
    contract { returns() implies condition }
    if (!condition) {
        val explanation = PowerAssert.explanation ?: fail(message)
        val failureMessage = buildString {
            if (message?.isNotBlank() == true) appendLine(message)
            append(explanation.toDefaultMessage())
        }
        fail(failureMessage)
    }
}
```

- **`@PowerAssert.Ignore`** on a parameter (e.g. the `message`) excludes it from the generated failure message.
- **`PowerAssert.explanation`** (inside the annotated function body) returns the `CallExplanation` for the current call, or `null` if the caller has no Power-assert plugin, calls from Java, or calls via reflection — always null-check it.
- **`explanation.toDefaultMessage()`** renders the same diagram-style message the plugin normally produces (the `|`/values tree).

### `CallExplanation`
Exposes the call site's sub-expressions for building custom messages instead of the default renderer:

```kotlin
@PowerAssert
fun AssertScope<*>.check(condition: Boolean) {
    if (!condition) {
        val explanation = PowerAssert.explanation
        val message = explanation?.let {
            val conditionArg = it.arguments.last()!!
            val source = it.source.substring(conditionArg.startOffset, conditionArg.endOffset)
            "Condition failed: $source"
        }
        collect(message, explanation)
    }
}
```

Key members: `explanation.expressions` (list of captured sub-expressions, including `EqualityExpression` for `==`/`!=` comparisons — useful for filtering to just the failing equality checks), `explanation.arguments` (the call's argument expressions), `explanation.source` + argument `startOffset`/`endOffset` (extract the original source text for any sub-expression).

**When to reach for this vs. the `functions` list:** use `functions` registration for functions you don't own (stdlib, third-party) or one-off test helpers; use `@PowerAssert` when you're the author of a reusable assertion library and want it Power-assert-capable out of the box for every consumer.
</library_authors>
