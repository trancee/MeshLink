# Kotlin Binary Compatibility Validator — API Rules and KLib Support

<public_api_classes>
## What Constitutes Public API — Classes

A class is **effectively public** if ALL of the following are true:

1. **JVM access** is `ACC_PUBLIC` or `ACC_PROTECTED`

2. **Kotlin visibility** is one of:
   - No visibility (no Kotlin declaration corresponds to this compiled class — included by default)
   - `public`
   - `protected`
   - `internal` — **only** if annotated with `@PublishedApi`

3. **Not excluded** by structure:
   - Not a local class
   - Not a synthetic `$WhenMappings` class (generated for `when` tableswitches)

4. **Content check** for special class types:
   - If the class corresponds to a Kotlin **file with top-level members** or a **multifile facade**: must contain at least one effectively public member

5. **Nesting rules:**
   - If the class is a member of another class → the parent class must be effectively public
   - If the class is a **protected** member → the parent class must be **non-final**
</public_api_classes>

<public_api_members>
## What Constitutes Public API — Members

A member (field or method) is **effectively public** if ALL of the following are true:

1. **JVM access** is `ACC_PUBLIC` or `ACC_PROTECTED`

2. **Kotlin visibility** is one of:
   - No visibility (no Kotlin declaration corresponds to this member)
   - `public`
   - `protected`
   - `internal` — **only** if annotated with `@PublishedApi`

3. **Special rules:**
   - For `lateinit` properties: the field's effective visibility follows the **setter's** visibility (not the backing field)
   - If the member is **protected** → containing class must be **non-final**
   - Not a synthetic access method for a private field

### Excluding API from Public Surface

Use `nonPublicMarkers` to mark annotations that indicate internal API:
```kotlin
apiValidation {
    nonPublicMarkers.add("my.package.MyInternalApiAnnotation")
}
```
Any class or member annotated with these markers is excluded from the dump even if technically public.
</public_api_members>

<incompatible_class_changes>
## Binary-Incompatible Changes — Classes

A change to a class is **binary-incompatible** if it involves:

| Change | Why It Breaks |
|--------|--------------|
| **Changing full class name** (including package or containing classes) | All references to the class by name become invalid |
| **Changing the superclass** so the previous superclass is no longer in the inheritance chain | Code that casts to the old superclass or calls inherited methods breaks |
| **Removing an implemented interface** | Code that casts to or calls methods via that interface breaks |
| **Lessening visibility** (`ACC_PUBLIC` → `ACC_PROTECTED` → `ACC_PRIVATE`) | Code accessing the class from outside can no longer see it |
| **Making a non-final class `final`** (`ACC_FINAL` added) | Existing subclasses become invalid |
| **Making a non-abstract class `abstract`** (`ACC_ABSTRACT` added) | Code that instantiates the class directly breaks |
| **Changing `class` ↔ `interface`** (`ACC_INTERFACE` toggled) | All usage patterns differ between class and interface |
| **Changing `annotation` ↔ `interface`** (`ACC_ANNOTATION` toggled) | Annotation usage sites break |
</incompatible_class_changes>

<incompatible_member_changes>
## Binary-Incompatible Changes — Members

A change to a class member (field or method) is **binary-incompatible** if it involves:

| Change | Why It Breaks |
|--------|--------------|
| **Changing the name** | All call sites reference the old name |
| **Changing the descriptor** (erased return type or parameter types) — this includes changing a field to a method or vice versa | JVM resolves methods by name + descriptor; any change is a different symbol |
| **Lessening visibility** (`ACC_PUBLIC` → `ACC_PROTECTED` → `ACC_PRIVATE`) | Code accessing from outside can no longer see the member |
| **Making a non-final member `final`** (`ACC_FINAL` added) | Existing overrides in subclasses become invalid |
| **Making a non-abstract method `abstract`** (`ACC_ABSTRACT` added) | Concrete subclasses that don't override it break |
| **Changing `static` ↔ `instance`** (`ACC_STATIC` toggled) | Call sites use different bytecode instructions for static vs instance |

### What Is NOT Binary-Incompatible

- **Adding** new public classes, methods, or fields (purely additive)
- **Adding** a new superinterface (existing code doesn't know about it)
- **Widening** visibility (protected → public)
- **Removing** `final` from a class or method (allows new subclasses/overrides)
- **Removing** `abstract` from a method (makes it concrete — existing code still works)
- **Renaming parameters** (not part of the JVM descriptor for methods)
- **Changing default parameter values** in Kotlin (these generate new overloads, old overloads remain)
</incompatible_member_changes>

<klib_validation>
## Experimental KLib ABI Validation

Validates public ABI of Kotlin/Native and Kotlin/Multiplatform KLib targets. **Requires Kotlin ≥ 1.9.20.**

### Setup

```kotlin
apiValidation {
    @OptIn(kotlinx.validation.ExperimentalBCVApi::class)
    klib {
        enabled = true
    }
}
```

### How It Works

- Adds dependencies to existing `apiDump` and `apiCheck` tasks
- Generates `<project name>.klib.api` files alongside JVM dumps in `api/` folder
- Combines dumps from all targets into one file
- Target-specific declarations are annotated with target names
- Validation compares current dump against committed golden file

### Naming

Set `rootProject.name` in `settings.gradle.kts` for stable dumps. Without it, Gradle defaults to the directory name, which can vary across environments and break validation.

### Cross-Platform Host Behavior

| Host | Behavior |
|------|----------|
| **macOS** | Full support — all targets including Apple-specific (iosArm64, watchOS, etc.) |
| **Linux / Windows** | Apple targets cannot compile. By default, **skips** unsupported targets silently. |

### Strict Validation

Force errors when targets can't be compiled on the current host:

```kotlin
apiValidation {
    @OptIn(kotlinx.validation.ExperimentalBCVApi::class)
    klib {
        enabled = true
        strictValidation = true   // error instead of skip for unsupported targets
    }
}
```

### ABI Inference

On non-Apple hosts, BCV attempts to **infer** the ABI for unsupported targets from:
- Dumps generated for targets that are supported on the current host
- The existing `.klib.api` file in the project's `api/` folder (if present)

Inferred dumps may not match actual dumps. **Recommendation:** Update dumps on a host that supports all required targets when possible.

### Configuration

All `apiValidation` options work for KLibs too (ignoredPackages, ignoredClasses, nonPublicMarkers, etc.). Class names must use **JVM format**: `package.name.ClassName$SubclassName`.
</klib_validation>

<api_dump_format>
## The .api Dump File Format

The dump is a **human-readable text file** listing all effectively public classes and their members with JVM signatures. Example:

```
public final class com/example/MyClass {
    public fun doSomething (Ljava/lang/String;)V
    public final fun getValue ()I
    public fun <init> (I)V
}

public abstract interface class com/example/MyInterface {
    public abstract fun process ()V
}
```

Each entry shows:
- Access modifiers (`public`, `protected`, `final`, `abstract`, `static`)
- Class/interface distinction
- Fully-qualified JVM class name (with `/` separators)
- Method descriptors in JVM format (erased types)
- Constructors as `<init>`
- Synthetic/bridge methods if public

The diff of `.api` files during code review is the primary mechanism for verifying intentional API changes.
</api_dump_format>
