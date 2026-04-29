# Kotlin Scope Functions

<overview>
## The Five Scope Functions

All execute a block of code on an object within a temporary scope. They differ in how the object is referenced and what they return.

| Function | Object ref | Returns | Extension? | Use case |
|----------|-----------|---------|------------|----------|
| `let` | `it` | Lambda result | Yes | Null check + transform; introduce scoped variable |
| `run` | `this` | Lambda result | Yes | Object config + compute result |
| `with` | `this` | Lambda result | No (argument) | Group calls on an object |
| `apply` | `this` | Context object | Yes | Object configuration (builder-style) |
| `also` | `it` | Context object | Yes | Side effects (logging, validation) |
</overview>

<patterns>
## When to Use Each

### `let` — Transform or null-safe execute

```kotlin
// Null-safe execution
name?.let { println(it.length) }

// Transform and assign
val length = name?.let { it.trim().length } ?: 0

// Introduce scoped variable
numbers.firstOrNull()?.let { firstNumber ->
    println("First: $firstNumber")
}
```

### `apply` — Configure an object (builder pattern)

```kotlin
val person = Person().apply {
    name = "Alice"
    age = 30
    address = "123 Main St"
}

// Returns the object itself — great for initialization chains
```

### `also` — Side effects without changing the object

```kotlin
val numbers = mutableListOf(1, 2, 3).also {
    println("Original list: $it")
}

// Logging, validation, debugging
fun createUser(name: String) = User(name).also {
    logger.info("Created user: ${it.name}")
}
```

### `run` — Configure + compute a result

```kotlin
val result = service.run {
    port = 8080
    query("SELECT ...")
}

// Non-extension `run` — execute a block
val hexColor = run {
    val r = 255
    val g = 128
    val b = 0
    "#${r.toString(16)}${g.toString(16)}${b.toString(16)}"
}
```

### `with` — Group operations on an object

```kotlin
with(config) {
    println("Host: $host")
    println("Port: $port")
}
```
</patterns>

<decision_guide>
## Quick Decision Guide

1. **Need to transform a nullable?** → `let`
2. **Configuring an object?** → `apply`
3. **Side effect (logging/validation)?** → `also`
4. **Configure + compute result?** → `run`
5. **Grouping calls (not chaining)?** → `with`

**Avoid:**
- Nesting scope functions (confuses `this`/`it` context)
- Overusing them where a simple `if`/`val` is clearer
</decision_guide>

<takeif>
## `takeIf` and `takeUnless`

Filtering functions for a single object:

```kotlin
val positiveNumber = number.takeIf { it > 0 }     // number or null
val nonEmptyString = str.takeUnless { it.isBlank() } // str or null

// Combine with scope functions
person.takeIf { it.age >= 18 }?.let { registerVoter(it) }
```
</takeif>
