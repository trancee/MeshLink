# Kotlin API Design — Backward Compatibility

<compatibility_types>
## Compatibility Types

| Type | Definition | Broken by |
|------|-----------|-----------|
| **Binary** | New library version replaces old without recompiling client code | Changing method signatures in bytecode |
| **Source** | Client code compiles against new version without modifications | Removing/renaming public API |
| **Behavioral** | Same features, same semantics (except bug fixes) | Changing how existing features work |

- Binary can break without source breaking (and vice versa)
- Source compatibility is an aspiration, not a promise — too many ways users can invoke an API
</compatibility_types>

<binary_compat_validator>
## Binary Compatibility Validator

Gradle plugin from JetBrains (`org.jetbrains.kotlinx.binary-compatibility-validator`):
- `apiDump` — generates human-readable `.api` file describing public API
- `apiCheck` — compares current build against saved `.api` file (runs with `check`)
- Store `.api` files in VCS; update after deliberate API changes
- Experimental KLib validation support for multiplatform libraries
- Starting with Kotlin 2.2.0, built-in validation support in the Kotlin Gradle plugin
</binary_compat_validator>

<breaking_changes>
## Common Binary Compatibility Pitfalls

### Inferred return types
```kotlin
// v1: returns JsonDeserializer<Int>
fun Int.defaultDeserializer() = JsonDeserializer { ... }

// v2: returns JsonOrXmlDeserializer<Int> — binary break!
fun Int.defaultDeserializer() = JsonOrXmlDeserializer({ ... }, { ... })
```
**Fix:** Always specify return types explicitly. Use explicit API mode.

### Adding arguments (even with defaults)
```kotlin
// v1
fun fib() = ...

// v2 — binary break even with default!
fun fib(input: Int = 0) = ...
```
Adding a default argument changes the bytecode signature. `NoSuchMethodError` at runtime.

**Fix:** Create manual overloads:
```kotlin
fun fib() = ...
fun fib(input: Int) = ...
```
**Warning:** `@JvmOverloads` does NOT preserve binary compat when adding parameters (for Kotlin callers).

**Alternative — `@IntroducedAt` (Experimental, Kotlin 2.4.0+):** tag each new optional parameter with the version it was introduced in; the compiler auto-generates the hidden overloads that manual overloading would otherwise require.
```kotlin
@OptIn(ExperimentalVersionOverloading::class)
fun fib(@IntroducedAt("1.1") input: Int = 0) = ...
```
Requires `@OptIn(ExperimentalVersionOverloading::class)`. Prefer this or manual overloads over `@JvmOverloads` when evolving a published API.

### Widening or narrowing return types
Both directions break binary compatibility:
- `List → Collection` — source break (removes indexing)
- `Collection → List` — binary break (`NoSuchMethodError` because bytecode references old signature)

### Data classes in public API
**Avoid.** Adding a property to a data class:
- Changes constructor signature (binary break)
- Changes `copy()` signature (binary break)
- Changing property order changes `componentN()` methods (behavioral break)

Manual secondary constructors + overriding `copy()` negate the data class convenience.

### Changing annotation targets
**Avoid** changing an exposed annotation's allowed `@Target`s after publishing. The Kotlin compiler resolves an unqualified annotation on a property to the **property** target before the **field** target when both are declared — so adding `AnnotationTarget.PROPERTY` to an annotation that previously only declared `AnnotationTarget.FIELD` silently moves where existing unqualified usages attach on recompilation. This breaks tools/frameworks (notably Java reflection or annotation processors) that expect the annotation on a specific generated element, since the property target isn't visible to Java — those callers must switch to an explicit `@field:` use-site target to keep finding it.
</breaking_changes>

<evolving_apis>
## Evolving APIs

### Deprecation cycle
Use `@Deprecated` with a gradual progression across minor releases:
1. **Warning** — signals upcoming change
2. **Error** — prevents usage but keeps symbol
3. **Hidden** — removes from API surface

```kotlin
@Deprecated(
    message = "Use newFunction() instead",
    replaceWith = ReplaceWith("newFunction(x)"),
    level = DeprecationLevel.WARNING
)
fun oldFunction(x: Int) = ...
```

Breaking removals only in **major** releases. Document your versioning and deprecation policy.

### `@RequiresOptIn` mechanism
Mark APIs with stability annotations:
- **Preview** / **Experimental** / **Delicate** — each clearly explained
- Propagate experimental annotations to your own users
- Don't use `@RequiresOptIn` for deprecation — use `@Deprecated`

### `@PublishedApi`
Internal declarations called from inline public functions must be annotated `@PublishedApi`. This makes them effectively public — treat changes to them as public API changes (binary compat implications).
</evolving_apis>
