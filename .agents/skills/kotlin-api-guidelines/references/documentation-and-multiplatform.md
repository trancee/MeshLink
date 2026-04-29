# Kotlin API Design — Documentation & Multiplatform

<documentation>
## Informative Documentation

### Why documentation matters early
- If you can't explain your library in two paragraphs, reconsider scope
- Writing a Getting Started guide reveals cliff edges in complexity
- Documenting functions exposes edge cases that improve signatures
- If initialization code eclipses task code, rethink config options
- If you can't create clear examples, optimize the API for daily use
- If you can't test without real data sources, provide test doubles

### Required documentation artifacts
1. **Getting Started guide** — build system integration, common entities, small examples, environment config steps
2. **In-depth API description** — every public entry point documented with KDoc
3. **Recipes** — longer examples for common use cases
4. **Resources** — links to blogs, articles, talks

Provide separate docs per library version when possible. Mark version-specific sections clearly.

### Document every public entry point
Use [KDoc](https://kotlinlang.org/docs/kotlin-doc.html) and generate with [Dokka](https://kotlinlang.org/docs/dokka-introduction.html).

**Don't:** "Takes a String and returns a Connection"  
**Do:** "Attempts to connect to the database specified by the input string, returning a Connection if successful, and throwing a ConnectionTimeoutException otherwise"

For each entry point:
- Specify expected input values and behavior with different inputs
- Document what happens with empty, invalid, unsupported, or nonexistent inputs
- Document every exception the entry point may throw
- Use `@see` and internal links to make relationships between related functions explicit

### Document lambda parameters specifically
**Exception behavior:** Will the lambda be retried? Will the original exception be rethrown or wrapped?

**Concurrency behavior** (for non-inline functions):
- Which thread/dispatcher will invoke the lambda?
- Could multiple copies run in parallel?
- Can the user specify a thread?
- What sequencing guarantees exist for multiple lambdas?

### Writing style
- Use simple, clear English — accessible to non-native speakers
- Avoid jargon, Latin phrases, idiomatic expressions
- Be self-contained — don't force users to read external specs for basic info
- Use inline code examples in KDoc comments

### Create user personas
Define personas with constraints (existing stack, Kotlin expertise level). Be pessimistic — don't assume expertise in advanced Kotlin features. Keep code examples simple.
</documentation>

<multiplatform>
## Building for Multiplatform

### Maximize reach
Support as many KMP target platforms as possible. If your library doesn't support a project's targets, they'll choose an alternative.

Use cross-compilation to publish from any host (`.klib` artifacts for Apple targets without macOS). Use a tiered approach for Kotlin/Native targets.

### Design for common code
- Place APIs in the broadest relevant source set:
  1. `commonMain` — available on all platforms (most API should be here)
  2. Intermediate source sets — for platform subsets (e.g., `concurrent` for multi-threading targets)
  3. Platform-specific source sets — only when necessary (e.g., `androidMain`)
- Provide reasonable defaults so users can use the library from common code without platform-specific config

### Ensure consistent cross-platform behavior
- Same valid inputs, same actions, same results across all platforms
- Same error handling (exceptions, error types) across all platforms
- Use `expect`/`actual` declarations for platform-specific implementations — implementations must have identical behavior
- When consistent behavior per platform, document once in `commonMain`
- If platform differences are unavoidable, document them clearly

### Test on all platforms
- Write tests in common code using `kotlin-test`
- Execute common test suite on all supported platforms
- Use tiered testing for Kotlin/Native targets

### Consider non-Kotlin users
Think about how your types appear when called from Swift (or other languages). The [Kotlin-Swift interopedia](https://github.com/kotlin-hands-on/kotlin-swift-interopedia) shows how Kotlin APIs appear in Swift.

### Promote your library
Register at [klibs.io](https://klibs.io/) — JetBrains' search platform for KMP libraries, searchable by target platform.
</multiplatform>
