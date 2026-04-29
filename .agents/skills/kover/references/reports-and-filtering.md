# Kover Reports, Filtering & Instrumentation

<report_config>
## Configuring Reports

Report configuration lives inside `kover { reports { ... } }`. Configure per total or per named variant.

```kotlin
kover {
    reports {
        total {
            html {
                title = "My Project Coverage"
                onCheck = false          // don't run on `check` task
                charset = "UTF-8"
                htmlDir = layout.buildDirectory.dir("reports/kover-html")
            }
            xml {
                title = "XML Coverage"
                onCheck = false
                xmlFile = layout.buildDirectory.file("reports/kover.xml")
            }
            log {
                onCheck = false
                header = "Coverage"
                groupBy = GroupingEntityType.APPLICATION
                aggregationForGroup = AggregationType.COVERED_PERCENTAGE
                coverageUnits = CoverageUnit.LINE
                format = "<entity> line coverage: <value>%"
            }
            binary {
                onCheck = false
                file = layout.buildDirectory.file("reports/kover.ic")
            }
        }
    }
}
```

For named variants, replace `total` with `variant("debug")`.

### HTML Report Colors
- **Green** — line covered (executed at least once)
- **Red** — line missed (never executed)
- **Yellow** — partially covered (branch not fully explored)
- Filtered-out code has no highlight

### Binary Reports
Binary reports (IC format) are intermediate files for use with Kover CLI or Kover Features. Useful when measurement and report generation happen on different machines.

### Additional Binary Reports
Import external coverage data:
```kotlin
kover {
    reports {
        total {
            additionalBinaryReports.add(file("path/to/external.ic"))
        }
    }
}
```
</report_config>

<filtering>
## Filtering Reports

Filters control which classes appear in reports and verification. Excludes take priority over includes.

### Common filters (all report variants)
```kotlin
kover {
    reports {
        filters {
            excludes {
                classes("com.example.generated.*")
                annotatedBy("Generated", "Deprecated")
                inheritedFrom("com.example.BaseGenerated")
            }
            includes {
                classes("com.example.myapp.*")
            }
        }
    }
}
```

### Per-variant filters (override common)
```kotlin
kover {
    reports {
        total {
            filters {
                excludes { classes("com.example.ExcludedOnly*") }
            }
        }
        variant("debug") {
            filters {
                // completely replaces common filters for debug variant
                excludes { classes("com.example.DebugHelper") }
            }
        }
    }
}
```

### Appending vs overriding filters
- `filters { }` — clears and replaces
- `filtersAppend { }` — adds to existing filters

### Class name wildcards
| Pattern | Meaning |
|---------|---------|
| `*` or `**` | Zero or more of any character |
| `?` | Exactly one of any character |

Must be fully-qualified class names. File paths are NOT valid.

✅ `com.example.MyClass`  
✅ `com.example.*Service`  
✅ `com.*.internal.?Impl`  
❌ `src/main/kotlin/MyClass.kt`

### Filter types
| Filter | Description | JaCoCo support |
|--------|-------------|----------------|
| `classes(...)` | By fully-qualified class name (wildcards OK) | ✅ |
| `annotatedBy(...)` | By annotation (BINARY or RUNTIME retention) | ❌ Kover only |
| `inheritedFrom(...)` | By superclass/interface | ❌ Kover only |
</filtering>

<source_sets>
## Source Set Exclusion

Exclude or include specific JVM source sets. `test` source set is excluded by default.

```kotlin
kover {
    currentProject {
        sources {
            // Exclude specific source sets
            excludedSourceSets.addAll("test1", "extra")

            // OR: include only specified (everything else excluded)
            includedSourceSets.addAll("main")
        }
    }
}
```
Excludes have priority when both are used.
</source_sets>

<instrumentation>
## Instrumentation

Kover uses on-the-fly JVM agent instrumentation — classes are modified as they load during tests.

### Excluding classes from instrumentation
If instrumentation breaks a class (e.g., `VerifyError`, `No instrumentation registered!`):
```kotlin
kover {
    currentProject {
        instrumentation {
            excludedClasses.add("com.example.Problematic*")
        }
    }
}
```
Side effect: excluded classes always show 0% coverage.

### Excluding test tasks
```kotlin
kover {
    currentProject {
        instrumentation {
            // Exclude specific test tasks
            disabledForTestTasks.add("nightlyLoadTest")

            // Disable ALL instrumentation in this project
            disableForAll = true
        }
    }
}
```
Excluded tests are not triggered by Kover report tasks and their coverage is not collected.

### Common instrumentation errors
```
No instrumentation registered! Must run under a registering instrumentation.
java.lang.VerifyError at sun.instrument.InstrumentationImpl.retransformClasses0
```
Fix: exclude the problematic class from instrumentation.
</instrumentation>

<jacoco>
## Using JaCoCo

Use JaCoCo instead of the built-in Kover coverage engine:
```kotlin
kover {
    useJacoco()          // default JaCoCo version
    useJacoco("0.8.14")  // specific version
}
```

Limitations with JaCoCo:
- `annotatedBy` and `inheritedFrom` filters don't work
- Rule names are not printed on violation
- Must use the same coverage library across all `kover`-dependent modules
</jacoco>
