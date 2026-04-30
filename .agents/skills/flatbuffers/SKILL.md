---
name: flatbuffers
description: Google FlatBuffers reference — zero-copy cross-platform serialization. Schema language (.fbs IDL with tables, structs, scalars, vectors, strings, enums, unions, namespaces, root_type, file_identifier). Schema evolution rules (append-only or explicit id, deprecate never remove, never change defaults). All attributes (id, deprecated, required, force_align, bit_flags, key, hash). Binary format (little-endian, uoffset_t/soffset_t offsets, vtable sharing, backward construction). flatc compiler (15 language targets including --kotlin, --binary/--json, --conform, --gen-object-api). FlexBuffers schema-less variant. Kotlin/JVM usage. Style guide and efficiency guidelines. Use when writing .fbs schemas, generating code, understanding the binary format, evolving schemas, or any FlatBuffers question.
---

<essential_principles>

**FlatBuffers** is Google's cross-platform serialization library (C++, Java, Kotlin, C#, Go, Python, Rust, Swift, JS/TS, and more). Originally created for game development and performance-critical applications. Apache 2.0.

### Core Advantage

**Zero-copy access** — read serialized data directly from the buffer without parsing, unpacking, or per-object heap allocation. The only memory needed is the buffer itself.

### Schema Language (.fbs)

```fbs
namespace MyGame;
attribute "priority";

enum Color : byte { Red = 1, Green, Blue }
union Any { Monster, Weapon, Pickup }

struct Vec3 { x:float; y:float; z:float; }

table Monster {
  pos:Vec3;
  mana:short = 150;
  hp:short = 100;
  name:string;
  friendly:bool = false (deprecated, priority: 1);
  inventory:[ubyte];
  color:Color = Blue;
  test:Any;
}
root_type Monster;
```

### Tables vs Structs

| | **Table** | **Struct** |
|---|-----------|-----------|
| Fields optional | Yes (default/optional/required) | No — all required |
| Versioning | Add fields, deprecate old ones | No changes allowed |
| Storage | Indirect via offset + vtable | Inline in parent |
| Speed | Fast (vtable lookup) | Fastest (direct memory) |
| Content | Any type | Scalars and other structs only |

**Use structs** for small fixed data that will never change (Vec3, Color). **Use tables** for everything else.

### Field Semantics (Tables)

- **Default** — returns default value if absent. Scalars only. `mana:short = 150;` (0 if unspecified). **Never change defaults after deployment.**
- **Optional** — returns null if absent. `hp:short = null;`
- **Required** — buffer invalid if absent. `hp:short (required);` Cannot combine with default.

Non-scalar fields (string, vector, table) default to null when absent.

### Schema Evolution Rules

1. **Add new fields at the END** of tables only (or use explicit `id:` on all fields)
2. **Never remove fields** — mark `deprecated` instead
3. **Never change default values** — absent fields rely on old defaults
4. **Renaming** fields/tables is OK (names aren't in binary)
5. **Type changes** only if same width (e.g. int↔uint), and with care
6. **Unions** — add new members at end, or use explicit discriminant values

### Scalars

| Size | Signed | Unsigned | Float |
|------|--------|----------|-------|
| 8-bit | `byte`/`int8`, `bool` | `ubyte`/`uint8` | — |
| 16-bit | `short`/`int16` | `ushort`/`uint16` | — |
| 32-bit | `int`/`int32` | `uint`/`uint32` | `float`/`float32` |
| 64-bit | `long`/`int64` | `ulong`/`uint64` | `double`/`float64` |

No variable-length integers. All little-endian in the binary.

### Style Guide

- Types (table, struct, enum, union, rpc): **UpperCamelCase**
- Table/struct field names: **snake_case** (auto-translated to lowerCamelCase in Java/Kotlin)
- Enum values: **UpperCamelCase**
- Namespaces: **UpperCamelCase**
- Indent 2 spaces. Opening brace on same line.

</essential_principles>

<routing>

| Topic | Reference |
|-------|-----------|
| Schema IDL details (tables, structs, arrays, all scalar/non-scalar types, vectors, strings, enums, unions with aliases, namespaces, includes, root_type, file_identifier/extension, RPC, comments/docs, all attributes with id/deprecated/required/force_align/bit_flags/nested_flatbuffer/flexbuffer/key/hash/original_order, JSON parsing rules, efficiency guidelines, gotchas), schema evolution rules with examples (table/union evolution, addition/removal/reordering/type changes/default changes/renaming, id attributes, --conform checking), formal EBNF grammar | `references/schema-and-evolution.md` |
| Binary format internals (little-endian, IEEE-754, two's complement, uoffset_t uint32 offsets, format identification, structs inline with cross-platform alignment, tables with soffset_t to vtable, vtable structure voffset_t with size/object-size/field-offsets and sharing, unions as enum+offset pair, strings as null-terminated length-prefixed vectors, vectors as count-prefixed contiguous scalars, backward construction, encoding example with hex layout), FlexBuffers schema-less format (variable bit-width, untyped/typed/fixed-size vectors, type byte encoding, scalars/bools/nulls, blobs/strings/keys, maps with sorted keys and binary search, root at buffer end, nesting inside FlatBuffer via flexbuffer attribute, efficiency tips) | `references/internals-and-encoding.md` |
| flatc compiler (all language flags --cpp/--java/--kotlin/--rust/--swift/etc, --binary/--json conversion, -o/-I paths, --conform for schema evolution checking, --gen-object-api, --gen-mutable, --gen-onefile, --gen-jvmstatic, --require-explicit-ids, --grpc, --strict-json, --force-defaults, --schema for binary schema reflection, --filename-suffix/--filename-ext, --proto for protobuf conversion), Kotlin/JVM guide (flatbuffers-java runtime, JVM-only no Native/JS, property-style access, companion object statics, reading via ByteBuffer + getRootAs*, FlatBufferBuilder for writing), supported languages list (15 languages with feature matrix) | `references/compiler-and-languages.md` |

</routing>

<reference_index>

**schema-and-evolution.md** — schema (.fbs) IDL overview with full example, tables (field_decl grammar, three field semantics: default with scalar-only explicit defaults and never-change-defaults warning, optional with null default for scalars, required with verifier enforcement), structs (all fields required, no defaults, no add/deprecate, inline storage, scalars and other structs only, fixed-size arrays [type:N] syntax), types (scalar table: byte/bool ubyte short ushort int uint float long ulong double with int8-int64/uint8-uint64/float32/float64 aliases, non-scalar: vectors [type] no nesting wrap in table, strings UTF-8 zero-terminated, use [byte] for binary), enums (integer-backed byte through ulong, values only added never removed, no deprecation, code must handle unknown values), unions (table references with implicit _type field, alias names, NONE=0 reserved, must be in table not root, experimental vector of unions and struct/string members), namespaces (C++ namespaces / Java packages, dot-separated nesting), includes (parsed once, only current file generates code), root_type, file_identifier (exactly 4 chars at offsets 4-7, auto-added by flatc -b and Finish*Buffer, check via *BufferHasIdentifier), file_extension, rpc_service (table→table, --grpc), comments (// and /// for doc generation), all attributes (id:n contiguous from 0 on all fields with union gap, deprecated stops accessor gen, required on non-scalar verifier-enforced, force_align on struct/vector, bit_flags on unsigned enum values=1<<N, nested_flatbuffer:"table" on [ubyte], flexbuffer on [ubyte], key for binary search, hash fnv1_32/64/fnv1a_32/64, original_order disable size-sorting, native_* for C++ object API), JSON parsing (strongly typed, unquoted field names, symbolic enums with or-for-flags, union requires foo_type+foo pair, null=default, math functions rad/deg/cos/sin/tan/acos/asin/atan, escape codes including non-standard \xXX, flexible number parsing hex/float/nan/inf), efficiency guidelines (prefer enum over string, use structs for fixed data, smallest integer types, share repeated data, tables with many sparse fields are efficient), style guide, gotchas (testing field presence: default-valued fields not stored so absent looks same as explicitly-set-to-default). Schema evolution rules (addition at end only or use id, removal via deprecated only never delete, name changes OK binary-safe, type changes same-width-only with care, default changes NOT OK old data relies on old defaults, id attribute for explicit ordering and merge-conflict safety, union evolution add at end or use discriminant values). --conform FILE for checking. Formal EBNF grammar (schema, include, namespace_decl, type_decl, enum_decl, root_decl, field_decl, rpc_decl, type, metadata, scalar, value, string_constant, ident, integer/float/boolean constants).

**internals-and-encoding.md** — FlatBuffer binary format (little-endian scalars, IEEE-754 floats, two's complement signed, cross-platform 32/64-bit compatible, format is meta-format no version number, undefined field/object ordering for optimization flexibility, two implementations may produce different binaries from same input), offsets (uoffset_t = uint32 forward only, buffer starts with uoffset_t to root table, 64-bit/16-bit variants possible), structs (inline in parent, compiler-independent alignment to largest scalar, all fields present), tables (referred by offset, start with soffset_t to vtable subtracted from object start, followed by aligned scalar fields/offsets in any order, vtable: voffset_t=uint16 entries, first=vtable size, second=object inline size, remaining=field offsets 0 means not present→default, vtables shared between objects with same layout, accessor checks offset against vtable size for forward compat), unions (enum + offset pair, NONE=0), strings/vectors (strings = null-terminated byte vectors, vectors = 32-bit count prefix + contiguous aligned elements, both referred by offset), construction (built backward from high to low memory addresses for simpler bookkeeping), full encoding example (Monster with pos/name/hp: vtable 16 bytes with 6 field slots, root table with inline Vec3 struct, offset to name string, hp field, string with 4-byte length prefix + null termination + padding). FlexBuffers (schema-less, children stored before parents, root at end, variable bit-width 8/16/32/64 determined by parent, single offset type: unsigned backward, type byte = 2-bit child width + 6-bit type, untyped vectors with per-element type bytes after data, typed vectors omit type bytes for int/uint/float/key, fixed-size typed vectors of 2/3/4 omit size field, scalars TYPE_INT/UINT/FLOAT inline or indirect, bools/nulls as inlined uint, blobs like vectors with uint8 elements, strings like blobs + null termination + must be UTF-8, keys like strings without size field for maps, maps = untyped vector + 2 prefixes: offset to sorted keys vector + key vector byte width, keys must be strcmp-sorted for binary search, root = last 3+ bytes: value + type byte + root byte width). Nesting FlexBuffers in FlatBuffers via `(flexbuffer)` attribute on `[ubyte]` field.

**compiler-and-languages.md** — flatc compiler usage (`flatc [OPTIONS] [-o PATH] [-I PATH] FILES... [-- BINARY_FILES...]`), language generators (--cpp/--java/--kotlin/--csharp/--go/--python/--js/--ts/--php/--dart/--lua/--lobster/--rust/--swift/--nim, --grpc for RPC stubs), data conversion (--binary/-b serialize JSON→binary, --json/-j binary→JSON, requires schema first in FILES), key options (--conform FILE check evolution, --require-explicit-ids, --gen-object-api convenience object construction at cost of allocation, --gen-mutable in-place mutation accessors, --gen-onefile single output for C#/Go/Java/Kotlin/Python, --gen-jvmstatic for Kotlin/Java interop, --strict-json, --defaults-json output default-valued fields, --force-defaults write defaults to binary, --schema binary schema for reflection, --proto convert .proto→.fbs, --raw-binary allow no file_identifier, --size-prefixed, -M print make rules, --filename-suffix default '_generated', --no-includes, --gen-name-strings, --gen-compare, --flexbuffers). Kotlin guide (JVM only — no Kotlin Native or Kotlin.js, uses flatbuffers-java runtime library, generate with `flatc --kotlin`, reading: ByteArray→ByteBuffer→Monster.getRootAsMonster(bb), fields accessed as Kotlin properties not getter methods, static methods in companion object, writing via FlatBufferBuilder, --gen-jvmstatic adds @JvmStatic for Java interop). Supported languages (C, C++, C#, Dart, Go, Java, JavaScript, Kotlin, Lobster, Lua, PHP, Python, Rust, Swift, TypeScript — 15 total, feature support varies per language for object API, FlexBuffers, gRPC).

</reference_index>
