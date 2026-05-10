---
name: kotlin-coroutines
description: Kotlin Coroutines (kotlinx.coroutines) reference covering the full coroutines guide. Includes suspend functions, coroutine builders (launch, async, runBlocking, withContext), structured concurrency, cancellation and timeouts, dispatchers (Default, IO, Main), coroutine context, SupervisorJob and supervisorScope, Asynchronous Flow (builders, operators, flowOn, buffer, conflate, collectLatest, StateFlow, SharedFlow), Channels (producer, pipelines, fan-out/fan-in), exception handling (CoroutineExceptionHandler, supervision), and shared mutable state (Mutex, AtomicInteger, confinement). Use when writing or debugging coroutine code, asking about "launch vs async", "how to cancel a coroutine", "Flow operators", "StateFlow vs SharedFlow", "Mutex vs synchronized", "supervisorScope", "flowOn", "structured concurrency", or any kotlinx.coroutines topic.
---

<essential_principles>

**kotlinx.coroutines** is the standard library for Kotlin concurrency. Coroutines suspend instead of blocking threads, enabling lightweight concurrency with sequential-looking code.

### Core Rules an Agent Must Know

- **Structured concurrency is mandatory.** Every coroutine must launch inside a `CoroutineScope`. Parents wait for children; cancelling a parent cancels all children. Never use `GlobalScope`.
- **`launch` for fire-and-forget, `async` for results.** `launch` returns `Job`; `async` returns `Deferred<T>` — call `.await()` to get the value.
- **Cancellation is cooperative.** Coroutines only stop at suspension points or explicit `isActive`/`ensureActive()` checks. CPU-bound loops must check for cancellation.
- **Always rethrow `CancellationException`.** Swallowing it breaks cancellation propagation.
- **Use `withContext(Dispatchers.IO)` for blocking I/O.** `Dispatchers.Default` for CPU work. Never block `Dispatchers.Main`.
- **Flow is cold, Channel is hot.** `flow { }` runs on each `collect`; `Channel` exists independently. Use `flowOn` (not `withContext`) to change a flow's emission context.
- **Use `Mutex` instead of `synchronized`** in coroutines — `Mutex` suspends, `synchronized` blocks the thread.
- **`supervisorScope`** lets children fail independently. Use it when sibling failures should not propagate.
- **`StateFlow` replaces `LiveData`** — always has a value, conflated, replay-1. `SharedFlow` for events.
- **`runBlocking` is for entry points and tests only** — it blocks the calling thread.

</essential_principles>

<routing>

Based on what you need, read the appropriate reference:

| Topic | Reference |
|-------|-----------|
| Suspend functions, coroutine builders (launch/async/runBlocking/withContext), structured concurrency, composing suspend fns, anti-patterns | `references/fundamentals.md` |
| Cancellation, cooperative cancellation, cleanup with finally/NonCancellable, CancellationException rules, withTimeout/withTimeoutOrNull | `references/cancellation.md` |
| Dispatchers (Default/IO/Main/Unconfined), coroutine context, CoroutineName, Job hierarchy, exception handling, CoroutineExceptionHandler, SupervisorJob, supervisorScope, debugging | `references/context-and-dispatchers.md` |
| Flow builders, intermediate operators (map/filter/transform/take), terminal operators (collect/toList/reduce/fold), flowOn, buffer/conflate/collectLatest, combining (zip/combine), flatMap*, exception handling in flows, onCompletion, StateFlow, SharedFlow | `references/flow.md` |
| Channels (send/receive/close), produce/consumeEach, pipelines, fan-out/fan-in, buffered channels, shared mutable state (AtomicInteger, Mutex, confinement, actors), @Volatile warning, Channel vs Flow comparison | `references/channels-and-concurrency.md` |

For getting started, read `references/fundamentals.md`. For Flow questions, go directly to `references/flow.md`.

</routing>

<reference_index>

All domain knowledge in `references/`:

**Fundamentals:** fundamentals.md — suspend functions, dependency setup, structured concurrency (parent-child tree), coroutine builders (launch/Job, async/Deferred, runBlocking, withContext, coroutineScope), sequential vs concurrent composition, lazy async, extracting builders as CoroutineScope extensions, anti-patterns (GlobalScope, runBlocking in production, async-style functions, swallowing CancellationException)
**Cancellation:** cancellation.md — Job.cancel(), cancellation propagation (parent→children, child cancel doesn't cancel parent), cooperative cancellation (isActive, ensureActive, yield), cleanup with finally, NonCancellable context, CancellationException rules (always rethrow), withTimeout, withTimeoutOrNull, resource cleanup with timeouts
**Context & Dispatchers:** context-and-dispatchers.md — Dispatchers (Default, IO, Main, Unconfined, newSingleThreadContext), context elements (+operator), CoroutineName, Job hierarchy and lifecycle, exception propagation (launch auto-propagates, async exposes via await), CoroutineExceptionHandler (root coroutines only), exception aggregation, SupervisorJob, supervisorScope, debugging tips
**Flow:** flow.md — flow builder, flowOf, asFlow, cold streams, context preservation, intermediate operators (map, filter, transform, take, drop, distinctUntilChanged, onEach), terminal operators (collect, toList, first, single, reduce, fold), flowOn (correct context switching), buffer/conflate/collectLatest (back-pressure), combining flows (zip, combine, flatMapConcat/Merge/Latest), exception handling (catch operator, onCompletion), StateFlow (MutableStateFlow, conflated, replay-1), SharedFlow (MutableSharedFlow, events)
**Channels & Concurrency:** channels-and-concurrency.md — Channel basics (send/receive/close), produce builder, consumeEach, pipelines, fan-out (multiple consumers), fan-in (multiple producers), buffered/conflated/unlimited channels, shared mutable state problem, solutions (AtomicInteger, thread confinement, Mutex.withLock, actor pattern), @Volatile warning, Channel vs Flow comparison table

</reference_index>
