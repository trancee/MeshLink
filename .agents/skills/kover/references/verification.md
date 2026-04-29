# Kover Verification Rules

<verification_concepts>
## Coverage Concepts

### Coverage Units (`CoverageUnit`)
| Unit | Description |
|------|-------------|
| `LINE` | Source code lines (default) |
| `INSTRUCTION` | JVM bytecode instructions |
| `BRANCH` | Code branches (if/else, when, try/catch) |

### Aggregation (`AggregationType`)
| Type | Description |
|------|-------------|
| `COVERED_PERCENTAGE` | (covered / total) × 100 **(default)** |
| `MISSED_PERCENTAGE` | (missed / total) × 100 |
| `COVERED_COUNT` | Total covered units |
| `MISSED_COUNT` | Total missed units |

### Grouping (`GroupingEntityType`)
| Type | Description |
|------|-------------|
| `APPLICATION` | One value for the entire app **(default)** |
| `CLASS` | Per-class coverage (bounds checked per class) |
| `PACKAGE` | Per-package coverage (bounds checked per package) |
</verification_concepts>

<rules>
## Writing Verification Rules

### Basic — 100% line coverage for the entire app
```kotlin
kover {
    reports {
        verify {
            rule {
                minBound(100)
            }
        }
    }
}
```

### Full — named rule with explicit units and grouping
```kotlin
kover {
    reports {
        verify {
            rule("line coverage") {
                groupBy = GroupingEntityType.APPLICATION
                bound {
                    aggregationForGroup = AggregationType.COVERED_PERCENTAGE
                    coverageUnits = CoverageUnit.LINE
                    minValue = 100
                }
            }
            rule("branch coverage") {
                bound {
                    aggregationForGroup = AggregationType.COVERED_PERCENTAGE
                    coverageUnits = CoverageUnit.BRANCH
                    minValue = 100
                }
            }
        }
    }
}
```

### Per-class minimum
```kotlin
rule("per-class coverage") {
    groupBy = GroupingEntityType.CLASS
    bound {
        minValue = 80
        coverageUnits = CoverageUnit.LINE
        aggregationForGroup = AggregationType.COVERED_PERCENTAGE
    }
}
```
</rules>

<rule_scoping>
## Rule Scoping

### Rules for all report variants (including total and named)
```kotlin
kover {
    reports {
        verify {
            rule { minBound(100) }
        }
    }
}
```

### Rules only for total variant (all classes)
```kotlin
kover {
    reports {
        total {
            verify {
                rule { minBound(100) }
            }
        }
    }
}
```

### Rules only for a named variant
```kotlin
kover {
    reports {
        variant("debug") {
            verify {
                rule { minBound(80) }
            }
        }
    }
}
```

### Appending rules (vs overriding)
`verify { }` clears and replaces rules. Use `verifyAppend { }` to add without clearing.
</rule_scoping>
