# KGP Compiler Options

<overview>
## Compiler Options DSL

Configure compiler options at three levels (higher = default for lower):

1. **Extension level** — applies to all targets/source sets
2. **Target level** — applies to a specific target
3. **Compilation unit level** — applies to a specific task

Lower levels override higher levels.

### Extension Level (Recommended Default)

```kotlin
kotlin {
    compilerOptions {
        apiVersion.set(KotlinVersion.KOTLIN_2_3)
        languageVersion.set(KotlinVersion.KOTLIN_2_3)
        jvmTarget.set(JvmTarget.JVM_17)
        optIn.add("kotlin.RequiresOptIn")
        freeCompilerArgs.add("-Xjsr305=strict")
        progressiveMode.set(true)
    }
}
```

### Target Level (KMP)

```kotlin
kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
}
```

### Task Level (Override Specific Task)

```kotlin
tasks.named<KotlinJvmCompile>("compileKotlin") {
    compilerOptions {
        apiVersion.set(KotlinVersion.KOTLIN_2_3)
    }
}
```
</overview>

<common_options>
## Common Compiler Options (All Targets)

| Option | Type | Description |
|--------|------|-------------|
| `apiVersion` | `KotlinVersion` | Restrict API to specified Kotlin version |
| `languageVersion` | `KotlinVersion` | Kotlin language version for source compat |
| `progressiveMode` | `Boolean` | Enable progressive mode (stricter checks) |
| `optIn` | `ListProperty<String>` | Opt-in to experimental annotations |
| `freeCompilerArgs` | `ListProperty<String>` | Additional compiler arguments |
| `allWarningsAsErrors` | `Boolean` | Treat all warnings as errors |
| `suppressWarnings` | `Boolean` | Suppress all warnings |
| `verbose` | `Boolean` | Enable verbose compiler output |
</common_options>

<jvm_options>
## JVM-Specific Options

| Option | Type | Description |
|--------|------|-------------|
| `jvmTarget` | `JvmTarget` | JVM bytecode target (e.g., `JVM_17`) |
| `javaParameters` | `Boolean` | Generate metadata for Java reflection on params |
| `noJdk` | `Boolean` | Don't include JDK in classpath |

```kotlin
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        javaParameters.set(true)         // Useful for Spring/frameworks
        freeCompilerArgs.addAll(
            "-Xjsr305=strict",           // Strict null-safety for JSR-305 annotations
            "-Xemit-jvm-type-annotations" // Emit type-use annotations in bytecode
        )
    }
}
```
</jvm_options>

<migration>
## Migration from `kotlinOptions` to `compilerOptions`

`kotlinOptions {}` is deprecated since Kotlin 2.0.0 and removed in 2.2.0. Migrate to `compilerOptions {}`:

**Before (deprecated):**
```kotlin
tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions {
        jvmTarget = "17"
        languageVersion = "2.3"
        freeCompilerArgs += "-Xjsr305=strict"
    }
}
```

**After:**
```kotlin
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        languageVersion.set(KotlinVersion.KOTLIN_2_3)
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}
```

Key changes:
- Use typed values (`JvmTarget.JVM_17`) instead of strings (`"17"`)
- Use `add()` / `addAll()` instead of `+=` for `freeCompilerArgs`
- Use `-progressive` → `progressiveMode.set(true)`
- Prefer extension-level config over task-level
- For Android: move from `android { kotlinOptions {} }` to `kotlin { compilerOptions {} }`
</migration>
