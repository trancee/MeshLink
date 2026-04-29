# KGP Compilation, Caching & Performance

<incremental>
## Incremental Compilation

Enabled by default for Kotlin/JVM and Kotlin/JS. Tracks changes between builds so only affected files are recompiled.

### How It Works (JVM)

- Uses **classpath snapshots** to detect API changes
- **Fine-grained snapshots** for project modules — recompiles only classes depending on changed members
- **Coarse-grained snapshots** for `.jar` files — recompiles all dependent classes on ABI change
- Works with Gradle's build cache and compilation avoidance

### Disabling (Troubleshooting Only)

```properties
# gradle.properties
kotlin.incremental=false       # Disable for Kotlin/JVM
kotlin.incremental.js=false    # Disable for Kotlin/JS
```

Or via command line: `-Pkotlin.incremental=false`
</incremental>

<build_cache>
## Gradle Build Cache

Stores task outputs for reuse. Enable in `gradle.properties`:

```properties
org.gradle.caching=true
```

Disable caching for Kotlin tasks only:
```bash
./gradlew build -Dkotlin.caching.enabled=false
```

### Remote Build Cache

Share outputs across machines (CI → dev):

```kotlin
// settings.gradle.kts
buildCache {
    local { isEnabled = true }
    remote<HttpBuildCache> {
        url = uri("https://cache.example.com/")
        isPush = System.getenv("CI") != null  // Only CI pushes
    }
}
```
</build_cache>

<config_cache>
## Configuration Cache

Caches the configuration phase result. Significant speedup for repeat builds:

```properties
# gradle.properties
org.gradle.configuration-cache=true
```

Also enables parallel task execution implicitly.
</config_cache>

<kotlin_daemon>
## Kotlin Daemon

Separate from Gradle daemon. Compiles Kotlin sources. Starts during execution phase, stops after 2 idle hours.

### JVM Arguments (Precedence, Low → High)

1. Inherited from Gradle daemon (`org.gradle.jvmargs`)
2. `kotlin.daemon.jvm.options` system property in `org.gradle.jvmargs`
3. `kotlin.daemon.jvmargs` property in `gradle.properties`
4. `kotlin {}` extension: `kotlinDaemonJvmArgs`
5. Per-task: `kotlinDaemonJvmArguments`

```properties
# gradle.properties — typical daemon config
kotlin.daemon.jvmargs=-Xmx1500m -Xms512m
```

### Troubleshooting

Multiple daemon instances may spawn if subprojects use different JVM args. Keep Kotlin daemon JVM args consistent across the build.
</kotlin_daemon>

<best_practices>
## Best Practices

### Organization
- **Use Kotlin DSL** (`build.gradle.kts`) — strict typing, better IDE support
- **Use version catalog** (`gradle/libs.versions.toml`) — centralized dependency management
- **Use convention plugins** — shared build logic in `buildSrc/` or `build-logic/`
- **Modularize** — split into subprojects for parallelism and incremental builds

### Performance
- **Enable build cache:** `org.gradle.caching=true`
- **Enable configuration cache:** `org.gradle.configuration-cache=true`
- **Enable parallel execution:** `org.gradle.parallel=true`
- **Migrate kapt → KSP** — faster annotation processing, no Java stubs
- **Use toolchains** — consistent JDK across environments, enables remote cache sharing
- **For KMP multi-target:** Run specific `linkDebug*` tasks during development, not `build`

### Recommended `gradle.properties`

```properties
org.gradle.jvmargs=-Xmx2g -XX:+HeapDumpOnOutOfMemoryError -Dfile.encoding=UTF-8
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.configuration-cache=true

# Kotlin-specific
kotlin.code.style=official
```

### Don't

- Don't add `kotlin-stdlib` manually (KGP adds it automatically since 1.4)
- Don't use `kotlinOptions {}` (deprecated since 2.0.0, removed in 2.2.0)
- Don't use kapt if KSP is available for your processor
- Don't store the `.kotlin` directory in VCS (add to `.gitignore`)
</best_practices>

<build_reports>
## Build Reports

Track compilation statistics and incremental compilation history:

```properties
# gradle.properties
kotlin.build.report.output=file        # Output to file
kotlin.build.report.file.output_dir=build/reports/kotlin
```

Options: `file`, `build_scan`, `http`. Useful for diagnosing incremental compilation issues.
</build_reports>
