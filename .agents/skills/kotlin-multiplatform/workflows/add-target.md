# Workflow: Add or Configure a Target

<required_reading>
**Read these reference files NOW:**
1. references/project-structure.md
2. references/gradle-config.md
</required_reading>

<process>
## Step 1: Declare the Target

Add the target DSL call inside `kotlin {}`:

```kotlin
kotlin {
    // Existing targets
    androidTarget()
    iosArm64()
    iosSimulatorArm64()

    // New target
    jvm()  // or macosArm64(), wasmJs { browser() }, etc.
}
```

This automatically creates `jvmMain`, `jvmTest`, and corresponding intermediate source sets.

## Step 2: Create Source Directories

Create the source set directories:

```
src/jvmMain/kotlin/com/example/module/
src/jvmTest/kotlin/com/example/module/
```

## Step 3: Provide Missing Actual Declarations

If the project has `expect` declarations in `commonMain`, the new target's source set needs matching `actual` declarations.

**Check what's missing:**
```bash
./gradlew :module:compileKotlinJvm  # replace Jvm with your target name
```

The compiler lists every unresolved `expect` declaration.

**For intermediate source sets:** If you add `macosArm64()` and `appleMain` already has the `actual` declarations, they'll be inherited — no new code needed.

## Step 4: Add Platform-Specific Dependencies

```kotlin
kotlin {
    sourceSets {
        jvmMain.dependencies {
            // JVM-specific libraries
        }
    }
}
```

## Step 5: Configure Target-Specific Options

**JVM:**
```kotlin
jvm {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
```

**iOS Framework:**
```kotlin
listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
    target.binaries.framework {
        baseName = "SharedModule"
        isStatic = true
    }
}
```

**JS/WasmJs:**
```kotlin
js(IR) {
    browser {
        testTask { useKarma { useChromeHeadless() } }
    }
}
```

## Step 6: Verify

```bash
# Full build across all targets
./gradlew assemble

# Run tests for the new target
./gradlew :module:jvmTest  # replace with your target
```
</process>

<success_criteria>
- [ ] Target declared in `kotlin {}` block
- [ ] Source directories created
- [ ] All `expect` declarations have matching `actual` implementations
- [ ] Target-specific dependencies added
- [ ] `./gradlew assemble` passes
- [ ] Tests pass on the new target
</success_criteria>
