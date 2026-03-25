# KMP Testing Patterns Reference

Detailed testing patterns, library usage, and platform-specific test setup for Kotlin Multiplatform projects.

## Table of Contents
- [kotlin.test API reference](#kotlintest-api-reference)
- [Turbine deep dive](#turbine-deep-dive)
- [Coroutines testing patterns](#coroutines-testing-patterns)
- [Fakes over mocks](#fakes-over-mocks)
- [Platform-specific test setup](#platform-specific-test-setup)
- [Gradle test configuration](#gradle-test-configuration)

---

## kotlin.test API reference

### Annotations

| Annotation | Purpose |
|---|---|
| `@Test` | Marks a function as a test case |
| `@BeforeTest` | Runs before each test in the class |
| `@AfterTest` | Runs after each test in the class |
| `@Ignore` | Skips the test |

There is no `@BeforeClass` / `@AfterClass` equivalent in `kotlin.test`. If you need class-level setup, use a `companion object` with lazy initialization or restructure to per-test setup.

### Assertion functions

```kotlin
assertEquals(expected, actual, message?)
assertNotEquals(illegal, actual, message?)
assertTrue(condition, message?)
assertFalse(condition, message?)
assertNull(value, message?)
assertNotNull(value, message?)  // returns non-null value — useful for chaining
assertContains(collection, element)
assertContains(string, substring)
assertIs<T>(value)              // smart-casts to T on success
assertIsNot<T>(value)
assertFailsWith<T> { block }   // asserts block throws T, returns the exception
assertContentEquals(expected, actual)  // for arrays and iterables
```

### Example: comprehensive test class

```kotlin
import kotlin.test.*

class StringUtilsTest {

    private lateinit var formatter: StringFormatter

    @BeforeTest
    fun setup() {
        formatter = StringFormatter(locale = "en")
    }

    @AfterTest
    fun cleanup() {
        formatter.close()
    }

    @Test
    fun capitalizes() {
        assertEquals("Hello World", formatter.titleCase("hello world"))
    }

    @Test
    fun handlesEmpty() {
        assertEquals("", formatter.titleCase(""))
    }

    @Test
    fun rejectsNull() {
        assertFailsWith<IllegalArgumentException> {
            formatter.titleCase(null)
        }
    }

    @Ignore("Known bug, tracked in #1234")
    @Test
    fun handlesUnicode() {
        assertEquals("Ñoño", formatter.titleCase("ñoño"))
    }
}
```

---

## Turbine deep dive

Turbine (`app.cash.turbine:turbine`) is a KMP-compatible Flow testing library. Latest stable: `1.2.1`.

### Basic Flow testing

```kotlin
import app.cash.turbine.test

@Test
fun emitsValues() = runTest {
    flowOf("a", "b", "c").test {
        assertEquals("a", awaitItem())
        assertEquals("b", awaitItem())
        assertEquals("c", awaitItem())
        awaitComplete()  // assert the flow completed
    }
}
```

### StateFlow testing

```kotlin
@Test
fun stateFlowUpdates() = runTest {
    val viewModel = CounterViewModel()

    viewModel.count.test {
        assertEquals(0, awaitItem())       // initial state
        viewModel.increment()
        assertEquals(1, awaitItem())
        cancelAndIgnoreRemainingEvents()    // StateFlow never completes
    }
}
```

### Testing multiple Flows

```kotlin
@Test
fun multipleFlows() = runTest {
    turbineScope {
        val names = namesFlow.testIn(backgroundScope)
        val ages = agesFlow.testIn(backgroundScope)

        assertEquals("Alice", names.awaitItem())
        assertEquals(30, ages.awaitItem())

        names.cancelAndIgnoreRemainingEvents()
        ages.cancelAndIgnoreRemainingEvents()
    }
}
```

### Error testing

```kotlin
@Test
fun flowError() = runTest {
    errorFlow.test {
        assertEquals("loading", awaitItem())
        val error = awaitError()
        assertIs<NetworkException>(error)
        assertEquals("timeout", error.message)
    }
}
```

### Standalone Turbine for fakes

Turbine is useful beyond Flow testing — use standalone Turbines in fakes to verify interactions:

```kotlin
class FakeNavigator : Navigator {
    val screens = Turbine<Screen>()

    override fun navigateTo(screen: Screen) {
        screens.add(screen)
    }
}

@Test
fun navigatesOnClick() = runTest {
    val navigator = FakeNavigator()
    val presenter = MyPresenter(navigator)

    presenter.onButtonClick()

    assertEquals(Screen.Details, navigator.screens.awaitItem())
}
```

---

## Coroutines testing patterns

### runTest basics

`runTest` from `kotlinx-coroutines-test` provides:
- Automatic delay skipping (virtual time)
- 60-second timeout by default
- Uncaught exception handling
- Works on all KMP targets

```kotlin
@Test
fun fetchData() = runTest {
    val repo = Repository(FakeApi())
    val result = repo.getData()  // even if this calls delay(5000), the test is instant
    assertEquals("data", result)
}
```

### Dispatchers.Main replacement

`Dispatchers.Main` is unavailable in unit tests on JVM (no Android Looper). Replace it:

```kotlin
class ViewModelTest {
    @BeforeTest
    fun setup() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun loadsData() = runTest {
        val vm = MyViewModel(FakeRepo())
        vm.load()
        assertEquals(UiState.Ready, vm.state.value)
    }
}
```

### advanceUntilIdle vs advanceTimeBy

```kotlin
@Test
fun debounceSearch() = runTest {
    val vm = SearchViewModel(FakeSearch())
    vm.onQuery("kotlin")

    // debounce hasn't fired yet
    assertEquals(emptyList(), vm.results.value)

    advanceTimeBy(500)  // advance virtual time past debounce threshold
    runCurrent()        // execute pending coroutines

    assertEquals(listOf("Kotlin Multiplatform"), vm.results.value)
}
```

### backgroundScope for long-running coroutines

```kotlin
@Test
fun collectsUpdates() = runTest {
    val source = MutableSharedFlow<Int>()
    val collected = mutableListOf<Int>()

    // Launch in backgroundScope — auto-cancelled when test finishes
    backgroundScope.launch {
        source.collect { collected.add(it) }
    }

    source.emit(1)
    source.emit(2)
    runCurrent()

    assertEquals(listOf(1, 2), collected)
}
```

---

## Fakes over mocks

Most mocking libraries (Mockito, MockK) are JVM-only and can't run in `commonTest`. KMP testing naturally pushes you toward fakes, which is actually a better testing practice:

### Interface + fake pattern

```kotlin
// commonMain
interface UserApi {
    suspend fun getUser(id: String): User
    suspend fun saveUser(user: User)
}

// commonTest
class FakeUserApi : UserApi {
    val users = mutableMapOf<String, User>()
    var saveCallCount = 0

    override suspend fun getUser(id: String): User =
        users[id] ?: throw NotFoundException("User $id not found")

    override suspend fun saveUser(user: User) {
        users[user.id] = user
        saveCallCount++
    }
}

class UserServiceTest {
    @Test
    fun updatesUserName() = runTest {
        val api = FakeUserApi()
        api.users["1"] = User("1", "Alice")
        val service = UserService(api)

        service.updateName("1", "Bob")

        assertEquals("Bob", api.users["1"]!!.name)
        assertEquals(1, api.saveCallCount)
    }
}
```

### Benefits of fakes in KMP
- Work in `commonTest` across all platforms
- Compile-time safety (implement all interface methods)
- Easy state inspection (just read the fake's properties)
- No reflection or bytecode manipulation needed
- Simpler debugging — fakes are just plain Kotlin

---

## Platform-specific test setup

### Android unit tests (`androidUnitTest`)

These run on the local JVM, not a device. Access to Android framework classes is limited unless you use Robolectric.

```
shared/src/
├── androidUnitTest/kotlin/   ← local JVM tests
└── androidInstrumentedTest/kotlin/  ← device/emulator tests
```

```kotlin
// androidUnitTest — JUnit 4 annotations work alongside kotlin.test
import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidSpecificTest {
    @Test
    fun usesJvmApis() {
        val runtime = System.getProperty("java.vm.name")
        assertNotNull(runtime)
    }
}
```

### iOS tests (`iosTest`)

iOS tests run on the Kotlin/Native test runner via the simulator. The test binary is compiled by the Kotlin/Native compiler and executed directly — no XCTest integration by default.

```
shared/src/
└── iosTest/kotlin/  ← runs on iOS simulator
```

```kotlin
// iosTest — can use platform.Foundation and other iOS APIs
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertTrue

class IosSpecificTest {
    @Test
    fun generatesUuid() {
        val uuid = NSUUID().UUIDString()
        assertTrue(uuid.length == 36)
    }
}
```

**Running iOS tests requires:**
- A Mac with Xcode installed
- `iosSimulatorArm64` target declared (or `iosX64` for Intel Macs)
- A simulator runtime installed

### Test source set hierarchy

Like main source sets, test source sets follow the hierarchy. With the default hierarchy template:

```
commonTest
├── appleTest        ← shared Apple tests (uses platform.Foundation)
│   └── iosTest      ← shared across all iOS targets
│       ├── iosArm64Test
│       └── iosSimulatorArm64Test
└── androidUnitTest
```

---

## Gradle test configuration

### Configuring test tasks

```kotlin
kotlin {
    // Filter tests
    targets.withType<KotlinNativeTarget> {
        testRuns["test"].executionTask.configure {
            // Pass arguments to the native test runner
            filter.includePatterns.add("com.example.critical.*")
        }
    }
}

// Android-specific test config
android {
    testOptions {
        unitTests.isReturnDefaultValues = true  // avoid "Method not mocked" errors
    }
}
```

### CI-friendly test execution

```bash
# Run all tests with Gradle build cache
./gradlew allTests --build-cache --parallel

# Run only iOS simulator tests
./gradlew iosSimulatorArm64Test

# Run only Android unit tests (debug variant)
./gradlew testDebugUnitTest

# Generate and view test reports
./gradlew allTests
open shared/build/reports/tests/allTests/index.html
```

### Test timeout configuration

```kotlin
// In a test class — for slow integration tests
@Test
fun slowTest() = runTest(timeout = 30.seconds) {
    // ...
}
```

---

## Key library versions (as of March 2026)

| Library | Artifact | Latest |
|---|---|---|
| kotlin.test | `kotlin("test")` | Matches Kotlin version |
| kotlinx-coroutines-test | `org.jetbrains.kotlinx:kotlinx-coroutines-test` | 1.10.2 |
| Turbine | `app.cash.turbine:turbine` | 1.2.1 |

All three are fully KMP-compatible and work in `commonTest`.
