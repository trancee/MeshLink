# Official sources for `swift-expert`

Use this file as a quick map to the official documentation that should anchor answers. When linking docs in a response, prefer the most specific link that covers the user's actual question.

## When to use which source

- **Syntax, types, concurrency** → Swift Book chapters (docs.swift.org)
- **SwiftUI views, modifiers, data flow** → Apple SwiftUI docs + WWDC sessions
- **Observation framework (@Observable)** → Apple Observation docs
- **Testing** → Swift Testing docs (modern) or XCTest docs (legacy)
- **SPM setup** → swift.org package manager docs + PackageDescription API reference
- **ObjC/C++ interop** → Apple interop guides + swift.org C++ interop
- **Server-side** → Vapor/Hummingbird docs + swift.org server overview

## Core language

- `https://docs.swift.org/swift-book/documentation/the-swift-programming-language/thebasics`
  - Use for constants, variables, type annotations, type safety, type inference, numeric types, tuples, optionals, and assertions/preconditions.

- `https://docs.swift.org/swift-book/documentation/the-swift-programming-language/stringsandcharacters`
  - Use for string mutability, character iteration, Unicode scalars, string interpolation, substrings, and string indexing.

- `https://docs.swift.org/swift-book/documentation/the-swift-programming-language/collectiontypes`
  - Use for `Array`, `Set`, `Dictionary` creation, access, iteration, and set operations.

- `https://docs.swift.org/swift-book/documentation/the-swift-programming-language/controlflow`
  - Use for `if`, `guard`, `switch`, `for-in`, `while`, `repeat-while`, ranges, pattern matching, `where` clauses, and labeled statements.

- `https://docs.swift.org/swift-book/documentation/the-swift-programming-language/functions`
  - Use for parameter labels, default values, variadic parameters, in-out parameters, function types, and nested functions.

- `https://docs.swift.org/swift-book/documentation/the-swift-programming-language/closures`
  - Use for closure syntax, trailing closures, capturing values, escaping closures, and autoclosures.

- `https://docs.swift.org/swift-book/documentation/the-swift-programming-language/enumerations`
  - Use for enum syntax, associated values, raw values, recursive enumerations, and pattern matching on enums.

- `https://docs.swift.org/swift-book/documentation/the-swift-programming-language/classesandstructures`
  - Use for value vs reference semantics, memberwise initializers, identity operators, and choosing between structs and classes.

## Type system and abstraction

- `https://docs.swift.org/swift-book/documentation/the-swift-programming-language/protocols`
  - Use for protocol requirements, protocol extensions, default implementations, protocol inheritance, class-only protocols, protocol composition, and checking conformance.

- `https://docs.swift.org/swift-book/documentation/the-swift-programming-language/generics`
  - Use for generic functions, generic types, type constraints, associated types, `where` clauses, and generic subscripts.

- `https://docs.swift.org/swift-book/documentation/the-swift-programming-language/opaquetypes`
  - Use for `some` (opaque return types) vs `any` (existential types), boxed protocol types, and when to use each.

- `https://docs.swift.org/swift-book/documentation/the-swift-programming-language/extensions`
  - Use for adding computed properties, methods, initializers, subscripts, nested types, and protocol conformances via extensions.

## Concurrency

- `https://docs.swift.org/swift-book/documentation/the-swift-programming-language/concurrency`
  - Use for `async`/`await`, `async let`, task groups, structured vs unstructured concurrency, actors, `Sendable`, `@MainActor`, and data-race safety.
  - Key version details: `async`/`await` introduced in Swift 5.5; actors and structured concurrency in 5.5; strict Sendable checking evolving through Swift 5.7–6.0+.

## Error handling and memory

- `https://docs.swift.org/swift-book/documentation/the-swift-programming-language/errorhandling`
  - Use for `throws`/`try`/`catch`, error propagation, `try?`, `try!`, `defer`, and defining custom error types.
  - Typed throws available in Swift 6+.

- `https://docs.swift.org/swift-book/documentation/the-swift-programming-language/automaticreferencecounting`
  - Use for strong/weak/unowned references, retain cycles, capture lists in closures, and ARC with closures.

- `https://docs.swift.org/swift-book/documentation/the-swift-programming-language/memorysafety`
  - Use for conflicting access to memory, in-out parameter conflicts, and understanding Swift's memory safety guarantees.

## Advanced features

- `https://docs.swift.org/swift-book/documentation/the-swift-programming-language/macros`
  - Use for freestanding macros, attached macros, macro declarations, and macro expansion. Macros require Swift 5.9+.

- `https://docs.swift.org/swift-book/documentation/the-swift-programming-language/advancedoperators`
  - Use for bitwise operators, overflow operators, operator overloading, custom operators, and result builders.

- `https://docs.swift.org/swift-book/documentation/the-swift-programming-language/accesscontrol`
  - Use for `open`, `public`, `package`, `internal`, `fileprivate`, `private`, and access control for specific constructs.

## Properties and initialization

- `https://docs.swift.org/swift-book/documentation/the-swift-programming-language/properties`
  - Use for stored properties, computed properties, property observers (`willSet`/`didSet`), property wrappers, and type properties.

- `https://docs.swift.org/swift-book/documentation/the-swift-programming-language/initialization`
  - Use for default initializers, memberwise initializers, designated vs convenience initializers, required initializers, failable initializers, and two-phase initialization.

- `https://docs.swift.org/swift-book/documentation/the-swift-programming-language/deinitialization`
  - Use for deinitializers and cleanup patterns.

## Testing

- `https://developer.apple.com/documentation/testing`
  - Use for Swift Testing framework: `@Test`, `#expect`, `#require`, parameterized tests, suites, and tags. Requires Swift 6+.

- `https://developer.apple.com/documentation/xctest`
  - Use for XCTest: `XCTestCase`, `XCTAssert*`, performance tests, async test methods, and setUp/tearDown patterns.

## Swift Package Manager

- `https://www.swift.org/documentation/package-manager/`
  - Use for SPM concepts, `Package.swift` manifest format, targets, products, dependencies, and plugins.

- `https://github.com/swiftlang/swift-package-manager/blob/main/Documentation/PackageDescription.md`
  - Use for the definitive `Package.swift` API reference: `Package`, `Target`, `Product`, `Dependency`, platform requirements, and Swift settings.

## How to cite these sources

- Link to the most specific page that covers the user's question — not just the top-level doc.
- For conceptual understanding, prefer WWDC sessions (below) over API reference.
- For API details, link the Apple developer docs directly.
- If a recommendation depends on a specific Swift version, say so explicitly.
- When the user is debugging a compiler error, include the exact doc link that covers the relevant language concept.

## WWDC sessions (conceptual deep dives)

These are often the best resource for *understanding why* a feature works the way it does:

- **Observation framework:** "Discover Observation in SwiftUI" (WWDC23) — explains per-property tracking, migration from ObservableObject, and the @Observable macro.
- **Swift concurrency:** "Meet async/await in Swift" (WWDC21), "Protect mutable state with Swift actors" (WWDC21), "Swift concurrency: Update a sample app" (WWDC21) — the foundational concurrency talks.
- **Sendable and data-race safety:** "Eliminate data races using Swift Concurrency" (WWDC22), "Migrate your app to Swift 6" (WWDC24) — covers strict concurrency checking and migration strategies.
- **SwiftUI data flow:** "Demystify SwiftUI" (WWDC21), "Data Essentials in SwiftUI" (WWDC20) — identity, lifetime, and dependency tracking.
- **Swift Testing:** "Meet Swift Testing" (WWDC24) — the modern testing framework with @Test, #expect, and parameterized tests.
- **C++ interop:** "Mix Swift and C++" (WWDC23) — direct C++ interoperability without bridging layers.
- **Navigation:** "The SwiftUI cookbook for navigation" (WWDC22) — NavigationStack, navigationDestination, and programmatic navigation.
- **Macros:** "Write Swift macros" (WWDC23), "Expand on Swift macros" (WWDC23) — freestanding and attached macros.

## SwiftUI and Observation

- `https://developer.apple.com/documentation/swiftui`
  - Use for SwiftUI views, modifiers, layouts, navigation, gestures, and animations. The canonical reference for all SwiftUI APIs.

- `https://developer.apple.com/documentation/observation`
  - Use for the `@Observable` macro, per-property tracking, and the Observation framework (iOS 17+/macOS 14+). This replaces `ObservableObject`/`@Published` for new code.

- `https://developer.apple.com/documentation/swiftui/model-data`
  - Use for data flow patterns: `@State`, `@Binding`, `@Environment`, `@Observable`, and how SwiftUI tracks dependencies.

- `https://developer.apple.com/documentation/swiftui/navigationstack`
  - Use for programmatic navigation, `navigationDestination(for:)`, and value-based routing.

## Objective-C and C++ interoperability

- `https://developer.apple.com/documentation/swift/importing-objective-c-into-swift`
  - Use for bridging headers, module maps, and how Objective-C APIs appear in Swift.

- `https://developer.apple.com/documentation/swift/migrating-your-objective-c-code-to-swift`
  - Use for migration strategies, bridging header management, and incremental adoption.

- `https://www.swift.org/documentation/cxx-interop/`
  - Use for C++ interoperability: calling C++ from Swift and vice versa, supported C++ features, and limitations.

- `https://developer.apple.com/documentation/swift/calling-objective-c-apis-asynchronously`
  - Use for how Objective-C completion-handler APIs are imported as async functions in Swift.

## Server-side Swift

- `https://docs.vapor.codes`
  - Use for Vapor framework: routing, middleware, Fluent ORM, authentication, WebSockets, and deployment.

- `https://hummingbird-project.github.io/hummingbird-docs/2.0/documentation/hummingbird/`
  - Use for Hummingbird framework: lightweight server setup, routing, and middleware.

- `https://www.swift.org/documentation/server/`
  - Use for Swift on Server ecosystem overview, performance characteristics, and deployment guidance.

- `https://swiftpackageindex.com`
  - Use for discovering Swift packages, checking platform compatibility, and finding popular libraries.

## High-value factual anchors

Use these when the user needs a precise, doc-grounded answer:

- **`some` vs `any`**
  - `some P` (opaque type) hides the concrete type from the caller but the compiler knows it — enables optimization and preserves type identity. Available as return types since Swift 5.1, as parameter types since Swift 5.7.
  - `any P` (existential type) is a runtime box that can hold any conforming type — required for heterogeneous collections. Explicit `any` keyword required since Swift 5.6.

- **Structured concurrency guarantees**
  - Child tasks created with `async let` or `TaskGroup` are always scoped to their parent. If the parent is cancelled, children are cancelled. If the parent returns, it awaits all children first.
  - `Task { }` creates an unstructured task that inherits the current actor context and priority. `Task.detached { }` inherits neither.

- **Sendable checking**
  - Swift 6 enables strict concurrency checking by default (`-strict-concurrency=complete`).
  - Value types with all-Sendable stored properties are implicitly `Sendable`.
  - Classes must explicitly conform to `Sendable` and must be `final` with immutable stored properties (or use `@unchecked Sendable` for manual thread safety).
  - `@Sendable` on closures restricts captures to Sendable types.

- **Actor isolation**
  - Actor-isolated properties and methods can only be accessed with `await` from outside the actor.
  - `nonisolated` opts out of isolation — the member cannot access the actor's mutable state.
  - `@MainActor` isolates to the main thread. It can be applied to types, functions, or closures.

- **Typed throws (Swift 6+)**
  - `func doSomething() throws(MyError)` specifies the exact error type.
  - Enables exhaustive `catch` blocks without a catch-all.
  - `throws(Never)` means the function cannot throw (equivalent to non-throwing).
  - `throws(any Error)` is equivalent to plain `throws`.
