# Kotlin Advanced Features

<operator_overloading>
## Operator Overloading

Define custom behavior for operators via `operator` member or extension functions:

### Operator → Function Mapping

| Expression | Function | Category |
|-----------|----------|----------|
| `+a` / `-a` / `!a` | `unaryPlus()` / `unaryMinus()` / `not()` | Unary prefix |
| `a++` / `a--` | `inc()` / `dec()` | Increment/decrement |
| `a + b` | `a.plus(b)` | Arithmetic |
| `a - b` | `a.minus(b)` | Arithmetic |
| `a * b` | `a.times(b)` | Arithmetic |
| `a / b` | `a.div(b)` | Arithmetic |
| `a % b` | `a.rem(b)` | Arithmetic |
| `a..b` | `a.rangeTo(b)` | Range |
| `a..<b` | `a.rangeUntil(b)` | Open range |
| `a in b` | `b.contains(a)` | Containment |
| `a[i]` | `a.get(i)` | Indexed access |
| `a[i] = b` | `a.set(i, b)` | Indexed access |
| `a()` | `a.invoke()` | Invoke |
| `a += b` | `a.plusAssign(b)` | Augmented assignment |
| `a == b` | `a?.equals(b) ?: (b === null)` | Equality |
| `a > b` | `a.compareTo(b) > 0` | Comparison |

```kotlin
data class Vector(val x: Double, val y: Double) {
    operator fun plus(other: Vector) = Vector(x + other.x, y + other.y)
    operator fun times(scalar: Double) = Vector(x * scalar, y * scalar)
    operator fun unaryMinus() = Vector(-x, -y)
}

val v = Vector(1.0, 2.0) + Vector(3.0, 4.0)  // Vector(4.0, 6.0)
```
</operator_overloading>

<equality>
## Equality

| Operator | Name | Checks |
|----------|------|--------|
| `==` / `!=` | Structural equality | Calls `equals()` — content comparison |
| `===` / `!==` | Referential equality | Same object in memory (identity) |

```kotlin
val a = listOf(1, 2, 3)
val b = listOf(1, 2, 3)
println(a == b)   // true — same content
println(a === b)  // false — different instances
```

- `data class` auto-generates `equals()` from primary constructor properties.
- When overriding `equals()`, always override `hashCode()` too.
- `==` is null-safe: `null == null` is `true`, `null == "x"` is `false`.
- `===` / `!==` cannot be overloaded.
</equality>

<exceptions>
## Exception Handling

**All exceptions in Kotlin are unchecked** — no checked exceptions. No `throws` declaration required.

```kotlin
// Throw
throw IllegalArgumentException("Invalid input: $input")

// Try-catch-finally
try {
    riskyOperation()
} catch (e: IOException) {
    logger.error("IO failed", e)
} catch (e: Exception) {
    logger.error("Unexpected", e)
} finally {
    cleanup()  // Always runs
}

// Try as expression
val result = try { parseInt(input) } catch (e: NumberFormatException) { -1 }
```

### Precondition Functions

| Function | Exception | Use case |
|----------|-----------|----------|
| `require(condition) { msg }` | `IllegalArgumentException` | Validate input arguments |
| `check(condition) { msg }` | `IllegalStateException` | Validate object state |
| `error(msg)` | `IllegalStateException` | Indicate illegal state unconditionally |

```kotlin
fun process(age: Int) {
    require(age >= 0) { "Age must be non-negative: $age" }
    check(isInitialized) { "Must call init() first" }
}
```

### `Nothing` Type

Functions that never return have return type `Nothing`:

```kotlin
fun fail(message: String): Nothing {
    throw IllegalArgumentException(message)
}

// Useful with elvis operator:
val name = person.name ?: fail("Name required")
```
</exceptions>

<destructuring>
## Destructuring Declarations

Unpack objects into multiple variables via `componentN()` functions:

```kotlin
// Data classes provide componentN() automatically
data class User(val name: String, val age: Int)
val (name, age) = User("Alice", 30)

// In for-loops
for ((key, value) in map) { println("$key = $value") }

// In lambdas
map.forEach { (key, value) -> println("$key = $value") }

// Skip with underscore
val (_, status) = getResult()
```

Compiles to: `val name = user.component1(); val age = user.component2()`

Non-data classes can opt in by declaring `operator fun componentN()` methods.
</destructuring>

<annotations>
## Annotations

```kotlin
// Declare
annotation class Fancy

// With parameters
annotation class Special(val why: String)

// Meta-annotations
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class JsonName(val name: String)

// Use
@JsonName("user_name")
val userName: String = ""

// Annotate primary constructor
class Foo @Inject constructor(val dep: Dependency)

// Use-site targets (disambiguate where annotation applies)
class Example(
    @field:JsonName("name") val name: String,   // Targets backing field
    @get:JsonName("age") val age: Int            // Targets getter
)
```

### Use-Site Targets

| Target | Applies to |
|--------|-----------|
| `@field:` | JVM backing field |
| `@get:` / `@set:` | Property getter/setter |
| `@param:` | Constructor parameter |
| `@file:` | File-level (e.g., `@file:JvmName("Utils")`) |
| `@receiver:` | Extension receiver |
| `@delegate:` | Delegate field |

Allowed parameter types: primitives, strings, classes (`Foo::class`), enums, other annotations, and arrays of these.
</annotations>

<type_aliases>
## Type Aliases

Create alternative names for existing types:

```kotlin
typealias StringMap = Map<String, String>
typealias Predicate<T> = (T) -> Boolean
typealias NodeSet = Set<Network.Node>

val isEven: Predicate<Int> = { it % 2 == 0 }
```

Type aliases don't create new types — they're interchangeable with the original type at compile time.
</type_aliases>
