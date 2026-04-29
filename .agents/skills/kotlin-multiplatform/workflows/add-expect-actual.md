# Workflow: Add Expect/Actual Declarations

<required_reading>
**Read these reference files NOW:**
1. references/expect-actual.md
2. references/project-structure.md
</required_reading>

<process>
## Step 1: Choose the Right Pattern

**Decision tree:**

1. **Is it a single function or property?** → Use `expect fun` / `expect val`
2. **Is it a service with multiple methods?** → Use `interface` + `expect fun createX(): X` factory
3. **Does it need to be a singleton?** → Use `expect object`
4. **Does the actual class need to extend a platform base class?** → Use `expect class` (last resort)

**Prefer interfaces + factory functions** over expect classes. They're easier to test, support multiple implementations per platform, and don't require the Beta flag.

## Step 2: Define the Expected Declaration

In `commonMain`, declare the API contract:

```kotlin
// For a service with multiple methods — prefer this pattern
interface PlatformStorage {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun delete(key: String)
}

expect fun createPlatformStorage(): PlatformStorage
```

**Rules:**
- No implementation in the expected declaration
- Must be in a package that matches across all source sets
- Only declare what common code actually needs

## Step 3: Implement Actual Declarations

For **each platform source set** that has a target:

```kotlin
// androidMain
actual fun createPlatformStorage(): PlatformStorage = AndroidStorage()

class AndroidStorage : PlatformStorage {
    override fun get(key: String): String? = /* SharedPreferences */
    override fun put(key: String, value: String) = /* SharedPreferences */
    override fun delete(key: String) = /* SharedPreferences */
}
```

```kotlin
// iosMain
actual fun createPlatformStorage(): PlatformStorage = IosStorage()

class IosStorage : PlatformStorage {
    override fun get(key: String): String? = /* NSUserDefaults */
    override fun put(key: String, value: String) = /* NSUserDefaults */
    override fun delete(key: String) = /* NSUserDefaults */
}
```

**Naming convention:** Prefix actual classes with the platform name: `Android*`, `Ios*` (not `IOS*` or `iOS*`).

## Step 4: Handle Intermediate Source Sets

If `iosMain` is an intermediate source set covering `iosArm64` and `iosSimulatorArm64`, place the `actual` there — not in the individual target source sets. The compiler uses the intermediate source set for all covered targets.

## Step 5: Verify Compilation

```bash
# Compile metadata (checks common code)
./gradlew compileKotlinMetadata

# Compile all targets
./gradlew assemble
```

The compiler will error if:
- Any `expect` declaration is missing a matching `actual`
- An `actual` has a different signature than the `expect`
- An `actual` is in a different package

## Step 6: Add Tests

Write tests in `commonTest` that exercise the expect/actual API:

```kotlin
class PlatformStorageTest {
    @Test
    fun roundTrip() {
        val storage = createPlatformStorage()
        storage.put("key", "value")
        assertEquals("value", storage.get("key"))
        storage.delete("key")
        assertNull(storage.get("key"))
    }
}
```

These tests run on all declared targets automatically.
</process>

<success_criteria>
- [ ] Expected declaration in `commonMain` — no implementation, just API contract
- [ ] Actual declaration in each platform source set — same package, matching signature
- [ ] Platform-specific classes use platform prefix naming (`Android*`, `Ios*`)
- [ ] `./gradlew compileKotlinMetadata` passes
- [ ] `./gradlew assemble` passes (all targets)
- [ ] Tests written in `commonTest` cover the shared API
</success_criteria>
