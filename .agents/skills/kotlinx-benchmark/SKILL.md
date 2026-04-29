---
name: kotlinx-benchmark
description: Complete reference for kotlinx-benchmark (v0.4.16) — Kotlin multiplatform benchmarking toolkit for JVM, JS, Native, and WasmJs targets. Built on JMH for JVM, with a compatible annotation-based API across all platforms. Covers Gradle plugin setup (org.jetbrains.kotlinx.benchmark, apply to project, register targets, allopen plugin required for JVM), runtime dependency (kotlinx-benchmark-runtime in commonMain), target configuration (JVM with allopen for @State, JS with nodejs(), Native with host-only execution, WasmJs experimental with nodejs()), writing benchmarks (@State(Scope.Benchmark) on class, @Benchmark on methods, @Setup/@TearDown for lifecycle, @Param for parameterized inputs with string values coerced to property type, @BenchmarkMode for Throughput/AverageTime, @OutputTimeUnit for result display units, @Warmup/@Measurement for iteration control, Blackhole for preventing dead-code elimination), configuration profiles (benchmark block with configurations section, named "main" default plus custom profiles like "smoke", include/exclude regex patterns, iterations/warmups/iterationTime/iterationTimeUnit/outputTimeUnit/mode/reportFormat/param options, build script values override annotations), Gradle tasks (benchmark runs main profile on all targets, <target>Benchmark for specific target, <config>Benchmark for custom profile, <target><config>Benchmark for target+profile, <target>BenchmarkJar for self-contained JMH JAR on JVM), separate source set for benchmarks, result analysis with Kotlin Notebooks, and report formats (json default JMH-compatible, csv, scsv, text). Use when setting up benchmarks in a Kotlin project, writing benchmark classes, configuring benchmark profiles, understanding the task naming convention, choosing between throughput and average time mode, parameterizing benchmarks, preventing compiler optimizations with Blackhole, running benchmarks on specific targets, or any kotlinx-benchmark question.
---

<essential_principles>

**kotlinx-benchmark** — Kotlin multiplatform benchmarking toolkit (v0.4.16). JMH-based on JVM, compatible annotation API on JS/Native/WasmJs. Apache 2.0.

Requires Kotlin ≥ 2.2.0 and Gradle ≥ 8.0. WasmJs is experimental.

### Quick Setup (Multiplatform)

```kotlin
// build.gradle.kts
plugins {
    id("org.jetbrains.kotlinx.benchmark") version "0.4.16"
}

kotlin {
    jvm()
    sourceSets {
        commonMain {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-benchmark-runtime:0.4.16")
            }
        }
    }
}

benchmark {
    targets {
        register("jvm")
    }
}
```

**JVM requires allopen** — JMH needs benchmark classes and methods to be `open`. Kotlin classes are `final` by default:

```kotlin
plugins {
    kotlin("plugin.allopen") version "2.2.0"
}

allOpen {
    annotation("org.openjdk.jmh.annotations.State")
}
```

### Writing a Benchmark

```kotlin
import kotlinx.benchmark.*

@State(Scope.Benchmark)
class MyBenchmark {
    private val list = ArrayList<Int>()

    @Param("10", "100", "1000")
    var size: Int = 0

    @Setup
    fun prepare() { for (i in 0..<size) list.add(i) }

    @TearDown
    fun cleanup() { list.clear() }

    @Benchmark
    fun sumList(): Int = list.sum()
}
```

### Core Annotations

| Annotation | Purpose | Scope |
|-----------|---------|-------|
| `@State(Scope.Benchmark)` | **Required** on benchmark class. Non-JVM only supports `Scope.Benchmark`. | Class |
| `@Benchmark` | Marks method to measure. Must be `public`, zero args or one `Blackhole` arg. | Method |
| `@Setup` | Runs before iterations (not timed). `public`, no args. | Method |
| `@TearDown` | Runs after iterations (not timed). `public`, no args. | Method |
| `@Param("v1", "v2")` | Parameterize benchmark. Property must be `var`, public, primitive or String. | Property |
| `@BenchmarkMode(Mode.Throughput)` | Throughput (ops/time) or AverageTime (time/op). Default: Throughput. | Class |
| `@OutputTimeUnit(BenchmarkTimeUnit.MILLISECONDS)` | Display unit for results. Default: seconds. | Class |
| `@Warmup(iterations=10, time=500, timeUnit=...)` | Warmup phase config. Not included in results. | Class |
| `@Measurement(iterations=20, time=1, timeUnit=...)` | Measurement phase config. | Class |

**JVM-only extras:** `@Setup(Level.Iteration)`, `@TearDown(Level.Iteration)`, `Scope.Thread`, `Mode.SingleShotTime`, annotations on individual methods. Non-JVM: annotations on class only.

### Blackhole

Prevents dead-code elimination. Use when benchmark produces **multiple values**. Single return values are consumed implicitly.

```kotlin
@Benchmark
fun iterateBenchmark(bh: Blackhole) {
    for (e in myList) bh.consume(e)
}
```

### Running Benchmarks

```bash
./gradlew benchmark          # all targets, "main" profile
./gradlew jvmBenchmark       # JVM only, "main" profile
./gradlew smokeBenchmark     # all targets, "smoke" profile
./gradlew jvmSmokeBenchmark  # JVM, "smoke" profile
./gradlew jvmBenchmarkJar    # self-contained JMH JAR (JVM only)
```

The JAR (in `build/benchmarks/jvm/jars/`) can be run with `java -jar` and supports JMH profilers.

### Configuration Profiles

```kotlin
benchmark {
    configurations {
        named("main") {
            warmups = 20
            iterations = 10
            iterationTime = 3
            iterationTimeUnit = "s"
        }
        register("smoke") {
            include("<regex pattern>")
            warmups = 5
            iterations = 3
            iterationTime = 500
            iterationTimeUnit = "ms"
        }
    }
}
```

**Build script values override annotation values.** `include`/`exclude` use regex against fully qualified benchmark names.

### Report Formats

`reportFormat` option: `"json"` (default, JMH-compatible), `"csv"`, `"scsv"`, `"text"`. Results saved to file after each run. Kotlin Notebooks can analyze JSON results.

</essential_principles>

<routing>

| Topic | Reference |
|-------|-----------|
| Full configuration options table (all DSL options with types, defaults, corresponding annotations), time unit values, target-specific setup (JVM allopen, JS nodejs, Native host-only, WasmJs experimental), separate benchmark source set, task naming rules, JVM-only features (Level, Scope, SingleShotTime, BenchmarkJar) | `references/configuration-and-targets.md` |

</routing>

<reference_index>

**configuration-and-targets.md** — Full configuration DSL options table (iterations, warmups, iterationTime, iterationTimeUnit, outputTimeUnit, mode with "thrpt"/"avgt", include/exclude regex, param("name", values), reportFormat json/csv/scsv/text — build script values override annotations). Time unit string values (NANOSECONDS/ns, MICROSECONDS/us, MILLISECONDS/ms, SECONDS/s, MINUTES/m). Target setup: JVM (register "jvm", allopen plugin required with @State annotation, JMH under the hood, supports Level.Iteration/Trial/Invocation for @Setup/@TearDown, Scope.Benchmark/Thread/Group, Mode.SingleShotTime, annotations on individual @Benchmark methods), JS (register "js", requires nodejs()), Native (register target name e.g. "linuxX64", host-only execution, supports all Kotlin/Native targets), WasmJs (register "wasmJs", requires nodejs(), experimental — supported only for the Kotlin version used to build the library). Separate benchmark source set (create custom compilation, register as benchmark target). Task naming pattern (<target><Config>Benchmark, with "main" config having no prefix), JVM-only BenchmarkJar task for self-contained executable JAR with JMH profiler support. Kotlin Notebooks for result analysis (single run, comparing runs, comparing hypotheses).

</reference_index>
