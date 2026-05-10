---
name: kotlin-api-guidelines
description: Official Kotlin library authors' guidelines for API design. Covers minimizing mental complexity (simplicity, readability, consistency, predictability, debuggability, testability), backward compatibility (binary/source/behavioral, Binary Compatibility Validator, pitfalls with default args, return types, data classes, deprecation cycles, @RequiresOptIn, @PublishedApi), informative documentation (KDoc, Dokka, lambda docs, user personas), and building for multiplatform (common code design, cross-platform behavior, testing, Swift interop). Use when designing a Kotlin library API, reviewing API design decisions, asking about "backward compatibility", "binary compatibility", "how to deprecate an API", "explicit API mode", "sealed vs open", "DSL builder design", "avoid data classes in API", "document lambda parameters", "KMP library design", or any Kotlin API design guideline topic.
---

<essential_principles>

**Official Kotlin guidelines** for designing library APIs that are simple, readable, consistent, predictable, debuggable, testable, backward-compatible, well-documented, and multiplatform-ready.

### Core Rules an Agent Must Know

**Simplicity:**
- Enable explicit API mode — forces visibility modifiers and explicit return types
- Reuse standard library types (`Duration`, `Result`) instead of reinventing
- Define a small core API; build additional operations on top of it

**Readability:**
- Compose behaviors (separate functions) instead of adding parameters for customization
- Use DSL builders for configuration (backward-compatible, self-documenting)
- Extensions for derived functionality; members only for core concept
- Avoid boolean parameters — use separate named functions or enums

**Consistency:**
- Consistent parameter order, naming, and overloading across all functions
- Pick one error handling approach and apply it uniformly
- Name pairs predictably: `first`/`firstOrNull`, `run`/`runCatching`

**Predictability:**
- Provide sensible defaults for the happy path
- Use sealed types to prevent invalid extensions
- Return read-only collections; avoid exposing mutable state
- Validate with `require()` (inputs) and `check()` (state)

**Debuggability:**
- Meaningful `toString()` for every stateful type (no sensitive data)
- Consistent exception handling policy — type indicates error, data helps locate root cause

**Testability:**
- No global state or stateful top-level functions — make dependencies injectable

**Backward compatibility:**
- Always specify return types explicitly
- Don't add parameters (even with defaults) — use manual overloads
- Don't widen/narrow return types
- Avoid data classes in public API
- Deprecate gradually: warning → error → hidden across minor releases
- Use Binary Compatibility Validator (`apiDump`/`apiCheck`)

**Documentation:**
- Document every public entry point with KDoc (not just restating the signature)
- Document lambda exception behavior and concurrency semantics
- Provide Getting Started, API reference, and recipes

</essential_principles>

<routing>

| Topic | Reference |
|-------|-----------|
| Explicit API mode, reusing types, core API, DSL builders, composability, extension functions, boolean params, numeric types, parameter naming, error handling consistency, OO vs FP, linters and testing | `references/simplicity-readability-consistency.md` |
| Sensible defaults, sealed types, mutable state, input validation (`require`/`check`), `toString()`, exception policy, testability, injectable dependencies | `references/predictability-debuggability-testability.md` |
| Binary/source/behavioral compatibility, Binary Compatibility Validator, inferred return types, default args, return type widening, data classes in API, `@Deprecated` cycles, `@RequiresOptIn`, `@PublishedApi` | `references/backward-compatibility.md` |
| KDoc and Dokka, documentation artifacts, lambda docs, user personas, writing style, KMP library design (common code, cross-platform behavior, testing, Swift interop, klibs.io) | `references/documentation-and-multiplatform.md` |

</routing>

<reference_index>

**simplicity-readability-consistency.md** — explicit API mode (visibility modifiers, explicit return types), reusing existing types (Duration, Result), core API + derived operations pattern, explicit composability (chaining vs parameters), DSL builders (lambda-with-receiver, factory functions, required values as params, compat advantage), extension functions vs members (CharSequence example), avoiding boolean parameters (named functions, enums), numeric type usage (Int/Long/Double for arithmetic, Byte/Float/Short for memory, unsigned for full range), consistent parameter order/naming/overloading, error handling mechanism choice (nullable/exception/Result/Catching suffix), OO for state + FP for transforms, automated quality (linters, Kover)

**predictability-debuggability-testability.md** — sensible defaults for happy path, extension points (plugins, user extension functions), sealed types to prevent invalid extensions (JsonElement example), avoiding mutable state (read-only collections, defensive copies, vararg copying), input validation with require() and state validation with check(), meaningful toString() for stateful types (format consistency, security, no data classes), exception handling policy (type→error type, data→root cause, wrapping low-level exceptions), avoiding global state (Clock.System injection pattern), injectable dependencies for testability

**backward-compatibility.md** — binary/source/behavioral compatibility definitions, Binary Compatibility Validator (apiDump/apiCheck, KLib support, KGP 2.2.0 built-in), explicit return types to prevent binary breaks, adding default arguments breaks binary compat (manual overloads fix, @JvmOverloads warning), widening/narrowing return types breaks binary compat, data classes in public API (constructor/copy/componentN breaks), @Deprecated deprecation cycle (WARNING→ERROR→HIDDEN, replaceWith, major releases only for removal), @RequiresOptIn for stability annotations (Preview/Experimental/Delicate, propagation), @PublishedApi annotation implications

**documentation-and-multiplatform.md** — documentation as feedback loop, required artifacts (Getting Started, API description, recipes, resources), KDoc + Dokka, entry point documentation (inputs, exceptions, edge cases, not restating signatures), lambda parameter documentation (exception behavior, concurrency/threading), @see tags and internal links, self-contained docs, simple English, user personas, KMP reach maximization (cross-compilation, tiered native targets), designing for commonMain (source set priority), consistent cross-platform behavior (expect/actual with identical semantics), multiplatform testing (kotlin-test), Swift interop considerations, klibs.io promotion

</reference_index>
