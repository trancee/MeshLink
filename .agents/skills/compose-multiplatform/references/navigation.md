# Compose Multiplatform — Navigation

<concepts>
## Core Concepts

| Concept | Description |
|---------|-------------|
| **Navigation graph** | All destinations + connections. Can be nested. |
| **Destination** | A node — composable, nested graph, or dialog |
| **Route** | Identifies a destination + its arguments. Serializable object or data class. |
| **Back stack** | Stack of destinations. Navigate pushes; back/pop pops. |
| **Deep link** | URI/action/MIME type associated with a destination |

### Core classes
| Class | Purpose |
|-------|---------|
| `NavController` | Transition between destinations, manage back stack, handle deep links |
| `NavHost` | Composable displaying the current destination. Requires `startDestination`. |
| `NavGraph` | Describes all destinations, usually built as a lambda |
</concepts>

<setup>
## Setup

Add to `commonMain`:
```
org.jetbrains.androidx.navigation:navigation-compose:2.9.2
```

### Basic example
```kotlin
// 1. Define routes as serializable objects/data classes
@Serializable object Profile
@Serializable data class FriendsList(val userId: String)

// 2. Create NavController
val navController = rememberNavController()

// 3. Build NavHost with navigation graph
NavHost(navController = navController, startDestination = Profile) {
    composable<Profile> { ProfileScreen() }
    composable<FriendsList> { backStackEntry ->
        val route: FriendsList = backStackEntry.toRoute()
        FriendsListScreen(route.userId)
    }
}
```
</setup>

<navigation_patterns>
## Navigation Patterns

### Navigate to a destination
```kotlin
Button(onClick = { navController.navigate(Profile) }) {
    Text("Go to profile")
}
```

### Pass arguments
```kotlin
// Route with parameters
@Serializable data class Profile(val name: String)

// Navigate with arguments
navController.navigate(Profile("Alice"))

// Retrieve at destination
composable<Profile> { backStackEntry ->
    val profile: Profile = backStackEntry.toRoute()
    Text("Hello, ${profile.name}")
}
```

### Data passing best practices
Pass **only minimum necessary data** (IDs, not objects):
- ✅ Pass user ID → load profile at destination
- ✅ Pass image URI → load image at destination
- ❌ Don't pass entire user profiles, images, or ViewModels

### Back stack management
- `navController.popBackStack()` — pop current destination
- `navController.navigateUp()` — navigate up within the app
- `popUpTo()` in `.navigate()` — pop stack up to a specific destination
- Support for multiple back stacks (e.g., bottom navigation tabs)

### Back gesture (platform-specific)
| Platform | Default behavior |
|----------|-----------------|
| iOS | Back swipe with native-like animation |
| Desktop | Esc key |
| Android | System back button/gesture |

Custom `enterTransition`/`exitTransition` on `NavHost` overrides iOS default animation.

Disable iOS back gesture:
```kotlin
ComposeUIViewController(
    configure = { enableBackGesture = false }
) { App() }
```

### Alternative libraries
| Library | Description |
|---------|-------------|
| Voyager | Pragmatic navigation |
| Decompose | Full lifecycle + DI |
| Circuit | Compose-driven architecture |
| Appyx | Model-driven + gesture control |
| PreCompose | Jetpack-inspired ViewModel + Navigation |
</navigation_patterns>

<navigation_3>
## Navigation 3 (Compose Multiplatform 1.10+)

A ground-up redesign of the Navigation library, not just a new version — supported on Android, iOS, desktop, and web starting with Compose Multiplatform 1.10. Both this and the classic `NavHost`/`NavController` library above are currently valid choices; Navigation 3 is newer and lower-level.

### Key differences from the classic library
- **User-owned back stack**: instead of an internal library-managed stack, you hold a `SnapshotStateList` of route/state objects yourself and the UI observes it directly.
- **Low-level building blocks**: closer integration with Compose gives more flexibility to build custom navigation components and behavior.
- **Adaptive layout system**: can display multiple destinations simultaneously and switch layouts adaptively (list-detail, supporting pane, etc.).

### Dependencies
```kotlin
// version catalog
[versions]
multiplatform-nav3-ui = "1.1.1"

[libraries]
jetbrains-navigation3-ui = { module = "org.jetbrains.androidx.navigation3:navigation3-ui", version.ref = "multiplatform-nav3-ui" }
```
`navigation3-common` is pulled in transitively; only `navigation3-ui` has a CMP-specific implementation. For Material 3 Adaptive + ViewModel integration, also add `org.jetbrains.compose.material3.adaptive:adaptive-navigation3` and `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-navigation3`.

### Polymorphic serialization is required off-JVM
Android's Navigation 3 relies on reflection-based serialization, unavailable on iOS/web. Use the `SavedStateConfiguration`-accepting overload of `rememberNavBackStack()` and register each route's serializer explicitly:

```kotlin
@Serializable
private data object RouteA : NavKey

@Serializable
private data class RouteB(val id: String) : NavKey

private val config = SavedStateConfiguration {
    serializersModule = SerializersModule {
        polymorphic(NavKey::class) {
            subclass(RouteA::class, RouteA.serializer())
            subclass(RouteB::class, RouteB.serializer())
        }
    }
}

@Composable
fun BasicDslActivity() {
    val backStack = rememberNavBackStack(config, RouteA)
    NavDisplay(backStack = backStack, /* ... */)
}
```

**Choosing a serialization pattern:**
- **Single module, sealed routes** — one `sealed interface Route : NavKey`; Kotlin serialization handles the hierarchy automatically (`subclassesOfSealed<Route>()` if using `rememberNavBackStack()` explicitly).
- **Multi-module, aggregated sealed types** — one sealed type per module, aggregated in the app module via `subclassesOfSealed<FeatureA>()` / `subclassesOfSealed<FeatureB>()`.
- **Multi-module, individual registration** — when routes can't be grouped into sealed types, combine each module's `SerializersModule` with `+` in the app module. Most flexible, most manual upkeep.

### ViewModel scoping with Navigation 3
ViewModels are **not** automatically scoped to navigation entries — without explicit scoping every ViewModel is tied to the Activity, not the screen. Pass entry decorators to `NavDisplay`:
```kotlin
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator

NavDisplay(
    entryDecorators = listOf(
        rememberSaveableStateHolderNavEntryDecorator(), // saves Compose state per entry
        rememberViewModelStoreNavEntryDecorator()       // scopes ViewModel per entry
    ),
    backStack = backStack,
    entryProvider = entryProvider { }
)
```

**Not a migration, a rewrite**: treat adopting Navigation 3 as replacing your navigation layer, not upgrading it — it is closer to a new library than a new version of the old one.
</navigation_3>
