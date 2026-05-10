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
