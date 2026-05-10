# Gradle Build Lifecycle & Tasks

<lifecycle>
## Build Lifecycle — Three Phases

| Phase | What happens |
|-------|-------------|
| **1. Initialization** | Detects `settings.gradle.kts`, creates `Settings` instance, evaluates it to find all projects, creates a `Project` instance for each. |
| **2. Configuration** | Evaluates build scripts of all projects. Configures tasks (inputs/outputs). Builds the task graph (DAG). |
| **3. Execution** | Schedules and executes the selected tasks in dependency order. |

```kotlin
// settings.gradle.kts
rootProject.name = "demo"
println("INIT: settings evaluated")  // Phase 1

// build.gradle.kts
println("CONFIG: build script evaluated")  // Phase 2

tasks.register("hello") {
    println("CONFIG: task configured")     // Phase 2 (lazy — only if task is needed)
    doLast {
        println("EXEC: task executed")     // Phase 3
    }
}
```

**Key insight:** Code in `doFirst {}` / `doLast {}` runs during execution. Code directly in the task configuration block runs during configuration.
</lifecycle>

<tasks>
## Tasks

Tasks are the building blocks of every Gradle build.

### Running Tasks

```bash
./gradlew build                    # Run build task
./gradlew clean build              # Run clean, then build
./gradlew :app:test                # Run test in app subproject
./gradlew tasks                    # List available tasks
./gradlew tasks --all              # List all tasks including dependencies
./gradlew help --task test         # Show details about a task
```

### Common Built-in Tasks (Java/Kotlin projects)

| Task | Purpose |
|------|---------|
| `compileJava` / `compileKotlin` | Compile source code |
| `test` | Run unit tests |
| `jar` | Create JAR archive |
| `assemble` | Build all outputs (JARs, etc.) |
| `check` | Run all checks (tests, lint) |
| `build` | `assemble` + `check` |
| `clean` | Delete the `build/` directory |
| `run` | Run the application (requires `application` plugin) |
| `dependencies` | Show dependency tree |
| `projects` | List all projects |
| `properties` | Show all project properties |

### Task Dependencies

Tasks automatically run their dependencies:

```bash
$ ./gradlew build
> Task :compileJava
> Task :processResources
> Task :classes
> Task :jar
> Task :assemble
> Task :compileTestJava
> Task :test
> Task :check
> Task :build
```

### Registering Custom Tasks

```kotlin
// Lazy registration (preferred — configured only when needed)
tasks.register("greet") {
    group = "custom"
    description = "Prints a greeting"
    doLast {
        println("Hello from Gradle!")
    }
}

// With typed task
tasks.register<Copy>("copyDocs") {
    from("src/docs")
    into(layout.buildDirectory.dir("docs"))
}
```

### Task Inputs/Outputs (Incremental Builds)

Gradle skips tasks when inputs/outputs haven't changed:

```kotlin
abstract class GenerateReport : DefaultTask() {
    @get:InputFile
    abstract val inputData: RegularFileProperty

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val data = inputData.get().asFile.readText()
        reportFile.get().asFile.writeText("Report: $data")
    }
}

tasks.register<GenerateReport>("report") {
    inputData.set(file("data.json"))
    reportFile.set(layout.buildDirectory.file("report.txt"))
}
```

### Up-to-date Checking

```bash
$ ./gradlew build
> Task :compileJava UP-TO-DATE       # Skipped — nothing changed
> Task :test UP-TO-DATE
BUILD SUCCESSFUL in 500ms
0 actionable tasks: 0 executed       # Everything cached
```
</tasks>
