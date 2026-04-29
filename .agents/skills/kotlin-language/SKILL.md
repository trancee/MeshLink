---
name: kotlin-language
description: Kotlin language reference for writing correct, idiomatic Kotlin code. Covers types, null safety, classes, sealed classes, data classes, functions, lambdas, scope functions, coroutines, generics, operator overloading, annotations, exceptions, Java interop, collections, and idiomatic patterns. Use when writing Kotlin code, reviewing Kotlin for correctness, needing syntax reference, or asked about Kotlin language features like "how do sealed classes work", "when to use let vs apply", "how do coroutines work", "how does variance work in Kotlin", "idiomatic Kotlin", or "calling Java from Kotlin".
---

<essential_principles>

**Kotlin** is a statically typed language with type inference, null safety built into the type system, and first-class support for functional and object-oriented programming.

### Core Rules an Agent Must Know

- **Null safety is enforced at compile time.** `String` cannot hold null; `String?` can. Use `?.` (safe call), `?:` (elvis), and `let` — avoid `!!` unless you have external proof.
- **`val` by default.** Use `var` only when mutation is genuinely needed.
- **Classes are `final` by default.** Mark with `open` to allow inheritance.
- **`when` is exhaustive on sealed classes and enums** — the compiler verifies all branches are covered. No `else` needed.
- **Trailing lambda convention.** If the last parameter is a function, the lambda goes outside parentheses: `list.filter { it > 0 }`.
- **`it` is the implicit single parameter name** in lambdas: `list.map { it.name }`.
- **Data classes** auto-generate `equals`, `hashCode`, `toString`, `copy`, and destructuring.
- **Extension functions** add methods to existing types without inheritance — resolved statically.
- **Coroutines** use `suspend` functions and structured concurrency via `CoroutineScope`. `launch` for fire-and-forget, `async`/`await` for results.
- **Scope functions** (`let`, `run`, `with`, `apply`, `also`) differ by object reference (`this` vs `it`) and return value (lambda result vs context object).
- **All exceptions are unchecked.** No `throws` declarations. Use `require()` / `check()` for preconditions.
- **`==` is structural equality** (calls `equals()`). **`===` is referential equality** (same object).
- **Generics use `out` (covariant) and `in` (contravariant)** instead of Java's `? extends` / `? super`.
- **Java interop is seamless** — Java getters/setters become Kotlin properties, SAM interfaces accept lambdas, platform types (`T!`) need explicit null handling.

</essential_principles>

<routing>

Based on what you need, read the appropriate reference:

| Topic | Reference |
|-------|-----------|
| Types, null safety, `?.`, `?:`, `!!`, smart casts, `lateinit` | `references/types-and-null-safety.md` |
| Classes, inheritance, interfaces, data classes, sealed classes, enums, objects, delegation | `references/classes-and-oop.md` |
| Functions, lambdas, higher-order functions, extension functions, inline, reified | `references/functions-and-lambdas.md` |
| Scope functions (`let`, `run`, `with`, `apply`, `also`, `takeIf`) | `references/scope-functions.md` |
| Coroutines, suspend functions, Flow, StateFlow, channels, dispatchers | `references/coroutines.md` |
| Generics, variance (`out`/`in`), type projections, star projections, `where`, type erasure | `references/generics.md` |
| Operator overloading, equality, exceptions, destructuring, annotations, type aliases | `references/advanced-features.md` |
| Java interop — platform types, SAM, `@JvmStatic`, `@JvmOverloads`, collections | `references/java-interop.md` |
| Idiomatic patterns, control flow, collections, sequences, common operations | `references/idioms-and-patterns.md` |

For general Kotlin coding tasks, read `references/idioms-and-patterns.md` first — it covers the patterns used most frequently. Load additional references as needed for the specific language features in play.

</routing>

<reference_index>

All domain knowledge in `references/`:

**Types:** types-and-null-safety.md — type system, null safety operators, smart casts, strings, variables
**OOP:** classes-and-oop.md — classes, inheritance, interfaces, data/sealed/enum classes, objects, delegation
**Functions:** functions-and-lambdas.md — functions, lambdas, higher-order, extensions, inline, reified
**Scope:** scope-functions.md — let, run, with, apply, also, takeIf/takeUnless decision guide
**Concurrency:** coroutines.md — suspend, launch, async, Flow, StateFlow, channels, dispatchers, cancellation
**Generics:** generics.md — variance (out/in), type projections, star projections, upper bounds, where, type erasure, reified
**Advanced:** advanced-features.md — operator overloading, equality (== vs ===), exceptions, precondition functions, destructuring, annotations, type aliases
**Java Interop:** java-interop.md — platform types, nullability annotations, SAM conversions, @JvmStatic, @JvmOverloads, @Throws, collections interop
**Patterns:** idioms-and-patterns.md — idiomatic Kotlin, control flow, collections, sequences

</reference_index>
