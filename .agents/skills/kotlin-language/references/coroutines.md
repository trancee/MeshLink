# Kotlin Coroutines

<fundamentals>
## Core Concepts

Coroutines are lightweight alternatives to threads for asynchronous programming. They can **suspend** without blocking the underlying thread.

### Suspending Functions

Mark with `suspend` — can pause and resume:

```kotlin
suspend fun fetchUser(id: String): User {
    return httpClient.get("https://api.example.com/users/$id")
}
```

Suspending functions can only be called from other suspending functions or coroutine builders.

### Coroutine Builders

| Builder | Returns | Use case |
|---------|---------|----------|
| `launch` | `Job` | Fire-and-forget; no result needed |
| `async` | `Deferred<T>` | Returns a result via `.await()` |
| `runBlocking` | `T` | Bridges blocking and suspending worlds (main/tests only) |

```kotlin
// launch — fire and forget
scope.launch {
    val user = fetchUser("123")
    updateUI(user)
}

// async — get a result
val deferred = scope.async { fetchUser("123") }
val user = deferred.await()

// Parallel decomposition
coroutineScope {
    val user = async { fetchUser("123") }
    val orders = async { fetchOrders("123") }
    processResult(user.await(), orders.await())
}
```
</fundamentals>

<structured_concurrency>
## Structured Concurrency

Coroutines follow a parent-child hierarchy via `CoroutineScope`. When a parent is cancelled, all children are cancelled too.

```kotlin
class MyViewModel : ViewModel() {
    fun loadData() {
        viewModelScope.launch {  // Scoped to ViewModel lifecycle
            val data = repository.fetch()
            _state.value = data
        }
    }
}
```

### `coroutineScope` — Structured block

```kotlin
suspend fun loadBoth(): Pair<User, Orders> = coroutineScope {
    val user = async { fetchUser() }
    val orders = async { fetchOrders() }
    Pair(user.await(), orders.await())
}
// If either fails, the other is cancelled
```
</structured_concurrency>

<context_dispatchers>
## Context & Dispatchers

| Dispatcher | Thread | Use case |
|-----------|--------|----------|
| `Dispatchers.Main` | UI thread | UI updates (Android) |
| `Dispatchers.IO` | Thread pool | Network, disk, database |
| `Dispatchers.Default` | CPU pool | CPU-intensive computation |
| `Dispatchers.Unconfined` | Caller thread | Testing/special cases |

```kotlin
launch(Dispatchers.IO) {
    val data = fetchFromNetwork()
    withContext(Dispatchers.Main) {
        updateUI(data)
    }
}
```
</context_dispatchers>

<flow>
## Flow (Asynchronous Streams)

Cold stream of values — emits only when collected:

```kotlin
fun numbers(): Flow<Int> = flow {
    for (i in 1..3) {
        delay(100)
        emit(i)
    }
}

// Collect
numbers().collect { value -> println(value) }

// Operators
numbers()
    .filter { it > 1 }
    .map { it * 2 }
    .collect { println(it) }
```

### StateFlow and SharedFlow

```kotlin
// StateFlow — holds latest value, replays to new collectors
private val _state = MutableStateFlow<UiState>(UiState.Loading)
val state: StateFlow<UiState> = _state.asStateFlow()

// SharedFlow — broadcast to multiple collectors
private val _events = MutableSharedFlow<Event>()
val events: SharedFlow<Event> = _events.asSharedFlow()
```
</flow>

<cancellation>
## Cancellation

```kotlin
val job = launch {
    repeat(1000) { i ->
        println("Working $i")
        delay(500)  // Cooperative cancellation — checks for cancellation
    }
}

delay(2000)
job.cancel()     // Cancel the coroutine
job.join()       // Wait for it to finish

// Or combined:
job.cancelAndJoin()
```

Cancellation is **cooperative** — suspending functions like `delay()`, `yield()`, and `ensureActive()` check for cancellation. CPU-bound loops must check manually via `isActive` or `ensureActive()`.
</cancellation>

<channels>
## Channels

For coroutine-to-coroutine communication:

```kotlin
val channel = Channel<Int>()

launch {
    for (x in 1..5) channel.send(x)
    channel.close()
}

for (value in channel) {
    println(value)
}
```

Each value is delivered to exactly one receiver (unlike SharedFlow which broadcasts).
</channels>
