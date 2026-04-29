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
