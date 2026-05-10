# Channels & Shared State

<channel_basics>
## Channel Basics

A `Channel<T>` is a concurrent queue with suspending `send` and `receive` (unlike `BlockingQueue`).

```kotlin
val channel = Channel<Int>()

launch {
    for (x in 1..5) channel.send(x * x)
    channel.close()   // signal no more elements
}

for (value in channel) println(value)  // iterate until closed
```

### Closing
- `channel.close()` sends a special close token; iteration stops
- All previously sent elements are received before the close takes effect
- Sending to a closed channel throws `ClosedSendChannelException`
</channel_basics>

<produce>
## Producer Pattern

The `produce` builder creates a channel and launches a coroutine that sends to it:

```kotlin
fun CoroutineScope.produceSquares(): ReceiveChannel<Int> = produce {
    for (x in 1..5) send(x * x)
}

// Consume with consumeEach (auto-cancels on completion)
val squares = produceSquares()
squares.consumeEach { println(it) }
```
</produce>

<pipelines>
## Pipelines

Chain producers — each stage transforms the stream:

```kotlin
fun CoroutineScope.produceNumbers() = produce {
    var x = 1
    while (true) send(x++)
}

fun CoroutineScope.square(numbers: ReceiveChannel<Int>) = produce {
    for (x in numbers) send(x * x)
}

// Usage:
val numbers = produceNumbers()
val squares = square(numbers)
repeat(5) { println(squares.receive()) }
coroutineContext.cancelChildren()  // clean up
```
</pipelines>

<fan_out_in>
## Fan-Out & Fan-In

### Fan-out: multiple consumers from one channel
```kotlin
val producer = produce { /* send values */ }
repeat(5) { id ->
    launch { for (msg in producer) println("Processor #$id: $msg") }
}
```
Values are distributed fairly — each value goes to exactly one consumer.

### Fan-in: multiple producers to one channel
```kotlin
val channel = Channel<String>()
launch { repeat(10) { channel.send("producer1: $it"); delay(100) } }
launch { repeat(10) { channel.send("producer2: $it"); delay(200) } }
repeat(20) { println(channel.receive()) }
```
</fan_out_in>

<channel_types>
## Buffered Channels

```kotlin
// Default: rendezvous (capacity 0) — send suspends until receive
val rendezvous = Channel<Int>()

// Buffered — send doesn't suspend until buffer is full
val buffered = Channel<Int>(capacity = 10)

// Conflated — keeps only the latest value
val conflated = Channel<Int>(Channel.CONFLATED)

// Unlimited — never suspends send (may OOM)
val unlimited = Channel<Int>(Channel.UNLIMITED)
```
</channel_types>

<shared_state>
## Shared Mutable State

The problem: concurrent coroutines incrementing a shared counter produce incorrect results.

### Solutions (from simplest to most powerful)

**1. Thread-safe data structures (AtomicInteger, ConcurrentHashMap)**
```kotlin
val counter = AtomicInteger()
withContext(Dispatchers.Default) {
    massiveRun { counter.incrementAndGet() }
}
println("Counter = ${counter.get()}")  // correct: 100000
```
Best for simple counters/collections. Doesn't scale to complex state.

**2. Thread confinement — confine all access to a single thread**
```kotlin
val counterContext = newSingleThreadContext("CounterContext")
var counter = 0
withContext(Dispatchers.Default) {
    massiveRun {
        withContext(counterContext) { counter++ }  // all access on one thread
    }
}
```
Correct but slow (context switching per operation). Use coarse-grained confinement instead.

**3. Mutex — coroutine-friendly lock**
```kotlin
val mutex = Mutex()
var counter = 0
withContext(Dispatchers.Default) {
    massiveRun {
        mutex.withLock { counter++ }
    }
}
```
`Mutex.withLock` suspends (doesn't block). Fine-grained locking. Never use `synchronized` or `ReentrantLock` in coroutines — they block the thread.

**4. Actor pattern — message-passing**
Confine state to a single coroutine. Other coroutines send messages via a channel:
```kotlin
sealed class CounterMsg
object Increment : CounterMsg()
class GetCounter(val response: CompletableDeferred<Int>) : CounterMsg()

fun CoroutineScope.counterActor() = actor<CounterMsg> {
    var counter = 0
    for (msg in channel) {
        when (msg) {
            is Increment -> counter++
            is GetCounter -> msg.response.complete(counter)
        }
    }
}
```
</shared_state>

<volatile_warning>
## @Volatile is NOT Enough

`@Volatile` provides visibility (reads see latest write) but NOT atomicity. `counter++` is read + write — not atomic even with `@Volatile`.

```kotlin
@Volatile var counter = 0  // WRONG — still racy under concurrent access
```

Use `AtomicInteger`, `Mutex`, or confinement instead.
</volatile_warning>

<channels_vs_flow>
## Channels vs Flow

| | `Channel` | `Flow` |
|-|-----------|--------|
| **Nature** | Hot — exists independently of consumers | Cold — runs on each `collect` |
| **Backpressure** | Suspending send/receive | Built-in (sequential by default) |
| **Multiple consumers** | Values distributed (fan-out) | Each collector gets all values |
| **Use case** | Communication between coroutines | Data streams, reactive pipelines |
| **State** | `StateFlow`/`SharedFlow` bridge the gap | `Flow` for cold, `StateFlow`/`SharedFlow` for hot |

**Rule of thumb:** Use `Flow` for data streams. Use `Channel` for coroutine-to-coroutine communication.
</channels_vs_flow>
