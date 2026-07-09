# Compose Multiplatform — Testing

<setup>
## Setup

### Add dependencies
```kotlin
kotlin {
    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
        }
        // Desktop tests need the OS runtime
        val jvmTest by getting
        jvmTest.dependencies {
            implementation(compose.desktop.currentOs)
        }
    }
}
```

### Android instrumented tests (optional)
```kotlin
kotlin {
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        instrumentedTestVariant.sourceSetTree.set(KotlinSourceSetTree.test)
    }
}

android {
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    androidTestImplementation("androidx.compose.ui:ui-test-junit4-android:1.10.5")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.10.5")
}
```

Create test directory: `composeApp/src/commonTest/kotlin/`
</setup>

<writing_tests>
## Writing Tests

**Key difference from Jetpack Compose:** No `TestRule`. Use `runComposeUiTest {}` function.

```kotlin
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test

class ExampleTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun myTest() = runComposeUiTest {
        setContent {
            var text by remember { mutableStateOf("Hello") }
            Text(text = text, modifier = Modifier.testTag("text"))
            Button(
                onClick = { text = "Compose" },
                modifier = Modifier.testTag("button")
            ) { Text("Click me") }
        }

        onNodeWithTag("text").assertTextEquals("Hello")
        onNodeWithTag("button").performClick()
        onNodeWithTag("text").assertTextEquals("Compose")
    }
}
```

### API (same as Jetpack Compose testing)
- **Finders:** `onNodeWithTag()`, `onNodeWithText()`, `onNodeWithContentDescription()`
- **Assertions:** `assertTextEquals()`, `assertIsDisplayed()`, `assertExists()`
- **Actions:** `performClick()`, `performTextInput()`, `performScrollTo()`
</writing_tests>

<behavior_changes>
## Idle and Timing Behavior Changes (1.8.2+)

These changed the meaning of "idle" and the test clock — code written against older behavior can hang or silently pass/fail differently now:

- **`delay()` inside `LaunchedEffect` no longer blocks idle detection.** `waitForIdle()`, `awaitIdle()`, and `runOnIdle()` now treat Compose as idle even while a composition-scoped coroutine is suspended in `delay()`. A `while (true) { delay(1000) }` effect used to hang these calls forever; now it doesn't — but tests that relied on `waitForIdle()` to let a delayed effect finish must advance the clock explicitly instead:
  ```kotlin
  updateText = true
  waitForIdle()                    // no longer waits out the delay
  mainClock.advanceTimeBy(1001)    // advance time to make the assertion correct
  assertEquals("1", text)
  ```
- **`runOnIdle()` now matches Android**: runs its action on the UI thread, and no longer calls `waitForIdle()` afterward — add an explicit `waitForIdle()` call after `runOnIdle()` if your test depended on that implicit wait.
- **`mainClock.advanceTimeBy()` is decoupled from rendering**: it no longer forces recomposition/layout/draw unless the advanced time crosses a virtual frame boundary (frames render every 16ms). Tests that relied on every `advanceTimeBy()` call triggering a render may need adjusting.
- **`runComposeUiTest()` accepts a suspend lambda (1.9.0+)**, so you can call `awaitIdle()` and other suspend APIs directly inside it. Behavior differs slightly per target: JVM/native behave like `runBlocking()` but skip delays; web (Wasm/JS) returns a `Promise` and also skips delays.
</behavior_changes>

<running_tests>
## Running Tests

| Target | Command |
|--------|---------|
| iOS Simulator | `./gradlew :composeApp:iosSimulatorArm64Test` |
| Android Emulator | `./gradlew :composeApp:connectedAndroidTest` |
| Desktop (JVM) | `./gradlew :composeApp:jvmTest` |
| Wasm (headless) | `./gradlew :composeApp:wasmJsTest` |

IDE: Click green gutter icon next to test function, select target platform.

**Note:** Android local test configurations don't work for common Compose tests — use `connectedAndroidTest` for emulator tests.

JUnit-based API is available for desktop targets via `compose-desktop-ui-testing`.
</running_tests>
