# Cancellation and Timeouts

<cancel_basics>
## Cancellation Basics

Cancellation works through the `Job` handle. Call `.cancel()` to request cancellation.

```kotlin
val job = launch {
    repeat(1000) { i ->
        delay(100)     // suspension point — checks cancellation
        println("Working $i")
    }
}
delay(500)
job.cancel()           // request cancellation
job.join()             // wait for completion
// Or: job.cancelAndJoin()
```

When cancelled, the coroutine throws `CancellationException` at the next suspension point.
</cancel_basics>

<propagation>
## Cancellation Propagation

Structured concurrency ensures cancelling a parent cancels all children:

```kotlin
val parent = launch {
    launch { /* child 1 — cancelled when parent is cancelled */ }
    launch { /* child 2 — also cancelled */ }
}
parent.cancel()  // cancels both children
```

Cancelling a **child** does NOT cancel its parent:
```kotlin
val child = launch { ... }
child.cancel()   // only this child is cancelled; parent continues
```
</propagation>

<cooperative>
## Cooperative Cancellation

Cancellation is **cooperative**. A coroutine only reacts to cancellation at suspension points or when it explicitly checks.

### CPU-bound work must check for cancellation
```kotlin
// BAD — never suspends, ignores cancellation
launch {
    var i = 0
    while (i < 1_000_000) { i++ }  // runs to completion even if cancelled
}

// GOOD — check isActive
launch {
    var i = 0
    while (isActive && i < 1_000_000) { i++ }
}

// GOOD — use ensureActive() (throws CancellationException)
launch {
    var i = 0
    while (i < 1_000_000) {
        ensureActive()
        i++
    }
}

// GOOD — use yield() (gives other coroutines a chance to run + checks cancellation)
launch {
    var i = 0
    while (i < 1_000_000) {
        yield()
        i++
    }
}
```
</cooperative>

<cleanup>
## Cleanup on Cancellation

Use `finally` blocks for cleanup. They run even when cancelled:

```kotlin
val job = launch {
    try {
        repeat(1000) {
            delay(100)
            println("Working...")
        }
    } finally {
        // Cleanup runs here — but you CANNOT suspend in finally after cancellation
        println("Cleaning up")
    }
}
```

### Suspending in finally (NonCancellable)
After cancellation, suspending functions throw `CancellationException` immediately. Use `NonCancellable` to run suspending cleanup:

```kotlin
finally {
    withContext(NonCancellable) {
        delay(100)          // OK — runs despite cancellation
        closeResources()
    }
}
```
</cleanup>

<cancellation_exception>
## CancellationException Rules

- `CancellationException` is **ignored** by `CoroutineExceptionHandler` — it's normal cancellation, not a failure
- If you `catch (e: CancellationException)`, **always rethrow** it
- If you `catch (e: Exception)`, check `if (e is CancellationException) throw e`
- A coroutine that completes with `CancellationException` does NOT cancel its parent

```kotlin
try {
    delay(Long.MAX_VALUE)
} catch (e: CancellationException) {
    println("Cancelled: ${e.message}")
    throw e   // ALWAYS rethrow
}
```
</cancellation_exception>

<timeouts>
## Timeouts

### withTimeout — throws on timeout
```kotlin
withTimeout(1000) {
    // throws TimeoutCancellationException if not done in 1s
    repeat(100) {
        delay(100)
        println("Tick $it")
    }
}
```
`TimeoutCancellationException` extends `CancellationException`, so it's treated as normal cancellation within the coroutine.

### withTimeoutOrNull — returns null on timeout
```kotlin
val result: String? = withTimeoutOrNull(1000) {
    delay(2000)
    "Done"
}
println(result) // null
```

### Resource cleanup with timeouts
Resources acquired inside `withTimeout` may leak if acquired before the timeout fires. Use `try/finally`:
```kotlin
withTimeout(1000) {
    val resource = acquireResource()
    try {
        useResource(resource)
    } finally {
        releaseResource(resource)
    }
}
```
</timeouts>
