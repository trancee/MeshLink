# AGP Troubleshooting Guide

## Diagnosis Checklist

Before diving into specific errors:
1. Run `./gradlew <task> --stacktrace` for full stack traces
2. Run `./gradlew <task> --info` for verbose task output
3. Check that AGP and Gradle versions are compatible (`references/version-compatibility.md`)
4. Try `./gradlew clean` then re-run (some errors are stale-cache artifacts)
5. Check if the error reproduces with `./gradlew --no-daemon` (daemon corruption)

---

## Version / Compatibility Errors

### "The Android Gradle plugin supports only Kotlin Gradle plugin version X or higher"
- Upgrade your Kotlin Gradle plugin version in `build.gradle.kts`:
  ```kotlin
  id("org.jetbrains.kotlin.android") version "2.1.0" apply false
  ```

### "Minimum supported Gradle version is X. Current version is Y"
- Update `gradle/wrapper/gradle-wrapper.properties`:
  ```properties
  distributionUrl=https\://services.gradle.org/distributions/gradle-9.3.1-bin.zip
  ```
- Run `./gradlew wrapper --gradle-version 9.3.1` twice

### "compileSdk is not supported by the current version of the Android Gradle plugin"
- You need a newer AGP. Check `references/version-compatibility.md` for the minimum AGP that supports your `compileSdk`.

### "Android Gradle Plugin requires Java 17 to run"
- The JDK running the build is too old. In Android Studio: *File → Project Structure → SDK Location → Gradle JDK* → select JDK 17+
- In CI: ensure `JAVA_HOME` points to JDK 17

---

## Dependency Resolution Errors

### "Duplicate class kotlin.collections.jdk8.CollectionsJDK8Kt found in modules..."
- Common when mixing `kotlin-stdlib` and `kotlin-stdlib-jdk8`. Kotlin 1.8+ merged these.
  Add to `build.gradle.kts`:
  ```kotlin
  configurations.all {
      resolutionStrategy {
          force("org.jetbrains.kotlin:kotlin-stdlib:2.1.0")
      }
  }
  ```

### "Cannot resolve symbol" / "Unresolved reference" at compile time
1. Check if the dependency is correctly declared in `build.gradle.kts`
2. Check if it's in the right configuration (`implementation` vs `compileOnly`)
3. Run `./gradlew :app:dependencies --configuration debugRuntimeClasspath`
4. For multi-module: check that library modules expose the dep with `api` if consumers need it

### "Failed to resolve: com.example:lib:1.0.0"
1. Verify the repository is declared in `settings.gradle.kts` under `dependencyResolutionManagement`
2. Check network / VPN (for CI: ensure maven credentials are configured)
3. Try `./gradlew :app:dependencies --configuration debugRuntimeClasspath --refresh-dependencies`

### Version conflict: "Conflict with dependency '...'"
```kotlin
// In the module with the conflict:
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "com.example" && requested.name == "conflicting-lib") {
            useVersion("2.0.0")
            because("Force version to resolve conflict")
        }
    }
}
```

---

## R8 / ProGuard Errors

### App crashes with `ClassNotFoundException` or `NoSuchMethodException` in release only
- R8 removed or renamed a class/method. Common causes: reflection, serialization, dependency with missing keep rules.
- Fix: add a keep rule in `proguard-rules.pro`:
  ```proguard
  # Keep specific class
  -keep class com.example.MyClass { *; }

  # Keep all classes in a package
  -keep class com.example.model.** { *; }

  # Keep Kotlin data classes used for JSON serialization
  -keepclassmembers class com.example.** {
      @com.google.gson.annotations.SerializedName <fields>;
  }
  ```
- Or annotate your class with `@Keep`

### "R8: Error: Program type already present: ..."
- A class exists in multiple dependencies. Find the source with:
  ```bash
  ./gradlew :app:checkDebugDuplicateClasses
  ```
- Exclude the duplicate from one of the dependencies:
  ```kotlin
  implementation("com.example:lib:1.0.0") {
      exclude(group = "com.example.transitive", module = "duplicate-class")
  }
  ```

### Stack traces are unreadable after R8 obfuscation
- Use the mapping file: `app/build/outputs/mapping/release/mapping.txt`
- In Android Studio: *Analyze → Analyze Stack Trace* → enable retrace
- Command line: `java -jar proguard-retrace.jar mapping.txt obfuscated-stacktrace.txt`
- For CI: upload the mapping file to Firebase Crashlytics, Play Console, or Sentry

### R8 full mode breaks code that worked before AGP 8.0
- Full mode makes stricter assumptions about reflection. Options:
  1. Add keep rules (preferred)
  2. Opt out temporarily: `android.enableR8.fullMode=false` in `gradle.properties`

### AGP 9.1: Release build crashes due to repackaging
- R8 now moves all classes to the unnamed package by default. If your code or a library uses reflection to look up classes by their original package paths, they may fail.
- Fix: add `-dontrepackage` to `proguard-rules.pro`

---

## Manifest Merger Errors

### "Manifest merger failed: Attribute application@... value (...) from ... is also present in ..."
- Two libraries declare conflicting manifest attributes. Override in your `AndroidManifest.xml`:
  ```xml
  <application
      tools:replace="android:theme,android:label"
      android:theme="@style/AppTheme"
      android:label="@string/app_name">
  ```
- Add `xmlns:tools="http://schemas.android.com/tools"` to the `<manifest>` tag.

### "uses-sdk:minSdkVersion X cannot be smaller than version Y declared in library"
- A library requires a higher `minSdk` than your app. Options:
  1. Increase your app's `minSdk` (correct fix if users on older versions don't matter)
  2. Override with `tools:overrideLibrary="com.example.lib"` in `AndroidManifest.xml` (risky — you accept the consequences)

---

## Build Performance Issues

### Builds are slow
1. Enable configuration cache: `org.gradle.configuration-cache=true`
2. Enable build cache: `org.gradle.caching=true`  
3. Increase heap: `org.gradle.jvmargs=-Xmx4g`
4. Run `./gradlew :app:assembleDebug --profile` — opens HTML report showing bottleneck tasks
5. Check for tasks that are not cacheable: look for `not cacheable` in `--info` output
6. Remove `isMinifyEnabled = true` from debug builds if enabled

### Configuration cache problems
- If you get "configuration cache" errors after enabling it, there are incompatible plugins or code accessing the project at execution time.
- Check the error report (saved to `build/reports/configuration-cache/`)
- Workaround: `org.gradle.configuration-cache.problems=warn` to continue despite problems

---

## Build Variant / Flavor Errors

### "No variants found for ':app'"
- Likely the AGP plugin wasn't applied. Verify `id("com.android.application")` is in the module's `plugins {}` block (not `apply false`).

### "More than one flavor dimension" / "All flavors must now belong to a named flavor dimension"
- Every `productFlavor` must declare a `dimension`. Add `flavorDimensions += "myDimension"` and `dimension = "myDimension"` to each flavor.

### A specific build variant is missing
- Check `androidComponents { beforeVariants { variantBuilder.enable = ... } }` — something may be disabling it.

---

## Annotation Processor / KSP Errors

### "error: Annotation processor must be explicitly declared"
- Add `kapt` or `ksp` plugin and dependency:
  ```kotlin
  plugins { id("com.google.devtools.ksp") version "2.1.0-1.0.28" }
  dependencies { ksp(libs.hilt.compiler) }
  ```
- Prefer KSP over kapt for Kotlin-first processors (faster, no stub generation)

### KSP: "error: [ksp] ...processor requires..."
- KSP version must match Kotlin version. Check https://github.com/google/ksp/releases for the right version for your Kotlin.

---

## AGP Upgrade Assistant Tips

The AGP Upgrade Assistant (*Android Studio → File → Project Structure → Suggest Upgrade*):
- Automatically handles many mechanical migrations (API renames, removed properties)
- Run it even for small version bumps — it catches things you'd miss
- After running it, review the diff before committing
- It does NOT handle all breaking changes — always read the release notes too
