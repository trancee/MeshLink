# FlatBuffers — Compiler and Language Guides

<flatc_usage>
## flatc Compiler

```bash
flatc [GENERATOR_OPTIONS] [-o PATH] [-I PATH] FILES... [-- BINARY_FILES...]
```

- `FILES...` — schema (`.fbs`) or data (`.json`/`.bin`) files, processed in order
- `-o PATH` — output directory (default: current)
- `-I PATH` — include search paths (default: current, then relative to schema)

### Language Code Generators

| Flag | Language | Flag | Language |
|------|----------|------|----------|
| `--cpp` | C++ | `--kotlin` | Kotlin |
| `--java` | Java | `--swift` | Swift |
| `--csharp` | C# | `--rust` | Rust |
| `--go` | Go | `--dart` | Dart |
| `--python` | Python | `--lua` | Lua |
| `--js` | JavaScript | `--lobster` | Lobster |
| `--ts` | TypeScript | `--nim` | Nim |
| `--php` | PHP | `--grpc` | + gRPC stubs |

### Data Conversion

**JSON → Binary:**
```bash
flatc --binary myschema.fbs mydata.json    # → mydata_wire.bin
```

**Binary → JSON:**
```bash
flatc --json myschema.fbs -- mydata.bin    # → mydata.json
```
Schema must be listed first. Use `--raw-binary` if no `file_identifier` defined.
</flatc_usage>

<flatc_key_options>
## Key flatc Options

### Schema Evolution
- `--conform FILE` — check that input schemas are valid evolutions of FILE. Returns 0 if OK.
- `--require-explicit-ids` — require `id:` attribute on all table fields during parsing.

### Code Generation
- `--gen-object-api` — generate convenience object-based API (allocates objects). **Use only when base API is insufficient.**
- `--gen-mutable` — generate non-const accessors for in-place buffer mutation.
- `--gen-onefile` — single output file (C#, Go, Java, Kotlin, Python).
- `--gen-jvmstatic` — add `@JvmStatic` to Kotlin companion object methods for Java interop.
- `--gen-name-strings` — generate type name functions (C++).
- `--gen-compare` — generate `operator==` for object API types.
- `--gen-nullable` — add `@Nullable` (Java) or `_Nullable` (C++).
- `--gen-all` — generate code for all included files, not just current.

### JSON Options
- `--strict-json` — require/emit quoted field names, no trailing commas.
- `--defaults-json` — include default-valued fields in JSON output.
- `--force-defaults` — write default values to binary (normally omitted).
- `--natural-utf8` — output UTF-8 as readable strings instead of `\uXXXX`.
- `--allow-non-utf8` — pass non-UTF-8 through with `\x` escapes.
- `--json-nested-bytes` — allow nested_flatbuffer parsed as byte vector in JSON.

### Binary Schema / Reflection
- `--schema` — output binary schema (reflection.fbs format) instead of JSON. Use with `-b`.
- `--bfbs-comments` — include doc comments in binary schema.

### Protocol Buffers
- `--proto` — convert `.proto` files to `.fbs`. Supports package, message, enum, nested, import, extend, oneof, group.
- `--oneof-union` — translate `.proto` oneofs to FlatBuffer unions.

### Output Control
- `--filename-suffix SUFFIX` — default `_generated`.
- `--filename-ext EXT` — override language-specific extension.
- `--include-prefix PATH` — prefix for include statements.
- `--no-includes` — skip include generation (C++/Python).
- `--root-type T` — override `root_type` from schema.
- `-M` — print make rules for generated files.
- `--file-names-only` — list would-be-generated files to stdout (CI use).

### Misc
- `--size-prefixed` — input binaries are size-prefixed.
- `--flexbuffers` — use FlexBuffers for binary/json operations.
- `--no-warnings` — suppress warnings.
- `--force-empty` / `--force-empty-vectors` — force empty strings/vectors instead of null in object API serialization.
</flatc_key_options>

<kotlin_guide>
## Kotlin Language Guide

### Runtime
Kotlin codegen uses the **flatbuffers-java runtime library**. **JVM only** — no Kotlin Native or Kotlin.js support.

### Generating Code
```bash
flatc --kotlin -o output/ myschema.fbs
```

### Reading a FlatBuffer
```kotlin
import MyGame.Example.*
import com.google.flatbuffers.FlatBufferBuilder

// Read binary file into ByteArray
val data = RandomAccessFile(File("monsterdata_test.mon"), "r").use {
    val temp = ByteArray(it.length().toInt())
    it.readFully(temp)
    temp
}

val bb = ByteBuffer.wrap(data)
val monster = Monster.getRootAsMonster(bb)
```

### Accessing Fields
Fields are Kotlin **properties** (not getter methods):
```kotlin
val hp = monster.hp          // not monster.getHp()
val pos = monster.pos!!      // nullable — struct may be absent
```

### Static Methods
Accessed via **companion object**:
```kotlin
val monster = Monster.getRootAsMonster(bb)   // companion object method
```

### Java Interop
Use `--gen-jvmstatic` to add `@JvmStatic` annotations, enabling direct static access from Java code.

### Key Differences from Java
- Fields as properties (not getters)
- Static methods in companion object
- Null safety via Kotlin's type system
- Otherwise follows Java patterns closely
</kotlin_guide>

<supported_languages>
## Supported Languages (15 Total)

| Language | Code Gen | Object API | FlexBuffers | gRPC |
|----------|----------|------------|-------------|------|
| C++ | ✓ | ✓ | ✓ | ✓ |
| Java | ✓ | ✓ | ✓ | ✓ |
| Kotlin | ✓ | — | — | — |
| C# | ✓ | ✓ | ✓ | ✓ |
| Go | ✓ | — | ✓ | ✓ |
| Python | ✓ | ✓ | ✓ | ✓ |
| JavaScript | ✓ | — | ✓ | — |
| TypeScript | ✓ | — | ✓ | ✓ |
| Rust | ✓ | — | ✓ | ✓ |
| Swift | ✓ | ✓ | — | ✓ |
| C | ✓ | — | — | — |
| Dart | ✓ | ✓ | — | — |
| Lua | ✓ | — | — | — |
| Lobster | ✓ | — | — | — |
| PHP | ✓ | — | ✓ | — |

Feature support varies. Check [flatbuffers.dev/support](https://flatbuffers.dev/support/) for the current matrix.
</supported_languages>
