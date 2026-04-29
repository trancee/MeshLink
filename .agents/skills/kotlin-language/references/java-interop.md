# Kotlin-Java Interoperability

<calling_java>
## Calling Java from Kotlin

Pretty much all Java code works in Kotlin without changes.

### Getters/Setters Become Properties

Java `getX()`/`setX()` methods appear as Kotlin properties:

```kotlin
// Java: calendar.getFirstDayOfWeek() / calendar.setFirstDayOfWeek(...)
calendar.firstDayOfWeek = Calendar.MONDAY  // Kotlin property syntax

// Java: view.isVisible() / view.setVisible(...)
view.isVisible = true
```

### Platform Types

Java types without nullability annotations become **platform types** (`T!`):

```kotlin
val list = ArrayList<String>()  // non-null (constructor result)
list.add("Item")
val item = list[0]              // Platform type: String!

// You choose how to handle it:
val nullable: String? = item    // Safe — always works
val notNull: String = item      // Assertion emitted — NPE if null at runtime
```

**Best practice:** Always assign Java return values to explicitly typed Kotlin variables (`String` or `String?`) rather than relying on inferred platform types.

### Nullability Annotations Are Respected

Java types annotated with `@NotNull`, `@Nullable` (JetBrains, JSR-305, Android, JSpecify, etc.) map to Kotlin non-null / nullable types automatically:

```kotlin
// Java: @NotNull String getName() → Kotlin: fun getName(): String
// Java: @Nullable String getTitle() → Kotlin: fun getTitle(): String?
```

### Escaping Kotlin Keywords

Java identifiers that are Kotlin keywords require backtick escaping:

```kotlin
foo.`is`(bar)       // Java method named 'is'
obj.`object`        // Java field named 'object'
```

### SAM Conversions

Java interfaces with a single abstract method can be implemented with a lambda:

```kotlin
// Java: executor.execute(new Runnable() { public void run() { ... } });
executor.execute { println("Running") }

// Java: button.setOnClickListener(new View.OnClickListener() { ... });
button.setOnClickListener { view -> handleClick(view) }
```

### Mapped Types

Some Java types are automatically mapped to Kotlin types:

| Java | Kotlin |
|------|--------|
| `java.lang.Object` | `kotlin.Any!` |
| `java.lang.String` | `kotlin.String!` |
| `java.lang.Integer` | `kotlin.Int!` |
| `java.lang.Iterable` | `kotlin.collections.MutableIterable!` |
| `java.util.List` | `kotlin.collections.MutableList!` |
| `java.util.Map` | `kotlin.collections.MutableMap!` |

### Java Arrays

```kotlin
val intArray: IntArray = intArrayOf(1, 2, 3)         // int[]
val stringArray: Array<String> = arrayOf("a", "b")   // String[]
```
</calling_java>

<calling_kotlin_from_java>
## Making Kotlin Friendly to Java Callers

### `@JvmStatic` — Expose as static method

```kotlin
class Util {
    companion object {
        @JvmStatic fun create(): Util = Util()
    }
}
// Java: Util.create() instead of Util.Companion.create()
```

### `@JvmField` — Expose as field (no getter/setter)

```kotlin
class Config {
    @JvmField val timeout = 5000
}
// Java: config.timeout instead of config.getTimeout()
```

### `@JvmOverloads` — Generate overloads for default parameters

```kotlin
@JvmOverloads
fun format(value: Double, decimals: Int = 2, prefix: String = "$"): String = ...
// Java sees: format(double), format(double, int), format(double, int, String)
```

### `@JvmName` — Custom JVM name

```kotlin
@file:JvmName("StringUtils")
package com.example

fun String.toSlug(): String = ...
// Java: StringUtils.toSlug(str)
```

### `@Throws` — Declare checked exceptions for Java callers

```kotlin
@Throws(IOException::class)
fun readFile(path: String): String { ... }
// Java sees: throws IOException
```
</calling_kotlin_from_java>

<collections_interop>
## Collections Interoperability

Kotlin read-only collections (`List`, `Set`, `Map`) are interfaces. At the JVM level they map to their Java counterparts. Java code sees them as regular `java.util.*` types and can mutate them unless guarded.

```kotlin
// Kotlin read-only list
val list: List<String> = listOf("a", "b")

// Java can receive this as java.util.List and call .add() — throws UnsupportedOperationException
// For safety, use .toMutableList() when passing to Java code that mutates
```
</collections_interop>
