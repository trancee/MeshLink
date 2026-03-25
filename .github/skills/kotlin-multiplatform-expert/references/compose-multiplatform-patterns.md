# Compose Multiplatform Patterns Reference

Detailed patterns for building shared UI with Compose Multiplatform across Android, iOS, desktop, and web.

## Table of Contents
- [Project structure](#project-structure)
- [Navigation](#navigation)
- [ViewModel patterns](#viewmodel-patterns)
- [Resources](#resources)
- [iOS rendering and UIKit interop](#ios-rendering-and-uikit-interop)
- [Common gotchas](#common-gotchas)

---

## Project structure

A typical Compose Multiplatform project:

```
my-project/
├── composeApp/                          ← shared UI module
│   ├── build.gradle.kts
│   └── src/
│       ├── commonMain/
│       │   ├── kotlin/App.kt           ← shared @Composable functions
│       │   └── composeResources/        ← images, strings, fonts
│       ├── androidMain/kotlin/          ← Android-specific composables
│       └── iosMain/kotlin/              ← iOS-specific composables
├── iosApp/                              ← Xcode project (thin wrapper)
│   └── iosApp.xcworkspace
├── shared/                              ← optional: non-UI shared logic
└── build.gradle.kts
```

### Gradle configuration

```kotlin
// composeApp/build.gradle.kts
plugins {
    kotlin("multiplatform")
    id("com.android.application")  // or com.android.library
    id("org.jetbrains.compose") version "1.7.3"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20"
}

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
    }
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
            implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
            implementation("org.jetbrains.androidx.navigation:navigation-compose:2.9.2")
        }
        androidMain.dependencies {
            implementation(compose.preview)
            implementation("androidx.activity:activity-compose:1.9.3")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
```

---

## Navigation

Compose Multiplatform uses the same Navigation library API as Jetpack Compose:

```kotlin
// commonMain
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

@Serializable data object HomeScreen
@Serializable data class DetailScreen(val id: String)

@Composable
fun App() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = HomeScreen) {
        composable<HomeScreen> {
            HomeContent(onItemClick = { id ->
                navController.navigate(DetailScreen(id))
            })
        }
        composable<DetailScreen> { backStackEntry ->
            val detail = backStackEntry.toRoute<DetailScreen>()
            DetailContent(detail.id)
        }
    }
}
```

### Type-safe navigation

Use `@Serializable` data objects/classes as route types (requires kotlinx.serialization). This is the recommended approach — it replaces string-based routes with compile-time safety.

### Back stack entries and lifecycle

Each back stack entry implements `LifecycleOwner`. Screens transition between `RESUMED` (active) and `STARTED` (backgrounded). Use `collectAsStateWithLifecycle()` for lifecycle-aware state collection.

---

## ViewModel patterns

### Creating ViewModels

On non-JVM platforms (iOS, web), ViewModels can't be instantiated via reflection. Always provide an initializer:

```kotlin
// Works on all platforms
@Composable
fun MyScreen(viewModel: MyViewModel = viewModel { MyViewModel() }) { ... }

// Does NOT work on non-JVM platforms (no reflection)
@Composable
fun MyScreen(viewModel: MyViewModel = viewModel()) { ... }  // ❌
```

### ViewModel with dependencies

Use a factory pattern for ViewModels with constructor parameters:

```kotlin
class UserViewModel(private val repo: UserRepository) : ViewModel() {
    val users = repo.getUsers().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
}

@Composable
fun UserScreen(repo: UserRepository) {
    val viewModel = viewModel { UserViewModel(repo) }
    val users by viewModel.users.collectAsStateWithLifecycle()
    // ...
}
```

### viewModelScope

`viewModelScope` is available in multiplatform ViewModels. Coroutines launched in `viewModelScope` are automatically cancelled when the ViewModel is cleared.

---

## Resources

### Directory structure with qualifiers

```
composeResources/
├── drawable/                   ← default images
├── drawable-dark/              ← dark theme images
├── drawable-xxhdpi/            ← high-density images
├── font/                       ← TTF/OTF fonts
├── values/
│   └── strings.xml             ← default strings
├── values-fr/
│   └── strings.xml             ← French strings
├── values-ja/
│   └── strings.xml             ← Japanese strings
└── files/                      ← raw files (no qualifiers)
```

### Accessing resources

```kotlin
// Images
Image(painterResource(Res.drawable.logo), contentDescription = "Logo")

// Strings
Text(stringResource(Res.string.welcome_message))
Text(stringResource(Res.string.greeting, userName))  // with format args

// Fonts
val customFont = FontFamily(Font(Res.font.roboto_regular))

// Raw files (async)
val bytes = Res.readBytes("files/data.json")
```

### String resources format

```xml
<!-- composeResources/values/strings.xml -->
<resources>
    <string name="app_name">My App</string>
    <string name="greeting">Hello, %1$s!</string>
    <string name="item_count">%1$d items</string>
</resources>
```

### Qualifier priority

Qualifiers are resolved in order: language → theme → density. If a qualified resource isn't found, the default (unqualified) version is used.

---

## iOS rendering and UIKit interop

### How Compose renders on iOS

Compose Multiplatform uses **Skiko** (Skia for Kotlin) to render UI on iOS. It draws directly to a `CAMetalLayer` — it does NOT use UIKit views for Compose widgets. This means:
- Compose UI looks pixel-identical across platforms
- Native UIKit widgets (maps, cameras, web views) need explicit wrapping
- Performance is generally excellent but scrolling physics may differ from native

### Wrapping UIKit views in Compose

```kotlin
// iosMain
import androidx.compose.ui.interop.UIKitView
import platform.MapKit.MKMapView

@Composable
actual fun MapView(latitude: Double, longitude: Double) {
    UIKitView(
        factory = {
            MKMapView().apply {
                // configure the map
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}
```

### Wrapping Compose in UIKit

For hybrid apps that want to embed Compose screens in existing UIKit navigation:

```swift
// iOS — embed Compose in a UIViewController
import ComposeApp

let composeVC = ComposeViewControllerKt.MainViewController()
navigationController.pushViewController(composeVC, animated: true)
```

The `MainViewController` function is declared in `iosMain`:

```kotlin
// iosMain
fun MainViewController(): UIViewController = ComposeUIViewController { App() }
```

---

## Common gotchas

1. **Plugin version mismatch** — `kotlin.plugin.compose` version MUST match the Kotlin version. Mismatches cause cryptic compilation errors.

2. **ViewModel initializer required** — `viewModel()` without parameters only works on JVM. Always pass `viewModel { MyViewModel() }` for cross-platform compatibility.

3. **Material3 vs Material** — use `compose.material3` for the latest design system. `compose.material` is the older Material 2.

4. **Resources not regenerating** — after adding/removing resources, run `./gradlew generateComposeResClass` or do a clean build. The `Res` class is generated at build time.

5. **iOS scrolling/gesture differences** — Compose renders its own scrolling physics, which may feel slightly different from native UIKit. Test scrolling UX on real iOS devices.

6. **Static framework for SPM** — when distributing Compose Multiplatform via SPM, use `isStatic = true` in the framework configuration.

7. **Preview support** — `@Preview` works in Android Studio and IntelliJ IDEA for `commonMain` composables. iOS-specific previews require Xcode.

---

## Key library versions (as of March 2026)

| Library | Artifact | Latest |
|---|---|---|
| Compose Multiplatform | `org.jetbrains.compose` plugin | 1.7.3 |
| Compose Compiler | `kotlin.plugin.compose` | Matches Kotlin version |
| Navigation | `org.jetbrains.androidx.navigation:navigation-compose` | 2.9.2 |
| ViewModel | `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose` | 2.10.0 |
| Resources | `compose.components.resources` | Bundled with compose plugin |
