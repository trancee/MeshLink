# kotlinx-benchmark — Configuration and Targets Reference

<configuration_options>
## Configuration DSL — Complete Options

All options go inside `benchmark { configurations { ... } }`. Build script values **override** annotation values.

| Option | Type | Default | Corresponding Annotation |
|--------|------|---------|-------------------------|
| `iterations` | Positive Int | Platform-dependent | `@Measurement(iterations = N)` |
| `warmups` | Non-negative Int | Platform-dependent | `@Warmup(iterations = N)` |
| `iterationTime` | Positive Int | Platform-dependent | `@Measurement(time = N)` |
| `iterationTimeUnit` | Time unit string | `"s"` | `@Measurement(timeUnit = ...)` |
| `outputTimeUnit` | Time unit string | `"s"` | `@OutputTimeUnit(...)` |
| `mode` | `"thrpt"` or `"avgt"` | `"thrpt"` | `@BenchmarkMode(...)` |
| `include("regex")` | Regex pattern | Include all | — |
| `exclude("regex")` | Regex pattern | Exclude none | — |
| `param("name", "v1", "v2")` | String values | Use `@Param` defaults | `@Param(...)` |
| `reportFormat` | `"json"`, `"csv"`, `"scsv"`, `"text"` | `"json"` | — |

### Time Unit Values

| Annotation Enum | DSL String | Alt String |
|----------------|-----------|------------|
| `BenchmarkTimeUnit.NANOSECONDS` | `"ns"` | `"NANOSECONDS"` |
| `BenchmarkTimeUnit.MICROSECONDS` | `"us"` | `"MICROSECONDS"` |
| `BenchmarkTimeUnit.MILLISECONDS` | `"ms"` | `"MILLISECONDS"` |
| `BenchmarkTimeUnit.SECONDS` | `"s"` | `"SECONDS"` |
| `BenchmarkTimeUnit.MINUTES` | `"m"` | `"MINUTES"` |

### Mode Values

| Annotation Enum | DSL String | What It Measures |
|----------------|-----------|------------------|
| `Mode.Throughput` | `"thrpt"` or `"Throughput"` | Operations per unit time |
| `Mode.AverageTime` | `"avgt"` or `"AverageTime"` | Time per operation |

### Include/Exclude

Regex matched against **fully qualified benchmark names** (package + class + method):

```kotlin
configurations {
    register("fast") {
        include(".*Fast.*")     // only benchmarks with "Fast" in FQN
        exclude(".*Slow.*")     // skip anything with "Slow"
    }
}
```

### Param Override

Override `@Param` values from build script — useful for smoke-test profiles:

```kotlin
configurations {
    register("smoke") {
        param("size", "10")           // only test with size=10
        param("algorithm", "quick")   // only test "quick" variant
    }
}
```

### Example: Full Configuration

```kotlin
benchmark {
    configurations {
        named("main") {
            warmups = 20
            iterations = 10
            iterationTime = 3
            iterationTimeUnit = "s"
            outputTimeUnit = "ms"
            mode = "avgt"
            reportFormat = "json"
        }
        register("smoke") {
            include(".*Essential.*")
            warmups = 5
            iterations = 3
            iterationTime = 500
            iterationTimeUnit = "ms"
            mode = "thrpt"
            reportFormat = "text"
        }
    }
}
```
</configuration_options>

<target_setup>
## Target-Specific Setup

### Kotlin/JVM

```kotlin
kotlin { jvm() }

plugins {
    kotlin("plugin.allopen") version "2.2.0"
}

allOpen {
    annotation("org.openjdk.jmh.annotations.State")
}

benchmark {
    targets { register("jvm") }
}
```

**Why allopen:** JMH generates subclasses of benchmark classes. Kotlin classes are `final` by default → JMH can't subclass them without the allopen plugin.

**JVM-only features:**
- `@State` is optional (JMH defaults apply)
- `Scope.Thread`, `Scope.Group` (in addition to `Scope.Benchmark`)
- `Mode.SingleShotTime` — measure single invocation
- `Level.Iteration`, `Level.Trial`, `Level.Invocation` for `@Setup`/`@TearDown`
- Annotations on individual `@Benchmark` methods (not just class)
- **BenchmarkJar** task produces self-contained executable JAR with JMH infrastructure

### Kotlin/JS

```kotlin
kotlin { js { nodejs() } }

benchmark {
    targets { register("js") }
}
```

Requires Node.js execution environment.

### Kotlin/Native

```kotlin
kotlin { linuxX64() }   // or macosArm64(), mingwX64(), etc.

benchmark {
    targets { register("linuxX64") }
}
```

**Host-only execution** — benchmarks can only run for the target matching the current host OS/architecture. You can register multiple native targets, but only the host target's benchmarks will actually execute. Supports all Kotlin/Native compiler targets.

### Kotlin/WasmJs (Experimental)

```kotlin
kotlin { wasmJs { nodejs() } }

benchmark {
    targets { register("wasmJs") }
}
```

Experimental — guarantees support only for the specific Kotlin version used to build the library (currently 2.2.0). May change at any time.
</target_setup>

<task_naming>
## Gradle Task Naming

Tasks are generated for each combination of **configuration profile × registered target**.

### "main" Configuration (Default)

| Task | Description |
|------|-------------|
| `benchmark` | All targets, "main" profile |
| `<target>Benchmark` | Specific target, "main" profile |

### Custom Configuration Profiles

| Task | Description |
|------|-------------|
| `<config>Benchmark` | All targets, custom profile |
| `<target><Config>Benchmark` | Specific target, custom profile |

### Example

With targets `jvm` and `js`, and profiles `main` and `smoke`:

| Task | Target | Profile |
|------|--------|---------|
| `benchmark` | jvm + js | main |
| `jvmBenchmark` | jvm | main |
| `jsBenchmark` | js | main |
| `smokeBenchmark` | jvm + js | smoke |
| `jvmSmokeBenchmark` | jvm | smoke |
| `jsSmokeBenchmark` | js | smoke |

### JVM-Only Tasks

| Task | Description |
|------|-------------|
| `<target>BenchmarkJar` | Produces self-contained JMH JAR in `build/benchmarks/<target>/jars/` |

Run with `java -jar <path>.jar`. Use `-h` for JMH options. Supports JMH profilers (e.g. `-prof gc`, `-prof async`).
</task_naming>

<separate_source_set>
## Separate Source Set for Benchmarks

To keep benchmarks separate from production code (like tests), create a custom compilation. See the [detailed guide](https://github.com/Kotlin/kotlinx-benchmark/blob/master/docs/separate-benchmark-source-set.md) for full setup.

The key steps:
1. Create a custom compilation (e.g. `benchmarks`) in each target
2. Associate the compilation with `main` (to access production code)
3. Add `kotlinx-benchmark-runtime` dependency to the benchmark compilation
4. Register the compilation name as a benchmark target

### KMP Source Sets

Benchmarks in `commonMain` run on **all** registered targets. Benchmarks in platform source sets (e.g. `jvmMain`) run only on that platform.
</separate_source_set>

<result_analysis>
## Analyzing Results

Results are saved as files after each benchmark run. Default format is JMH-compatible JSON.

### Kotlin Notebooks

Use [Kotlin Notebooks](https://kotlinlang.org/docs/kotlin-notebook-overview.html) for analysis:
- **Single run analysis** — visualize results from one benchmark execution
- **Comparing runs** — diff two or more runs (e.g. before/after optimization)
- **Comparing hypotheses** — compare different benchmark functions from the same run

Example notebooks are available in the [examples directory](https://github.com/Kotlin/kotlinx-benchmark/tree/master/examples).

### Report Formats

| Format | Description |
|--------|-------------|
| `"json"` | JMH-compatible JSON (default). Best for programmatic analysis. |
| `"csv"` | Comma-separated values |
| `"scsv"` | Semicolon-separated values |
| `"text"` | Human-readable text |
</result_analysis>

<jvm_only_features>
## JVM-Only Features (JMH)

On JVM, kotlinx-benchmark delegates to Java Microbenchmark Harness (JMH). This enables features not available on other platforms:

### State Scopes

| Scope | Meaning |
|-------|---------|
| `Scope.Benchmark` | Shared across all threads (default, only option on non-JVM) |
| `Scope.Thread` | One instance per thread |
| `Scope.Group` | Shared within thread group |

### Setup/TearDown Levels

| Level | When |
|-------|------|
| `Level.Trial` | Once before/after all iterations (default on non-JVM) |
| `Level.Iteration` | Before/after each iteration |
| `Level.Invocation` | Before/after each benchmark method call (use with caution — can skew results) |

### Benchmark Modes

| Mode | Non-JVM | JVM |
|------|---------|-----|
| `Throughput` | ✓ | ✓ |
| `AverageTime` | ✓ | ✓ |
| `SingleShotTime` | ✗ | ✓ |
| `SampleTime` | ✗ | ✓ |
| `All` | ✗ | ✓ |

### BenchmarkJar

The `<target>BenchmarkJar` task produces a self-contained executable JAR:

```bash
./gradlew jvmBenchmarkJar
java -jar build/benchmarks/jvm/jars/main.jar -h     # show JMH help
java -jar build/benchmarks/jvm/jars/main.jar -prof gc  # with GC profiler
```

### Additional JMH Annotations

On JVM, use any JMH annotation directly: `@Fork`, `@Threads`, `@GroupThreads`, `@CompilerControl`, `@OperationsPerInvocation`, etc. See [JMH annotations Javadoc](https://javadoc.io/doc/org.openjdk.jmh/jmh-core/latest/org/openjdk/jmh/annotations/package-summary.html).
</jvm_only_features>
