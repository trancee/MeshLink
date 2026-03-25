---
name: kotlin-multiplatform-expert
description: "Deep specialist for Kotlin Multiplatform (KMP) projects targeting Android+iOS. ALWAYS use for ANY KMP question — KMP has subtle pitfalls (wrong source sets, broken iOS interop, Gradle misconfig) that generic Kotlin knowledge misses. Covers: commonMain/iosMain/appleMain source sets, expect/actual, kotlin(\"multiplatform\") plugin, CocoaPods/SPM/XCFramework, cinterop, SKIE plugin, Swift interop (@Throws, sealed classes), Compose Multiplatform, KMP testing (commonTest, kotlin.test, runTest, Turbine, fakes), Maven Central publishing, CI/CD with macOS runners. Triggers on: iosMain, commonMain, expect fun, actual class, .podspec, shared Android+iOS module, Kotlin/Native interop, KMP Gradle errors. NOT for pure Android-only, iOS-only, or Kotlin/JVM-only tasks."
metadata:
  version: "1.3.0"
  author: grosswilerp
  created: 2026-03-25
  updated: 2026-03-25
---

# Kotlin Multiplatform Expert

Help the user build, configure, debug, and evolve Kotlin Multiplatform projects that share code between Android and iOS. Give practical, doc-backed guidance with working code examples and Gradle configuration. This skill complements the general `kotlin-expert` by going deep on KMP-specific architecture, tooling, and patterns.

## Identify the problem layer first

KMP questions often span multiple concerns. Before answering, figure out which layer is actually at play:

- **Project structure** — source sets, targets, directory layout
- **Gradle configuration** — plugin setup, dependency declarations, compilation settings
- **Shared code architecture** — what goes in `commonMain` vs platform source sets
- **Platform API access** — expect/actual, interfaces, DI-based injection
- **iOS integration** — CocoaPods, Swift Package Manager, framework export, Xcode setup
- **Dependency management** — multiplatform libraries, platform-specific deps
- **Testing** — commonTest, platform-specific test source sets, kotlin.test
- **Publishing** — multiplatform library publication, Maven artifacts

Ask for these details early if they matter and are missing:

- Kotlin version and Gradle version
- Which targets are declared (Android, iosArm64, iosSimulatorArm64, etc.)
- Whether they use Compose Multiplatform or just shared logic
- The actual error message or build output
- Whether this is a new project or adding KMP to an existing one

## Core KMP concepts

### Source sets and the compilation model

KMP projects organize code into source sets. Each source set compiles to specific targets:

- **`commonMain`** compiles to all declared targets. Only Kotlin standard library and multiplatform library APIs are available here. You cannot use `java.io.File` or `platform.Foundation.NSUUID` in common code — the compiler enforces this.
- **Platform source sets** like `androidMain` and `iosMain` compile for a single target (or target family) and can use platform-specific APIs and dependencies.
- **Intermediate source sets** like `appleMain` or `nativeMain` share code among a subset of targets — for example, all Apple platforms can share code that uses `platform.Foundation`.

The Kotlin compiler labels each source set with its targets, then during compilation for a specific target, collects all source sets labeled with that target and compiles them together. So compiling for iOS means `commonMain` + `appleMain` + `iosMain` + `iosArm64Main` are all combined.

### Default hierarchy template

The Kotlin Gradle plugin includes a default hierarchy template that automatically creates intermediate source sets based on your declared targets. For a typical mobile project declaring `android`, `iosArm64`, and `iosSimulatorArm64`, you automatically get:

```
commonMain
├── androidMain
└── appleMain
    └── iosMain
        ├── iosArm64Main
        └── iosSimulatorArm64Main
```

Use the default template rather than manual `dependsOn` calls unless you need custom intermediate source sets. If you do need additional source sets beyond the template, call `applyDefaultHierarchyTemplate()` first, then add your custom `dependsOn` edges.

### iOS device vs simulator targets

There is no single `ios` target. Most projects need at least:
- **`iosArm64`** — device target for physical iPhones
- **`iosSimulatorArm64`** — simulator target for Apple Silicon Macs (or `iosX64` for Intel Macs)

The `iosMain` intermediate source set is shared among all iOS targets. Platform source sets like `iosArm64Main` and `iosSimulatorArm64Main` are usually empty because device and simulator Kotlin code is normally identical.

## Accessing platform APIs

When common code needs something platform-specific, choose the right mechanism based on complexity:

### Expect/actual declarations

Best for small, focused platform abstractions. Declare the API shape in `commonMain` with `expect`, implement in each platform source set with `actual`:

```kotlin
// commonMain
expect fun randomUUID(): String

// androidMain
import java.util.UUID
actual fun randomUUID(): String = UUID.randomUUID().toString()

// iosMain
import platform.Foundation.NSUUID
actual fun randomUUID(): String = NSUUID().UUIDString()
```

The compiler ensures every `expect` has a matching `actual` in every platform source set. Both must be in the same package.

You can use `expect`/`actual` on functions, properties, classes, interfaces, objects, enums, and annotations. For classes, add `-Xexpect-actual-classes` to compiler options since the feature is in Beta.

**When to prefer interfaces over expect/actual classes:** Use interfaces with factory functions when you want multiple implementations per platform, easier test faking, or a DI-friendly architecture. Reserve `expect class` for cases where you need to inherit from an existing platform type.

### Interfaces with platform injection

For more complex cases, define an interface in common code and inject platform implementations:

```kotlin
// commonMain
interface Platform {
    val name: String
}

// Inject via expect/actual factory function, entry-point wiring, or DI framework
```

### Dependency injection

If you already use a DI framework like Koin, use it for platform dependencies too. Define `expect val platformModule: Module` in common code and provide `actual` implementations that register platform-specific bindings. This keeps all dependency wiring consistent and avoids mixing manual expect/actual with DI patterns.

## Gradle configuration

### Basic project setup

A minimal KMP mobile project in `build.gradle.kts`:

```kotlin
plugins {
    kotlin("multiplatform")
    id("com.android.library")  // or com.android.application
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        }
        androidMain.dependencies {
            implementation("androidx.core:core-ktx:1.15.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
```

### Adding dependencies

- **Multiplatform libraries** — add to `commonMain.dependencies`. The Gradle plugin automatically provides the right platform artifact to each target. Use the base artifact name (e.g., `kotlinx-coroutines-core`, not `kotlinx-coroutines-core-jvm`).
- **Platform-specific libraries** — add to the appropriate platform source set (e.g., `androidMain.dependencies` for AndroidX, `iosMain.dependencies` for iOS-specific libs).
- **Other multiplatform modules** — use `implementation(project(":shared-module"))` in the source set that needs it.

### Compilation configuration

Configure compiler options at different scopes:

```kotlin
kotlin {
    // All targets
    compilerOptions {
        allWarningsAsErrors.set(true)
    }

    // Single target
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
}
```

## iOS integration

### Choosing an integration method

There are three main ways to connect your Kotlin framework to an iOS project. Pick based on your needs:

| Method | Best for | Setup complexity |
|--------|----------|-----------------|
| **CocoaPods** | Projects already using CocoaPods for iOS deps | Medium |
| **Direct integration** | Simple projects, full Xcode control | Low |
| **SPM local package** | Projects using Swift Package Manager | Medium |

### Framework declaration

Before any integration method works, declare the framework binary in your Gradle config. Use a `listOf` loop to configure all iOS targets identically:

```kotlin
kotlin {
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true  // static recommended for SPM; dynamic is default
        }
    }
}
```

**Static vs dynamic frameworks:**
- **Static (`isStatic = true`)** — required for SPM integration, smaller app size (dead code elimination), faster launch. Recommended default for most projects.
- **Dynamic (`isStatic = false`)** — needed when multiple frameworks share the same Kotlin runtime, or when you want hot-reload-like behavior during development.

### XCFramework generation

For distributing your shared module (CI, binary distribution, SPM remote), use XCFrameworks instead of per-architecture frameworks:

```kotlin
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

kotlin {
    val xcf = XCFramework()
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            xcf.add(this)
        }
    }
}
```

Build with `./gradlew assembleSharedReleaseXCFramework`.

### Exporting dependencies to the framework

When Swift code needs to see not just your shared module's API but also its dependencies, export them:

```kotlin
kotlin {
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            // Export a sibling KMP module so Swift can see its types
            export(project(":core"))
        }
    }
    sourceSets {
        iosMain.dependencies {
            api(project(":core"))  // must use api, not implementation
        }
    }
}
```

The dependency must be declared as `api` (not `implementation`) in the source set, and `export()` in the framework binary. Without `export()`, Swift only sees `Shared` module types, not `:core` types.

### CocoaPods

The `kotlin("native.cocoapods")` plugin generates a `.podspec` file and integrates the Kotlin framework into Xcode's build process:

```kotlin
plugins {
    kotlin("multiplatform")
    kotlin("native.cocoapods")
}

kotlin {
    cocoapods {
        version = "1.0"
        summary = "Shared KMP module"
        homepage = "https://example.com"
        framework {
            baseName = "Shared"
            isStatic = false
        }
    }
}
```

After configuring, the iOS app's `Podfile` must reference the shared module. Run `pod install` and use the `.xcworkspace` file in Xcode.

Common issues:
- `module not found` errors — check that the `moduleName` matches the actual framework name in the Pod's `module.modulemap`
- Sandbox errors with `rsync` — disable "User Script Sandboxing" in the Xcode target's build settings
- CocoaPods path issues — set `kotlin.apple.cocoapods.bin` in `local.properties`

### Direct integration (no package manager)

Use the `embedAndSignAppleFrameworkForXcode` Gradle task in an Xcode build phase:

1. Declare `binaries.framework` in your Gradle config (see above)
2. In Xcode, add a **Run Script** build phase *before* "Compile Sources":
```bash
cd "$SRCROOT/.."
./gradlew :shared:embedAndSignAppleFrameworkForXcode
```
3. Disable "Based on dependency analysis" and "User Script Sandboxing" in build settings

The IDE-aware variant (used by IntelliJ/Android Studio) sets `OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED=YES` to avoid double-building.

### SPM local package integration

For projects using Swift Package Manager, connect the framework via a local package:

1. Use direct integration setup (build phase script) as a pre-action in the scheme
2. In your local `Package.swift`, import the generated framework
3. `import Shared` in your Swift package code

This gives you clean SPM dependency management while still building the Kotlin framework automatically.

### Android Context initialization pattern

Android platform APIs often require a `Context` (e.g., `BatteryManager`, `ConnectivityManager`, `SharedPreferences`). Since `commonMain` can't know about `Context`, use a singleton initializer:

```kotlin
// androidMain
internal object AndroidContext {
    lateinit var appContext: android.content.Context
        private set

    fun init(context: android.content.Context) {
        appContext = context.applicationContext
    }
}
```

Initialize once in your `Application.onCreate()`:
```kotlin
// Android app module
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AndroidContext.init(this)
    }
}
```

Then use `AndroidContext.appContext` in any `actual` implementation that needs Android system services. Register `MyApp` in `AndroidManifest.xml` via `android:name=".MyApp"`.

## Swift/Objective-C interop

Kotlin/Native interoperates with Swift indirectly through Objective-C. Understanding the mapping is critical for a good iOS developer experience.

### Key type mappings

| Kotlin | Swift | Notes |
|--------|-------|-------|
| `class` | `class` | Prefixed with framework name in Obj-C |
| `interface` | `protocol` | |
| `object` (singleton) | `class` with `.shared` | Access via `MyObject.shared` |
| `companion object` | `class` with `.companion` | Access via `MyClass.companion` |
| `enum class` | `class` (not Swift enum) | Requires `default` in `switch` |
| `suspend fun` | `async` function | Swift 5.5+ async/await support |
| `String` | `String` / `NSString` | Extra copy on conversion |
| `List` / `Map` / `Set` | `Array` / `Dictionary` / `Set` | Extra copy on conversion |
| Top-level function | `FileNameKt.functionName()` | Static member of utility class |

### Making Kotlin APIs Swift-friendly

**`@ObjCName`** — rename declarations for more natural Swift usage:
```kotlin
@ObjCName(swiftName = "UserList")
class KotlinUserList { ... }
```

**`@HiddenFromObjC`** — hide internal Kotlin APIs from the Swift-visible framework header.

**`@ShouldRefineInSwift`** — mark declarations as `swift_private` (prefixed with `__`), allowing you to wrap them with a Swift-native API.

**`@Throws`** — required for Kotlin exceptions to propagate as Swift `throws`. Without this annotation, uncaught Kotlin exceptions terminate the app:
```kotlin
@Throws(IOException::class)
fun loadData(): String { ... }
```

### Suspending functions in Swift

Kotlin `suspend` functions are exposed as Swift `async` functions (Swift 5.5+). From Swift:
```swift
// Swift
let result = try await shared.fetchData()
```

Without `@Throws`, only `CancellationException` propagates as an error. Add `@Throws` for any exception you want Swift callers to handle.

### Performance considerations

- **Collections** are copied when crossing the Kotlin↔Swift boundary. For large collections, consider passing them as `NSArray`/`NSDictionary` to avoid the extra Swift conversion.
- **Strings** are copied twice (Kotlin → Obj-C `NSString` → Swift `String`). For performance-critical paths, work with `NSString` on the Swift side.
- **KDoc comments** are exported to the framework header, so Swift developers see your documentation in Xcode autocomplete.

### Customizing the framework Info.plist

Set bundle metadata via binary options:
```kotlin
target.binaries.framework {
    baseName = "Shared"
    binaryOption("bundleId", "com.example.shared")
    binaryOption("bundleVersion", "2")
}
```

### SKIE — Swift-friendly API generator

[SKIE](https://skie.touchlab.co/) (pronounced "sky") is a Kotlin compiler plugin by Touchlab that dramatically improves the iOS developer experience. It modifies the Xcode Framework produced by the Kotlin compiler, generating Swift wrappers that restore Kotlin features lost in the Kotlin → Obj-C → Swift translation.

**When to recommend SKIE:** Recommend it whenever the iOS team is frustrated with enum `switch` defaults, suspend function ergonomics, Flow collection, or clunky API naming. It's the single highest-impact improvement for KMP iOS interop.

#### Installation

```kotlin
// build.gradle.kts (shared module)
plugins {
    kotlin("multiplatform")
    id("co.touchlab.skie") version "0.10.0"  // check for latest
}
```

No changes needed on the iOS side — SKIE modifies the framework at build time.

#### What SKIE fixes

**Enums → proper Swift enums** (transparent, automatic):
```kotlin
// Kotlin
enum class Direction { LEFT, RIGHT, UP, DOWN }
```
```swift
// Swift with SKIE — exhaustive switch, no default needed
switch direction {
case .left:  goLeft()
case .right: goRight()
case .up:    goUp()
case .down:  goDown()
}
```

**Sealed classes → Swift enums with associated values:**
```kotlin
// Kotlin
sealed class Result {
    data class Success(val data: String) : Result()
    data class Error(val message: String) : Result()
    object Loading : Result()
}
```
```swift
// Swift with SKIE — exhaustive via onEnum(of:)
switch onEnum(of: result) {
case .success(let data): showData(data.data)
case .error(let err):    showError(err.message)
case .loading:           showSpinner()
}
```

**Suspend functions → proper Swift async with cancellation:**
- Two-way cancellation: cancelling a Swift `Task` also cancels the Kotlin coroutine
- Can be called from any thread (not just main)
- No wrapping needed

**Flows → Swift AsyncSequence:**
```swift
// Swift with SKIE — Flow becomes AsyncSequence with preserved generics
for await messages in chatRoom.messages {
    self.messages = messages  // [String], type preserved
}
```

**Default arguments restored:**
```kotlin
fun greet(name: String = "World") { ... }
```
```swift
greet()  // calls greet(name: "World") — works with SKIE
```

**Global functions simplified:**
```swift
// Without SKIE: FileNameKt.doSomething()
// With SKIE:    doSomething()
```

#### SKIE compatibility

- Kotlin 2.0.0 through 2.3.10 (new Kotlin versions usually supported within days)
- Swift 5.8+ (Xcode 14.3+)
- Works with all iOS integration methods (CocoaPods, SPM, direct)

#### When SKIE isn't enough

SKIE solves most interop friction, but some things still require manual work:
- **Custom type mappings** — use `@ObjCName` and `@ShouldRefineInSwift` + Swift wrappers
- **Error handling** — still add `@Throws` on Kotlin suspend functions for proper error propagation
- **Complex generics** — Obj-C generic limitations persist; use concrete types or wrappers
- **Swift code bundling** — SKIE supports bundling hand-written Swift directly into the Kotlin framework for custom wrappers

## Compose Multiplatform

Compose Multiplatform (by JetBrains) extends Jetpack Compose to share UI code across Android, iOS, desktop, and web from `commonMain`. On iOS it renders via Skiko (Skia-based), not native UIKit.

### Plugin setup

```kotlin
plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose") version "1.7.3"          // Compose Multiplatform resources/tooling
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20" // Compose compiler plugin (match Kotlin version)
}
```

Both plugins are required. The `kotlin.plugin.compose` version must match your Kotlin version exactly.

### Key dependencies

```kotlin
sourceSets {
    commonMain.dependencies {
        implementation(compose.runtime)
        implementation(compose.foundation)
        implementation(compose.material3)
        implementation(compose.components.resources)       // multiplatform resources (images, strings, fonts)
        implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0") // ViewModel
        implementation("org.jetbrains.androidx.navigation:navigation-compose:2.9.2")          // Navigation
    }
}
```

### ViewModel in common code

The multiplatform ViewModel requires an explicit initializer (no reflective instantiation on non-JVM):

```kotlin
// commonMain
class MyViewModel : ViewModel() {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
}

@Composable
fun MyScreen(viewModel: MyViewModel = viewModel { MyViewModel() }) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // ...
}
```

### Multiplatform resources

Place resources in `composeResources/` within any source set:

```
composeApp/src/commonMain/composeResources/
├── drawable/       ← images (PNG, JPEG, XML vectors)
├── font/           ← custom fonts
├── values/         ← strings.xml
└── files/          ← arbitrary files
```

Access via generated `Res` class: `painterResource(Res.drawable.my_image)`, `stringResource(Res.string.greeting)`. Supports qualifiers: `drawable-dark/`, `values-fr/`, `drawable-xxhdpi/`.

### Platform-specific UI

Use `expect/actual` for platform capabilities, or wrap platform views:

```kotlin
// commonMain — compose UI with platform-specific pieces
@Composable
expect fun MapView(latitude: Double, longitude: Double)

// androidMain — wrap Android MapView
@Composable
actual fun MapView(latitude: Double, longitude: Double) { /* AndroidView { ... } */ }

// iosMain — wrap MKMapView via UIKitView
@Composable
actual fun MapView(latitude: Double, longitude: Double) { /* UIKitView(factory = { ... }) */ }
```

### Compose Multiplatform vs shared-logic-only KMP

| Approach | UI code | Use when |
|---|---|---|
| **Compose Multiplatform** | Shared in `commonMain` | Same UI on all platforms, fast iteration, simpler team structure |
| **Shared logic only** | Native per platform (Jetpack Compose + SwiftUI) | Platform-native feel is critical, existing native UI teams |

Both approaches use the same KMP shared module for business logic. Compose Multiplatform adds shared UI on top.

For detailed Compose Multiplatform patterns (navigation, resources, iOS rendering, UIKit interop), read `references/compose-multiplatform-patterns.md`.

## Testing

### Test source set structure

Tests mirror the main source set hierarchy. Each main source set has a corresponding test source set:

| Main source set | Test source set | Runs on | Test runner |
|---|---|---|---|
| `commonMain` | `commonTest` | All declared targets | Platform-appropriate (JUnit, K/N runner) |
| `androidMain` | `androidUnitTest` | Local JVM | JUnit 4/5 |
| `androidMain` | `androidInstrumentedTest` | Device/emulator | AndroidJUnit4 |
| `iosMain` | `iosTest` | iOS simulator | Kotlin/Native test runner |

Common tests run once per target — writing a test in `commonTest` gives you coverage across Android and iOS from a single test definition. Maximize what you put here.

### Test dependencies

```kotlin
sourceSets {
    commonTest.dependencies {
        implementation(kotlin("test"))                            // kotlin.test assertions + @Test
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2") // runTest
        implementation("app.cash.turbine:turbine:1.2.1")          // Flow testing
    }
    androidUnitTest.dependencies {
        implementation("junit:junit:4.13.2")                      // Android-specific
    }
}
```

`kotlin("test")` automatically brings the right test framework per platform — JUnit for JVM/Android, the Kotlin/Native test runner for iOS. You don't need to add JUnit separately for common tests.

### Running tests

- **All targets:** `./gradlew allTests`
- **Single platform:** `./gradlew iosSimulatorArm64Test`, `./gradlew testDebugUnitTest`
- **Single test class:** `./gradlew :shared:iosSimulatorArm64Test --tests "com.example.MyTest"`
- HTML reports land in `shared/build/reports/tests/`

### kotlin.test API essentials

The `kotlin.test` package provides platform-agnostic annotations and assertions:

- **Annotations:** `@Test`, `@BeforeTest`, `@AfterTest`, `@Ignore`
- **Assertions:** `assertEquals`, `assertTrue`, `assertFalse`, `assertNotNull`, `assertNull`, `assertContains`, `assertIs<T>()`, `assertFailsWith<T> { ... }`

```kotlin
import kotlin.test.*

class UserParserTest {
    @BeforeTest
    fun setup() { /* runs before each test */ }

    @Test
    fun parsesValidInput() {
        val user = parseUser("""{"name":"Alice"}""")
        assertIs<User>(user)
        assertEquals("Alice", user.name)
    }

    @Test
    fun throwsOnInvalidJson() {
        assertFailsWith<ParseException> {
            parseUser("not json")
        }
    }
}
```

### Testing coroutines with runTest

Use `runTest` from `kotlinx-coroutines-test` to test suspend functions. It auto-skips `delay()` calls, so tests complete immediately rather than waiting for real time:

```kotlin
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class UserRepositoryTest {
    @Test
    fun fetchesUser() = runTest {
        val repo = UserRepository(FakeApiClient())
        val user = repo.getUser("123")
        assertEquals("Alice", user.name)
    }
}
```

**Dispatchers.Main in tests:** KMP code using `Dispatchers.Main` needs it replaced in tests. Use `Dispatchers.setMain()` with a `StandardTestDispatcher`:

```kotlin
@BeforeTest
fun setup() {
    Dispatchers.setMain(StandardTestDispatcher())
}

@AfterTest
fun tearDown() {
    Dispatchers.resetMain()
}
```

### Testing Flows with Turbine

Turbine (by Cash App) is a KMP-compatible library that makes Flow assertions concise and reliable:

```kotlin
import app.cash.turbine.test
import kotlin.test.Test
import kotlin.test.assertEquals

class ViewModelTest {
    @Test
    fun emitsLoadingThenData() = runTest {
        val vm = MyViewModel(FakeRepo())

        vm.state.test {
            assertEquals(UiState.Loading, awaitItem())
            assertEquals(UiState.Success("data"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
```

Turbine works with `Flow`, `StateFlow`, and `SharedFlow`. Use `awaitItem()` for emissions, `awaitError()` for errors, and `awaitComplete()` for completion. It times out automatically if the expected event never arrives — no hanging tests.

### Testing expect/actual declarations

Don't test `expect` declarations directly — test the behavior they enable. Two patterns:

**Pattern 1: Test through common interfaces.** If your expect/actual provides a platform capability, wrap it in an interface and inject a fake in tests:

```kotlin
// commonMain
interface UuidGenerator {
    fun generate(): String
}

expect fun createUuidGenerator(): UuidGenerator

// commonTest — test the consumer, not the generator
class ItemFactoryTest {
    @Test
    fun createsItemWithId() {
        val factory = ItemFactory(FakeUuidGenerator("abc-123"))
        assertEquals("abc-123", factory.create().id)
    }
}
```

**Pattern 2: Platform-specific tests for actual implementations.** When you need to verify the `actual` itself works correctly on each platform:

```kotlin
// iosTest/kotlin/
class IosUuidTest {
    @Test
    fun generatesValidUuid() {
        val gen = createUuidGenerator()  // calls the actual iOS implementation
        assertTrue(gen.generate().matches(Regex("[a-f0-9-]{36}")))
    }
}
```

### Testing pitfalls

1. **Avoid mocking frameworks in `commonTest`** — most mocking libraries (Mockito, MockK) are JVM-only. Use fakes (manual test implementations) instead, which work everywhere and are simpler to reason about.

2. **iOS test simulator target** — `iosTest` needs a simulator target. If you only declared `iosArm64` (device), tests have nowhere to run. Always declare `iosSimulatorArm64` as well.

3. **Background coroutines in runTest** — use `backgroundScope` for coroutines that should outlive individual assertions but be cancelled when the test finishes. `TestScope.backgroundScope` is purpose-built for this.

4. **Flaky iOS tests from threading** — Kotlin/Native's strict memory model means tests that spawn threads or access shared mutable state need `@SharedImmutable` or atomics. Prefer structured concurrency.

For detailed testing patterns, Turbine examples, and platform-specific test setup, read `references/kmp-testing-patterns.md`.

## CI/CD and Publishing

### GitHub Actions for KMP

KMP projects that include iOS targets **must run on macOS** for iOS compilation and tests. A typical CI workflow:

```yaml
# .github/workflows/ci.yml
name: CI
on: [push, pull_request]

jobs:
  build-and-test:
    runs-on: macos-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: 'zulu'
          java-version: '21'

      - uses: gradle/actions/setup-gradle@v4  # caches Gradle automatically

      - name: Cache Kotlin/Native compiler
        uses: actions/cache@v4
        with:
          path: ~/.konan
          key: konan-${{ runner.os }}-${{ hashFiles('**/*.gradle.kts') }}

      - name: Run all tests
        run: ./gradlew allTests --parallel

      - name: Build XCFramework
        run: ./gradlew assembleReleaseXCFramework
```

**Key CI considerations:**
- **macOS runner is required** for iOS compilation, tests, and framework generation. Use `macos-latest` or a specific version.
- **Cache `~/.konan`** — the Kotlin/Native compiler and platform libraries download is ~1GB. Caching cuts minutes off each build.
- **`allTests` runs tests on all targets** — common tests execute once per platform target, ensuring cross-platform correctness.
- **Gradle parallel mode** (`--parallel`) speeds up multi-target builds significantly.

### Publishing multiplatform libraries

Apply the `maven-publish` plugin. The Kotlin plugin automatically creates publications for each target plus a root `kotlinMultiplatform` publication.

The recommended approach is the **vanniktech/gradle-maven-publish-plugin** which simplifies Maven Central publishing:

```kotlin
plugins {
    kotlin("multiplatform")
    id("com.vanniktech.maven.publish") version "0.36.0"
}

group = "com.example"
version = "1.0.0"

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    coordinates(group.toString(), "my-kmp-library", version.toString())

    pom {
        name = "My KMP Library"
        description = "Shared code for Android and iOS"
        url = "https://github.com/example/my-kmp-library"
        licenses { license { name = "Apache-2.0"; url = "https://www.apache.org/licenses/LICENSE-2.0" } }
        developers { developer { id = "you"; name = "Your Name" } }
        scm { url = "https://github.com/example/my-kmp-library" }
    }
}
```

**Publishing CI workflow:**

```yaml
# .github/workflows/publish.yml
name: Publish
on:
  release:
    types: [released]

jobs:
  publish:
    runs-on: macos-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: 'zulu', java-version: '21' }
      - name: Publish to Maven Central
        run: ./gradlew publishAndReleaseToMavenCentral --no-configuration-cache
        env:
          ORG_GRADLE_PROJECT_mavenCentralUsername: ${{ secrets.MAVEN_CENTRAL_USERNAME }}
          ORG_GRADLE_PROJECT_mavenCentralPassword: ${{ secrets.MAVEN_CENTRAL_PASSWORD }}
          ORG_GRADLE_PROJECT_signingInMemoryKeyId: ${{ secrets.SIGNING_KEY_ID }}
          ORG_GRADLE_PROJECT_signingInMemoryKeyPassword: ${{ secrets.SIGNING_PASSWORD }}
          ORG_GRADLE_PROJECT_signingInMemoryKey: ${{ secrets.GPG_KEY_CONTENTS }}
```

**Key publishing points:**
- The root `kotlinMultiplatform` publication is the consumer entry point — consumers add it to `commonMain.dependencies` and Gradle resolves the right platform artifact
- **Publish from macOS** — Apple target `.klib` artifacts can be cross-compiled, but cinterop and framework tasks require macOS
- **GPG signing** is required for Maven Central — generate keys via `gpg --full-generate-key` or `./gradlew generatePgpKeys`
- **Use `publishAndReleaseToMavenCentral`** to auto-release after validation, or `publishToMavenCentral` for manual release via the Central Portal UI
- Store Maven Central credentials and GPG key as GitHub repository secrets

For detailed Maven Central setup (namespace verification, GPG key generation, token-based auth), read `references/kmp-cicd-publishing.md`.

## Common pitfalls

1. **Using JDK classes in `commonMain`** — the compiler will catch this, but it's the #1 beginner mistake. Move platform-specific code to `androidMain`/`iosMain` and expose it through an interface or expect/actual.

2. **Forgetting simulator targets** — declaring only `iosArm64` means you can't run on the simulator. Always declare both device and simulator targets.

3. **Manual `dependsOn` with the default template** — mixing manual source set wiring with the default hierarchy template causes warnings and confusion. Pick one approach.

4. **Expecting `iosMain` to be a platform source set** — it's actually an intermediate source set shared among all iOS targets. Platform source sets are `iosArm64Main`, `iosSimulatorArm64Main`, etc.

5. **Adding platform-suffixed artifacts to `commonMain`** — use base artifact names. Don't add `kotlinx-coroutines-core-jvm` to common; add `kotlinx-coroutines-core`.

6. **Version drift between Kotlin, Gradle, and AGP** — KMP is sensitive to version compatibility. Check the Kotlin Gradle compatibility matrix and AGP requirements when upgrading.

## Response pattern

1. Direct diagnosis or recommendation
2. Working Kotlin / Gradle code
3. Why this approach fits KMP best practices
4. Version or environment notes
5. Links to official KMP docs

Keep answers scannable. Don't force this template for tiny requests.

## Reference file

Read `references/kmp-official-sources.md` when you need the curated KMP documentation map, exact API references, or links to include in answers.
