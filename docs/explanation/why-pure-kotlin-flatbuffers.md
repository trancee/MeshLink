# Why Pure-Kotlin FlatBuffers

## The decision

MeshLink implements a FlatBuffers-compatible binary codec entirely in pure Kotlin (~600 lines: ReadBuffer + WriteBuffer). It does not use `flatc` codegen or any FlatBuffers library dependency in commonMain.

## The constraint

Kotlin Multiplatform requires all shared code to compile on three targets: JVM, Android (via JVM bytecode), and iOS (via Kotlin/Native). The FlatBuffers ecosystem doesn't serve this:

- **`flatbuffers-java`** (the Google artifact on Maven Central) is JVM-only. Importing it in commonMain causes an immediate iOS compilation failure.
- **`flatbuffers-kotlin`** does not exist on Maven Central as a separate artifact. There is no official KMP FlatBuffers runtime.
- **`flatc`** codegen produces code that depends on the Java runtime.

## What we built instead

Two classes (~300 lines each):

- **`ReadBuffer`**: Reads FlatBuffers binary format — vtable navigation, field offset lookup, typed field accessors (Int, UInt, Byte, ByteArray, String, nested tables).
- **`WriteBuffer`**: Builds FlatBuffers-compatible binary output — back-to-front construction, vtable generation, string/vector caching, alignment padding.

Plus per-message-type encode/decode functions in `WireCodec.kt`.

## Why this works

The FlatBuffers binary format is a well-documented, stable specification:
- Fixed-size vtable header at known offset
- Field offsets stored as uint16 in the vtable
- Absent optional fields have offset 0 (return default value)
- Strings are null-terminated, length-prefixed
- Tables can be extended with new fields (forward compatibility)

Implementing it manually is straightforward because we don't need the full feature set — no unions, no vectors-of-tables, no file identifiers. Just flat tables with scalar and byte-array fields.

## What we proved

- 99 tests at 100% Kover line+branch coverage
- All 12 message types round-trip correctly
- Golden byte vectors match expected encoding
- InboundValidator rejects all malformed inputs
- Forward-compatible: old decoders skip unknown vtable slots

## Where flatbuffers-java lives

It's still in the project — but only in `jvmMain` source set, used by benchmarks as a reference encoder to validate our pure-Kotlin implementation produces identical bytes.

## Tradeoffs

| Aspect | Manual codec | Library codegen |
|--------|-------------|-----------------|
| KMP compatibility | ✅ All targets | ❌ JVM-only |
| Maintenance | Manual updates | Auto from `.fbs` |
| Performance | Identical (same binary format) | Identical |
| Code size | ~600 lines | Generated ~2000 lines |
| Feature coverage | 12 message types only | Full FlatBuffers spec |

## When to revisit

If a KMP-compatible FlatBuffers runtime is published to Maven Central, the manual codec could be replaced. The binary format is identical, so this would be a drop-in replacement with no wire-level changes.
