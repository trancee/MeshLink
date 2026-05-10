# KMP iOS — Direct Integration

<overview>
## When to Use

- Mono-repo with KMP + iOS in the same project
- **No CocoaPods dependencies** in your KMP project
- Default method when using the Kotlin Multiplatform IDE plugin

Requires: `binaries.framework` declared in `build.gradle.kts` for the iOS target.
</overview>

<setup>
## Setup: Xcode Build Phase Script

### 1. Declare the framework in Gradle
```kotlin
kotlin {
    iosArm64 {
        binaries.framework {
            baseName = "Shared"
            isStatic = true // or false for dynamic
        }
    }
    iosSimulatorArm64 {
        binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }
}
```

### 2. Add Run Script phase in Xcode
In Xcode: Target → Build Phases → + → New Run Script Phase. Paste:

```bash
if [ "YES" = "$OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED" ]; then
    echo "Skipping Gradle build task invocation due to OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED environment variable set to \"YES\""
    exit 0
fi
cd "$SRCROOT/.."
./gradlew :shared:embedAndSignAppleFrameworkForXcode
```

- Replace `$SRCROOT/..` with the path to your KMP project root
- Replace `:shared` with your shared module name (e.g. `:composeApp`)

### 3. Configure Xcode settings
- **Disable** "Based on dependency analysis" on the Run Script phase
- **Move** the Run Script phase **before** Compile Sources
- **Disable** "User Script Sandboxing" under Build Settings → Build Options
- If sandboxing was previously enabled, run `./gradlew --stop` to kill sandboxed daemons

### 4. Custom build configurations
If you have custom Xcode build configurations (not just Debug/Release), add a User-Defined setting:
- Key: `KOTLIN_FRAMEWORK_BUILD_TYPE`
- Value: `Debug` or `Release`

### How it works
The `embedAndSignAppleFrameworkForXcode` Gradle task:
1. Builds the Kotlin framework for the current architecture/configuration
2. Copies it into the correct directory in the iOS project structure
3. Handles code signing of the embedded framework

When launched from IntelliJ IDEA or Android Studio, the IDE sets `OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED=YES` to prevent double-building (IDE builds Kotlin first, then starts Xcode).
</setup>

<migrate_from_cocoapods>
## Migrating from CocoaPods to Direct Integration

1. In Xcode: Product → Clean Build Folder (Cmd+Shift+K)
2. In the directory with Podfile, run: `pod deintegrate`
3. Remove the `cocoapods {}` block from `build.gradle.kts`
4. Delete the `.podspec` file and `Podfile`
5. Add the Run Script phase as described above

**CocoaPods and direct integration cannot be used together.**
</migrate_from_cocoapods>

<swiftpm_local>
## Using Kotlin from Local Swift Packages

Instead of a build phase, use a **pre-action** in the Xcode scheme:

1. Xcode → Product → Scheme → Edit Scheme
2. Build → Pre-actions → + → New Run Script Action
3. Paste the same `embedAndSignAppleFrameworkForXcode` script (without the IDE guard)
4. Set "Provide build settings from" to your app target

Then in your local Swift package:
```swift
import Shared

public func greetFromPackage() -> String {
    return Greeting.greet()
}
```

**Advantage over direct integration:** Only the KMP project needs rebuilding to see common code changes — the Xcode build phase doesn't need to rebuild too.

**Requirement:** Kotlin 2.0.0+, static linking type for the iOS project.
</swiftpm_local>
