# Asynchronous Flow

<flow_basics>
## Flow Basics

`Flow<T>` represents an asynchronous stream of values. Like `Sequence<T>` but with suspension support.

```kotlin
fun numbers(): Flow<Int> = flow {   // flow builder
    for (i in 1..3) {
        delay(100)         // can suspend
        emit(i)            // emit next value
    }
}

// Collect (terminal operator — triggers execution)
numbers().collect { value -> println(value) }
```

### Key properties
- **Flows are cold** — code in `flow { }` doesn't run until `collect` is called. Each `collect` restarts from scratch.
- **Context preservation** — flow body runs in the collector's context by default
- **The function returning a Flow is NOT `suspend`** — it returns immediately; work happens on `collect`
- **Cancellation** — flows respect cooperative cancellation; collection stops at the next suspension point
</flow_basics>

<flow_builders>
## Flow Builders

```kotlin
// 1. flow { } — most general
flow {
    emit(1)
    emit(2)
}

// 2. flowOf() — fixed values
flowOf(1, 2, 3)

// 3. .asFlow() — convert collections/sequences/ranges
(1..10).asFlow()
listOf("a", "b").asFlow()
```
</flow_builders>

<intermediate_operators>
## Intermediate Operators

Intermediate operators are cold — they define a pipeline but don't trigger collection.

```kotlin
(1..10).asFlow()
    .filter { it % 2 == 0 }           // keep evens
    .map { it * it }                    // square them
    .take(3)                            // first 3 only
    .collect { println(it) }            // 4, 16, 36
```

### Key operators
| Operator | Description |
|----------|-------------|
| `map { }` | Transform each value (can call suspend fns) |
| `filter { }` | Keep values matching predicate |
| `transform { }` | General — can emit 0 or more values per input |
| `take(n)` | Take first n values, then cancel upstream |
| `drop(n)` | Skip first n values |
| `distinctUntilChanged()` | Skip consecutive duplicates |
| `onEach { }` | Side effect on each value (doesn't transform) |

### transform — most general
```kotlin
(1..3).asFlow().transform { value ->
    emit("Making request $value")
    emit(performRequest(value))  // can call suspend functions
}
```
</intermediate_operators>

<terminal_operators>
## Terminal Operators

Terminal operators are `suspend` functions that trigger flow collection.

| Operator | Returns | Description |
|----------|---------|-------------|
| `collect { }` | `Unit` | Process each value |
| `toList()` | `List<T>` | Collect all values into a list |
| `toSet()` | `Set<T>` | Collect all values into a set |
| `first()` | `T` | First value (cancels flow after) |
| `single()` | `T` | Exactly one value (throws if 0 or 2+) |
| `reduce { acc, v -> }` | `T` | Accumulate without initial value |
| `fold(init) { acc, v -> }` | `R` | Accumulate with initial value |

```kotlin
val sum = (1..5).asFlow()
    .map { it * it }
    .reduce { a, b -> a + b }  // 55
```
</terminal_operators>

<flow_context>
## Flow Context and flowOn

Flow preserves context — emission runs in the collector's context. **Do NOT use `withContext` inside `flow { }`** to change the emission context. It throws `IllegalStateException`.

### flowOn — correct way to change upstream context
```kotlin
fun cpuIntensiveFlow(): Flow<Int> = flow {
    for (i in 1..3) {
        Thread.sleep(100)  // CPU work
        emit(i)
    }
}.flowOn(Dispatchers.Default)  // emission runs on Default, collection on caller's context
```

`flowOn` changes the context for everything **upstream** of it. Collection still runs in the collector's context.
</flow_context>

<buffering>
## Buffering & Back-Pressure

### buffer() — overlap emission and collection
```kotlin
flow.buffer().collect { ... }
// Emission and collection run concurrently in separate coroutines
```
Without buffer: ~1200ms (100ms emit + 300ms collect × 3, sequential)  
With buffer: ~1000ms (emission overlaps with collection)

### conflate() — skip intermediate values
When the collector is slower than the emitter, process only the most recent value:
```kotlin
flow.conflate().collect { value ->
    delay(300)
    println(value)  // may skip values 2, 4, etc.
}
```

### collectLatest { } — cancel slow collector on new value
```kotlin
flow.collectLatest { value ->
    println("Collecting $value")
    delay(300)
    println("Done $value")  // only the last value completes
}
```
</buffering>

<combining>
## Combining Flows

### zip — pairs values by position
```kotlin
val nums = (1..3).asFlow()
val strs = flowOf("one", "two", "three")
nums.zip(strs) { a, b -> "$a -> $b" }
    .collect { println(it) }  // 1 -> one, 2 -> two, 3 -> three
```

### combine — re-emit on either flow's new value
```kotlin
val nums = (1..3).asFlow().onEach { delay(300) }
val strs = flowOf("one", "two", "three").onEach { delay(400) }
nums.combine(strs) { a, b -> "$a -> $b" }
    .collect { println(it) }
// Emits every time either flow produces a new value, using latest from both
```

### Flattening flows
| Operator | Behavior |
|----------|----------|
| `flatMapConcat` | Process inner flows sequentially |
| `flatMapMerge` | Process inner flows concurrently (configurable concurrency) |
| `flatMapLatest` | Cancel previous inner flow when new value arrives |
</combining>

<flow_exceptions>
## Exception Handling in Flows

### catch operator — handle upstream exceptions
```kotlin
flow { emit(1); throw RuntimeException("oops") }
    .catch { e -> emit(-1) }  // recovers; only catches upstream exceptions
    .collect { println(it) }   // 1, -1
```

`catch` is **transparent to downstream** — it doesn't catch exceptions in `collect { }`.

### Declarative handling
```kotlin
simple()
    .onEach { check(it > 0) }         // throws on bad values
    .catch { emit(-1) }               // handles errors
    .onCompletion { cause -> ... }     // like finally
    .collect { println(it) }
```

### onCompletion — flow completion handler
```kotlin
flow.onCompletion { cause ->
    if (cause != null) println("Flow failed: $cause")
    else println("Flow completed")
}.collect { ... }
```
</flow_exceptions>

<stateflow_sharedflow>
## StateFlow & SharedFlow

### StateFlow — observable state holder (hot)
```kotlin
val _state = MutableStateFlow(0)
val state: StateFlow<Int> = _state.asStateFlow()

// Update
_state.value = 42
_state.update { it + 1 }  // atomic read-modify-write

// Collect (always gets latest value immediately + updates)
state.collect { println(it) }
```
- Always has a value (initial value required)
- Conflated: collectors always see the latest value
- Replays 1 value to new collectors

### SharedFlow — event bus (hot)
```kotlin
val _events = MutableSharedFlow<Event>()
val events: SharedFlow<Event> = _events.asSharedFlow()

_events.emit(Event.Click)  // suspend until all collectors receive

// Configure replay and buffer
MutableSharedFlow<Event>(replay = 1, extraBufferCapacity = 64)
```
</stateflow_sharedflow>
