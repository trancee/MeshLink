# AGP Version Compatibility Reference

## AGP ↔ Gradle Wrapper Compatibility

| AGP Version | Minimum Gradle | Recommended Gradle |
|-------------|---------------|-------------------|
| 9.1         | 9.3.1         | 9.3.1             |
| 9.0         | 9.1.0         | 9.1.0             |
| 8.13        | 8.13          | 8.13              |
| 8.12        | 8.13          | 8.13              |
| 8.11        | 8.13          | 8.13              |
| 8.10        | 8.11.1        | 8.11.1            |
| 8.9         | 8.11.1        | 8.11.1            |
| 8.8         | 8.10.2        | 8.10.2            |
| 8.7         | 8.9           | 8.9               |
| 8.6         | 8.7           | 8.7               |
| 8.5         | 8.7           | 8.7               |
| 8.4         | 8.6           | 8.6               |
| 8.3         | 8.4           | 8.4               |
| 8.2         | 8.2           | 8.2               |
| 8.1         | 8.0           | 8.0               |
| 8.0         | 8.0           | 8.0               |
| 7.4         | 7.5           | 7.5               |
| 7.3         | 7.4           | 7.4               |
| 7.2         | 7.3.3         | 7.3.3             |
| 7.1         | 7.2           | 7.2               |
| 7.0         | 7.0           | 7.0               |
| 4.2.x       | 6.7.1         | 6.7.1             |

## AGP ↔ Android Studio Compatibility

| Android Studio Version              | Supported AGP Versions |
|------------------------------------|------------------------|
| Panda 2 \| 2025.3.2                | 4.0 – 9.1              |
| Panda 1 \| 2025.3.1                | 4.0 – 9.0              |
| Otter 3 Feature Drop \| 2025.2.3   | 4.0 – 9.0              |
| Otter 2 Feature Drop \| 2025.2.2   | 4.0 – 8.13             |
| Otter \| 2025.2.1                  | 4.0 – 8.13             |
| Narwhal 4 Feature Drop \| 2025.1.4 | 4.0 – 8.13             |
| Narwhal 3 Feature Drop \| 2025.1.3 | 4.0 – 8.13             |
| Narwhal Feature Drop \| 2025.1.2   | 4.0 – 8.12             |
| Narwhal \| 2025.1.1                | 3.2 – 8.11             |
| Meerkat Feature Drop \| 2024.3.2   | 3.2 – 8.10             |
| Meerkat \| 2024.3.1                | 3.2 – 8.9              |
| Ladybug Feature Drop \| 2024.2.2   | 3.2 – 8.8              |
| Ladybug \| 2024.2.1                | 3.2 – 8.7              |
| Koala Feature Drop \| 2024.1.2     | 3.2 – 8.6              |
| Koala \| 2024.1.1                  | 3.2 – 8.5              |
| Jellyfish \| 2023.3.1              | 3.2 – 8.4              |
| Iguana \| 2023.2.1                 | 3.2 – 8.3              |
| Hedgehog \| 2023.1.1               | 3.2 – 8.2              |
| Giraffe \| 2022.3.1                | 3.2 – 8.1              |

## Minimum AGP Version per API Level

| compileSdk / targetSdk | Minimum Android Studio | Minimum AGP |
|------------------------|------------------------|-------------|
| 36.1                   | Narwhal 3 FD (2025.1.3) | 8.13.0     |
| 36.0                   | Meerkat (2024.3.1 p1)  | 8.9.1       |
| 35                     | Koala FD (2024.2.1)    | 8.6.0       |
| 34                     | Hedgehog (2023.1.1)    | 8.1.1       |
| 33                     | Flamingo (2022.2.1)    | 7.2         |

## AGP 9.1 Compatibility Details

- Maximum supported API level: 36.1
- Minimum Gradle: 9.3.1
- Default Gradle: 9.3.1
- SDK Build Tools: 36.0.0 (min and default)
- NDK default: 28.2.13676358
- JDK: 17 (minimum and default)

## Key Breaking Changes by Version

### AGP 9.1
- R8 repackages all classes to the unnamed (default) package by default
  → Opt out: add `-dontrepackage` to your ProGuard rules
- R8: `-maximumremovedandroidloglevel` now accepts named levels (ASSERT, ERROR, WARN, INFO, DEBUG, VERBOSE, NONE)

### AGP 9.0
- Optimized resource shrinking is **on by default** when `isShrinkResources = true` (no need for `android.r8.optimizedResourceShrinking=true`)
- Library consumer rules no longer support global options (`-dontobfuscate`, etc.) — apps filter them out
- R8 Kotlin null-check optimization enabled by default (use `-processkotlinnullchecks false` to disable)
- `getDefaultProguardFile("proguard-android.txt")` removed (includes `-dontoptimize`); use `"proguard-android-optimize.txt"` instead

### AGP 8.0
- R8 full mode enabled **by default** (previously opt-in via `android.enableR8.fullMode=true`)
  → More aggressive optimization; code relying on unconstrained reflection may break
  → Opt out: `android.enableR8.fullMode=false` in `gradle.properties` (not recommended for production)

### AGP 7.0
- `compile` and `provided` configurations removed — use `implementation` / `api` / `compileOnly`
- `android.viewBinding.enabled` replaced by `buildFeatures { viewBinding = true }`

## Updating the Gradle Wrapper

```bash
# Run twice to update both Gradle and the wrapper itself
./gradlew wrapper --gradle-version 9.3.1
./gradlew wrapper --gradle-version 9.3.1
```

Or edit `gradle/wrapper/gradle-wrapper.properties` directly:
```properties
distributionUrl=https\://services.gradle.org/distributions/gradle-9.3.1-bin.zip
```
