# Kotlin API Design — Predictability, Debuggability & Testability

<predictability>
## Predictability

### Do the right thing by default
Anticipate the happy path for each use case and provide defaults. Users should not need to supply values for the library to function correctly.
```kotlin
// Good: minimal code for common case
val client = HttpClient(CIO)
val response = client.get("https://example.com/")
// No need to specify HTTP headers, timeout, retry, etc.
```

### Allow extension, prevent invalid extension
**Allow:** Let users supply custom implementations, install plugins, write extension functions.

**Prevent:** Use `sealed` types to restrict extension to valid domain values:
```kotlin
sealed interface JsonElement
class JsonNumber(val value: Number) : JsonElement
class JsonObject(val values: Map<String, JsonElement>) : JsonElement
class JsonArray(val values: List<JsonElement>) : JsonElement
class JsonBoolean(val value: Boolean) : JsonElement
class JsonString(val value: String) : JsonElement
object JsonNull : JsonElement
```
Sealed types also give exhaustive `when` expressions without `else`.

### Avoid exposing mutable state
- Return read-only collections, not mutable ones
- Avoid arrays in APIs (mutable); if unavoidable, make defensive copies
- `vararg` automatically copies arrays (spread operator creates a copy)

### Validate inputs and state
Use `require()` for input validation and `check()` for state validation:
```kotlin
fun saveUser(username: String, password: String) {
    require(username.isNotBlank()) { "Username should not be blank" }
    require(username.all { it.isLetterOrDigit() }) {
        "Username can only contain letters and digits, was: $username"
    }
    require(password.length >= 7) { "Password must contain at least 7 characters" }
    // Don't include sensitive values (password) in error messages
}
```

```kotlin
class ShoppingCart {
    private val contents = mutableListOf<Item>()
    fun purchase(): Amount {
        check(contents.isNotEmpty()) { "Cannot purchase an empty cart" }
        // ...
    }
}
```
</predictability>

<debuggability>
## Debuggability

### Provide meaningful `toString()` for stateful types
Every type with state needs a `toString()` that shows current content — both for debugger inspection and logging.

```kotlin
override fun toString(): String {
    val resultText = if (result) "succeeded" else "failed"
    return "Subscription $resultText (reason=$reason, description=\"$description\")"
}
```

**Rules:**
- Use consistent format across all types in the library
- Document the format if it's part of a contract (e.g., parseable output)
- **Don't expose sensitive data** (passwords, tokens) in `toString()`
- Don't use `data class` just for `toString()` — it breaks backward compatibility (see backward-compatibility reference)
- Include internal state too (builder progress, connection health, request status)

### Exception handling policy
Adopt and document a consistent approach:
- Exception **type** should indicate the **type of error**
- Exception **data** should help locate the **root cause**
- Wrap low-level exceptions in library-specific exceptions with original as `cause`
- If library A wraps library B internally, don't expose B's exception types
- If library A is a convenience layer over B, rethrowing B's exceptions may be appropriate
</debuggability>

<testability>
## Testability

### Avoid global state and stateful top-level functions
Global state makes user code untestable — tests can't control values.

**Bad:** `val instant = Clock.now()` — always returns real time.

**Good:** Inject a `Clock` instance that can be replaced in tests:
```kotlin
val clock: Clock = Clock.System       // production
val instant: Instant = clock.now()

// In tests:
val fakeClock: Clock = ...            // controllable fake
val instant: Instant = fakeClock.now()
```

**Principle:** Dependencies on the outside world (time, network, filesystem, randomness) should be injectable so users can substitute fakes in tests.
</testability>
