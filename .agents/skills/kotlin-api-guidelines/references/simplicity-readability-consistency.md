# Kotlin API Design — Simplicity, Readability & Consistency

<simplicity>
## Simplicity

### Use explicit API mode
Enable the Kotlin compiler's explicit API mode — forces you to:
- Add visibility modifiers to all declarations (no accidental `public` defaults)
- Define return types explicitly for all public functions and properties

### Reuse existing concepts
Use standard library types (`kotlin.time.Duration`, `Result`, etc.) instead of reinventing. Avoids forcing users to learn new types. Be cautious with third-party types — they create coupling.

### Define and build on top of core API
Create a small set of core operations, then build higher-level operations on top of them.

**Example:** In Flows, `filter` and `map` are built on `transform`. Users learn the core, then discover convenience operations.

Build additional operations that combine or specialize core ones. Users should be able to solve non-trivial problems with core operations alone and refactor to convenience operations without changing behavior.
</simplicity>

<readability>
## Readability

### Prefer explicit composability
Instead of adding parameters for customization, design operations that compose together.

**Bad:** Every operation accepts buffering/conflation/retry parameters.  
**Good:** Separate `buffer()`, `conflate()`, `retry()` operations chain with `filter()` and `map()`.

### Use DSLs for configuration
Builder DSLs with lambda-with-receiver improve readability and backward compatibility:
```kotlin
val client = HttpClient(CIO) {
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            isLenient = true
        })
    }
}
```
**Why DSLs beat parameter lists for compat:** Adding a new property to a DSL builder is backward-compatible. Adding a new parameter to a function is not.

**DSL rules:**
- Builder functions take lambda-with-receiver as final parameter
- Factory functions creating instances: same name as return type, capitalized (e.g., `Json { }`)
- Pass required values as function parameters (compile-time enforcement), not as properties inside the lambda

### Use extension functions and properties
Core concept in the class/interface. Additional functionality as extensions. This makes it clear what's fundamental vs. derived.

**Example:** `CharSequence` has only `length`, `get(index)`, `subSequence()`. Everything else (`isEmpty()`, `trimStart()`, etc.) is an extension.

**Rule of thumb:** Only regular properties, overrides, and overloaded operators as members. Computed properties and normal methods as extensions.

### Avoid boolean parameters
`doWork(true)` is unreadable without IDE hints. Instead:
- Create separate named functions: `map()` vs `mapNotNull()`
- Use enum classes for operation modes

### Use numeric types appropriately
| Type | Use for | Avoid for |
|------|---------|-----------|
| `Int`, `Long`, `Double` | Arithmetic (calculations) | IDs, handles, non-arithmetic entities |
| `Byte`, `Float`, `Short` | Memory layout (caches, network) | General arithmetic |
| `UByte`–`ULong` | Full positive range, native interop | General non-negative integers |

Use inline value classes for domain-specific wrappers around numeric types (e.g., `Duration`).
</readability>

<consistency>
## Consistency

### Parameter order, naming, and overloading
- Maintain consistent parameter order across related functions
- Pick one term and stick with it (`element` vs `item` — not both)
- Name related functions predictably: `first`/`firstOrNull`, `single`/`singleOrNull`
- Order: essential inputs first, optional inputs last
- Overloaded functions must behave identically (same semantics, different input types)

### Error handling — choose one approach and be consistent
| Mechanism | When to use |
|-----------|-------------|
| Nullable return (`T?`) | Data cannot be fetched/calculated — `null` means "missing" |
| Exception | Invariant violated, unrecoverable error |
| `Result` type | Caller needs to inspect error without try/catch |
| `Catching` suffix | Wraps exception in result — `run`/`runCatching`, `receive`/`receiveCatching` |

**Anti-pattern:** Using exceptions for normal control flow. Use Command/Query Separation — let users check conditions before attempting operations.

### OO for data and state, FP for transforms
- Use classes for data and state; inheritance for hierarchical data
- Top-level functions when all state is in parameters
- Extension functions when calls will be chained

### Maintain quality with tools
- Use linters for static analysis (coding conventions)
- Comprehensive unit + integration tests covering all documented behavior
- Run tests on every release; use Kover for coverage
</consistency>
