# Compose Multiplatform — Layouts, Lifecycle & ViewModel

<layouts>
## Layout Basics

### Composable functions
```kotlin
@Composable
fun Greeting(name: String) {
    Text(text = "Hello, $name!")
}
```

### Core layout containers
| Container | Purpose |
|-----------|---------|
| `Column` | Items vertically |
| `Row` | Items horizontally |
| `Box` | Items stacked/overlapping |
| `FlowRow` / `FlowColumn` | Items wrap to next line when space runs out |

### Modifiers
Chain modifiers to control dimensions, padding, alignment, click behavior, etc.:
```kotlin
Text(
    text = "Hello",
    modifier = Modifier
        .padding(16.dp)
        .fillMaxWidth()
        .clickable { /* ... */ }
)
```
Modifier order matters — each modifier wraps the next.
</layouts>

<lifecycle>
## Lifecycle

Adopted from Jetpack Compose lifecycle. Add dependency:
```
org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.10.0
```

### Key concepts
- All composables share a common `LifecycleOwner` by default (provided as `CompositionLocal`)
- States: `INITIALIZED` → `CREATED` → `STARTED` → `RESUMED` → (reverse on teardown)
- Create your own `LifecycleOwner` to manage a subtree's lifecycle separately

### Platform mapping

**iOS:**
| Native event | Lifecycle event | State change |
|--------------|----------------|--------------|
| `viewWillAppear` | `ON_START` | CREATED → STARTED |
| `didBecomeActive` | `ON_RESUME` | STARTED → RESUMED |
| `willResignActive` | `ON_PAUSE` | RESUMED → STARTED |
| `viewDidDisappear` | `ON_STOP` | STARTED → CREATED |
| `didEnterBackground` | `ON_STOP` | STARTED → CREATED |
| `willEnterForeground` | `ON_START` | CREATED → STARTED |
| `viewControllerDidLeaveWindowHierarchy` | `ON_DESTROY` | CREATED → DESTROYED |

**Desktop:**
| Swing callback | Lifecycle event | State change |
|----------------|----------------|--------------|
| `windowGainedFocus` | `ON_RESUME` | STARTED → RESUMED |
| `windowLostFocus` | `ON_PAUSE` | RESUMED → STARTED |
| `windowDeiconified` | `ON_START` | CREATED → STARTED |
| `windowIconified` | `ON_STOP` | STARTED → CREATED |
| `dispose` | `ON_DESTROY` | CREATED → DESTROYED |

**Web:** Skips CREATED state; never reaches DESTROYED. Uses `visibilitychange`, `focus`/`blur`.

### Desktop coroutine gotcha
`Lifecycle.coroutineScope` uses `Dispatchers.Main.immediate`, which is unavailable on desktop by default. Add `kotlinx-coroutines-swing` dependency.
</lifecycle>

<viewmodel>
## Common ViewModel

Add dependency:
```
org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0
```

### Declaration
```kotlin
class OrderViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(OrderUiState())
    val uiState: StateFlow<OrderUiState> = _uiState.asStateFlow()
}
```

### Usage in composables
```kotlin
@Composable
fun CupcakeApp(
    viewModel: OrderViewModel = viewModel { OrderViewModel() }
) {
    val uiState by viewModel.uiState.collectAsState()
    // ...
}
```

### Key differences from Android-only
- **Always provide an initializer:** `viewModel { MyViewModel() }` — non-JVM platforms can't instantiate via type reflection
- **Desktop coroutines:** `viewModelScope` uses `Dispatchers.Main.immediate` — add `kotlinx-coroutines-swing`
- Custom factories work the same as Jetpack Compose
</viewmodel>
