---
name: skie
description: SKIE (Touchlab) reference — Kotlin Native compiler plugin improving Swift interop for KMP. Restores language features lost in Kotlin→ObjC→Swift bridge. Features (exhaustive enums, sealed classes with onEnum(of:), default arguments, global functions without FileKt, suspend as Swift async with cancellation, Flows as AsyncSequence). Preview features (Flows in SwiftUI with Observing/collect, Combine bridge). Installation (Gradle plugin co.touchlab.skie, framework-producing module only). Configuration (skie{} DSL, @FlowInterop annotations, isEnabled toggle). Migration guide for existing projects. Compatibility (Kotlin 2.0.0–2.3.10, Swift 5.8+/Xcode 14.3+). Limitations (enum generics, default arg overload cap, generic suspend, Flow casting). Use when configuring SKIE, consuming Kotlin types from Swift, migrating to SKIE, or any SKIE question.
---

<objective>
Provide comprehensive, accurate reference for the SKIE compiler plugin — everything needed to install, configure, use, and troubleshoot SKIE in a Kotlin Multiplatform project targeting iOS/macOS via Swift.
</objective>

<overview>
SKIE (pronounced "sky") is a Kotlin Native compiler plugin by Touchlab that improves Swift interop for Kotlin Multiplatform. Without SKIE, Kotlin communicates with Swift only through Objective-C, losing many language features. SKIE modifies the Xcode Framework produced by the Kotlin compiler to restore these features. It requires no changes to how you distribute or consume KMP frameworks.

- **Current version:** 0.10.11
- **Kotlin compatibility:** 2.0.0 through 2.3.10
- **Swift compatibility:** 5.8+ (Xcode 14.3+)
- **Gradle plugin ID:** `co.touchlab.skie`
- **Configuration annotations:** `co.touchlab.skie:configuration-annotations:0.10.11`
</overview>

<installation>
**Step 1:** Locate the KMP module that creates Xcode Frameworks (has `kotlin("native.cocoapods")` plugin or a `framework` block inside the `kotlin` configuration).

**Step 2:** Add the SKIE Gradle plugin:

```kotlin
// build.gradle.kts
plugins {
    id("co.touchlab.skie") version "0.10.11"
}
```

The plugin only needs to be applied in the module that creates Xcode Frameworks. SKIE will instrument all code exported in that Framework, including exported dependencies.

Ensure `mavenCentral()` is in your plugin repositories (settings.gradle.kts):
```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
```

**Gradle cache issue:** If Gradle fails to resolve SKIE artifacts after a new release, run `./gradlew dependencies --refresh-dependencies`.

**Step 3:** For existing projects, read the migration notes before building. For new projects, build your Xcode Framework and start using SKIE features.
</installation>

<features>

## Enums — Exhaustive Switching

SKIE generates wrapping Swift enums for Kotlin enums, enabling exhaustive `switch` without `default`. The original Kotlin enum is still available prefixed with `__` (e.g., `__Turn`).

**Kotlin:**
```kotlin
enum class Turn { Left, Right }
```

**Swift with SKIE:**
```swift
func changeDirection(turn: Turn) {
    switch turn {
    case .left:  goLeft()
    case .right: goRight()
    }
}
```

**Case naming:** SKIE uses a sophisticated algorithm supporting both UPPER_SNAKE_CASE and PascalCase (Kotlin's default algorithm only handles UPPER_SNAKE_CASE). Cases colliding with Swift keywords get a `the` prefix (e.g., `zone` → `theZone`).

**Conversion methods:**
- `turn.toKotlinEnum()` → `__Turn`
- `kotlinEnum.toSwiftEnum()` → `Turn`
- `turn as __Turn` / `kotlinEnum as Turn` (casting works both ways)

**Built-in properties preserved:**
- `name` — returns the Kotlin case name
- `ordinal` — same as Kotlin
- `values()` → replaced by `allCases` (CaseIterable conformance)
- `valueOf(String)` — use `__Turn.valueOf(String)` and convert with `toSwiftEnum()`

**Limitations:**
- Swift enums cannot implement Obj-C protocols, so Kotlin enum interfaces are not carried over. Use `toKotlinEnum()` to pass to functions expecting the interface type.
- Enums in generics: Obj-C generics require class types, so `ResultWrapper<Turn>` becomes `ResultWrapper<__Turn>` in Swift. Use `toSwiftEnum()` on the value.

## Sealed Classes — onEnum(of:)

SKIE generates a wrapping Swift enum for Kotlin sealed classes/interfaces, plus a global `onEnum(of:)` function for conversion.

**Kotlin:**
```kotlin
sealed class Status {
    object Loading : Status()
    data class Error(val message: String) : Status()
    data class Success(val result: SomeData) : Status()
}
```

**Swift with SKIE:**
```swift
func updateStatus(status: Status) {
    switch onEnum(of: status) {
    case .loading:
        showLoading()
    case .error(let data):
        showError(message: data.message)
    case .success(let data):
        showResult(data: data.result)
    }
}
```

**Optional sealed class:** An overload of `onEnum(of:)` accepts an optional, adding a `.none` case.

**Hidden subclasses:** If some subclasses are `internal`/`private`, SKIE generates an `.else` case to handle them.

**Hashable:** SKIE adds `Hashable` conformance to the generated enum when all exposed direct children of the sealed type are classes. Sealed interfaces with interface children require manual `Hashable` implementation via Swift extensions on the generated enum.

**Migration:** This feature should not cause breaking changes.

## Default Arguments

SKIE generates Kotlin overloads that simulate default arguments (since Obj-C has no equivalent).

**Disabled by default** — enable selectively via annotation configuration:

```kotlin
import co.touchlab.skie.configuration.annotations.DefaultArgumentInterop

@DefaultArgumentInterop.Enabled
fun sayHello(message: String = "Hello") {
    println(message)
}
```

Add the annotations dependency:
```kotlin
val commonMain by sourceSets.getting {
    dependencies {
        implementation("co.touchlab.skie:configuration-annotations:0.10.11")
    }
}
```

**Limitations:**
- Generates O(2^n) overloads where n = number of default arguments (capped at 5 params, max 31 overloads)
- Does not support interface methods
- Not compatible with Kotlin native library caching when applied to 3rd-party library functions

## Global Functions and Properties

SKIE generates actual global Swift functions, eliminating the `FileKt.` namespace prefix.

**Without SKIE:** `FileKt.globalFunction(i: 1)`
**With SKIE:** `globalFunction(i: 1)`

Original namespaced functions remain available for backward compatibility.

## Interface Extensions

Interface extension functions become member-style calls instead of static calls.

**Kotlin:**
```kotlin
interface I
class C : I
fun I.interfaceExtension(i: Int): Int = i
```

**Without SKIE:** `FileKt.interfaceExtension(C(), i: 1)`
**With SKIE:** `C().interfaceExtension(i: 1)`

## Overloaded Functions

SKIE preserves original function names for overloads that Kotlin would normally rename with `_` suffix for Obj-C compatibility.

**Without SKIE:** `foo(i: 1)` and `foo(i_: "A")`
**With SKIE:** `foo(i: 1)` and `foo(i: "A")`

## Suspend Functions — Proper Swift Async

SKIE generates real Swift async functions with a custom runtime bridging Kotlin Coroutines and Swift Concurrency.

**Key improvements over vanilla Kotlin:**
- Two-way cancellation: canceling a Swift `Task` cancels the Kotlin coroutine, and vice versa
- No main-thread restriction: call suspend functions from any thread
- Kotlin `CancellationException` maps to Swift `CancellationError`

**Kotlin:**
```kotlin
class ChatRoom {
    suspend fun send(message: String) { /* ... */ }
}
```

**Swift with SKIE:**
```swift
let chatRoom = ChatRoom()
let task = Task.detached {
    try? await chatRoom.send(message: "some message")
}
task.cancel() // Also cancels the Kotlin coroutine
```

**Generic classes:** Use the `skie()` wrapper for member/extension suspend functions of generic classes:
```swift
let a = A<NSString>()
try await skie(a).foo()
```

**Overriding suspend functions:** Override the `__`-prefixed version in Swift subclasses:
```swift
class B: A {
    override func __foo() async throws -> KotlinInt {
        return KotlinInt(1)
    }
}
```
Note: calls from the overridden function to other async functions lose cancellation bridging.

**Migration note:** SKIE changes threading semantics — Swift 5.7+ runs async functions on background threads by default, while Kotlin Coroutines stay on the calling thread. Add explicit thread switching in suspend functions if your code depends on running on the main thread.

## Flows — AsyncSequence

SKIE converts Kotlin Flows to Swift classes implementing `AsyncSequence`, with preserved generics and two-way cancellation.

**Supported Flow types and their Swift equivalents:**
- `Flow` → `SkieSwiftFlow`
- `SharedFlow` → `SkieSwiftSharedFlow`
- `MutableSharedFlow` → `SkieSwiftMutableSharedFlow`
- `StateFlow` → `SkieSwiftStateFlow`
- `MutableStateFlow` → `SkieSwiftMutableStateFlow`

**Kotlin:**
```kotlin
class ChatRoom {
    val messages: StateFlow<List<String>> = MutableStateFlow(emptyList())
}
```

**Swift with SKIE:**
```swift
class ChatRoomViewModel: ObservableObject {
    let chatRoom = ChatRoom()
    @Published private(set) var messages: [String] = []

    @MainActor
    func activate() async {
        for await messages in chatRoom.messages {
            self.messages = messages // No type cast needed
        }
    }
}
```

**Cancellation:** Flow cancellation from Kotlin ends the Swift `for await` loop (consistent with `AsyncSequence` semantics). Use `withTaskCancellationHandler` if you need to handle cancellation explicitly.

**Type bridging:** Kotlin `String` in Flow generics becomes Swift `String` (not `NSString`), because SKIE's custom classes aren't constrained by Obj-C's `AnyObject` requirement.

**Nullable type arguments:** `Flow<Int?>` maps to `SkieSwiftOptionalFlow<Int>` (separate class hierarchy from non-optional variants). Convert between them using conversion constructors.

**Limitations:**
- Custom exceptions in Flow cause runtime crash (cannot propagate to Swift)
- Type casting (`as!`, `as?`, `is`) on `SkieKotlin___Flow` is unsafe — use conversion constructors instead
- `SkieSwift___Flow` classes do not inherit from each other
- Flows inside generics (`List<Flow<*>>`, `Map<*, Flow<*>>`, `Flow<Flow<*>>`) and return types of SKIE-generated suspend functions are not auto-converted — use manual conversion: `listOfFlows.map { SkieSwiftFlow(SkieKotlinFlow<KotlinInt>($0)) }`
- Custom Flow types not supported
- No `AsyncSequence` → `Flow` conversion

## Swift Code Bundling

Bundle hand-written Swift code into the Kotlin framework alongside SKIE-generated code.

**Source set locations** (derived from Kotlin source sets):
- `src/commonMain/kotlin` → `src/commonMain/swift`
- `src/iosArm64Main/kotlin` → `src/iosArm64Main/swift`
- `src/${kotlinSourceSetName}/kotlin` → `src/${kotlinSourceSetName}/swift`

Swift source sets follow the Kotlin hierarchy and are only created in the module where SKIE is applied.

**Important:** Swift defaults to `internal` visibility — use `public` explicitly for declarations that need to be visible outside the framework.

The bundled Swift code shares the same Framework module as Kotlin code, so no import is needed to call Kotlin APIs.

</features>

<preview_features>

## Flows in SwiftUI (Preview)

Enable in Gradle:
```kotlin
skie {
    features {
        enableSwiftUIObservingPreview = true
    }
}
```

**`Observing` view** — observe one or more Flows directly in SwiftUI:
```swift
// With StateFlow or Flow + initial value
Observing(viewModel.counter.withInitialValue(0), viewModel.toggle) { counter, toggle in
    Text("Counter: \(counter), Toggle: \(toggle)")
}

// With initial content view (for non-StateFlow flows)
Observing(viewModel.counter, viewModel.toggle) {
    ProgressView("Waiting...")
} content: { counter, toggle in
    Text("Counter: \(counter), Toggle: \(toggle)")
}
```

**`collect` view modifier** — collect a Flow into a `@State` property:
```swift
@State var counter: KotlinInt = 0

Text("Counter: \(counter)")
    .collect(flow: viewModel.counter, into: $counter)

// Or with async closure for custom processing:
Text("Counter: \(manualCounter)")
    .collect(flow: viewModel.counter) { latestValue in
        manualCounter = latestValue.intValue
    }
```

## Combine Bridge (Preview)

**Suspend function → `Combine.Future`:**
```kotlin
skie {
    features {
        enableFutureCombineExtensionPreview = true
    }
}
```
```swift
let future = Future(async: helloWorld)
future.sink { error in /* handle */ } receiveValue: { value in print(value) }
```

**Flow → `Combine.Publisher`:**
```kotlin
skie {
    features {
        enableFlowCombineConvertorPreview = true
    }
}
```
```swift
let publisher = helloWorld().toPublisher()
publisher.sink { value in /* each emitted value */ }
```

Note: Store the cancellable returned by `sink` to prevent immediate cancellation. Futures are hot and invoke immediately.

</preview_features>

<configuration>

## Gradle Configuration

The `skie {}` extension in `build.gradle.kts` configures features globally or selectively.

**Disable a feature globally:**
```kotlin
import co.touchlab.skie.configuration.FlowInterop
skie {
    features {
        group {
            FlowInterop.Enabled(false)
        }
    }
}
```

**Selective by package prefix:**
```kotlin
skie {
    features {
        group {
            FlowInterop.Enabled(false) // default: disabled
        }
        group("co.touchlab.skie.types") {
            FlowInterop.Enabled(true) // override for this package
        }
    }
}
```

Group matching is prefix-based on fully qualified names. Last matching group wins. Use `overridesAnnotations = true` on a group to prevent annotation overrides:
```kotlin
group("co.touchlab.skie.types", overridesAnnotations = true) {
    FlowInterop.Enabled(false) // annotations cannot override this
}
```

**Disable SKIE entirely** (useful for debugging):
```kotlin
skie {
    isEnabled.set(false)
}
```

## Annotation Configuration

Add per-declaration configuration directly in Kotlin source code.

**Dependency (add to modules using annotations):**
```kotlin
val commonMain by sourceSets.getting {
    dependencies {
        implementation("co.touchlab.skie:configuration-annotations:0.10.11")
    }
}
```

**Usage:**
```kotlin
import co.touchlab.skie.configuration.annotations.FlowInterop

@FlowInterop.Enabled
fun enabledFlow(): Flow<Int> = flowOf(1)

@FlowInterop.Disabled
fun disabledFlow(): Flow<Int> = flowOf(1)
```

Annotations override Gradle configuration by default (unless `overridesAnnotations = true` is set on the Gradle group).

</configuration>

<migration>

## Migrating Existing Projects

SKIE causes some source-breaking changes in Swift code. Kotlin code should not need changes.

**Common migration tasks:**
1. **Enum case names** — SKIE's naming algorithm differs from Kotlin's. Look for `Type 'X' has no member 'y'` errors. Check generated Swift enums in Xcode for correct names.
2. **Exhaustive switch** — Remove now-unnecessary `default` cases (they produce warnings).
3. **Sealed classes** — Adopt `onEnum(of:)` pattern. Not a breaking change (additive).
4. **Flows** — Remove manual `Flow` → `AsyncSequence` conversions, remove unnecessary type casts, replace runtime Flow casts with conversion constructors.
5. **Suspend functions** — Add `__` prefix to overridden suspend function names. Wrap generic class receivers with `skie()`.
6. **Threading** — Verify code doesn't depend on suspend functions running on main thread.

**Incremental migration:** SKIE is fully configurable — enable/disable features per-package or per-declaration, so migration can be done iteratively.

</migration>

<compatibility>

## Kotlin Compatibility

SKIE supports Kotlin 2.0.0 through 2.3.10. A single SKIE version supports multiple Kotlin versions. SKIE checks compatibility during installation and reports unsupported versions.

New SKIE versions supporting new Kotlin releases typically ship within a couple of working days. Preview SKIE versions for Kotlin RC1/RC2 are sometimes published.

Policy: at least two feature releases of Kotlin supported (e.g., if latest is 2.1.x, support from 2.0.0+).

## Swift Compatibility

SKIE supports Swift 5.8 (Xcode 14.3) and newer. Some features require newer Swift versions and are automatically unavailable on older ones. Minimum supported version increases over time (at least one year of support after release).

</compatibility>

<gotchas>
- Default arguments are **disabled by default** — enable selectively with `@DefaultArgumentInterop.Enabled` annotations
- SKIE runs on **all exported dependencies**, not just your code — use Gradle configuration to control scope
- The `__` prefix on original Kotlin types (enums, suspend functions) is intentional — SKIE generates wrappers that replace them
- Flow type casting (`as!`, `as?`, `is`) on SKIE types is **unsafe at runtime** — always use conversion constructors
- Swift code bundled into the framework defaults to `internal` visibility — add `public` explicitly
- Combine `Future` is hot (executes immediately) and doesn't support cancellation
- Store Combine `sink` cancellables or collection stops immediately
- Suspend function overrides in Swift lose SKIE's cancellation bridge
- `skie {}` group matching is **prefix-based** — a group for class `Foo` also matches `FooBar`
</gotchas>

<success_criteria>
The agent should be able to:
- Install and configure SKIE in a KMP project
- Write idiomatic Swift code consuming SKIE-enhanced Kotlin APIs (enums, sealed classes, suspend functions, Flows)
- Configure SKIE features globally or per-declaration
- Diagnose and fix common SKIE migration errors
- Explain SKIE limitations and provide workarounds
- Set up Swift code bundling, SwiftUI Flow observation, and Combine bridges
</success_criteria>
