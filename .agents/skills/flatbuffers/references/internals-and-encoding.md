# FlatBuffers — Internals and Binary Encoding

<format_overview>
## Binary Format Overview

A FlatBuffer is a binary format consisting of little-endian scalars aligned to their own size. Assumptions for cross-platform interop:
- **IEEE-754** for floating-point
- **Two's complement** for signed integers
- Same endianness for floats and integers

The format intentionally leaves field/object ordering undefined — two implementations may produce different binaries from the same input. This allows optimization (e.g., size-sorting fields). The format is defined in terms of **offsets and adjacency** only.

### No Self-Description

FlatBuffers are **not self-describing** — you need the schema to parse correctly. The format contains no version number (versioning is intrinsic via field optionality/extensibility). It's a "meta-format" that accommodates all data needs.
</format_overview>

<offsets>
## Offsets

**`uoffset_t`** = `uint32_t` — the primary offset type. Used for references to tables, unions, strings, and vectors (never stored inline). 32-bit keeps format compatible between 32/64-bit systems. Unsigned → typically points forward (higher memory). Backward offsets are explicitly marked.

**`soffset_t`** = `int32_t` — signed offset used for table→vtable references (vtables may be anywhere relative to the table).

**`voffset_t`** = `uint16_t` — vtable entry type.

The buffer starts with a `uoffset_t` pointing to the root table.
</offsets>

<structs_binary>
## Structs (Binary)

- Always stored **inline** in their parent (struct, table, or vector)
- Consistent memory layout with **alignment to largest scalar member**
- All components aligned to their own size
- Alignment rules are independent of the compiler → guaranteed cross-platform layout
- Enforced in generated code
</structs_binary>

<tables_binary>
## Tables (Binary)

Stored via offset (not inline). Structure:

```
[soffset_t to vtable] [field1] [field2] ... [fieldN]  (with alignment padding)
```

The `soffset_t` is **subtracted** from the object start to find the vtable. Fields follow as aligned scalars or offsets, in **any order** (not necessarily schema order).

### VTable Structure

```
voffset_t  vtable_size        // size of vtable in bytes (including this field)
voffset_t  object_inline_size // size of object inline data (including vtable offset)
voffset_t  field_0_offset     // offset from table start to field 0, or 0 if not present
voffset_t  field_1_offset     // ...
...
voffset_t  field_N_offset
```

- `N+2` entries total (N = number of fields in schema at compile time)
- Entry value `0` = field not present → return default
- **VTables are shared** between objects with identical layouts (deduplication)
- Accessor functions contain the vtable offset as a constant. If offset is out of range (newer code reading older data), field is treated as not present → default returned.

### Access Pattern
1. Read soffset_t at table start → compute vtable address
2. Check if field's vtable slot is within vtable size
3. If out of range or entry is 0 → return default
4. Otherwise, entry is offset from table start to field data → read field
</tables_binary>

<unions_binary>
## Unions (Binary)

Encoded as **two fields**: an enum value (the type discriminant) + an offset to the actual element. The enum constant `NONE` (0) means the union is not set.
</unions_binary>

<strings_vectors_binary>
## Strings and Vectors (Binary)

**Vectors:** contiguous aligned scalar elements prefixed by a **32-bit element count**. Not stored inline — referred to by offset. The count does not include null termination.

**Strings:** a vector of bytes that is always **null-terminated**. The length prefix does not include the null terminator.

A vector or table may contain multiple offsets pointing to the same value (explicit sharing).
</strings_vectors_binary>

<construction>
## Construction

Buffers are built **backward** (from highest memory address down). This significantly reduces bookkeeping and simplifies the builder API — you build children before parents, and offsets naturally point forward to already-written data.
</construction>

<encoding_example>
## Encoding Example

JSON input:
```json
{ pos: { x: 1, y: 2, z: 3 }, name: "fred", hp: 50 }
```

Binary layout (annotated):
```
// Buffer start:
uint32_t 20              // Offset to root table

// VTable (could be shared):
uint16_t 16              // VTable size (bytes, from here)
uint16_t 22              // Object inline size (bytes)
uint16_t 4, 0, 20, 16, 0, 0  // Field offsets (0 = not present)
                         // pos=4, mana=0(absent), name=20, hp=16, ...

// Root table:
int32_t  16              // soffset_t to vtable (subtracted from here)
float    1, 2, 3         // Vec3 struct, inline (12 bytes)
uint32_t 8               // Offset to name string
int16_t  50              // hp field
int16_t  0               // Padding for alignment

// Name string:
uint32_t 4               // String length
int8_t   'f','r','e','d', 0, 0, 0, 0  // Text + null + padding
```

**Notes:** Field order, alignment padding, and child-object order may vary between implementations. The encoding above is one valid representation.
</encoding_example>

<flexbuffers>
## FlexBuffers — Schema-less Format

Self-describing binary format. Can be used standalone or nested inside FlatBuffers (via `(flexbuffer)` attribute on `[ubyte]` field).

### Key Properties
- **Zero-copy access** like FlatBuffers (no parsing/unpacking)
- **Variable bit-width** (8/16/32/64) — parent determines child width
- Children stored **before parents** (front-to-back)
- **Root at end of buffer** (last byte = root byte width, preceding byte = root type)
- Compact encoding: automatic string pooling, automatic smallest-representation sizing

### Type Byte
2 lower bits = child bit-width (8/16/32/64), 6 upper bits = actual type. Only relevant for offset-based children.

### Vectors
Core primitive. Untyped vectors store per-element type bytes after the data.

```
// Untyped vector [1, 2, 3]:
uint8_t 3, 1, 2, 3, 4, 4, 4   // size, values, type bytes
```

Size field at index -1 (offset points to first element). Type `4` = 8-bit child width + `SL_INT`.

### Typed Vectors
Omit type bytes — type determined by vector type from parent. Available for int/uint/float/key types. **Fixed-size typed vectors** of size 2/3/4 also omit the size field (e.g., `TYPE_VECTOR_INT3`).

### Scalars
`TYPE_INT`, `TYPE_UINT`, `TYPE_FLOAT` — inline or indirect (`TYPE_INDIRECT_*`). Indirect versions are useful for sharing large values or keeping vector element widths small.

### Booleans and Nulls
Encoded as inlined unsigned integers (`TYPE_BOOL`, `TYPE_NULL`).

### Blobs, Strings, Keys
- **Blob** (`TYPE_BLOB`): like vector with `uint8_t` elements. Parent bit-width only sizes the length field.
- **String** (`TYPE_STRING`): blob + null terminator. **Must be UTF-8.**
- **Key** (`TYPE_KEY`): string without size field. Cannot contain null bytes. Used in maps.

### Maps
Like an untyped vector but with 2 prefixes before the size:

| Index | Field |
|-------|-------|
| -3 | Offset to keys vector (may be shared) |
| -2 | Byte width of keys vector |
| -1 | Size (from here on, same as vector) |
| 0+ | Elements |
| After elements | Type bytes |

Keys vector is a **sorted typed vector of keys** → lookup via **binary search** (strcmp order). Keys vector can be shared between multiple maps. Iterating a map as a vector (values + parallel keys) is faster than per-key lookup for full traversal.

### Root
Last bytes of buffer: `[root_value] [type_byte] [root_byte_width]`

Example — integer 13 as root:
```
uint8_t 13, 4, 1    // value=13, type=4(INT/8bit), byte_width=1
```

### Nesting in FlatBuffers
```fbs
a:[ubyte] (flexbuffer);    // generates a_flexbuffer_root() accessor
```

### Efficiency Tips
- **Prefer vectors over maps** for small objects (avoid key storage overhead)
- Maps can be iterated as vectors (faster than per-key binary search)
- Use `IndirectDouble`/`IndirectFloat` to avoid inflating vector element width
- Integers automatically use smallest width; doubles only compress to float if lossless
- Use **blobs** for large byte arrays (vector size field width won't inflate element storage)
</flexbuffers>
