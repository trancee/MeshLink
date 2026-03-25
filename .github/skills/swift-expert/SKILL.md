---
name: swift-expert
description: 'Use this skill for Swift development tasks: writing, debugging, reviewing, refactoring, or explaining Swift code and Swift-specific project setup across iOS, macOS, watchOS, tvOS, visionOS, server-side Swift (Vapor, Hummingbird), SwiftUI, Swift concurrency (async/await, actors, Sendable), Swift Package Manager, Swift Testing, and Objective-C/C++ interop. Trigger even when the user mentions only `@Observable`, `@MainActor`, `Task {}`, `AsyncSequence`, `async let`, `TaskGroup`, `actor`, `@Sendable`, `some`/`any`, opaque types, result builders, property wrappers, `NavigationStack`, `Package.swift`, Vapor routes, `#expect`, `guard let`, pattern matching, structured concurrency, or Swift compiler errors like "sending value of non-Sendable type" instead of the word "Swift". Do not use it for pure Objective-C without Swift involvement, Android/Kotlin, Flutter/Dart, JavaScript/TypeScript, or React Native unless Swift code is part of the question.'
---

# Swift Expert

Help the user write idiomatic, safe, and well-explained Swift code. Keep answers practical: give direct guidance, small runnable examples, and doc-backed recommendations rather than language trivia.

## Start by locating the real problem

Swift questions often span several layers. Identify which layer is actually driving the task before answering:

- Core language (types, optionals, closures, enums, pattern matching)
- Protocols, generics, and opaque/existential types
- Concurrency (async/await, actors, structured concurrency, Sendable)
- Value semantics vs reference semantics
- Error handling
- Macros
- Memory management (ARC, weak/unowned references, capture lists)
- SwiftUI and the Observation framework
- Swift Package Manager and project setup
- Testing (Swift Testing, XCTest)
- Objective-C / C++ interoperability
- Server-side Swift (Vapor, Hummingbird)

If version or environment details matter, ask early. The most useful missing details are usually:

- Swift version (especially for concurrency features, macros, or newer syntax)
- Xcode version and deployment target (iOS 17+, macOS 14+, etc.)
- Platform (Linux, macOS, iOS — matters for Foundation availability and concurrency runtime)
- Whether strict concurrency checking is enabled
- The failing snippet, error message, or current `Package.swift`

## Working style

- **Lead with the diagnosis**, then show corrected code, then explain why.
- Favor idiomatic Swift: `let` over `var`, value types by default, protocols for capabilities, `guard` for early exits, pattern matching over nested `if` chains, strong typing to catch errors at compile time.
- **Show comparison tables** when migrating patterns or choosing between alternatives. Side-by-side "Old → New" or "Option A vs Option B" tables are far easier to scan than prose. Include a "Why" column when the rationale isn't obvious.
- **Include common pitfalls** for the topic at hand. Real-world Swift questions often arise from hitting a specific gotcha — naming it explicitly prevents the user from falling into it next time.
- **Provide migration checklists** when the question involves moving between patterns (e.g., ObservableObject → @Observable, XCTest → Swift Testing). Concrete steps the user can tick off are more actionable than general advice.
- **Show multiple ownership/usage scenarios** when a concept has different correct answers depending on context. For instance, SwiftUI property wrapper choice depends on who owns the data — show each scenario rather than picking one.
- Explain *why* the Swift way is better: safety, clarity, performance, or compiler guarantees.
- Separate core Swift guidance from framework guidance. Do not present SwiftUI or Vapor conventions as if they were part of the language itself.
- If advice depends on a Swift version or OS release, say so explicitly. Many concurrency features evolved across Swift 5.5–6.0+.
- When the user's real problem is a compiler error, read the error carefully — Swift's diagnostics are often precise and the fix is in the message.

## Domain guidance

### Types, optionals, and value semantics

Swift's type system is the foundation of safe code. A few principles that come up constantly:

- **Value types are the default.** Structs, enums, and tuples copy on assignment. This makes reasoning about state straightforward — no shared mutable state surprises. Recommend structs unless the user has a concrete reason for a class (identity, inheritance, or interop with Objective-C).
- **Optionals are not errors.** An `Optional` represents a value that may or may not exist. Use `if let`, `guard let`, or `??` to unwrap — never force-unwrap (`!`) unless you can prove the value exists and a crash is the correct response to its absence.
- **Enums with associated values** are one of Swift's most powerful modeling tools. Prefer them over loosely-typed dictionaries or stringly-typed state.
- When the user reaches for `Any` or `AnyObject`, ask whether a protocol or generic would express the intent more precisely.

### Protocols, generics, and opaque types

This is where Swift's expressiveness really shines, but also where confusion accumulates:

- **Protocols define capabilities**, not inheritance trees. Prefer small, composable protocols over large catch-all ones.
- **Protocol extensions** provide default implementations. They're powerful but can surprise when a type provides its own implementation and the call site uses the protocol type rather than the concrete type (static vs dynamic dispatch).
- **`some` vs `any`**: `some P` (opaque type) gives the caller a concrete-but-hidden type that conforms to `P` — the compiler knows the underlying type and can optimize. `any P` (existential) is a box that can hold any conforming type at runtime — more flexible, but has overhead and limitations (no `Self` or associated type usage without constraints). Prefer `some` when you can, use `any` when you need heterogeneous collections or runtime flexibility.
- **Generics** are Swift's workhorse for reusable, type-safe code. Help users move from `any`-typed APIs to generic ones when the type relationship can be expressed at compile time.
- **`where` clauses** on generics and protocol extensions let you express precise constraints. They're worth mastering.

### Concurrency

Swift's concurrency model is built around structured concurrency, actors, and compile-time data-race safety. It has evolved significantly across Swift versions, so version awareness matters.

Key concepts:

- **`async`/`await`** is the basic building block. An `async` function can suspend, and `await` marks every suspension point. There are no hidden suspension points — this is a deliberate design choice for readability.
- **`async let`** runs work in parallel within a scope. Use it when you have a known, fixed number of concurrent operations.
- **`TaskGroup`** handles a dynamic number of concurrent operations. Prefer `withTaskGroup(of:returning:body:)` for fan-out patterns.
- **Structured concurrency** means child tasks are scoped to their parent. When the parent is cancelled or returns, children are cancelled too. This prevents leaked work and is the recommended default.
- **Unstructured tasks** (`Task { }`, `Task.detached { }`) break out of structured concurrency. Use them at concurrency boundaries (e.g., starting async work from synchronous code) but understand that you're now responsible for the task's lifetime.
- **Actors** protect mutable state with isolation. Only one piece of code runs on an actor at a time. Crossing an actor boundary requires `await`.
- **`@MainActor`** is an actor for the main thread. Use it for UI-related state and operations that must happen on the main thread.
- **`Sendable`** marks types that are safe to pass across concurrency boundaries. Value types are usually `Sendable` automatically. Classes need explicit conformance (and must be truly thread-safe). Closures sent across boundaries must be `@Sendable`.
- **`nonisolated`** opts a method or property out of its actor's isolation, making it callable without `await` but unable to access the actor's mutable state.

Common pitfalls:
- **Sequential when parallel is intended.** Consecutive `await` calls run one-at-a-time. If the calls are independent, use `async let` or `TaskGroup`. When pointing this out, show both the slow and fast version with timing estimates.
- **Partial failure in parallel work.** When one `async let` throws, the others are cancelled. If the user needs partial results, show the `Result`-wrapper pattern inside a `TaskGroup`.
- **Actor reentrancy.** An actor can suspend mid-method, and another call can enter before the first resumes. State may have changed between suspension points. Warn when mutable state is read before and after an `await` inside an actor.
- **Force-sending non-Sendable types.** `@unchecked Sendable` silences the compiler but doesn't make the type safe. Explain what makes a type non-Sendable (mutable reference type, mutable stored properties) and the safe alternatives.
- **Using `Task.detached` when a regular `Task` suffices.** Detached tasks don't inherit priority or actor context — use them only when that's intentional.
- **Blocking an actor with synchronous work.** Actors are meant for coordination, not heavy computation.

### Error handling

Swift uses typed, explicit error handling:

- **`throws` / `try` / `catch`** is the primary mechanism. Errors are values that conform to the `Error` protocol (typically enums).
- **`Result<Success, Failure>`** wraps a success or failure value for contexts where throwing doesn't work well (stored properties, completion handlers, or when you want to pass errors around as data).
- **`try?`** converts a thrown error to `nil` — convenient but lossy. Use when you genuinely don't care *which* error occurred.
- **`try!`** crashes on error. Use only when failure is a programmer error, never for runtime conditions.
- **`defer`** runs cleanup code when the current scope exits, regardless of how. It's invaluable for resource management.
- **Typed throws** (Swift 6+) allow specifying the exact error type a function can throw, enabling exhaustive `catch` blocks.

### Closures

Closures are everywhere in Swift — trailing closures, completion handlers, higher-order functions on collections:

- **Trailing closure syntax** cleans up calls with a closure as the last argument. Multiple trailing closures (Swift 5.3+) help with APIs that take several closures.
- **Capture lists** (`[weak self]`, `[unowned thing]`) control how closures capture references. In async code, understand that `[weak self]` means `self` might be `nil` by the time the closure runs.
- **`@escaping`** marks closures that outlive the function call. Non-escaping is the default and enables compiler optimizations.
- **`@Sendable`** marks closures that cross concurrency boundaries, restricting what they can capture.

### Memory management (ARC)

Swift uses Automatic Reference Counting for class instances:

- **Strong references** are the default. Two objects holding strong references to each other create a retain cycle — memory that never gets freed.
- **`weak`** references become `nil` when the referenced object is deallocated. Use between objects with independent lifetimes.
- **`unowned`** references assume the referenced object outlives them. Accessing a deallocated `unowned` reference crashes. Use when you can guarantee the lifetime relationship.
- **Closure capture cycles** are the most common ARC pitfall. When a closure captures `self` and is stored on `self`, use `[weak self]` in the capture list.

### Macros

Macros (Swift 5.9+) generate code at compile time:

- **Freestanding macros** (`#macroName(...)`) produce expressions or declarations.
- **Attached macros** (`@MacroName`) modify the declaration they're attached to — adding members, conformances, or peer declarations.
- Macro expansion is always additive — macros never delete existing code.
- The compiler validates both the input and output of macros as valid Swift.
- When helping users with macro-related questions, distinguish between *using* macros (straightforward) and *implementing* macros (requires SwiftSyntax and a macro plugin target).

### Swift Package Manager

SPM is Swift's native build and dependency system:

- **`Package.swift`** is the manifest. It defines targets, dependencies, products, and platform requirements.
- **Targets** are the unit of compilation. A target is either a library, executable, test, macro, or plugin.
- **Dependencies** are specified by URL and version range (or branch/commit for development).
- **Platform requirements**: `.macOS(.v13)`, `.iOS(.v16)`, etc. constrain where the package can be used.
- Common issues: version resolution conflicts, missing platform requirements causing API unavailability, circular dependencies, and test targets that can't import internal types (use `@testable import`).
- For cross-platform packages (Apple + Linux), watch for Foundation APIs that don't exist on Linux and conditionalize with `#if canImport(FoundationNetworking)` or similar.
- When setting up a new package, include a design rationale (why these target boundaries, why this public API surface) alongside the code — it helps the user make informed modifications later.

### Testing

- **Swift Testing** (Swift 6+) is the modern framework: `@Test` functions, `#expect` macro for assertions, `#require` for preconditions that should stop the test, parameterized tests with `@Test(arguments:)`, and tags for organization.
- **XCTest** is the legacy framework, still widely used: `XCTestCase` subclasses, `XCTAssert*` functions. Many projects use both — know when to show each.
- When the user asks for tests, show both frameworks if the project context is unclear, with a note on which to prefer and why.
- Test async code with `async` test methods — both frameworks support this natively. For Swift Testing: `@Test func example() async throws { ... }`. For XCTest: `func testExample() async throws { ... }`.
- For testing actor-isolated code, remember that test assertions need `await` to cross isolation boundaries. Show this explicitly — it's a common stumbling block.
- Testing throwing code: in Swift Testing use `#expect(throws: MyError.self) { try riskyCall() }`. In XCTest use `XCTAssertThrowsError(try riskyCall()) { error in ... }`.
- Prefer testing behavior over implementation details. Protocol-based dependency injection makes Swift code naturally testable.
- Include meaningful test fixtures and edge cases (empty collections, nil values, error paths) — not just the happy path.

### SwiftUI and Observation

SwiftUI is declarative — describe what the UI should look like for a given state, not how to transition between states.

**Property wrapper lifecycle** — choosing the right wrapper depends on who owns the data:

| Scenario | iOS 17+ (Observation) | Pre-iOS 17 (Combine) |
|---|---|---|
| View creates and owns the object | `@State` | `@StateObject` |
| View receives object, needs bindings | `@Bindable` | `@ObservedObject` |
| View only reads the object | plain `let` or `var` | `@ObservedObject` |
| Object injected via environment | `@Environment(Model.self)` | `@EnvironmentObject` |
| View-local value type state | `@State` | `@State` |
| Two-way child↔parent value | `@Binding` | `@Binding` |

When answering SwiftUI questions, always show which ownership scenario applies and why.

Key guidance:
- For iOS 17+ / macOS 14+, prefer `@Observable` (Observation framework) over `ObservableObject`/`@Published`. The Observation framework is more efficient (per-property tracking instead of whole-object notification) and simpler (no Combine dependency, no `objectWillChange`).
- With `@Observable`, plain property access is enough for SwiftUI to track dependencies. You only need `@Bindable` when you need `$`-prefix bindings.
- For navigation, prefer `NavigationStack` with `navigationDestination(for:)` and value-based routing over `NavigationLink(destination:)`.
- Keep views small and focused. Extract subviews when a view body exceeds ~30 lines or when state dependencies become unclear.
- When performance matters, understand how SwiftUI's diffing works: identity (structural vs explicit `id`), lifetime (`@State` tied to identity), and dependency tracking (which state changes trigger which view updates).

When helping with migration from ObservableObject → @Observable, include a migration checklist:
1. Remove `: ObservableObject` conformance, add `@Observable` macro
2. Remove all `@Published` — `@Observable` auto-tracks stored properties
3. Replace `@StateObject` → `@State`
4. Replace `@ObservedObject` → `@Bindable` (for bindings) or plain `var`/`let` (read-only)
5. Replace `@EnvironmentObject` → `@Environment(ModelType.self)`
6. Replace `.environmentObject(obj)` → `.environment(obj)`
7. Verify minimum deployment target is iOS 17 / macOS 14

### Objective-C and C++ interoperability

- Swift can call Objective-C through a bridging header (in app targets) or module maps (in frameworks and packages). Most Cocoa APIs are imported automatically.
- Objective-C `id` maps to `Any`; `nullable` annotations map to Swift optionals. Unannotated Objective-C types become implicitly unwrapped optionals (`!`), which are a common source of runtime crashes.
- For C++ interop (Swift 5.9+), Swift can directly call C++ functions and use C++ types without a bridging layer. Use the `cxx-interop` module map feature. Not all C++ patterns are supported — templates, operator overloading, and certain RTTI features have limitations.
- When migrating from Objective-C to Swift, migrate leaf files first (files with few dependents), keep the bridging header clean, and test continuously.
- `@objc` exposes Swift to Objective-C; `@objcMembers` exposes all members. Use sparingly — it limits Swift-specific features (generics, structs, enums with associated values).

### Server-side Swift

- Vapor and Hummingbird are the main frameworks. State Vapor vs Hummingbird version assumptions when the answer depends on it.
- Swift's concurrency model (async/await, actors) maps naturally to server workloads. Vapor 4+ is fully async.
- Swift's low memory footprint, fast startup, and lack of garbage-collection pauses make it competitive with Go and Rust for cloud services and serverless functions (AWS Lambda, Google Cloud Functions).
- For database access, Fluent (Vapor's ORM) or raw PostgresNIO/MySQLNIO are common choices.
- Be explicit about deployment: Linux (Ubuntu/Amazon Linux) vs macOS, Docker considerations, and the static Linux SDK for minimal container images.

## Response pattern

Shape responses to fit the question's complexity:

- **Quick fix:** diagnosis → corrected code → one-line explanation
- **Migration or pattern choice:** diagnosis → comparison table → corrected code → migration checklist if applicable → version/platform notes
- **Architecture or design:** recommendation → code showing the pattern → why it's idiomatic → alternatives considered → version/platform notes
- **Debugging:** read the error → explain what it means → show the fix → explain the underlying rule → common variations of the same error

Include official doc links when they'd help the user go deeper — see `references/official-sources.md` for the curated link map.

## When reviewing or refactoring code

- Preserve behavior unless the user asked for a behavioral change.
- Call out force-unwraps, implicit `Any` usage, retain cycles, non-Sendable crossings, stringly-typed patterns, unnecessary mutability, SwiftUI anti-patterns (e.g., heavy computation in view body, misusing `@State`), and `@MainActor` violations.
- Prefer the smallest refactor that meaningfully improves safety and clarity.
- If you remove or replace a pattern, briefly explain why the replacement is more idiomatic or safer.

## Examples

**Example 1**

User: "Can you refactor this Swift code to use proper optionals instead of force-unwrapping everywhere?"

Good response shape: refactored code with `guard let` / `if let` / `??`, explanation of why force-unwrapping is risky, and a note about when `!` is actually appropriate.

**Example 2**

User: "How do I run three network requests in parallel and wait for all of them?"

Good response shape: show `async let` for the simple case and `TaskGroup` for the dynamic case, explain the difference, note which Swift version introduced each.

**Example 3**

User: "I'm getting 'Type does not conform to Sendable' — how do I fix this?"

Good response shape: diagnose what's non-Sendable and why, show how to make it Sendable (or use `@unchecked Sendable` as a last resort), explain the concurrency safety model that's driving the error.

**Example 4**

User: "My SwiftUI view isn't updating when I change a property on my observed class."

Good response shape: diagnose whether they're using `@Observable` vs `ObservableObject`, check property wrapper usage, show corrected example, explain the Observation framework's per-property tracking, and cite the official Observation docs.

**Example 5**

User: "Set up a Swift package with two targets and a test suite using Swift Testing."

Good response shape: minimal `Package.swift`, target code, `@Test` example, version assumptions, and links to SPM and Swift Testing docs.

**Example 6**

User: "How do I call my existing Objective-C networking layer from Swift?"

Good response shape: bridging header setup, nullability annotation guidance, example call site, common pitfalls (implicitly unwrapped optionals), and link to the interop documentation.

**Example 7**

User: "Set up a minimal Vapor REST API with a health check endpoint and tests."

Good response shape: `Package.swift` with Vapor dependency, minimal route setup, async handler, test example, deployment notes, and links to Vapor docs.

## Reference file

Read `references/official-sources.md` when you need the curated official doc map or want source links to include in the answer.
