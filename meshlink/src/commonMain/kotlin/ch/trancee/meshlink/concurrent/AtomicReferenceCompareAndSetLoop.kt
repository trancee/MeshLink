package ch.trancee.meshlink.concurrent

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Atomically replaces this reference's value with `transform(currentValue)`, retrying the
 * read-transform-compare-and-swap cycle until no other writer raced the update, and returns the
 * value that was successfully stored.
 *
 * Kotlin's [kotlin.concurrent.atomics] package does not yet expose a public `updateAndGet`/
 * `getAndUpdate` on [AtomicReference] at this project's Kotlin version (2.4.0) -- only
 * `load`/`store`/`exchange`/`compareAndSet` are public; the stdlib's own `update`/`fetchAndUpdate`/
 * `updateAndFetch` extensions exist internally but are not visible outside the stdlib itself. This
 * is the shared replacement used by every `commonMain` caller that needs a compare-and-swap loop
 * over an [AtomicReference] (originally duplicated independently in `PowerPolicyController`, and
 * again in `L2capReconnectGuard`, before being consolidated here).
 */
@OptIn(ExperimentalAtomicApi::class)
internal fun <T> AtomicReference<T>.compareAndSetLoop(transform: (T) -> T): T {
    while (true) {
        val previous = load()
        val next = transform(previous)
        if (compareAndSet(previous, next)) {
            return next
        }
    }
}
