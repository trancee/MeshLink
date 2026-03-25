# Kotlin Multiplatform Official Sources

## Table of Contents
1. [Getting Started](#getting-started)
2. [Project Structure & Source Sets](#project-structure--source-sets)
3. [Expect/Actual Declarations](#expectactual-declarations)
4. [Sharing Code Across Platforms](#sharing-code-across-platforms)
5. [Platform API Access](#platform-api-access)
6. [Hierarchical Source Sets](#hierarchical-source-sets)
7. [Dependencies](#dependencies)
8. [CocoaPods Integration](#cocoapods-integration)
9. [Compilations & Build Config](#compilations--build-config)
10. [Publishing Libraries](#publishing-libraries)
11. [Compose Multiplatform](#compose-multiplatform)
12. [Testing](#testing)
13. [Community Resources](#community-resources)

---

## Getting Started

- **KMP Overview**: https://kotlinlang.org/docs/multiplatform/get-started.html
- **KMP Web Wizard** (project generator): https://kmp.jetbrains.com
- **KMP IDE Plugin**: https://plugins.jetbrains.com/plugin/14936-kotlin-multiplatform

## Project Structure & Source Sets

- **Discover your project structure**: https://kotlinlang.org/docs/multiplatform/multiplatform-discover-project.html
  - Common code, targets, source sets, intermediate source sets, test integration
- **Advanced project structure**: https://kotlinlang.org/docs/multiplatform/multiplatform-advanced-project-structure.html
- **DSL Reference**: https://kotlinlang.org/docs/multiplatform/multiplatform-dsl-reference.html

### Key concepts
- `commonMain` compiles to all declared targets
- Platform source sets (`androidMain`, `iosArm64Main`) compile for one target
- Intermediate source sets (`appleMain`, `iosMain`, `nativeMain`) share code among a subset of targets
- The `iosMain` source set is intermediate, not platform-specific — it covers all iOS device and simulator targets

## Expect/Actual Declarations

- **Full documentation**: https://kotlinlang.org/docs/multiplatform/multiplatform-expect-actual.html
  - Expected/actual functions, properties, classes, interfaces, objects, enums, annotations
  - Type aliases for satisfying actuals with existing platform types
  - Expanded visibility in actual declarations
  - Additional enum entries on actualization
  - `@OptionalExpectation` for annotations not needed on all platforms

### Rules
- Every `expect` must have a matching `actual` in every platform source set
- Expected declarations must not contain implementations
- Expected and actual declarations must be in the same package
- For `expect class`, add `-Xexpect-actual-classes` compiler flag (Beta feature)

## Sharing Code Across Platforms

- **Share on platforms**: https://kotlinlang.org/docs/multiplatform/multiplatform-share-on-platforms.html
- **Hierarchical project structure**: https://kotlinlang.org/docs/multiplatform/multiplatform-hierarchy.html
- Common code in `commonMain`, platform code in target-specific source sets
- Intermediate source sets for partial sharing (e.g., `appleMain` for all Apple targets)

## Platform API Access

- **Connect to platform APIs**: https://kotlinlang.org/docs/multiplatform/multiplatform-connect-to-apis.html
  - Expected/actual functions and properties
  - Interfaces in common code with platform implementations
  - DI framework approach (e.g., Koin)
  - Entry-point wiring

### Recommended approach hierarchy
1. Check for an existing multiplatform library first
2. Use expect/actual for simple cases
3. Use interfaces + DI for complex cases
4. Avoid expect/actual classes when interfaces suffice

## Hierarchical Source Sets

- **Hierarchy template docs**: https://kotlinlang.org/docs/multiplatform/multiplatform-hierarchy.html
- Default hierarchy template automatically creates intermediate source sets from declared targets
- Type-safe accessors for template source sets (no `by getting` needed)
- Custom source sets: call `applyDefaultHierarchyTemplate()` then add manual `dependsOn`
- Cannot modify default `dependsOn` relations — opt out with `kotlin.mpp.applyDefaultHierarchyTemplate=false` if needed

### Default template source sets (mobile-relevant)
- `commonMain` → all targets
- `appleMain` → all Apple targets
- `iosMain` → all iOS targets (device + simulator)
- `nativeMain` → all native targets

## Dependencies

- **Adding dependencies**: https://kotlinlang.org/docs/multiplatform/multiplatform-add-dependencies.html
- **Android dependencies**: https://kotlinlang.org/docs/multiplatform/multiplatform-android-dependencies.html
- **iOS dependencies**: https://kotlinlang.org/docs/multiplatform/multiplatform-ios-dependencies.html
- Standard library added automatically to every source set
- `kotlin("test")` in `commonTest` brings platform-appropriate test frameworks
- Use base artifact names for kotlinx libraries (e.g., `kotlinx-coroutines-core`)

## CocoaPods Integration

- **Overview**: https://kotlinlang.org/docs/multiplatform/multiplatform-cocoapods-overview.html
- **Adding Pod dependencies**: https://kotlinlang.org/docs/multiplatform/multiplatform-cocoapods-libraries.html
- **Xcode integration**: https://kotlinlang.org/docs/multiplatform/multiplatform-cocoapods-xcode.html
- **DSL reference**: https://kotlinlang.org/docs/multiplatform/multiplatform-cocoapods-dsl-reference.html
- Plugin: `kotlin("native.cocoapods")`
- Generates `.podspec` and integrates with Xcode build process
- Common fix: set `kotlin.apple.cocoapods.bin` in `local.properties`

## iOS Integration Methods

- **Integration overview**: https://kotlinlang.org/docs/multiplatform/multiplatform-ios-integration-overview.html
- **Direct integration**: https://kotlinlang.org/docs/multiplatform/multiplatform-direct-integration.html
  - Uses `embedAndSignAppleFrameworkForXcode` Gradle task in Xcode build phase
  - No package manager required, simplest setup
- **SPM local integration**: https://kotlinlang.org/docs/multiplatform/multiplatform-spm-local-integration.html
  - Connects Kotlin framework to a local Swift Package
  - Requires direct integration setup as scheme pre-action
- **SPM export**: https://kotlinlang.org/docs/multiplatform/multiplatform-spm-export.html

## Native Binaries & Frameworks

- **Build native binaries**: https://kotlinlang.org/docs/multiplatform/multiplatform-build-native-binaries.html
  - Framework, XCFramework, static/shared library, executable
  - Exporting dependencies to binaries via `export()`
  - Fat frameworks with `FatFrameworkTask`
  - XCFramework: `assembleXCFramework`, `assemble<Name>ReleaseXCFramework`
  - Info.plist customization via `binaryOption()`
- Binary types: `framework`, `staticLib`, `sharedLib`, `executable`, `test`
- Static vs dynamic frameworks: static recommended for SPM, dynamic for shared runtime

## Swift/Objective-C Interop

- **Interop reference**: https://kotlinlang.org/docs/native-objc-interop.html
- **Kotlin-Swift interopedia** (examples): https://github.com/kotlin-hands-on/kotlin-swift-interopedia
- **ARC integration**: https://kotlinlang.org/docs/native-arc-integration.html
- Key annotations:
  - `@ObjCName(swiftName = ...)` — rename for Swift-friendly API
  - `@HiddenFromObjC` — hide from Obj-C/Swift header
  - `@ShouldRefineInSwift` — mark as `swift_private` for wrapping
  - `@Throws` — propagate exceptions as Swift `throws`
- Suspend functions → Swift `async` (Swift 5.5+)
- Collections/strings are copied at the Kotlin↔Swift boundary
- KDoc comments export to framework headers (Xcode autocomplete)
- Top-level Kotlin functions appear as `FileNameKt.functionName()` in Swift
- Kotlin `object` → Swift `.shared` accessor
- Kotlin `enum class` → Swift `class` (not enum), requires `default` in `switch`

## SKIE (Swift Kotlin Interface Enhancer)

- **Homepage**: https://skie.touchlab.co/
- **Features overview**: https://skie.touchlab.co/features
- **GitHub**: https://github.com/touchlab/SKIE
- **Installation**: Gradle plugin `co.touchlab.skie`
- **Compatibility**: Kotlin 2.0.0–2.3.10, Swift 5.8+ (Xcode 14.3+)
- **What it fixes**:
  - Kotlin `enum class` → proper Swift enum (exhaustive switch, no `default` needed)
  - Sealed classes → Swift enum with associated values via `onEnum(of:)`
  - Suspend functions → proper Swift async with two-way cancellation, callable from any thread
  - `Flow`/`StateFlow`/`MutableStateFlow` → Swift `AsyncSequence` with preserved generics
  - Default arguments restored in Swift
  - Global functions callable without `FileNameKt.` prefix
  - Overloaded function names preserved
  - Interface extensions callable as instance methods
  - Swift code bundling — bundle custom Swift wrappers directly into the Kotlin framework
- **Key limitation**: Still need `@Throws` on Kotlin side for proper error propagation

## Compilations & Build Config

- **Configure compilations**: https://kotlinlang.org/docs/multiplatform/multiplatform-configure-compilations.html
- **Compiler options**: https://kotlinlang.org/docs/gradle-compiler-options.html
- Scope: all compilations, per-target, or per-compilation
- Android compilations tied to build variants
- Custom compilations for integration tests via `associateWith`
- JVM compilation can include Java sources alongside Kotlin

## Publishing Libraries

- **Publish setup**: https://kotlinlang.org/docs/multiplatform/multiplatform-publish-lib-setup.html
- **Publish to Maven Central**: https://kotlinlang.org/docs/multiplatform/multiplatform-publish-libraries.html
- **Library authors' guidelines**: https://kotlinlang.org/docs/api-guidelines-build-for-multiplatform.html
- **vanniktech/gradle-maven-publish-plugin**: https://github.com/vanniktech/gradle-maven-publish-plugin — recommended for Maven Central
- Root publication: `kotlinMultiplatform` — resolves to platform-specific artifacts
- Publish from macOS (required for iOS targets, cinterop, framework generation)
- GPG signing required for Maven Central — generate via `gpg --full-generate-key` or `./gradlew generatePgpKeys`
- `publishAndReleaseToMavenCentral` auto-releases; `publishToMavenCentral` requires manual release
- Android library publishing requires `androidLibrary {}` block configuration

## CI/CD

- **macOS runner required** for iOS compilation, tests, and framework generation
- Cache `~/.konan` (~1GB Kotlin/Native compiler + platform libs) for faster CI builds
- `gradle/actions/setup-gradle@v4` auto-caches Gradle wrapper and dependencies
- `-Pkotlin.native.ignoreDisabledTargets=true` allows building on hosts missing some targets
- Split Android (Linux runner) and iOS (macOS runner) jobs for cost-effective CI
- See `references/kmp-cicd-publishing.md` for detailed workflows, Maven Central setup, XCFramework distribution, and SPM integration

## Compose Multiplatform

- **Compose Multiplatform docs**: https://www.jetbrains.com/compose-multiplatform/
- **Getting started**: https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-multiplatform-create-first-app.html
- **Resources setup**: https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-multiplatform-resources-setup.html
- **Navigation**: https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-navigation-routing.html
- **ViewModel**: https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-viewmodel.html
- Plugins: `org.jetbrains.compose` (tooling) + `org.jetbrains.kotlin.plugin.compose` (compiler, must match Kotlin version)
- Shared UI in `commonMain` using `@Composable` functions, renders via Skia on iOS
- ViewModel: `lifecycle-viewmodel-compose:2.10.0` — always provide initializer on non-JVM: `viewModel { MyViewModel() }`
- Navigation: `navigation-compose:2.9.2` — type-safe routes with `@Serializable` data classes
- Resources: `compose.components.resources` — images, strings, fonts in `composeResources/` with qualifier support (language, theme, density)
- UIKit interop: `UIKitView(factory = { ... })` to wrap native iOS views in Compose
- See `references/compose-multiplatform-patterns.md` for detailed patterns (navigation, resources, iOS rendering, UIKit interop)

## Testing

- **Run tests tutorial**: https://kotlinlang.org/docs/multiplatform/multiplatform-run-tests.html
- **kotlin.test API**: https://kotlinlang.org/api/latest/kotlin.test/
- **kotlinx-coroutines-test**: https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-test/
- **Turbine (Flow testing)**: https://github.com/cashapp/turbine — KMP-compatible, latest stable 1.2.1
- `commonTest` source set for cross-platform tests — uses `kotlin.test` annotations and assertions
- `kotlinx-coroutines-test` provides `runTest` which auto-skips delays and manages virtual time
- Turbine provides `Flow.test { awaitItem() }` pattern for concise Flow assertions
- `@Test`, `@BeforeTest`, `@AfterTest`, `@Ignore` from `kotlin.test`
- Fakes over mocks: most mocking frameworks (Mockito, MockK) are JVM-only. Use interface + fake pattern in `commonTest`
- Run all: `./gradlew allTests`, per target: `./gradlew iosSimulatorArm64Test`, `./gradlew testDebugUnitTest`
- See `references/kmp-testing-patterns.md` for detailed patterns (Turbine, coroutines testing, fakes, platform-specific test setup)

## Community Resources

- **Awesome KMP libraries**: https://github.com/terrakok/kmm-awesome
- **KMP library catalog (klibs.io)**: https://klibs.io/
- **KMP samples**: https://kotlinlang.org/docs/multiplatform/multiplatform-samples.html
- **Kotlin Slack #multiplatform**: https://kotlinlang.slack.com/
