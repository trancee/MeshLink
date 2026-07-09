---
name: skie
description: SKIE (Touchlab) reference — Kotlin Native compiler plugin improving Swift interop for KMP. Restores language features lost in Kotlin→ObjC→Swift bridge. Features (exhaustive enums, sealed classes with onEnum(of:), default arguments, global functions without FileKt, suspend as Swift async with cancellation, Flows as AsyncSequence). Preview features (Flows in SwiftUI with Observing/collect, Combine bridge). Installation (Gradle plugin co.touchlab.skie, framework-producing module only). Configuration (skie{} DSL, per-feature annotation/Gradle keys for enums/sealed/functions/coroutines/default arguments, warning suppression, isEnabled toggle). Distributable framework build config (Swift library evolution, XCFrameworks, relative debug source paths). Analytics opt-out. Known issues (cinterop framework names, missing Foundation import, lambda type arguments, Gradle cache). Migration guide for existing projects. Compatibility (Kotlin 2.0.0–2.4.0, Swift 5.8+/Xcode 14.3+). Use when configuring SKIE, consuming Kotlin types from Swift, migrating to SKIE, or any SKIE question.
---

<objective>
Provide comprehensive, accurate reference for the SKIE compiler plugin — everything needed to install, configure, use, and troubleshoot SKIE in a Kotlin Multiplatform project targeting iOS/macOS via Swift.
</objective>

<overview>
SKIE (pronounced "sky") is a Kotlin Native compiler plugin by Touchlab that improves Swift interop for Kotlin Multiplatform. Without SKIE, Kotlin communicates with Swift only through Objective-C, losing many language features. SKIE modifies the Xcode Framework produced by the Kotlin compiler to restore these features. It requires no changes to how you distribute or consume KMP frameworks.

- **Current version:** 0.10.13
- **Kotlin compatibility:** 2.0.0 through 2.4.0
- **Swift compatibility:** 5.8+ (Xcode 14.3+)
- **Gradle plugin ID:** `co.touchlab.skie`
- **Configuration annotations:** `co.touchlab.skie:configuration-annotations:0.10.13`
- **Analytics:** SKIE collects non-identifying analytics by default (see `<analytics>`); opt-out is supported.
</overview>

<installation>
**Step 1:** Locate the KMP module that creates Xcode Frameworks (has `kotlin("native.cocoapods")` plugin or a `framework` block inside the `kotlin` configuration).

**Step 2:** Add the SKIE Gradle plugin:

```kotlin
// build.gradle.kts
plugins {
    id("co.touchlab.skie") version "0.10.13"
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
        implementation("co.touchlab.skie:configuration-annotations:0.10.13")
    }
}
```

**Limitations:**
- Generates O(2^n) overloads where n = number of default arguments, capped at 5 by default (max 31 overloads). Raise the cap per-declaration with `@DefaultArgumentInterop.MaximumDefaultArgumentCount(n)` — each extra argument doubles the overload count, so raising it risks long compiles or the compiler running out of memory.
- Does not support interface methods
- Disabled for 3rd-party library functions by default. Enable via the Gradle `defaultArgumentsInExternalLibraries` flag, then opt in per-declaration — this disables Kotlin native compiler caching for those libraries, which can significantly increase compilation time.

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
            FlowInterop.Enabled(false) // override the default (true) for the whole project
        }
        group("co.touchlab.skie.types") {
            FlowInterop.Enabled(true) // override again for this package
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
        implementation("co.touchlab.skie:configuration-annotations:0.10.13")
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

## Per-Feature Configuration Keys

Each key below exists as both a Gradle config class (`co.touchlab.skie.configuration.*`, used inside a `group { }` block) and, where noted, an annotation (`co.touchlab.skie.configuration.annotations.*`, applied directly to the declaration).

| Key | Applies to | Default | Notes |
|---|---|---|---|
| `EnumInterop.Enabled` | enum class | `true` | Disables Swift enum generation for this enum |
| `EnumInterop.LegacyCaseName` | enum class | `false` | Reverts to the original Kotlin compiler case-naming algorithm |
| `SealedInterop.Enabled` | sealed class/interface | `true` | Disables the wrapping enum and `onEnum(of:)` |
| `SealedInterop.ExportEntireHierarchy` | sealed class/interface | `true` | Forces export of all public sealed children to Obj-C even if the Kotlin compiler wouldn't |
| `SealedInterop.Function.Name` | sealed class/interface | `"onEnum"` | Renames the generated conversion function |
| `SealedInterop.Function.ArgumentLabel` | sealed class/interface | `"of"` | `""` omits the label, `"_"` disables it explicitly |
| `SealedInterop.Function.ParameterName` | sealed class/interface | `"sealed"` | Parameter name of the conversion function |
| `SealedInterop.ElseName` | sealed class/interface | `"else"` | Name of the case grouping hidden subclasses |
| `SealedInterop.Case.Visible` | sealed subclass | `true` | `false` hides this subclass's dedicated case (folded into `.else`) |
| `SealedInterop.Case.Name` | sealed subclass | `null` | Overrides the generated case name |
| `FunctionInterop.FileScopeConversion.Enabled` | global function/interface extension | `true` | Disables the wrapper that drops the `FileKt.` prefix / makes extensions member-style |
| `FunctionInterop.LegacyName` | function/property | `false` | Reverts to the original Kotlin compiler naming algorithm (still overridden on conflicts) |
| `CoroutinesInterop` (top-level `features {}` property, not a `group`) | whole project | `true` | Master switch; individual coroutine features below still need enabling |
| `SuspendInterop.Enabled` | suspend function | `true` | Disables Swift `async` generation for this function |
| `FlowInterop.Enabled` | function/property returning a Flow type | `true` | Disables `AsyncSequence` bridging for this signature |
| `DefaultArgumentInterop.Enabled` | function with default arguments | `false` | Opt-in; see Default Arguments feature for overload-count caveats |
| `DefaultArgumentInterop.MaximumDefaultArgumentCount` | function | `5` | Raises the overload cap (2^n growth — raise cautiously) |
| `defaultArgumentsInExternalLibraries` (top-level `features {}` property) | whole project | `false` | Prerequisite for enabling default arguments on 3rd-party functions; disables native compiler caching for them |
| `SuppressSkieWarning.NameCollision` | any declaration | `false` | Silences the warning SKIE emits when it renames a declaration to resolve a name collision |

Annotation form mirrors the Gradle key, e.g. `@EnumInterop.LegacyCaseName.Enabled`, `@SealedInterop.Case.Hidden`, `@SuspendInterop.Disabled`, `@SuppressSkieWarning.NameCollision`. Import from `co.touchlab.skie.configuration.annotations` for annotations and `co.touchlab.skie.configuration` for Gradle `group { }` classes.

## Swift Code Bundling Toggle

Swift code bundling (see Features) is itself configurable per module:
```kotlin
skie {
    swiftBundling {
        enabled = false // default: true
    }
}
```

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

SKIE supports Kotlin 2.0.0 through 2.4.0. A single SKIE version supports multiple Kotlin versions. SKIE checks compatibility during installation and reports unsupported versions.

New SKIE versions supporting new Kotlin releases typically ship within a couple of working days. Preview SKIE versions for Kotlin RC1/RC2 are sometimes published.

Policy: at least two feature releases of Kotlin supported (e.g., if latest is 2.1.x, support from 2.0.0+).

## Swift Compatibility

SKIE supports Swift 5.8 (Xcode 14.3) and newer. Some features require newer Swift versions and are automatically unavailable on older ones. Minimum supported version increases over time (at least one year of support after release).

</compatibility>

<build_configuration>

## Building Distributable Frameworks

SKIE-produced frameworks are **non-distributable by default**, because SKIE adds Swift code and normally assumes the framework only needs to build the final binary on the same machine. If the Kotlin framework itself will be shipped to other computers (binary distribution, XCFrameworks, SPM/CocoaPods binary targets), opt in explicitly:

```kotlin
skie {
    build {
        produceDistributableFramework()
    }
}
```

`produceDistributableFramework()` is a convenience that turns on three properties, each of which can also be set individually:

- **`enableSwiftLibraryEvolution`** — enables Swift's ABI-stability mode. Off by default since most KMP projects don't need it; automatically required (and enabled) when building XCFrameworks. Do not rely on that implicit behavior — set it explicitly if you need it.
  ```kotlin
  skie { build { enableSwiftLibraryEvolution.set(true) } }
  ```
- **`noClangModuleBreadcrumbsInStaticFrameworks`** — passes `-no-clang-module-breadcrumbs` to the Swift frontend for static frameworks, avoiding a "No such file or directory" / degraded-debug-info warning when the framework is imported on a machine that didn't build it.
  ```kotlin
  skie { build { noClangModuleBreadcrumbsInStaticFrameworks.set(true) } }
  ```
- **`enableRelativeSourcePathsInDebugSymbols`** — makes debug-info source paths relative (working around a Kotlin compiler bug that otherwise requires declaring every source directory), so LLDB can still hit breakpoints on a different machine. Only applies to project-local sources; 3rd-party dependency sources stay absolute.
  ```kotlin
  skie { build { enableRelativeSourcePathsInDebugSymbols.set(true) } }
  ```

Prefer `produceDistributableFramework()` unless you need to diverge from one of the three settings individually.

</build_configuration>

<analytics>

SKIE collects non-identifying analytics (SKIE/Kotlin/Gradle/Xcode versions, hashed project/module IDs, hardware, git repo stats, declaration counts, link-task timings) to guide performance work. Collection is optional and has two independent phases: **capture** (writes JSON under `build/skie/{framework}/{arch}/analytics`) and **upload**.

**Disable upload only** (inspect captured JSON locally before deciding):
```kotlin
skie {
    analytics {
        disableUpload.set(true)
    }
}
```

**Disable capture and upload entirely:**
```kotlin
skie {
    analytics {
        enabled.set(false)
    }
}
```

**Fine-grained control** — re-enable or suppress individual categories (`SkieConfigurationFlag.Analytics_SkiePerformance`, `_GradleEnvironment`, `_GradlePerformance`, `_Project`, `_Modules`, `_Hardware`, `_Git`, `_CompilerConfiguration`, `_CompilerEnvironment`, `_SkieConfiguration`) via `additionalConfigurationFlags` / `suppressedConfigurationFlags` on the `skie {}` extension, e.g. to send everything except git statistics:
```kotlin
skie {
    suppressedConfigurationFlags.add(SkieConfigurationFlag.Analytics_Git)
}
```

</analytics>

<known_issues>

## Cinterop Types Not Recognized

SKIE cannot infer the Obj-C framework backing a type that comes from a custom cinterop binding (built-in Kotlin/Native cinterop and CocoaPods-plugin cinterop work automatically). Without configuration, SKIE emits an unusable placeholder (`__SkieUnknownCInteropFrameworkErrorType`) that compiles but crashes if called. Fix by telling SKIE the framework name:
```kotlin
import co.touchlab.skie.configuration.ClassInterop
skie {
    features {
        group("co.touchlab.crashkios.bugsnag") {
            ClassInterop.CInteropFrameworkName("Bugsnag")
        }
    }
}
```
The original Kotlin declaration remains callable either way.

## Missing Foundation Classes After Adding SKIE

Plain Kotlin/Native frameworks transitively export `Foundation`, so Swift files importing the Kotlin module get Foundation classes for free. SKIE frameworks **do not** transitively export `Foundation` (it would risk name collisions with the bundled Swift code). Fix: add `import Foundation` next to every `import YourKotlinModuleName`.

## Lambda Type as a Generic Type Argument

Swift cannot express a Kotlin generic instantiated with a function type (e.g. `A<() -> Unit>`) as a class type. SKIE works around the resulting compile error by substituting a special `@available(*, unavailable)` type (`__SkieLambdaErrorType`) so the rest of the file still compiles; the specific declaration using that type is uncallable from Swift. Only lambda type arguments are affected — `A<Unit>` and other non-function type arguments work normally.

## Gradle Artifact Cache Lag

After a new SKIE release, Gradle's regional artifact caches can lag and cache a 404, producing artifact-resolution errors or a `ClassNotFoundException` from a mismatched cached jar. Run `./gradlew dependencies --refresh-dependencies` (or any task) to force re-resolution; retry after a delay if the region's cache hasn't caught up yet.

</known_issues>

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
- SKIE-produced frameworks are **non-distributable by default** — call `produceDistributableFramework()` before shipping the framework binary itself to another machine
- Cinterop-sourced types need an explicit `ClassInterop.CInteropFrameworkName` or SKIE generates an uncallable placeholder
- SKIE frameworks don't transitively export `Foundation` — add `import Foundation` alongside the Kotlin module import
</gotchas>

<success_criteria>
The agent should be able to:
- Install and configure SKIE in a KMP project
- Write idiomatic Swift code consuming SKIE-enhanced Kotlin APIs (enums, sealed classes, suspend functions, Flows)
- Configure SKIE features globally or per-declaration, using the correct per-feature key and its default
- Diagnose and fix common SKIE migration errors
- Explain SKIE limitations and provide workarounds, including the cinterop, Foundation-import, and lambda-type-argument known issues
- Set up Swift code bundling, SwiftUI Flow observation, and Combine bridges
- Configure a distributable-framework build (library evolution, XCFrameworks, relative debug paths) and adjust or disable SKIE analytics
</success_criteria>
