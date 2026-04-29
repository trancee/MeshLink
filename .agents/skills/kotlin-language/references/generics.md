# Kotlin Generics

<basics>
## Generic Classes and Functions

```kotlin
// Generic class
class Box<T>(val value: T)

val intBox = Box(1)         // Box<Int> inferred
val strBox = Box("hello")   // Box<String> inferred

// Generic function
fun <T> singletonList(item: T): List<T> = listOf(item)

// Generic extension function
fun <T> T.basicToString(): String = toString()

// Type parameter with upper bound
fun <T : Comparable<T>> sort(list: List<T>) { ... }

// Multiple upper bounds (where clause)
fun <T> copyWhenGreater(list: List<T>, threshold: T): List<String>
    where T : CharSequence,
          T : Comparable<T> {
    return list.filter { it > threshold }.map { it.toString() }
}
```

Default upper bound is `Any?` when none specified.
</basics>

<variance>
## Declaration-Site Variance (`out` / `in`)

Kotlin replaces Java's `? extends` and `? super` wildcards with declaration-site variance annotations.

### `out` — Covariant (producer)

The type parameter is only **returned** (produced), never consumed:

```kotlin
interface Source<out T> {
    fun nextT(): T   // T in out-position only
}

fun demo(strs: Source<String>) {
    val objects: Source<Any> = strs  // OK — String is subtype of Any
}
```

**Mnemonic:** `out` = read-only / produces `T` / covariant. `Source<String>` is a subtype of `Source<Any>`.

### `in` — Contravariant (consumer)

The type parameter is only **accepted** (consumed), never produced:

```kotlin
interface Comparable<in T> {
    operator fun compareTo(other: T): Int
}

fun demo(x: Comparable<Number>) {
    val y: Comparable<Double> = x  // OK — Number supertype of Double
}
```

**Mnemonic:** `in` = write-only / consumes `T` / contravariant. `Comparable<Number>` is a subtype of `Comparable<Double>`.

### Quick Reference

| Java | Kotlin | Direction | Can... |
|------|--------|-----------|--------|
| `? extends T` | `out T` | Covariant | Read `T`, not write |
| `? super T` | `in T` | Contravariant | Write `T`, not read meaningfully |
| `?` | `*` | Star projection | Safe unknown |
</variance>

<use_site_variance>
## Use-Site Variance (Type Projections)

When declaration-site variance isn't possible (e.g., `Array<T>` is invariant):

```kotlin
// out-projection — can read but not write
fun copy(from: Array<out Any>, to: Array<Any>) {
    for (i in from.indices) to[i] = from[i]
}

// in-projection — can write but not read meaningfully
fun fill(dest: Array<in String>, value: String) {
    for (i in dest.indices) dest[i] = value
}
```
</use_site_variance>

<star_projections>
## Star Projections (`*`)

When you know nothing about the type argument but still want type safety:

```kotlin
fun printAll(list: List<*>) {
    list.forEach { println(it) }  // items typed as Any?
}

// Star projection rules:
// Foo<out T : TUpper>  →  Foo<*> ≡ Foo<out TUpper>    (can read TUpper)
// Foo<in T>            →  Foo<*> ≡ Foo<in Nothing>     (can't write safely)
// Foo<T : TUpper>      →  Foo<*> ≡ read as TUpper, can't write
```
</star_projections>

<type_erasure>
## Type Erasure

Generic type arguments are erased at runtime. You **cannot** check `obj is List<String>` at runtime.

```kotlin
// Compile error:
// if (obj is List<String>) { }

// OK — star-projected check:
if (obj is List<*>) { }

// Reified type parameters (inline functions only) — retain type at runtime:
inline fun <reified T> isInstance(value: Any): Boolean = value is T
inline fun <reified T> filterByType(list: List<Any>): List<T> =
    list.filterIsInstance<T>()
```
</type_erasure>

<definitely_non_nullable>
## Definitely Non-Nullable Types

For Java interop — ensures a generic type parameter is non-null:

```kotlin
// Java: @NotNull T load(@NotNull T x)
interface ArcadeGame<T1> : Game<T1> {
    override fun load(x: T1 & Any): T1 & Any  // T1 is definitely non-nullable
}
```
</definitely_non_nullable>
