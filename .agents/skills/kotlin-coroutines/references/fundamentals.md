# Coroutine Fundamentals

<suspend_functions>
## Suspending Functions

A `suspend` function can pause its execution and resume later without blocking a thread. The `suspend` keyword is part of core Kotlin; the `kotlinx.coroutines` library provides the runtime.

```kotlin
suspend fun fetchUser(): User {
    delay(1000) // suspends, doesn't block
    return User("Alice")
}
```

- Can only be called from another suspend function or a coroutine builder
- A call to a suspend function is a **suspension point** — the coroutine may pause there
- Suspension is cooperative: the coroutine must reach a suspension point to check cancellation
</suspend_functions>

<dependency>
## Adding kotlinx.coroutines

```kotlin
// build.gradle.kts
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    // Android:
    // implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // Test:
    // testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
```
</dependency>

<structured_concurrency>
## Structured Concurrency

Coroutines form a parent-child tree. This is **structured concurrency**:

- A parent waits for all children to complete before it finishes
- If a parent fails or is cancelled, all children are cancelled recursively
- New coroutines can only launch inside a `CoroutineScope`

```kotlin
suspend fun doWork() = coroutineScope { // creates a scope
    launch { task1() }  // child coroutine
    launch { task2() }  // child coroutine
}   // waits for both children
```

The `coroutineScope()` function creates a scope, waits for all children, and rethrows any failure.
</structured_concurrency>

<builders>
## Coroutine Builders

### `launch` — fire-and-forget
Returns a `Job`. Use when you don't need the result.
```kotlin
val job: Job = scope.launch {
    doSomething()
}
job.join()    // optionally wait
job.cancel()  // optionally cancel
```

### `async` — concurrent computation with result
Returns a `Deferred<T>` (extends `Job`). Use `.await()` to get the result.
```kotlin
val deferred: Deferred<Int> = scope.async {
    computeValue()
}
val result = deferred.await()
```

### Concurrent vs sequential
```kotlin
// Sequential (default) — ~2000ms
val a = fetchA()
val b = fetchB()

// Concurrent with async — ~1000ms
val a = async { fetchA() }
val b = async { fetchB() }
val result = a.await() + b.await()
```

### Lazy async
```kotlin
val lazy = async(start = CoroutineStart.LAZY) { compute() }
lazy.start()       // explicitly start
val result = lazy.await()
```
If you call `await()` without `start()`, it runs sequentially (defeats the purpose).

### `runBlocking` — bridge to suspending world
Blocks the current thread. Use only at program entry points or in tests.
```kotlin
fun main() = runBlocking {
    launch { doWork() }
}
```

### `withContext` — switch context
Suspends and runs in a different context. Returns the result.
```kotlin
val result = withContext(Dispatchers.IO) {
    readFile()
}
```

### `coroutineScope` — new scope without switching context
Waits for all children. If any child fails, all siblings are cancelled.
```kotlin
suspend fun loadBoth() = coroutineScope {
    val a = async { loadA() }
    val b = async { loadB() }
    combine(a.await(), b.await())
}
```
</builders>

<extracting_builders>
## Extracting Coroutine Builders

To extract `launch`/`async` calls into a separate function, declare `CoroutineScope` as the receiver:

```kotlin
fun CoroutineScope.launchAll() {
    launch { task1() }
    launch { task2() }
}

// Usage:
coroutineScope { launchAll() }
```

Without the `CoroutineScope` receiver, calling `launch` would be a compilation error.
</extracting_builders>

<anti_patterns>
## Anti-Patterns to Avoid

- **Don't use `GlobalScope`** — it breaks structured concurrency, leaks coroutines. Use a proper scope.
- **Don't use `runBlocking` in production code** — it blocks the calling thread. Use it only at entry points or tests.
- **Don't use async-style functions** (`fun somethingAsync() = GlobalScope.async { ... }`) — they break structured concurrency. Instead, make the function `suspend` and let the caller decide on concurrency.
- **Don't swallow `CancellationException`** — always rethrow it, or cancellation propagation breaks.
</anti_patterns>
