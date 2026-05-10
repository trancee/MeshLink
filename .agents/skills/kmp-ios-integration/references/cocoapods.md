# KMP iOS — CocoaPods Integration

<when_to_use>
## When to Use

- Mono-repo with iOS project that already uses CocoaPods
- KMP project has CocoaPods dependencies (Pods you import from Kotlin)
- **Cannot be used together with direct integration** (`embedAndSignAppleFrameworkForXcode`)
</when_to_use>

<setup>
## Setup

### 1. Install CocoaPods
```bash
# Recommended: via RVM or rbenv
rvm install ruby 3.4.7
sudo gem install -n /usr/local/bin cocoapods

# Or via rbenv
rbenv install 3.4.7 && rbenv global 3.4.7
sudo gem install -n /usr/local/bin cocoapods
```
**Avoid Homebrew** for CocoaPods — it can cause Xcodeproj version conflicts.

### 2. Apply the plugin
```toml
# gradle/libs.versions.toml
[plugins]
kotlinCocoapods = { id = "org.jetbrains.kotlin.native.cocoapods", version.ref = "kotlin" }
```
```kotlin
// shared/build.gradle.kts
plugins {
    kotlin("multiplatform")
    kotlin("native.cocoapods")
}
```

### 3. Configure the cocoapods block
```kotlin
kotlin {
    iosArm64()
    iosSimulatorArm64()

    cocoapods {
        version = "1.0"
        summary = "Shared KMP module"
        homepage = "https://github.com/example/project"
        name = "MySharedPod"        // optional, defaults to project name
        ios.deploymentTarget = "16.0"

        framework {
            baseName = "Shared"
            isStatic = false        // dynamic by default
            // export(project(":another-module"))
            // transitiveExport = false  // default
        }

        // Map custom Xcode configs to Kotlin build types
        xcodeConfigurationToNativeBuildType["CUSTOM_DEBUG"] = NativeBuildType.DEBUG
        xcodeConfigurationToNativeBuildType["CUSTOM_RELEASE"] = NativeBuildType.RELEASE
    }
}
```

### 4. Update Podfile for Xcode
```ruby
# iosApp/Podfile
target 'iosApp' do
    pod 'Shared', :path => '../shared'  # path to your KMP module
end
```
Then run `pod install` and open the `.xcworkspace` (not `.xcodeproj`).

### What the plugin does
- Adds debug + release frameworks as output binaries for all Apple targets
- Creates a `podspec` task generating a Podspec file
- Automates framework building during Xcode builds
</setup>

<pod_dependencies>
## Adding Pod Dependencies

### From CocoaPods repository
```kotlin
cocoapods {
    ios.deploymentTarget = "16.0"
    pod("SDWebImage") { version = "5.20.0" }
}
```
Import in Kotlin: `import cocoapods.SDWebImage.*`

### From local path
```kotlin
pod("pod_dependency") {
    version = "1.0"
    source = path(project.file("../pod_dependency"))
}
pod("subspec_dependency/Core") {
    version = "1.0"
    source = path(project.file("../subspec_dependency"))
}
```

### From Git repository
```kotlin
pod("SDWebImage") {
    source = git("https://github.com/SDWebImage/SDWebImage") {
        tag = "5.20.0"       // or: branch = "main" / commit = "abc123"
    }
}
```
Priority: commit > tag > branch. Default: HEAD of master.

### From custom Podspec repository
```kotlin
cocoapods {
    specRepos {
        url("https://github.com/Kotlin/kotlin-cocoapods-spec.git")
    }
    pod("example")
}
```
Also add to Podfile: `source 'https://github.com/Kotlin/kotlin-cocoapods-spec.git'`

### Custom cinterop options
```kotlin
pod("FirebaseAuth") {
    version = "11.7.0"
    packageName = "FirebaseAuthWrapper"            // custom import name
    extraOpts += listOf("-compiler-option", "-fmodules")  // for @import directives
}
```
Import with custom package: `import FirebaseAuthWrapper.Auth`

### Sharing cinterop between dependent Pods
```kotlin
pod("WebImage") { version = "1.0" }
pod("Info") {
    version = "1.0"
    useInteropBindingFrom("WebImage")  // reuse WebImage's cinterop
}
```
Declare the dependency Pod **before** the Pod that uses it.

### linkOnly option
```kotlin
pod("SomePod") {
    linkOnly = true  // link without generating cinterop bindings
}
```
</pod_dependencies>

<dsl_reference>
## CocoaPods DSL Reference

### cocoapods {} block
| Property | Description |
|----------|-------------|
| `version` | Pod version (required — falls back to Gradle project version) |
| `summary` | Required description |
| `homepage` | Required homepage URL |
| `name` | Pod name (defaults to project name) |
| `authors` | Pod authors |
| `license` | License type and text |
| `source` | Source location for the podspec |
| `podfile` | Path to existing Podfile |
| `noPodspec()` | Skip Podspec generation |
| `extraSpecAttributes` | Additional podspec attributes (e.g. `vendored_frameworks`) |
| `publishDir` | Output directory for publishing |

### framework {} block (inside cocoapods)
| Property | Description |
|----------|-------------|
| `baseName` | **Required.** Framework name |
| `isStatic` | Linking type (default: dynamic) |
| `transitiveExport` | Export transitive dependencies (default: false) |
| `export()` | Export a project/library dependency |

### pod() function parameters
| Parameter | Description |
|-----------|-------------|
| `version` | Library version (omit for latest) |
| `source` | `git()` or `path()` source location |
| `packageName` | Custom import package name |
| `extraOpts` | Compiler options list |
| `linkOnly` | Link without cinterop bindings |
| `useInteropBindingFrom()` | Reuse another Pod's cinterop |

### Deployment targets
```kotlin
cocoapods {
    ios.deploymentTarget = "16.0"
    osx.deploymentTarget = "13.0"
    tvos.deploymentTarget = "16.0"
    watchos.deploymentTarget = "9.0"
}
```
</dsl_reference>

<troubleshooting>
## Troubleshooting

### CocoaPods path not found
```properties
# local.properties
kotlin.apple.cocoapods.bin=/Users/Jane.Doe/.rbenv/shims/pod
```
Or: `echo -e "kotlin.apple.cocoapods.bin=$(which pod)" >> local.properties`

### "module not found" / "framework not found"
1. Update Ruby and gems: `gem update --system && gem update`
2. Check `module.modulemap` in `build/cocoapods/synthetic/IOS/Pods/` for actual module name
3. Specify `moduleName`: `pod("SDWebImage/MapKit") { moduleName = "SDWebImageMapKit" }`
4. Specify headers if no `.modulemap`: `pod("NearbyMessages") { headers = "GNSMessages.h" }`

### Rsync error
Disable "User Script Sandboxing" in Xcode Build Settings, then `./gradlew --stop`.
</troubleshooting>
