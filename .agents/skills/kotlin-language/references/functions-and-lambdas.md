# Kotlin Functions & Lambdas

<functions>
## Functions

```kotlin
// Basic function
fun sum(a: Int, b: Int): Int {
    return a + b
}

// Single-expression function (return type inferred)
fun sum(a: Int, b: Int) = a + b

// Default parameters
fun greet(name: String, greeting: String = "Hello") = "$greeting, $name!"

// Named arguments
greet(name = "Alice", greeting = "Hi")

// Unit-returning (void equivalent — Unit can be omitted)
fun log(message: String) {
    println(message)
}

// vararg
fun printAll(vararg items: String) {
    items.forEach { println(it) }
}
printAll("a", "b", "c")
```

### Extension Functions

Add functions to existing types without inheriting:

```kotlin
fun String.addExclamation() = "$this!"
println("Hello".addExclamation())  // "Hello!"

fun <T> MutableList<T>.swap(i: Int, j: Int) {
    val tmp = this[i]
    this[i] = this[j]
    this[j] = tmp
}
```

Extensions are resolved **statically** (based on declared type, not runtime type).

### Infix Functions

```kotlin
infix fun Int.shl(x: Int): Int = this.shl(x)
val result = 1 shl 2  // Calls 1.shl(2)

// Built-in examples:
val pair = "key" to "value"  // Pair
val range = 1..10            // IntRange
```

### Local Functions

```kotlin
fun outer() {
    fun inner(x: Int) = x * 2  // Can access outer's variables
    println(inner(5))
}
```
</functions>

<lambdas>
## Lambda Expressions

```kotlin
// Full syntax
val sum: (Int, Int) -> Int = { x: Int, y: Int -> x + y }

// Type inference
val sum = { x: Int, y: Int -> x + y }

// Single parameter — implicit `it`
val double = { it: Int -> it * 2 }
listOf(1, 2, 3).filter { it > 1 }

// Last expression is the return value
val transform = { x: Int ->
    val doubled = x * 2
    doubled + 1  // This is the return value
}
```

### Trailing Lambda Convention

If the last parameter is a function, the lambda can go outside parentheses:

```kotlin
items.fold(0) { acc, elem -> acc + elem }

// If lambda is the only argument, parentheses can be omitted:
run { println("hello") }
```

### Underscore for Unused Parameters

```kotlin
map.forEach { (_, value) -> println(value) }
```

### Destructuring in Lambdas

```kotlin
map.forEach { (key, value) -> println("$key = $value") }
```
</lambdas>

<higher_order>
## Higher-Order Functions

Functions that take functions as parameters or return functions:

```kotlin
fun <T> List<T>.customFilter(predicate: (T) -> Boolean): List<T> {
    val result = mutableListOf<T>()
    for (item in this) {
        if (predicate(item)) result.add(item)
    }
    return result
}

listOf(1, 2, 3, 4).customFilter { it % 2 == 0 }  // [2, 4]
```

### Function Types

```kotlin
(Int, String) -> Boolean      // Takes Int and String, returns Boolean
() -> Unit                    // No params, returns Unit
(String) -> (Int) -> String   // Returns a function
suspend () -> Unit            // Suspending function type
String.(Int) -> String        // Extension function type (receiver)
```

### Function References

```kotlin
fun isOdd(x: Int) = x % 2 != 0
listOf(1, 2, 3).filter(::isOdd)         // Top-level function reference
listOf("a", "b").map(String::uppercase)  // Member reference
```

### Anonymous Functions

```kotlin
val double = fun(x: Int): Int = x * 2
ints.filter(fun(item) = item > 0)
```

Difference from lambdas: `return` returns from the anonymous function itself, not from the enclosing function.

### Closures

Lambdas and local functions can capture and modify variables from the enclosing scope:

```kotlin
var sum = 0
listOf(1, 2, 3).forEach { sum += it }
println(sum)  // 6
```
</higher_order>

<inline_functions>
## Inline Functions

`inline` eliminates lambda allocation overhead by inlining the function body at call sites:

```kotlin
inline fun <T> measureTime(block: () -> T): T {
    val start = System.nanoTime()
    val result = block()
    println("Took ${System.nanoTime() - start}ns")
    return result
}
```

Use `noinline` to prevent inlining specific lambda parameters. Use `crossinline` to prevent non-local returns from a lambda.

### Non-local `break`/`continue` (Kotlin 2.1+, Stable)

A lambda passed to an inline function can `break`/`continue` a loop enclosing the call, not just non-locally `return` (which was already supported):

```kotlin
fun processList(numbers: List<Int>) {
    for (number in numbers) {
        number.takeIf { it > 0 } ?: continue   // non-local continue into the enclosing for-loop
        if (number > 100) break                 // non-local break, from inside an inline lambda
        println(number)
    }
}
```
Only works through `inline` functions (including standard library ones like `let`, `run`, `forEach`) — `crossinline` lambdas still can't use it, same restriction as non-local `return`.

### Reified Type Parameters

Only available in `inline` functions — retains type info at runtime:

```kotlin
inline fun <reified T> isInstance(value: Any) = value is T
println(isInstance<String>("hello"))  // true
```
</inline_functions>

<context_parameters>
## Context Parameters (Kotlin 2.4+, Stable)

Declare values (services, configuration, scopes) that a function or property needs from its calling context, without threading them through every call as an explicit parameter. Replaces the older experimental **context receivers** — the key difference is that context parameters are *not* implicit receivers inside the function body, so you reference them by name rather than getting implicit `this`-style access to their members.

```kotlin
interface UserService {
    fun currentUser(): String
}

// Declares a function with a context parameter
context(service: UserService)
fun greetCurrentUser(): String = "Hello, ${service.currentUser()}!"

// Declares a property with a context parameter
context(service: UserService)
val currentUserName: String
    get() = service.currentUser()
```

Call a context-parameter function from inside a scope that provides a matching context parameter (or explicitly, since Kotlin 2.4, via an explicit context argument — Experimental):
```kotlin
context(service: UserService)
fun run() {
    println(greetCurrentUser())   // service is available implicitly by declaration, used by name inside
}
```

Use `_` as the parameter name when the value only needs to be available for resolution, not accessed by name:
```kotlin
context(_: UserService)
fun requiresServiceButDoesNotUseItByName() { /* ... */ }
```

**When to reach for this over a plain parameter or DI framework:** context parameters shine for cross-cutting dependencies threaded through many functions in a call chain (logging scope, transaction/session objects, DSL builders) — for a single function's one-off dependency, a normal parameter is simpler and clearer.
</context_parameters>
