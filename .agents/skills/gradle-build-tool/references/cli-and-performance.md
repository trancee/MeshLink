# Gradle CLI & Performance

<cli>
## Command-Line Interface

### Structure

```bash
./gradlew [taskName...] [--option-name...]
```

### Essential Commands

```bash
# Build
./gradlew build                        # Assemble + check (compile, test, jar)
./gradlew clean build                  # Clean first, then build
./gradlew assemble                     # Build outputs without tests
./gradlew check                        # Run all checks (tests, lint)

# Run
./gradlew run                          # Run application (needs application plugin)

# Test
./gradlew test                         # Run all tests
./gradlew test --tests "*.MyTest"      # Run specific test class
./gradlew test --tests "*.MyTest.myMethod"  # Run specific test method

# Multi-project
./gradlew :app:build                   # Build specific subproject
./gradlew :app:dependencies            # Dependencies of app subproject

# Info
./gradlew tasks                        # List available tasks
./gradlew tasks --all                  # All tasks including dependencies
./gradlew projects                     # List all projects
./gradlew properties                   # List project properties
./gradlew dependencies                 # Show dependency tree
./gradlew help --task test             # Help on a specific task
./gradlew dependencyInsight --dependency guava  # Where does guava come from?

# Wrapper management
./gradlew wrapper --gradle-version 9.6.1   # Upgrade wrapper
./gradlew wrapper --gradle-version 9       # Latest 9.x.y (Gradle 9+ wrapper accepts partial versions)
```

### Useful Flags

| Flag | Purpose |
|------|---------|
| `--info` / `--debug` | Increase log verbosity |
| `--stacktrace` / `--full-stacktrace` | Show exception stack traces |
| `--scan` | Generate a build scan (shareable diagnostics) |
| `--build-cache` / `--no-build-cache` | Toggle build cache |
| `--configuration-cache` / `--no-configuration-cache` | Toggle config cache |
| `--parallel` / `--no-parallel` | Toggle parallel execution |
| `--continue` | Continue after task failure |
| `--rerun-tasks` | Force all tasks to run (ignore up-to-date) |
| `--dry-run` / `-m` | Show which tasks would run without executing |
| `--task-graph` | Print the task dependency tree without executing anything (stable since 9.4) |
| `--console=plain` / `=colored` / `=rich` / `=auto` | Console output mode — `colored` (9.1+) adds color highlighting without rich-console progress bars, useful in CI |
| `-x <task>` | Exclude a task (e.g., `-x test`) |
| `-P<key>=<value>` | Set project property |
| `-D<key>=<value>` | Set JVM system property |

### Task Name Abbreviation

Gradle supports camelCase abbreviation:

```bash
./gradlew cJ      # → compileJava
./gradlew mCJD    # → myCustomJavaDoc
```
</cli>

<performance>
## Performance Optimization

### Build Cache

Reuses outputs from previous builds (local or remote):

```properties
# gradle.properties
org.gradle.caching=true
```

```bash
./gradlew build --build-cache
```

### Configuration Cache

Caches the result of the configuration phase. **Since Gradle 9.0, this is the preferred execution mode** — if it's off and your build has no known incompatibilities, Gradle prompts you at the end of the build to enable it. When a build hits an unsupported feature (e.g. some core publish plugins, certain IDE plugins), Gradle now falls back gracefully to non-cached mode instead of failing; check the configuration cache report for the fallback reason.

```properties
# gradle.properties
org.gradle.configuration-cache=true
```

Suppress the enable-prompt without enabling it:
```properties
org.gradle.configuration-cache=false
```

### Parallel Execution

Runs independent subproject tasks in parallel:

```properties
# gradle.properties
org.gradle.parallel=true
```

### Incremental Builds

Gradle automatically skips tasks whose inputs/outputs haven't changed. Tasks show `UP-TO-DATE` when skipped.

### Gradle Daemon

Long-lived background process that avoids JVM startup cost. Enabled by default since Gradle 3.0.

```bash
./gradlew --status            # Show running daemons
./gradlew --stop              # Stop all daemons
```

### Recommended `gradle.properties` for Performance

```properties
org.gradle.jvmargs=-Xmx2g -XX:+HeapDumpOnOutOfMemoryError -Dfile.encoding=UTF-8
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.configuration-cache=true
```
</performance>

<troubleshooting>
## Troubleshooting

```bash
# Debug build issues
./gradlew build --info                  # Detailed logging
./gradlew build --stacktrace            # Show stack traces
./gradlew build --scan                  # Generate build scan URL

# Dependency conflicts
./gradlew dependencyInsight --dependency <name> --configuration runtimeClasspath

# Refresh dependencies (bypass cache)
./gradlew build --refresh-dependencies

# Clean everything
./gradlew clean
rm -rf ~/.gradle/caches/                # Nuclear option — clears all caches

# Daemon issues
./gradlew --stop                        # Stop all daemons
./gradlew build --no-daemon             # Run without daemon
```
</troubleshooting>
