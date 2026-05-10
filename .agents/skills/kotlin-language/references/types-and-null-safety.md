# Kotlin Types & Null Safety

<type_system>
## Type System Basics

Kotlin is statically typed with type inference. Every expression has a type known at compile time.

### Basic Types

| Type | Examples | Notes |
|------|----------|-------|
| `Int` | `val x = 42` | 32-bit signed integer |
| `Long` | `val x = 42L` | 64-bit; use `L` suffix |
| `Double` | `val x = 3.14` | 64-bit floating point |
| `Float` | `val x = 3.14f` | 32-bit; use `f` suffix |
| `Boolean` | `true`, `false` | |
| `Char` | `'a'` | Single character |
| `String` | `"hello"` | Immutable; supports templates `"$name"` / `"${expr}"` |
| `Byte` | `val x: Byte = 1` | 8-bit signed |
| `Short` | `val x: Short = 1` | 16-bit signed |
| `UInt`, `ULong`, `UByte`, `UShort` | `val x = 42u` | Unsigned variants |

### Type Inference

```kotlin
val x = 5          // Int inferred
val s = "hello"    // String inferred
val list = listOf(1, 2, 3)  // List<Int> inferred
```

Explicit types are required when: declaring without initializing, function return types (recommended), or when the inferred type would be ambiguous.

### Type Checks and Casts

```kotlin
// Type check
if (obj is String) {
    // Smart cast: obj is now treated as String in this scope
    println(obj.length)
}

// Safe cast (returns null if cast fails)
val str: String? = obj as? String

// Unsafe cast (throws ClassCastException if it fails)
val str: String = obj as String
```

Smart casts work after `is` checks, null checks (`!= null`), and `when` branches. The compiler tracks the check and narrows the type automatically.

### Any, Unit, Nothing

- `Any` — root of the type hierarchy (like Java's `Object`). All non-nullable types inherit from `Any`.
- `Unit` — equivalent of `void`. Functions that return no meaningful value return `Unit` (implicit).
- `Nothing` — no value. Used for functions that never return (e.g., `throw`, infinite loops, `TODO()`).
</type_system>

<null_safety>
## Null Safety

Kotlin's type system distinguishes nullable types (`String?`) from non-nullable types (`String`). This eliminates most `NullPointerException`s at compile time.

### Declaring Nullable Types

```kotlin
var name: String = "hello"   // Cannot hold null
var nullable: String? = null // Can hold null
```

### Safe Call Operator `?.`

Returns `null` if the receiver is null, otherwise calls the member:

```kotlin
val length: Int? = name?.length  // null if name is null
bob?.department?.head?.name      // chain — null if any link is null
```

### Elvis Operator `?:`

Provides a default when the left side is null:

```kotlin
val len = name?.length ?: 0           // 0 if name is null
val parent = node.getParent() ?: return null  // early return
val email = map["email"] ?: throw IllegalStateException("missing")
```

### Not-Null Assertion `!!`

Forces a nullable to non-null. Throws `NullPointerException` if null:

```kotlin
val len = name!!.length  // NPE if name is null
```

**Avoid `!!` unless you have external proof the value is non-null** (e.g., post-validation). Prefer safe calls + elvis.

### Smart Casts with Null Checks

```kotlin
if (b != null) {
    // b is smart-cast to non-nullable String here
    println(b.length)
}
```

### `let` for Null Handling

```kotlin
name?.let { nonNullName ->
    println(nonNullName.length)  // only executes if name != null
}
```

### Safe Casts `as?`

```kotlin
val num: Int? = value as? Int  // null if value isn't Int
```

### `lateinit`

Defers initialization of non-null `var` properties:

```kotlin
lateinit var adapter: RecyclerAdapter

// Later:
adapter = RecyclerAdapter(...)

// Check before access:
if (::adapter.isInitialized) { ... }
```

Throws `UninitializedPropertyAccessException` if accessed before assignment.

### Collections and Nullability

```kotlin
val nullableList: List<String?> = listOf("a", null, "b")
val nonNullOnly: List<String> = nullableList.filterNotNull()
```
</null_safety>

<variables>
## Variables

```kotlin
val x = 5      // Immutable (read-only) — prefer this
var y = 10     // Mutable — can be reassigned
y += 1

val z: Int     // Type required when not initializing immediately
z = 42
```

**Convention:** Use `val` by default. Use `var` only when mutation is genuinely needed.
</variables>

<strings>
## String Templates

```kotlin
val name = "World"
println("Hello, $name!")                    // Simple reference
println("Length: ${name.length}")           // Expression
println("${if (x > 0) "positive" else "negative"}")  // Complex expression
```

### Multiline Strings

```kotlin
val text = """
    |Line 1
    |Line 2
""".trimMargin()
```
</strings>
