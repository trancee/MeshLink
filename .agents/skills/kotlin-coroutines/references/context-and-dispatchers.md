# Context, Dispatchers & Supervision

<dispatchers>
## Dispatchers

The coroutine context includes a `CoroutineDispatcher` that determines which thread(s) the coroutine uses.

| Dispatcher | Thread(s) | Use For |
|-----------|----------|---------|
| `Dispatchers.Default` | Shared pool (CPU cores) | CPU-intensive work |
| `Dispatchers.IO` | Shared elastic pool (64+ threads) | Blocking I/O (files, network, DB) |
| `Dispatchers.Main` | Main/UI thread | UI updates (Android, Swing, JavaFX) |
| `Dispatchers.Unconfined` | Caller thread until first suspension | Testing, special cases only |
| `newSingleThreadContext("name")` | Dedicated thread | Thread confinement (expensive — close when done) |

```kotlin
launch(Dispatchers.Default) { /* CPU work */ }
launch(Dispatchers.IO) { /* file read */ }

// Switch dispatcher mid-coroutine:
withContext(Dispatchers.IO) {
    val data = readFile()
}
// Back on original dispatcher here
```

### Unconfined dispatcher
Starts in the caller thread but resumes in whatever thread the suspending function used. Don't use in general code.

### Inheriting dispatcher
`launch { }` without a dispatcher inherits from its parent scope. Inside `runBlocking`, that's the calling thread.
</dispatchers>

<context_elements>
## Coroutine Context

The context is a set of elements. Main elements: `Job`, `CoroutineDispatcher`, `CoroutineName`, `CoroutineExceptionHandler`.

### Combining context elements
```kotlin
launch(Dispatchers.Default + CoroutineName("worker")) {
    println("Running in ${coroutineContext[CoroutineName]}")
}
```

### CoroutineName (debugging)
```kotlin
launch(CoroutineName("fetchUser")) {
    log("Fetching user")  // [DefaultDispatcher-worker-1 @fetchUser#3] Fetching user
}
```

### Debugging coroutines
Add `-Dkotlinx.coroutines.debug` JVM option. Thread names include coroutine name and ID:
```
[main @coroutine#2] I'm computing a piece of the answer
```
</context_elements>

<job_hierarchy>
## Job and Parent-Child Hierarchy

Every coroutine has a `Job` in its context. The parent-child relationship:

```kotlin
val parentJob = launch {
    val childJob = launch {
        // parentJob is parent of childJob
    }
}
```

- `coroutineContext[Job]` — access the current Job
- Children inherit the parent's context (dispatcher, name, etc.) and can override individual elements
- A new `Job()` in context **breaks** the parent-child link (the coroutine becomes a root)

### Job lifecycle states
```
New → Active → Completing → Completed
                    ↓
               Cancelling → Cancelled
```
</job_hierarchy>

<exception_handling>
## Exception Handling

### Propagation rules
- **`launch`** — propagates exceptions **up** to parent automatically (uncaught exception)
- **`async`** — holds the exception in the `Deferred` until `.await()` is called

### CoroutineExceptionHandler
Only works on **root** coroutines (not children). Children delegate exceptions to their parent.

```kotlin
val handler = CoroutineExceptionHandler { _, exception ->
    println("Caught: $exception")
}

val scope = CoroutineScope(Dispatchers.Default + handler)
scope.launch {
    throw RuntimeException("oops")  // handled by handler
}
```

### Exception aggregation
If multiple children fail, the first exception is handled; subsequent ones are attached as `suppressed` exceptions.
</exception_handling>

<supervision>
## Supervision

### SupervisorJob
A `SupervisorJob` lets children fail independently — one child's failure does NOT cancel siblings.

```kotlin
val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
scope.launch { throw RuntimeException("fails") }  // only this one fails
scope.launch { delay(1000); println("Still alive") }  // unaffected
```

### supervisorScope
Like `coroutineScope` but with supervisor behavior:

```kotlin
supervisorScope {
    launch {
        throw RuntimeException("child 1 fails")
    }
    launch {
        delay(100)
        println("child 2 is fine")  // still runs
    }
}
```

Key difference: in `coroutineScope`, if one child fails, all siblings are cancelled. In `supervisorScope`, they're independent.

**Important:** `CoroutineExceptionHandler` works in `supervisorScope` children — each failed child is a "root" from the supervisor's perspective.
</supervision>

<debugging>
## Debugging Tips

- **JVM flag:** `-Dkotlinx.coroutines.debug` — coroutine names in thread names
- **IntelliJ Coroutine Debugger** — shows coroutine state, local variables, creation stacks
- **Build reports:** Enable `kotlin.build.report.output=file` for compilation diagnostics
- **Logging pattern:**
  ```kotlin
  fun log(msg: String) = println("[${Thread.currentThread().name}] $msg")
  ```
</debugging>
