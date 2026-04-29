# FlatBuffers — Schema Language and Evolution

<schema_overview>
## Schema (.fbs) IDL

The schema language (IDL) uses C-family syntax. A complete example:

```fbs
// example IDL file
namespace MyGame;
attribute "priority";

enum Color : byte { Red = 1, Green, Blue }
union Any { Monster, Weapon, Pickup }

struct Vec3 {
  x:float;
  y:float;
  z:float;
}

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

table Weapon {}
table Pickup {}

root_type Monster;
```
</schema_overview>

<tables>
## Tables

Main way of defining objects. Field list can be appended to and deprecated from while maintaining compatibility.

### Field Declaration Grammar
```
field_decl = ident `:` type [ `=` scalar ] metadata `;`
```

### Three Field Semantics (Mutually Exclusive)

**1. Default** — returns default if absent in binary. Only scalars can have explicit defaults; non-scalars (string, vector, table) default to null.
```fbs
mana:short = 150;   // explicit default 150
hp:short;           // implicit default 0
inventory:[ubyte];  // implicit default null
```
**WARNING: Never change default values after deployment.** Fields with default value are NOT stored in the binary. Old code that wrote the old default explicitly will have those values read back differently under the new schema.

**2. Optional** — returns null/None/Optional in generated code when absent.
```fbs
hp:short = null;    // optional scalar
```
Not all languages support optional scalars yet.

**3. Required** — buffer is invalid if field is missing. Verifier rejects it.
```fbs
hp:short (required);
```
Cannot combine `required` with an explicit default. Adding or removing `required` may break forward/backward compatibility.
</tables>

<structs>
## Structs

All fields required, no defaults, no adding/deprecating fields. May only contain **scalars or other structs**. Stored **inline** in parent — no vtable, no offset indirection.

```fbs
struct Vec3 {
  x:float;
  y:float;
  z:float;
}
```

### Fixed-Size Arrays (Struct Only)
Binary-equivalent shorthand:
```fbs
struct Vec3 { v:[float:3]; }   // equivalent to x/y/z above
```
Currently only supported inside structs.

**Use structs for:** small, fixed, never-changing data (coordinates, colors, timestamps). Faster access, less memory.
</structs>

<types>
## Types

### Scalars
| Size | Signed | Unsigned | Float |
|------|--------|----------|-------|
| 8-bit | `byte` (`int8`), `bool` | `ubyte` (`uint8`) | — |
| 16-bit | `short` (`int16`) | `ushort` (`uint16`) | — |
| 32-bit | `int` (`int32`) | `uint` (`uint32`) | `float` (`float32`) |
| 64-bit | `long` (`int64`) | `ulong` (`uint64`) | `double` (`float64`) |

Parenthesized names are aliases. No variable-length integers.

### Vectors
```fbs
inventory:[ubyte];
```
Vector of any type, denoted `[type]`. **Nesting vectors is not supported** — wrap inner vector in a table:
```fbs
table Nest { a:[ubyte]; }
table Monster { a:[Nest]; }
```

### Strings
```fbs
name:string;
```
UTF-8 or 7-bit ASCII, zero-terminated, length-prefixed. For binary data use `[byte]` or `[ubyte]`.
</types>

<enums>
## Enums

Integer-backed named constants. Specify underlying type with `:`.
```fbs
enum Color : byte { Red = 1, Green, Blue }
```
Only integer types allowed (byte through ulong). Default first value = 0. **Values should only be added, never removed** (no deprecation). Code must handle unknown values for forward compatibility.
</enums>

<unions>
## Unions

Tagged sum types referencing tables. Generates an implicit `_type` enum field.
```fbs
union Any { Monster, Weapon, Pickup }
```
With aliases:
```fbs
union Position {
  Start:MarkerPosition,
  Point:PointPosition,
  Finish:MarkerPosition
}
```
- `NONE` (0) is reserved — cannot be used as an alias
- Unions are two fields → must be in a table, cannot be root type
- Experimental: vector of unions `[Any]` (C++ only), struct/string members in unions
</unions>

<other_declarations>
## Other Declarations

### Namespaces
```fbs
namespace MyGame.Sample;   // dot-separated for nesting
```
Generates C++ namespaces, Java/Kotlin packages.

### Includes
```fbs
include "mydefinitions.fbs";
```
Parsed once even if included multiple times. **Only current file generates code** — included files generate separately.

### Root Type
```fbs
root_type Monster;
```
Declares the root table for the buffer. Required for JSON parsing.

### File Identification
```fbs
file_identifier "MYFI";   // exactly 4 characters → bytes at offsets 4-7
```
Optional magic number for file format use. Auto-added by `flatc -b` and `Finish*Buffer()`. Check with `*BufferHasIdentifier()`. For network messages prefer unions instead.

### File Extension
```fbs
file_extension "ext";      // changes flatc output from .bin
```

### RPC
```fbs
rpc_service MonsterStorage {
  Store(Monster):StoreResponse;
  Retrieve(MonsterId):Monster;
}
```
Preliminary gRPC support via `--grpc`.

### Comments
`//` for regular, `///` for doc comments (output in generated code).
</other_declarations>

<attributes>
## Attributes

Attached after field, enum value, or type name inside `( )`.

| Attribute | Target | Effect |
|-----------|--------|--------|
| `id: n` | table field | Explicit field ID. **Must use on ALL fields** if used on any. Contiguous from 0. Union field ID = second field (type field is implicit N-1). Allows any schema ordering. |
| `deprecated` | field | Stop generating accessors. Old data may contain it but new code can't access it. |
| `required` | non-scalar field | Verifier rejects buffer if missing. Reader can skip null check. |
| `force_align: size` | struct, vector | Override natural alignment (struct in buffer, vector elements in C++). |
| `bit_flags` | unsigned enum | Values become `1<<N` instead of `N`. |
| `nested_flatbuffer: "T"` | `[ubyte]` field | Field contains a nested FlatBuffer with root type `T`. Generates convenient accessor. |
| `flexbuffer` | `[ubyte]` field | Field contains FlexBuffer data. Generates FlexBuffer root accessor. |
| `key` | field | Used for in-place binary search on sorted vector of parent table type. |
| `hash` | int32/64 field | JSON parser accepts string values, stores hash. Algorithms: `fnv1_32`, `fnv1_64`, `fnv1a_32`, `fnv1a_64`. |
| `original_order` | table | Disable size-based field reordering optimization. |
| `native_*` | various | C++ object-based API attributes. |

Custom attributes must be declared: `attribute "priority";`
</attributes>

<json_parsing>
## JSON Parsing

The FlatBuffers parser can parse JSON conforming to the schema directly into binary.

- Field names accepted with or without quotes
- Symbolic enum values: `field: EnumVal` or `field: "Enum.EnumVal"`. Flags: `"Val1 Val2"` (space-separated OR)
- Unions require `foo_type: FooOne` field immediately before `foo` field
- `null` means default value (same as not specifying)
- Math functions: `rad()`, `deg()`, `cos()`, `sin()`, `tan()`, `acos()`, `asin()`, `atan()`
- Escape codes: standard JSON + `\xXX` (non-standard, for binary roundtrip)
- Flexible numbers: leading zeros ignored (not octal), hex `0x`, C++ float format, `nan`/`inf`
</json_parsing>

<evolution_rules>
## Schema Evolution

### Rules

**Addition:** New fields MUST go at end of table. Exception: using `id:` attribute on all fields.

**Removal:** NEVER remove fields. Mark `deprecated`. Old data still contains them.

**Name changes:** OK for fields/tables (not in binary). Breaks source code, not wire format.

**Type changes:** Only same-width (int↔uint). Dangerous if old data has negative values.

**Default changes:** **NOT OK.** Old data relying on implicit defaults will read wrong values.

### Table Evolution Example
```fbs
// V1                    // V2 (OK)               // V3 (OK)
table T {                table T {                table T {
  a:int;                   a:int;                   a:int (deprecated);
  b:int;                   b:int;                   b:int;
}                          c:int;  // added end     c:int;
                         }                        }
```

### Union Evolution
```fbs
// V1                    // V2 (OK - end)         // V2 (OK - discriminant)
union U { A, B }         union U { A, B, C:A }    union U { A=1, C:A=3, B=2 }
```
Adding to middle **without discriminant** is NOT OK — shifts values.

### Checking Evolution
```bash
flatc --conform schema_v1.fbs schema_v2.fbs    # returns 0 if valid evolution
```

### Version Control Advice
Commit schema changes before generating binary data. Or use explicit `id:` attributes (creates merge conflicts on ID collision).
</evolution_rules>

<grammar>
## Formal EBNF Grammar

```
schema = include* ( namespace_decl | type_decl | enum_decl | root_decl |
           file_extension_decl | file_identifier_decl |
           attribute_decl | rpc_decl | object )*

include = `include` string_constant `;`
namespace_decl = `namespace` ident ( `.` ident )* `;`
attribute_decl = `attribute` ident | `"` ident `"` `;`
type_decl = ( `table` | `struct` ) ident metadata `{` field_decl+ `}`
enum_decl = ( `enum` ident `:` type | `union` ident ) metadata `{` commasep(enumval_decl) `}`
root_decl = `root_type` ident `;`
field_decl = ident `:` type [ `=` scalar ] metadata `;`
rpc_decl = `rpc_service` ident `{` rpc_method+ `}`
rpc_method = ident `(` ident `)` `:` ident metadata `;`

type = `bool` | `byte` | `ubyte` | `short` | `ushort` | `int` | `uint` |
       `float` | `long` | `ulong` | `double` | `int8` | `uint8` | `int16` |
       `uint16` | `int32` | `uint32` | `int64` | `uint64` | `float32` |
       `float64` | `string` | `[` type `]` | ident

enumval_decl = ident [ `=` integer_constant ] metadata
metadata = [ `(` commasep( ident [ `:` single_value ] ) `)` ]
scalar = boolean_constant | integer_constant | float_constant
object = `{` commasep( ident `:` value ) `}`
single_value = scalar | string_constant
value = single_value | object | `[` commasep( value ) `]`
commasep(x) = [ x ( `,` x )* ]
```
</grammar>

<efficiency_guidelines>
## Efficiency Guidelines

- **Prefer enums over strings** for known value sets
- **Use structs** for small fixed data — inline, no vtable overhead
- **Use smallest integer type** that fits (don't default to int/long)
- **Share repeated data** — same string/table can be referenced multiple times in a buffer
- **Sparse tables are cheap** — unused fields cost nothing in the binary (just a vtable zero)
- **Avoid dictionary patterns** — use tables with explicit fields instead of vector-of-key-value tables

### Gotcha: Testing Field Presence

Default-valued fields are NOT stored in the binary. A field explicitly set to its default value looks identical to an absent field. There is no way to distinguish "explicitly set to default" from "not set at all" for default-mode fields. Use optional (`= null`) if you need to detect presence.
</efficiency_guidelines>
