---
name: compose-multiplatform
description: Compose Multiplatform UI framework reference by JetBrains for sharing Kotlin UI across Android, iOS, desktop, and web. Covers composable functions, layout containers (Column/Row/Box/FlowRow), modifiers, lifecycle (cross-platform mapping of Android/iOS/desktop/web events), common ViewModel, multiplatform Navigation library (routes, NavHost, NavController, back stack, deep links, back gestures, type-safe arguments), multiplatform resources (images, strings, fonts, raw files, qualifiers for locale/theme/density, Res class generation), and UI testing (runComposeUiTest, finders, assertions, actions). Use when building Compose Multiplatform UI, asking about "compose multiplatform navigation", "shared ViewModel", "multiplatform resources", "compose lifecycle on iOS", "compose UI testing", "NavHost setup", "string resources in CMP", "compose modifiers", or any Compose Multiplatform topic.
---

<essential_principles>

**Compose Multiplatform** is JetBrains' declarative UI framework extending Jetpack Compose to iOS, desktop (JVM/Swing), and web (Wasm). Version aligned with Kotlin 2.3.21.

### Core Rules an Agent Must Know

**Layouts:**
- Build UI with `@Composable` functions. Core containers: `Column`, `Row`, `Box`, `FlowRow`/`FlowColumn`.
- Modifiers chain to control padding, size, clicks, alignment. Order matters.

**Lifecycle:**
- Dep: `org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.10.0`
- All composables share a common `LifecycleOwner` by default
- Platform events map to Android-style lifecycle events (ON_START, ON_RESUME, etc.)
- **Desktop gotcha:** `Lifecycle.coroutineScope` needs `kotlinx-coroutines-swing`

**ViewModel:**
- Dep: `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0`
- **Always provide an initializer:** `viewModel { MyViewModel() }` — no reflection-based instantiation on non-JVM
- **Desktop:** `viewModelScope` needs `kotlinx-coroutines-swing`

**Navigation:**
- Dep: `org.jetbrains.androidx.navigation:navigation-compose:2.9.2`
- Routes are `@Serializable` objects/data classes. `NavHost` + `NavController` + back stack.
- Pass **minimal data** (IDs, not objects) between destinations.
- iOS back swipe works automatically; custom transitions override it.

**Resources:**
- Dep: `compose.components.resources`
- Place in `composeResources/` subdirs: `drawable/`, `font/`, `values/`, `files/`
- Qualifiers via hyphenated dirs: `drawable-en-rUS-mdpi-dark`
- Build generates `Res` class with type-safe accessors
- `painterResource()`, `stringResource()`, `pluralStringResource()`, `Font()`, `Res.readBytes()`

**Testing:**
- Dep: `compose.uiTest` (Experimental)
- Use `runComposeUiTest { }` — **no JUnit TestRule** in common code
- Same finders/assertions/actions API as Jetpack Compose testing
- Run: `./gradlew :composeApp:jvmTest` (desktop), `iosSimulatorArm64Test` (iOS), etc.

</essential_principles>

<routing>

| Topic | Reference |
|-------|-----------|
| Composable functions, Column/Row/Box/FlowRow, modifiers, lifecycle (states, events, platform mapping for iOS/desktop/web, coroutine gotcha), ViewModel (setup, initializer requirement, desktop coroutines) | `references/layouts-lifecycle-viewmodel.md` |
| Navigation library (concepts, routes, NavHost, NavController, back stack, passing arguments, deep links, back gestures per platform, alternative libraries) | `references/navigation.md` |
| Multiplatform resources (setup, directory structure, qualifiers for language/theme/density, images, strings, string arrays, plurals, fonts, raw files, Res class customization, web resource paths, URI access for external libraries) | `references/resources.md` |
| UI testing (setup with compose.uiTest, runComposeUiTest pattern, writing tests, finders/assertions/actions, running on each platform, Android instrumented test config) | `references/testing.md` |

</routing>

<reference_index>

**layouts-lifecycle-viewmodel.md** — @Composable functions, Column/Row/Box/FlowRow/FlowColumn, Modifier chaining (padding, fillMaxWidth, clickable), lifecycle dependency (lifecycle-runtime-compose:2.10.0), LifecycleOwner as CompositionLocal, lifecycle states/events, iOS lifecycle mapping (viewWillAppear→ON_START, didBecomeActive→ON_RESUME, willResignActive→ON_PAUSE, viewDidDisappear→ON_STOP, didEnterBackground→ON_STOP, willEnterForeground→ON_START, viewControllerDidLeaveWindowHierarchy→ON_DESTROY), desktop lifecycle mapping (windowGainedFocus→ON_RESUME, windowLostFocus→ON_PAUSE, windowIconified→ON_STOP, windowDeiconified→ON_START, dispose→ON_DESTROY), web lifecycle (skips CREATED, never DESTROYED, visibilitychange/focus/blur), kotlinx-coroutines-swing requirement for desktop, ViewModel dependency (lifecycle-viewmodel-compose:2.10.0), ViewModel declaration with StateFlow, viewModel{} initializer requirement (no reflection on non-JVM), viewModelScope desktop gotcha

**navigation.md** — navigation graph/destination/route/back stack concepts, NavController/NavHost/NavGraph classes, navigation-compose:2.9.2 dependency, @Serializable routes (objects + data classes), NavHost with startDestination, composable<Route> builder, navigate() calls, type-safe argument passing via data class constructors, backStackEntry.toRoute(), minimal data passing best practices (IDs not objects), back stack management (popBackStack, navigateUp, popUpTo, multiple back stacks for tabs), deep links, back gesture per platform (iOS swipe, desktop Esc, Android system back), disabling iOS back gesture (enableBackGesture=false), custom enter/exit transitions, alternative libraries (Voyager, Decompose, Circuit, Appyx, PreCompose)

**resources.md** — compose.components.resources dependency, composeResources directory structure (drawable/font/values/files), supported formats (PNG/JPEG/WebP/BMP/XML vectors/SVG except Android), qualifier system (language ISO 639, region with r prefix, theme light/dark, density ldpi-xxxhdpi), combined qualifiers, Res class generation and import, painterResource/imageResource/vectorResource for images, stringResource/stringArrayResource/pluralStringResource for strings, string templates with %N$s/%N$d, Font() composable for custom fonts, Res.readBytes for raw files, decodeToImageBitmap/decodeToImageVector/decodeToSvgPainter, Res.getUri for external library access, Res.allDrawableResources/allStringResources maps, compose.resources {} customization (publicResClass, packageOfResClass, generateResClass), custom directories, web resource path mapping, non-composable suspend accessors (getString, getStringArray, getPluralString)

**testing.md** — compose.uiTest dependency (Experimental), commonTest setup, desktop jvmTest needs compose.desktop.currentOs, Android instrumented test config (KotlinSourceSetTree.test, testInstrumentationRunner, ui-test-junit4-android), runComposeUiTest{} pattern (no TestRule), setContent{}, testTag modifier, onNodeWithTag/onNodeWithText finders, assertTextEquals/assertIsDisplayed assertions, performClick/performTextInput actions, Gradle commands per platform (jvmTest, iosSimulatorArm64Test, connectedAndroidTest, wasmJsTest), JUnit-based API for desktop only

</reference_index>
