# Kotlin Idioms & Patterns

<idioms>
## Idiomatic Kotlin

### Data Classes for DTOs

```kotlin
data class Customer(val name: String, val email: String)
// Gets equals(), hashCode(), toString(), copy(), componentN() for free
```

### Default Parameters Over Overloads

```kotlin
fun format(value: Double, decimals: Int = 2, prefix: String = "$") =
    "$prefix${"%.${decimals}f".format(value)}"
```

### Single-Expression Functions

```kotlin
fun transform(color: String): Int = when (color) {
    "Red" -> 0
    "Green" -> 1
    "Blue" -> 2
    else -> throw IllegalArgumentException("Invalid color: $color")
}
```

### `when` as Expression

```kotlin
val description = when {
    x > 0 -> "positive"
    x == 0 -> "zero"
    else -> "negative"
}
```

### String Interpolation

```kotlin
println("Name: $name, Age: ${person.age}")
```

### Read-Only Collections by Default

```kotlin
val list = listOf("a", "b", "c")       // Immutable
val map = mapOf("a" to 1, "b" to 2)    // Immutable
val mutable = mutableListOf(1, 2, 3)   // Mutable when needed
```

### Filtering and Mapping

```kotlin
val positives = list.filter { it > 0 }
val names = users.map { it.name }
val grouped = items.groupBy { it.category }

// Chain operations
fruits
    .filter { it.startsWith("a") }
    .sortedBy { it }
    .map { it.uppercase() }
    .forEach { println(it) }
```

### Lazy Property Initialization

```kotlin
val expensiveValue: String by lazy {
    computeExpensiveString()
}
```

### Extension Functions for Readability

```kotlin
fun String.isEmail() = matches(Regex("^[\\w.]+@[\\w.]+$"))
```

### `require` / `check` for Preconditions

```kotlin
fun process(age: Int) {
    require(age >= 0) { "Age must be non-negative: $age" }
    check(isInitialized) { "Must call init() first" }
}
```

### `use` for Auto-Closing Resources

```kotlin
File("data.txt").bufferedReader().use { reader ->
    println(reader.readText())
}
```

### Inline Value Classes for Type Safety

```kotlin
@JvmInline
value class UserId(val id: String)

@JvmInline
value class OrderId(val id: String)
// Compiler prevents mixing these up — zero runtime overhead
```

### `TODO()` for Incomplete Code

```kotlin
fun calculateTax(): BigDecimal = TODO("Waiting for accounting feedback")
// Always throws NotImplementedError — shows in IDE TODO window
```

### Swap Variables

```kotlin
var a = 1; var b = 2
a = b.also { b = a }
```
</idioms>

<control_flow>
## Control Flow Patterns

### `if` as Expression

```kotlin
val max = if (a > b) a else b
```

### `when` (Exhaustive Pattern Matching)

```kotlin
when (x) {
    1 -> print("one")
    2, 3 -> print("two or three")
    in 4..10 -> print("in range")
    is String -> print("is string: ${x.length}")
    !is Int -> print("not an int")
    else -> print("other")
}
```

### Ranges and Progressions

```kotlin
for (i in 1..5) { }           // 1, 2, 3, 4, 5
for (i in 1..<5) { }          // 1, 2, 3, 4 (open-ended)
for (i in 5 downTo 1) { }     // 5, 4, 3, 2, 1
for (i in 1..10 step 2) { }   // 1, 3, 5, 7, 9
if (x in 1..100) { }          // Range check
```

### `for` Loops

```kotlin
for (item in collection) { }
for ((index, value) in collection.withIndex()) { }
for ((key, value) in map) { }
```

### Try-Catch as Expression

```kotlin
val result = try {
    parseInt(input)
} catch (e: NumberFormatException) {
    -1
}
```
</control_flow>

<collections>
## Collections Quick Reference

| Create | Type |
|--------|------|
| `listOf(1, 2, 3)` | Immutable `List<Int>` |
| `mutableListOf(1, 2, 3)` | Mutable `MutableList<Int>` |
| `setOf("a", "b")` | Immutable `Set<String>` |
| `mapOf("a" to 1)` | Immutable `Map<String, Int>` |
| `buildList { add(1); add(2) }` | Builder pattern |

### Common Operations

```kotlin
list.filter { it > 0 }          // Filter
list.map { it * 2 }             // Transform
list.flatMap { it.children }    // Flatten nested
list.associate { it to it.name } // To map
list.groupBy { it.category }    // Group
list.partition { it > 0 }       // Split into (matching, rest)
list.any { it > 5 }             // Any match?
list.all { it > 0 }             // All match?
list.none { it < 0 }            // None match?
list.first { it > 3 }           // First matching (throws if none)
list.firstOrNull { it > 3 }     // First matching or null
list.fold(0) { acc, x -> acc + x }  // Reduce with initial
list.reduce { acc, x -> acc + x }   // Reduce (first element as initial)
list.zip(other)                  // Pair elements
list.chunked(3)                  // Split into chunks
list.windowed(3)                 // Sliding window
```

### Sequences (Lazy Evaluation)

```kotlin
list.asSequence()
    .filter { it > 0 }
    .map { it * 2 }
    .take(5)
    .toList()  // Terminal operation triggers evaluation
```

Use sequences for large collections or expensive chains to avoid creating intermediate lists.
</collections>
