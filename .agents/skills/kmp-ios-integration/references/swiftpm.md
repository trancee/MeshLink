# KMP iOS — SwiftPM Integration

<export>
## Exporting as a Swift Package (Remote Integration)

Use when you want iOS consumers to depend on your KMP framework as a regular Swift package.

### 1. Configure XCFramework in Gradle
```kotlin
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

kotlin {
    val xcframeworkName = "Shared"
    val xcf = XCFramework(xcframeworkName)

    listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach {
        it.binaries.framework {
            baseName = xcframeworkName
            binaryOption("bundleId", "org.example.${xcframeworkName}")
            xcf.add(this)
            isStatic = true
        }
    }
}
```

### 2. Build the XCFramework
```bash
./gradlew :shared:assembleSharedXCFramework
# Output: shared/build/XCFrameworks/release/Shared.xcframework
```

### 3. Prepare the Swift package
```bash
# Compress
zip -r Shared.xcframework.zip Shared.xcframework

# Compute checksum
swift package compute-checksum Shared.xcframework.zip
```

Upload the ZIP to file storage (GitHub release, S3, Maven, etc.).

### 4. Create Package.swift
```swift
// swift-tools-version:5.3
import PackageDescription

let package = Package(
    name: "Shared",
    platforms: [.iOS(.v14)],
    products: [
        .library(name: "Shared", targets: ["Shared"])
    ],
    targets: [
        .binaryTarget(
            name: "Shared",
            url: "<link to uploaded ZIP>",
            checksum: "<checksum>")
    ]
)
```

Push to a **separate Git repository** (recommended). Tag with semantic version.

### 5. Add to Xcode
Xcode → File → Add Package Dependencies → paste the Git repo URL.

### Validate the manifest
```bash
swift package reset && swift package show-dependencies --format json
```

### Exporting multiple modules
Create an umbrella module that re-exports others:
```kotlin
kotlin {
    val xcf = XCFramework("together")
    listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            export(projects.network)
            export(projects.database)
            baseName = "together"
            xcf.add(this)
        }
    }
    sourceSets {
        commonMain.dependencies {
            api(projects.network)    // must be api, not implementation
            api(projects.database)
        }
    }
}
```
**Note:** An empty `.kt` file is needed in the umbrella module's `commonMain` as a workaround.

### Repository layout options
| Approach | Pros | Cons |
|----------|------|------|
| **Separate repo for Package.swift** (recommended) | Independent versioning, scales well | Two repos to maintain |
| **Same repo as KMP code** | Simpler | Git tags conflict (package version vs project version) |
| **Consumer repo** | No separate repo | Blocks multi-package consumers, complicates CI |
</export>

<import>
## Importing Swift Packages into KMP (Experimental)

**Requires Kotlin 2.4.0-Beta2+.** Use `swiftPMDependencies {}` block.

### Setup
```kotlin
kotlin {
    iosArm64()
    iosSimulatorArm64()
    iosX64()

    swiftPMDependencies {
        swiftPackage(
            url = url("https://github.com/firebase/firebase-ios-sdk.git"),
            version = from("12.5.0"),
            products = listOf(product("FirebaseAnalytics")),
        )
    }
}
```

### Version specifiers
| Function | Behavior |
|----------|----------|
| `from("1.0")` | Minimum version (like Gradle `require`) |
| `exact("2.0")` | Exact version (like Gradle `strict`) |
| `branch("main")` | Git branch |
| `revision("abc123")` | Git commit |

### Use imported APIs
APIs are namespaced: `import swiftPMImport.<groupName>.<projectName>.<Module>`

```kotlin
import swiftPMImport.myGroup.shared.FIRAnalytics
import swiftPMImport.myGroup.shared.FIRApp
```

### Clang module discovery
By default, all Clang modules are discovered automatically. To control explicitly:
```kotlin
swiftPMDependencies {
    discoverClangModulesImplicitly = false
    swiftPackage(
        url = url("..."),
        version = from("12.5.0"),
        products = listOf(product("FirebaseFirestore")),
        importedClangModules = listOf("FirebaseFirestoreInternal"),
    )
}
```

### Platform constraints
When targeting both iOS and macOS, constrain iOS-only packages:
```kotlin
product("GoogleMaps", platforms = setOf(iOS()))
```

### Local Swift packages
```kotlin
swiftPMDependencies {
    localSwiftPackage(
        directory = project.layout.projectDirectory.dir("/path/to/ExamplePackage/"),
        products = listOf("ExamplePackage")
    )
}
```

### Initial Xcode integration
First time only — link the generated package to Xcode:
```bash
XCODEPROJ_PATH='/path/to/iosApp.xcodeproj' \
  ./gradlew :shared:integrateLinkagePackage
```
Commit the generated package and updated Xcode project.

### Lock files
- `Package.resolved` files are generated in `.swiftpm-locks/default/swiftImport/`
- Commit `.swiftpm-locks/` to your repository
- Force update: delete `build/` dir + `Package.resolved`, re-run resolution task

### Deployment target
```kotlin
swiftPMDependencies {
    iosMinimumDeploymentTarget.set("16.0")
}
```

### Limitations
- **Experimental** — share feedback in #kmp-swift-package-manager Slack channel
- Export of KMP modules that use SwiftPM import as a Swift package is not yet supported
- Pure Swift dependencies not supported (Objective-C or `@objc`-exported Swift only)
</import>
