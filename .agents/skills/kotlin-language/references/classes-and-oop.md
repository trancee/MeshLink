# Kotlin Classes & OOP

<classes>
## Classes

```kotlin
// Minimal class
class Empty

// Primary constructor with properties
class Person(val name: String, var age: Int = 0)

// Class with body
class Person(val name: String) {
    var age: Int = 0

    init {
        require(name.isNotBlank()) { "name must not be blank" }
    }

    fun greet() = "Hi, I'm $name"
}
```

### Key Rules

- Classes are **`final` by default**. Mark with `open` to allow inheritance.
- No `new` keyword — just `Person("Alice")`.
- Primary constructor properties with `val`/`var` are stored; without, they're constructor-only params.
- `init` blocks run in order during construction.
- Secondary constructors must delegate to the primary via `this(...)`.

### Visibility Modifiers

| Modifier | Scope |
|----------|-------|
| `public` (default) | Visible everywhere |
| `private` | Visible in the same file (top-level) or same class |
| `protected` | Visible in the class and subclasses |
| `internal` | Visible in the same module |
</classes>

<inheritance>
## Inheritance

```kotlin
open class Shape(val name: String) {
    open fun area(): Double = 0.0
}

class Circle(val radius: Double) : Shape("circle") {
    override fun area(): Double = Math.PI * radius * radius
}
```

- Base class must be `open`; members to override must be `open`.
- Use `override` keyword on subclass members.
- Use `abstract` for classes/members with no default implementation.
- Call super with `super.method()` or `super<Interface>.method()` for disambiguation.
</inheritance>

<interfaces>
## Interfaces

```kotlin
interface Clickable {
    fun click()                           // Abstract
    fun showOff() = println("Clickable!") // Default implementation
}

class Button : Clickable {
    override fun click() = println("Clicked")
}
```

Interfaces can have properties (abstract or with accessors), but no backing fields.
</interfaces>

<data_classes>
## Data Classes

Auto-generate `equals()`, `hashCode()`, `toString()`, `copy()`, and `componentN()`:

```kotlin
data class User(val name: String, val email: String)

val user = User("Alice", "alice@example.com")
val copy = user.copy(email = "new@example.com")
val (name, email) = user  // Destructuring
```

**Rules:**
- Primary constructor must have at least one `val`/`var` parameter.
- Cannot be `abstract`, `open`, `sealed`, or `inner`.
- Only primary constructor properties are included in generated methods.
</data_classes>

<sealed_classes>
## Sealed Classes & Interfaces

Restrict class hierarchies — all direct subclasses are known at compile time:

```kotlin
sealed class Result {
    data class Success(val data: String) : Result()
    data class Error(val exception: Exception) : Result()
    data object Loading : Result()
}

fun handle(result: Result) = when (result) {
    is Result.Success -> println(result.data)
    is Result.Error   -> println(result.exception.message)
    Result.Loading    -> println("Loading...")
    // No else needed — compiler knows all cases
}
```

**Key properties:**
- Subclasses must be in the same package and module.
- Exhaustive `when` — compiler ensures all branches are covered.
- Great for state machines, API responses, and error hierarchies.
- Sealed classes are implicitly `abstract`.
</sealed_classes>

<enum_classes>
## Enum Classes

```kotlin
enum class Direction { NORTH, SOUTH, EAST, WEST }

enum class Color(val rgb: Int) {
    RED(0xFF0000),
    GREEN(0x00FF00),
    BLUE(0x0000FF);

    fun containsRed() = (rgb and 0xFF0000) != 0
}

// Iterate
Color.entries.forEach { println(it.name) }
```
</enum_classes>

<objects>
## Object Declarations & Expressions

### Singleton (object declaration)

```kotlin
object DatabaseConfig {
    val url = "jdbc:..."
    fun connect() { ... }
}
```

### Companion Object

```kotlin
class MyClass {
    companion object {
        fun create(): MyClass = MyClass()
        const val TAG = "MyClass"
    }
}

val instance = MyClass.create()
```

### Object Expression (anonymous)

```kotlin
val listener = object : ClickListener {
    override fun onClick() { ... }
}
```
</objects>

<inline_value_classes>
## Inline Value Classes

Zero-cost type-safe wrappers:

```kotlin
@JvmInline
value class UserId(val id: String)

@JvmInline
value class OrderId(val id: String)

// Compile error if you mix UserId and OrderId
fun findUser(id: UserId): User = ...
```
</inline_value_classes>

<delegation>
## Delegation

```kotlin
interface Printer {
    fun print(message: String)
}

class ConsolePrinter : Printer {
    override fun print(message: String) = println(message)
}

class PrefixPrinter(printer: Printer) : Printer by printer
// Delegates all Printer methods to the provided instance
```

### Property Delegation

```kotlin
val lazyValue: String by lazy { computeExpensiveString() }
var observed: String by Delegates.observable("initial") { _, old, new ->
    println("Changed from $old to $new")
}
```
</delegation>
