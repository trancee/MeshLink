# Expect/Actual Declarations

<rules>
## Core Rules

1. In `commonMain`, declare a construct with the `expect` keyword — no implementation
2. In each platform source set, provide a matching `actual` declaration with the implementation
3. Both must be in the **same package**
4. The compiler ensures every `expect` has a matching `actual` in every platform source set
5. Actual declarations can live in intermediate source sets (e.g., `iosMain` covers both `iosArm64` and `iosSimulatorArm64`)

```kotlin
// commonMain
expect fun platformName(): String

// androidMain
actual fun platformName(): String = "Android"

// iosMain
actual fun platformName(): String = "iOS"
```
</rules>

<patterns>
## Patterns (Preferred → Least Preferred)

### 1. Expected/Actual Functions (Preferred for simple cases)

```kotlin
// commonMain
class Identity(val userName: String, val processID: Long)
expect fun buildIdentity(): Identity

// jvmMain
actual fun buildIdentity() = Identity(
    System.getProperty("user.name") ?: "None",
    ProcessHandle.current().pid()
)

// nativeMain
actual fun buildIdentity() = Identity(
    getlogin()?.toKString() ?: "None",
    getpid().toLong()
)
```

### 2. Interfaces + Expected Factory Functions (Preferred for complex APIs)

Define the interface in common, implement per-platform:

```kotlin
// commonMain
interface CryptoProvider {
    fun hash(data: ByteArray): ByteArray
    fun sign(data: ByteArray, key: ByteArray): ByteArray
}
expect fun createCryptoProvider(): CryptoProvider

// androidMain
actual fun createCryptoProvider(): CryptoProvider = AndroidCryptoProvider()
class AndroidCryptoProvider : CryptoProvider { /* ... */ }

// iosMain
actual fun createCryptoProvider(): CryptoProvider = IosCryptoProvider()
class IosCryptoProvider : CryptoProvider { /* ... */ }
```

**Advantages:** Multiple implementations per platform, easy to mock in tests, not limited to one implementation.

### 3. Expected/Actual Properties

```kotlin
// commonMain
expect val currentPlatform: String

// androidMain
actual val currentPlatform: String = "Android ${Build.VERSION.SDK_INT}"

// iosMain
actual val currentPlatform: String = "iOS ${UIDevice.currentDevice.systemVersion}"
```

### 4. Expected/Actual Objects (Singletons)

```kotlin
// commonMain
expect object PlatformLogger {
    fun log(message: String)
}

// androidMain
actual object PlatformLogger {
    actual fun log(message: String) = Log.d("App", message)
}
```

### 5. Expected/Actual Classes (Beta — use sparingly)

```kotlin
// commonMain
expect class Identity() {
    val userName: String
    val processID: Long
}

// jvmMain
actual class Identity {
    actual val userName: String = System.getProperty("user.name") ?: "None"
    actual val processID: Long = ProcessHandle.current().pid()
}
```

**Use only when:** inheriting from a platform-specific base class, or when a framework requires it. Prefer interfaces + factory functions otherwise.

Requires compiler flag: `freeCompilerArgs.add("-Xexpect-actual-classes")`
</patterns>

<type_aliases>
## Type Aliases for Actual Declarations

When a platform already provides the type you need, use `actual typealias`:

```kotlin
// commonMain
expect enum class Month {
    JANUARY, FEBRUARY, MARCH, APRIL, MAY, JUNE, JULY,
    AUGUST, SEPTEMBER, OCTOBER, NOVEMBER, DECEMBER
}

// jvmMain
actual typealias Month = java.time.Month
```

The aliased type must satisfy all the requirements of the expected declaration (same members, same signatures).
</type_aliases>

<optional_expectations>
## Optional Expectations (Annotations)

For annotations that only matter on some platforms, use `@OptionalExpectation`:

```kotlin
@OptIn(ExperimentalMultiplatform::class)
@OptionalExpectation
@Target(AnnotationTarget.CLASS)
expect annotation class JvmSerializable()
```

No `actual` needed on platforms where the annotation isn't used.
</optional_expectations>

<naming_conventions>
## Naming Conventions for Actual Classes

| Convention | Example | Avoid |
|-----------|---------|-------|
| Platform prefix for `actual` classes | `AndroidBleTransport`, `IosBleTransport` | `BleTransportAndroid` |
| `Ios` prefix (not `IOS` or `iOS`) | `IosSecureStorage` | `IOSSecureStorage` |
| Factory function in `commonMain` | `expect fun createX(): X` | Exposing platform classes directly |
</naming_conventions>
