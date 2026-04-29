# Build System Reference

## Toolchain Versions

| Tool | Version | Plugin ID |
|------|---------|-----------|
| Gradle | 9.4.1+ | — |
| Kotlin | 2.3.20 | `org.jetbrains.kotlin.multiplatform` |
| AGP | 9.2.0 | `com.android.kotlin.multiplatform.library` |
| Kover | 0.9.8 | `org.jetbrains.kotlinx.kover` |
| Detekt | 1.23.8 | `io.gitlab.arturbosch.detekt` |
| ktfmt | 0.26.0 | `com.ncorti.ktfmt.gradle` |
| BCV | 0.18.1 | `org.jetbrains.kotlinx.binary-compatibility-validator` |
| SKIE | 0.10.11 | `co.touchlab.skie` |
| Dokka | 2.2.0 | `org.jetbrains.dokka` |
| kotlinx-benchmark | 0.4.16 | `org.jetbrains.kotlinx.benchmark` |
| Compose MP | 1.10.3 | `org.jetbrains.compose` |
| Coroutines | 1.10.2 | — (library) |
| FlatBuffers | 25.2.10 | — (jvmMain only) |
| Navigation | 2.9.2 | — (library) |

## Gradle Properties

```properties
org.gradle.jvmargs=-Xmx4g -XX:+UseParallelGC
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.configuration-cache=true
kotlin.code.style=official
kotlin.mpp.enableCInteropCommonization=true
kotlin.mpp.stability.nowarn=true
kotlin.mpp.applyDefaultHierarchyTemplate=false
kotlin.native.ignoreDisabledTargets=true
kotlin.native.enableKlibsCrossCompilation=false
org.jetbrains.dokka.experimental.gradle.pluginMode=V2EnabledWithHelpers
android.useAndroidX=true
GROUP=ch.trancee
VERSION=0.1.0
```

## Plugin Ordering (Critical)

In `meshlink/build.gradle.kts`, plugins must appear in this order:

1. `kotlin.multiplatform`
2. `android.kotlin.multiplatform.library`
3. `kover`
4. `detekt`
5. `ktfmt`
6. `bcv`
7. `kotlin.power.assert`
8. **`kotlin.allopen`** (must precede benchmark for JMH)
9. `kotlinx.benchmark`
10. `skie`
11. `maven-publish` + `signing` + `dokka`

## KMP Targets

```kotlin
kotlin {
    explicitApi()
    compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }
    android { namespace = "ch.trancee.meshlink"; compileSdk = 36; minSdk = 29 }
    iosArm64("ios")  // renamed from iosArm64 for consistency
    jvm()            // test infrastructure only
}
```

## Source Set Dependencies

| Source Set | Dependencies |
|-----------|-------------|
| commonMain | `kotlinx-coroutines-core` |
| commonTest | `kotlin-test`, `kotlinx-coroutines-test` |
| androidMain | `androidx-datastore-preferences` |
| jvmMain | `flatbuffers-java` (benchmark reference only) |
| jvmTest (benchmark) | `kotlinx-benchmark-runtime` |

## Kover Configuration

```kotlin
kover {
    reports {
        verify {
            rule {
                bound { minValue = 100; coverageUnits = CoverageUnit.LINE; aggregation = AggregationType.COVERED_PERCENTAGE }
                bound { minValue = 100; coverageUnits = CoverageUnit.BRANCH; aggregation = AggregationType.COVERED_PERCENTAGE }
            }
        }
        filters {
            excludes {
                classes(
                    "ch.trancee.meshlink.crypto.AndroidCryptoProvider",
                    "ch.trancee.meshlink.crypto.IosCryptoProvider",
                    "ch.trancee.meshlink.transport.AndroidBleTransport",
                    "ch.trancee.meshlink.transport.IosBleTransport",
                    // ... platform classes that can't run on JVM
                )
            }
        }
    }
}
```

## BCV Configuration

```kotlin
apiValidation {
    klib { enabled = (System.getProperty("os.name")?.contains("Mac") == true) }
}
```

KLib validation is macOS-only due to BCV 0.18.x inference bug on Linux.

## API Baseline Files

- `meshlink/api/jvm/meshlink.api` — JVM public API dump (~680 lines)
- `meshlink/api/meshlink.klib.api` — KLib public API dump (~1328 lines, macOS-generated)

## CI Pipeline

### ci.yml (on push/PR)

| Job | Tasks | Depends On |
|-----|-------|------------|
| lint | `ktfmtCheck`, `detekt` | — |
| test | `allTests` | — |
| coverage | `koverVerify` | test |
| benchmark | `benchmark` (continue-on-error) | — |

### release.yml (on version tag)

| Job | Tasks | Runner |
|-----|-------|--------|
| publish-maven | `publishAllPublicationsToOSSRHRepository` | Ubuntu |
| publish-xcframework | `assembleMeshLinkReleaseXCFramework` + upload + SHA-256 + update Package.swift | macOS |

## Publishing Coordinates

| Group | Artifact | Type |
|-------|----------|------|
| ch.trancee | meshlink | KMP (JVM JAR, Android AAR, iOS KLib) |
| ch.trancee | meshlink-testing | KMP (test utilities) |

Signing conditional on `SIGNING_KEY` env var presence.
