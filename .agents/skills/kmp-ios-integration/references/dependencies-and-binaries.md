# KMP iOS — Dependencies & Native Binaries

<ios_dependencies>
## iOS Dependencies

### Apple SDK
Apple SDK dependencies (Foundation, CoreBluetooth, UIKit, etc.) are available automatically — no additional configuration needed.

### Interoperability rules
- Kotlin supports Objective-C dependencies and Swift dependencies exported with `@objc`
- **Pure Swift dependencies are not supported**
- Two mechanisms: **cinterop** (manual) or **CocoaPods** (managed)

### cinterop: Adding a library
1. Download and build the library
2. Create a `.def` file (e.g. `src/nativeInterop/cinterop/DateTools.def`):
```
language = Objective-C
headers = DateTools.h
package = DateTools
```
3. Configure in Gradle:
```kotlin
kotlin {
    iosArm64 {
        compilations.getByName("main") {
            val DateTools by cinterops.creating {
                definitionFile.set(project.file("src/nativeInterop/cinterop/DateTools.def"))
                includeDirs("include/this/directory")
            }
        }
        binaries.all {
            linkerOpts("-L/path/to/library/binaries", "-lbinaryname")
        }
    }
}
```
4. Import: `import DateTools.*`

### cinterop: Adding a framework
`.def` file uses `modules` instead of `headers`:
```
language = Objective-C
modules = MyFramework
package = MyFramework
```
```kotlin
compilations.getByName("main") {
    val MyFramework by cinterops.creating {
        definitionFile.set(project.file("src/nativeInterop/cinterop/MyFramework.def"))
        compilerOpts("-framework", "MyFramework", "-F/path/to/framework/")
    }
}
binaries.all {
    linkerOpts("-framework", "MyFramework", "-F/path/to/framework/")
}
```

### CocoaPods dependencies
See the CocoaPods reference. Import pattern: `import cocoapods.<library-name>.*`
</ios_dependencies>

<native_binaries>
## Native Binaries

By default, Kotlin/Native compiles to `.klib` (library artifact). To produce final binaries, use the `binaries` property.

### Binary types
| Factory method | Kind | Available for |
|---------------|------|---------------|
| `executable` | Product executable | All native targets |
| `test` | Test executable | All native targets |
| `sharedLib` | Shared native library | All native targets |
| `staticLib` | Static native library | All native targets |
| `framework` | Objective-C framework | macOS, iOS, watchOS, tvOS only |

### Build types
- `DEBUG` — non-optimized, includes debug metadata
- `RELEASE` — optimized, no debug info

```kotlin
kotlin {
    iosArm64 {
        binaries {
            framework {
                baseName = "Shared"
                isStatic = true
            }
            // Or: executable(), sharedLib(), staticLib()
        }
    }
}
```

### Accessing binaries
```kotlin
// By unique name: <prefix><BuildType><Kind>
binaries["releaseFramework"]
binaries.getFramework("DEBUG")
binaries.getExecutable("foo", DEBUG)
// Nullable variants
binaries.findFramework("RELEASE")
```

### Exporting dependencies
Only `api` dependencies can be exported:
```kotlin
sourceSets {
    iosMain.dependencies {
        api(project(":dependency"))           // will be exported
        api("org.example:lib:1.0")           // will be exported
        implementation("org.example:other:1.0") // NOT exported
    }
}
iosArm64().binaries {
    framework {
        export(project(":dependency"))
        export("org.example:lib:1.0")
        // transitiveExport = true  // NOT recommended — bloats binary
    }
}
```

### Info.plist customization
```kotlin
binaries {
    framework {
        binaryOption("bundleId", "com.example.app")
        binaryOption("bundleVersion", "2")
        binaryOption("bundleShortVersionString", "1.0.0")
    }
}
```
</native_binaries>

<xcframeworks>
## XCFrameworks

Bundles all architectures in a single artifact. No need to strip architectures before App Store submission.

### Basic setup
```kotlin
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

kotlin {
    val xcf = XCFramework()
    listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach {
        it.binaries.framework {
            baseName = "shared"
            xcf.add(this)
        }
    }
}
```

### Build tasks
| Task | Output |
|------|--------|
| `assembleXCFramework` | Both debug + release |
| `assemble<Name>DebugXCFramework` | Debug only |
| `assemble<Name>ReleaseXCFramework` | Release only |

### With CocoaPods plugin
Additional tasks for publishing:
- `podPublishReleaseXCFramework` — XCFramework + podspec
- `podPublishDebugXCFramework` — debug XCFramework + podspec
- `podPublishXCFramework` — both + podspecs
</xcframeworks>

<fat_frameworks>
## Universal (Fat) Frameworks

Merge single-architecture frameworks into one using `FatFrameworkTask`:
```kotlin
import org.jetbrains.kotlin.gradle.tasks.FatFrameworkTask

tasks.register<FatFrameworkTask>("debugFatFramework") {
    baseName = "MyFramework"  // must match source frameworks
    destinationDirProperty.set(layout.buildDirectory.dir("fat-framework/debug"))
    from(
        watchos32.binaries.getFramework("DEBUG"),
        watchos64.binaries.getFramework("DEBUG")
    )
}
```
**Prefer XCFrameworks** over fat frameworks for new projects.
</fat_frameworks>
